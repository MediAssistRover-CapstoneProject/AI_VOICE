"""Web Dashboard for MediAssistRover AI Voice & RAG Knowledge Studio.

Provides an interactive GUI to:
1. Upload & Manage hospital documents (.docx, .pdf, .md, .txt)
2. Live update & re-index Vector Database ('Vector P') on the fly
3. Test Voice QA, Intent Classification, Vector Similarity, and TTS Audio Playback
"""
from __future__ import annotations

import io
import json
import logging
import os
import shutil
import time
from pathlib import Path
from typing import Any

from dotenv import load_dotenv
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse, JSONResponse, Response
from fastapi.staticfiles import StaticFiles

ROOT = Path(__file__).resolve().parent.parent.parent
load_dotenv(ROOT / ".env", override=False)

from src.audio.player import play_wav
from src.intent import IntentClassifier
from src.rag import (
    Document,
    DocumentLoader,
    HospitalRAGPipeline,
    HospitalVectorStore,
    VietnameseTextSplitter,
    create_embeddings,
)
from src.tts import SpeechClient
from src.rag.faq_store import FAQStore, FAQDataError, faq_allowed
from src.web.faq_api import create_faq_router
from src.platform.faq_launcher import runtime_info
from src.utils.logger import setup_logger

log = setup_logger("mar_web_app")

DOCS_DIR = ROOT / "data" / "documents"
DOCS_DIR.mkdir(parents=True, exist_ok=True)
VECTOR_DB_FILE = ROOT / "data" / "hospital_vector_store.json"
faq_store = FAQStore(ROOT / 'data' / 'faqs.json')
STATIC_DIR = Path(__file__).resolve().parent / 'static'

# Initialize FastAPI
app = FastAPI(
    title="MediAssistRover AI Voice & RAG Studio",
    description="Interactive Testing & Knowledge Management GUI",
    version="2.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
app.include_router(create_faq_router(faq_store))
app.mount('/assets', StaticFiles(directory=STATIC_DIR, check_dir=False), name='assets')


@app.get('/faq', response_class=HTMLResponse)
def faq_page():
    return (STATIC_DIR/'faq.html').read_text(encoding='utf-8')


@app.get('/api/runtime')
def studio_runtime():
    return runtime_info(ROOT)

# Global State
embeddings = create_embeddings(
    provider=os.getenv("EMBEDDING_PROVIDER", "local"),
    openai_api_key=os.getenv("OPENAI_API_KEY", ""),
    gemini_api_key=os.getenv("GEMINI_API_KEY", ""),
)
vector_store = HospitalVectorStore(embeddings=embeddings, persist_path=VECTOR_DB_FILE)
rag_pipeline = HospitalRAGPipeline(
    vector_store=vector_store,
    llm_provider="openai" if os.getenv("OPENAI_API_KEY") else "offline",
    openai_api_key=os.getenv("OPENAI_API_KEY", ""),
    llm_model="gpt-4o-mini",
    faq_store=faq_store,
)
intent_classifier = IntentClassifier(
    confidence_threshold=0.6,
    use_llm_fallback=bool(os.getenv("OPENAI_API_KEY")),
    openai_api_key=os.getenv("OPENAI_API_KEY", ""),
)
fast_intent_classifier = IntentClassifier(use_llm_fallback=False)


class DummyBackend:
    def synthesize_speech(self, **kwargs):
        return None


tts_client = SpeechClient(
    DummyBackend(),
    speed_wpm=150,
    openai_api_key=os.getenv("OPENAI_API_KEY", ""),
)


def _rebuild_index_internal() -> int:
    """Internal helper to re-index all documents into Vector P."""
    loader = DocumentLoader(DOCS_DIR)
    raw_docs = loader.load_directory()
    if not raw_docs:
        vector_store.points = []
        vector_store.save(VECTOR_DB_FILE)
        return 0

    splitter = VietnameseTextSplitter(chunk_size=400, chunk_overlap=80)
    chunks = splitter.split_documents(raw_docs)

    vector_store.points = []
    vector_store.add_documents(chunks)
    vector_store.save(VECTOR_DB_FILE)
    log.info("[WEB] Re-indexed %d chunks from %d docs", len(chunks), len(raw_docs))
    return len(vector_store.points)


# ──────────────────────────────────────────────────────────────────
# REST API Endpoints
# ──────────────────────────────────────────────────────────────────


@app.get("/api/stats")
def get_stats() -> dict[str, Any]:
    doc_files = list(DOCS_DIR.glob("*.*"))
    try:faq_stats=faq_store.snapshot()
    except FAQDataError:faq_stats=dict(total=0,active=0)
    return {
        "vector_points_count": len(vector_store.points),
        "documents_count": len(doc_files),
        "faq_count": faq_stats['total'],
        "faq_active_count": faq_stats['active'],
        "embedding_provider": os.getenv("EMBEDDING_PROVIDER", "local"),
        "has_openai_key": bool(os.getenv("OPENAI_API_KEY")),
        "vector_db_file": str(VECTOR_DB_FILE.name),
        "vector_db_size_bytes": VECTOR_DB_FILE.stat().st_size if VECTOR_DB_FILE.exists() else 0,
    }


@app.get("/api/documents")
def list_documents() -> list[dict[str, Any]]:
    docs = []
    for file in DOCS_DIR.glob("*.*"):
        if file.suffix.lower() in (".docx", ".pdf", ".md", ".txt"):
            stat = file.stat()
            docs.append({
                "filename": file.name,
                "extension": file.suffix.lower().lstrip("."),
                "size_kb": round(stat.st_size / 1024, 1),
                "modified_at": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(stat.st_mtime)),
            })
    docs.sort(key=lambda x: x["modified_at"], reverse=True)
    return docs


@app.post("/api/documents/upload")
async def upload_document(
    file: UploadFile = File(...),
    auto_reindex: bool = Form(True),
) -> dict[str, Any]:
    allowed_exts = {".docx", ".pdf", ".md", ".txt"}
    ext = Path(file.filename).suffix.lower()
    if ext not in allowed_exts:
        raise HTTPException(
            status_code=400,
            detail=f"Định dạng '{ext}' không được hỗ trợ. Hãy tải file .docx, .pdf, .md hoặc .txt",
        )

    dest = DOCS_DIR / file.filename
    with open(dest, "wb") as buffer:
        shutil.copyfileobj(file.file, buffer)
    log.info("[WEB] Saved uploaded file: %s", dest.name)

    points_count = None
    if auto_reindex:
        points_count = _rebuild_index_internal()

    return {
        "status": "success",
        "filename": file.filename,
        "message": f"Đã lưu '{file.filename}' và cập nhật Vector Database thành công!",
        "total_vector_points": points_count or len(vector_store.points),
    }


@app.post("/api/documents/text")
def create_text_document(
    title: str = Form(...),
    content: str = Form(...),
    auto_reindex: bool = Form(True),
) -> dict[str, Any]:
    safe_title = "".join(c for c in title if c.isalnum() or c in ("-", "_", " ")).strip()
    safe_title = safe_title.replace(" ", "_") or "tai_lieu_moi"
    filename = f"{safe_title}.md"
    dest = DOCS_DIR / filename

    full_md = f"# {title}\n\n{content}\n"
    dest.write_text(full_md, encoding="utf-8")
    log.info("[WEB] Created text document: %s", filename)

    points_count = None
    if auto_reindex:
        points_count = _rebuild_index_internal()

    return {
        "status": "success",
        "filename": filename,
        "message": f"Đã tạo tài liệu '{filename}' và cập nhật Vector P!",
        "total_vector_points": points_count or len(vector_store.points),
    }


@app.delete("/api/documents/{filename}")
def delete_document(filename: str) -> dict[str, Any]:
    target = DOCS_DIR / filename
    if not target.is_file():
        raise HTTPException(status_code=404, detail="File không tồn tại")
    target.unlink()
    log.info("[WEB] Deleted file: %s", filename)
    points_count = _rebuild_index_internal()
    return {
        "status": "success",
        "message": f"Đã xóa '{filename}' và cập nhật lại Vector Database",
        "total_vector_points": points_count,
    }


@app.post("/api/reindex")
def trigger_reindex() -> dict[str, Any]:
    points = _rebuild_index_internal()
    return {
        "status": "success",
        "message": f"Đã cập nhật Vector Database ('Vector P') thành công! Tổng cộng {points} vector points.",
        "vector_points_count": points,
    }


@app.get("/api/chunks")
def list_chunks(limit: int = 30) -> list[dict[str, Any]]:
    results = []
    for pt in vector_store.points[:limit]:
        results.append({
            "id": pt.id[:8],
            "title": pt.metadata.get("title") or pt.metadata.get("source", "Tài liệu"),
            "source": pt.metadata.get("source", "Local"),
            "text": pt.text,
            "dimensions": len(pt.vector),
        })
    return results


@app.post("/api/chat")
def chat_query(query: str = Form(...)) -> dict[str, Any]:
    t0 = time.monotonic()
    user_text = query.strip()
    if not user_text:
        raise HTTPException(status_code=400, detail="Câu hỏi không được rỗng")

    # Intent Classification
    fast_intent = fast_intent_classifier.classify(user_text)
    try:faq = rag_pipeline.lookup_faq(user_text) if faq_allowed(user_text, fast_intent.intent) else None
    except FAQDataError:raise HTTPException(503, 'Kho FAQ chưa đọc được. Kiểm tra file FAQ trước khi dùng.') from None
    intent = fast_intent if faq else intent_classifier.classify(user_text)
    answer_mode = 'scripted'
    timings_ms = {}

    # Dispatch via RAG or Intent Handlers
    if faq:
        intent.intent='QUERY_HOSPITAL_INFO';intent.confidence=1.
        answer=faq['answer'];sources=faq['sources'];context_used=faq['context_used'];answer_mode='approved_faq'
    elif intent.intent == "ESCORT_REQUEST":
        room = intent.entities.get("destination_room", "phòng chỉ định")
        room_names = {
            "P_CAP_CUU": "Phòng Cấp cứu",
            "P_KHAM_NOI_01": "Phòng khám Nội 01",
            "P_KHAM_NGOAI_02": "Phòng khám Ngoại 02",
            "P_NHA_THUOC": "Nhà thuốc bệnh viện",
            "P_XET_NGHIEM": "Phòng Xét nghiệm",
            "P_TIEP_DON": "Quầy Tiếp đón",
        }
        display_name = room_names.get(room, room)
        answer = f"Bạn đang yêu cầu dẫn đến {display_name}. Trang chat này chỉ xem trước ý định; chưa tạo yêu cầu hay job điều khiển robot."
        sources = [{"source": "Bản đồ số SLAM bệnh viện", "score": 1.0}]
        context_used = f"Dẫn đường tự hành tới điểm đích: {display_name}"
    elif intent.intent == "EMERGENCY_SOS":
        answer = "Bạn hãy gọi ngay nhân viên y tế gần nhất. Trang chat này chưa gửi cảnh báo cấp cứu tới hệ thống."
        sources = [{"source": "Hệ thống Cấp cứu SOS Khẩn cấp", "score": 1.0}]
        context_used = "Báo động khẩn cấp - Điều dưỡng trực tiếp nhận tức thời"
    elif intent.intent == "GREETING":
        answer = "Xin chào bạn! Tôi là robot hỗ trợ bệnh viện MediAssist. Bạn cần tôi giúp gì hôm nay ạ?"
        sources = [{"source": "Lời chào hệ thống", "score": 1.0}]
        context_used = "Hội thoại tiếp đón ngoại trú"
    elif intent.intent == "FAREWELL":
        answer = "Chúc bạn luôn mạnh khỏe và khám bệnh thuận lợi! Tạm biệt bạn."
        sources = [{"source": "Lời chào kết thúc", "score": 1.0}]
        context_used = "Kết thúc phiên thoại"
    else:
        # Vector P RAG Pipeline
        rag_res = rag_pipeline.answer_question(user_text)
        answer = rag_res["answer"]
        sources = rag_res["sources"]
        context_used = rag_res["context_used"]
        answer_mode=rag_res.get('answer_mode','generated')
        timings_ms=rag_res.get('timings_ms',{})

    duration_ms = round((time.monotonic() - t0) * 1000, 1)

    return {
        "query": user_text,
        "answer_mode": answer_mode,
        "timings_ms": timings_ms,
        "answer": answer,
        "intent": intent.intent,
        "confidence": intent.confidence,
        "sources": sources,
        "context_used": context_used,
        "duration_ms": duration_ms,
    }


@app.post("/api/tts")
def play_tts(text: str = Form(...), play_speaker: bool = Form(False)) -> Response:
    """Synthesize speech and optionally play through the laptop speaker, or return WAV stream."""
    openai_key = os.getenv("OPENAI_API_KEY", "")
    if openai_key:
        try:
            from openai import OpenAI
            client = OpenAI(api_key=openai_key)
            res = client.audio.speech.create(
                model="tts-1",
                voice="coral",
                input=text,
                response_format="wav",
            )
            wav_bytes = res.read()
            if play_speaker:
                play_wav(wav_bytes)
            return Response(content=wav_bytes, media_type="audio/wav")
        except Exception as exc:
            log.warning("[WEB-TTS] Direct OpenAI TTS failed: %s", exc)

    # Fallback to console print or empty
    if play_speaker:
        print(f"\n🔊 Robot nói: {text}\n")
    return JSONResponse(status_code=503, content={"detail": "Không thể tổng hợp giọng đọc"})


@app.post("/api/test-live-mic")
def test_live_mic():
    """Trigger the live microphone test in the background."""
    import subprocess
    cmd = ["py", "-3.11", str(ROOT / "test_live_voice.py")]
    subprocess.Popen(cmd)
    return {"status": "success", "message": "Đã kích hoạt kiểm thử Micro & Loa laptop thực tế!"}


# ──────────────────────────────────────────────────────────────────
# Modern Single Page Application (HTML/CSS/JS)
# ──────────────────────────────────────────────────────────────────

HTML_DASHBOARD = """<!DOCTYPE html>
<html lang="vi">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>MediAssistRover AI Voice & RAG Studio</title>
  <script src="https://cdn.tailwindcss.com"></script>
  <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.4.0/css/all.min.css">
  <style>
    @import url('https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@400;500;600;700&display=swap');
    body { font-family: 'Plus Jakarta Sans', sans-serif; }
    .custom-scroll::-webkit-scrollbar { width: 6px; height: 6px; }
    .custom-scroll::-webkit-scrollbar-track { background: #f1f5f9; }
    .custom-scroll::-webkit-scrollbar-thumb { background: #cbd5e1; border-radius: 4px; }
  </style>
</head>
<body class="bg-slate-50 text-slate-800 antialiased h-screen flex flex-col overflow-hidden">

  <!-- Header -->
  <header class="bg-white border-b border-slate-200 px-6 py-3.5 flex items-center justify-between shrink-0 shadow-sm z-10">
    <div class="flex items-center space-x-3">
      <div class="w-10 h-10 rounded-xl bg-gradient-to-tr from-cyan-600 to-teal-500 flex items-center justify-center text-white shadow-md shadow-teal-500/20">
        <i class="fa-solid fa-robot text-xl"></i>
      </div>
      <div>
        <h1 class="text-lg font-bold text-slate-900 leading-tight">MediAssistRover <span class="text-teal-600">AI Voice & RAG Studio</span></h1>
        <p class="text-xs text-slate-500">Hệ thống Trợ lý Giọng nói & Quản lý Tri thức Vector Database ("Vector P")</p>
      </div>
    </div>
    
    <!-- Status Badges -->
    <div class="flex items-center space-x-3 text-xs">
      <div class="bg-slate-100 border border-slate-200 rounded-lg px-3 py-1.5 flex items-center space-x-2">
        <span class="w-2 h-2 rounded-full bg-emerald-500 animate-pulse"></span>
        <span class="font-medium text-slate-600">Vector Points: <strong id="stat-points" class="text-slate-900">0</strong></span>
      </div>
      <div class="bg-slate-100 border border-slate-200 rounded-lg px-3 py-1.5 flex items-center space-x-2">
        <i class="fa-solid fa-file-word text-blue-600"></i>
        <span class="font-medium text-slate-600">Tài liệu: <strong id="stat-docs" class="text-slate-900">0</strong></span>
      </div>
      <button onclick="triggerLiveMic()" class="bg-teal-600 hover:bg-teal-700 text-white font-medium px-3.5 py-1.5 rounded-lg flex items-center space-x-2 transition shadow-sm">
        <i class="fa-solid fa-microphone-lines"></i>
        <span>Test Mic & Loa Laptop</span>
      </button>
    </div>
  </header>

  <!-- Main Grid -->
  <div class="flex-1 grid grid-cols-12 gap-0 overflow-hidden">
    
    <!-- LEFT PANEL: DOCUMENT & VECTOR P MANAGEMENT (5 cols) -->
    <div class="col-span-5 border-r border-slate-200 bg-white flex flex-col h-full overflow-hidden">
      
      <!-- Panel Header & Actions -->
      <div class="p-4 border-b border-slate-200 bg-slate-50/50 flex items-center justify-between">
        <div>
          <h2 class="font-bold text-slate-900 text-sm flex items-center">
            <i class="fa-solid fa-database text-teal-600 mr-2"></i>
            Kho Dữ liệu Tài liệu & Vector P
          </h2>
          <p class="text-xs text-slate-500 mt-0.5">Tải lên file Word, PDF hoặc viết thêm bài viết mới</p>
          <a href="/faq" class="inline-flex mt-2 text-sm font-semibold text-teal-700 underline">Quản lý FAQ trả lời nhanh</a>
        </div>
        <button onclick="reindexVectorStore()" id="btn-reindex" class="text-xs font-semibold bg-emerald-50 hover:bg-emerald-100 text-emerald-700 border border-emerald-200 px-3 py-1.5 rounded-lg flex items-center space-x-1.5 transition">
          <i class="fa-solid fa-bolt"></i>
          <span>Re-index Vector P</span>
        </button>
      </div>

      <!-- Drag & Drop Upload Zone -->
      <div class="p-4 pb-2 border-b border-slate-100">
        <div id="drop-zone" onclick="document.getElementById('file-input').click()" class="border-2 border-dashed border-slate-300 hover:border-teal-500 hover:bg-teal-50/30 rounded-xl p-4 text-center cursor-pointer transition flex flex-col items-center justify-center">
          <input type="file" id="file-input" class="hidden" accept=".docx,.pdf,.md,.txt" onchange="handleFileSelect(event)">
          <div class="w-10 h-10 rounded-full bg-teal-100 text-teal-600 flex items-center justify-center mb-2">
            <i class="fa-solid fa-cloud-arrow-up text-lg"></i>
          </div>
          <p class="text-xs font-semibold text-slate-700">Kéo thả file vào đây hoặc <span class="text-teal-600 underline">chọn từ máy tính</span></p>
          <p class="text-[11px] text-slate-400 mt-1">Hỗ trợ Microsoft Word (.docx), PDF (.pdf), Markdown (.md), Text (.txt)</p>
        </div>
        
        <!-- Quick text toggle -->
        <div class="mt-2.5 flex items-center justify-between text-xs">
          <span class="text-slate-500">Hoặc thêm nội dung nhanh:</span>
          <button onclick="toggleQuickText()" class="text-teal-600 hover:text-teal-700 font-semibold flex items-center">
            <i class="fa-solid fa-plus-circle mr-1"></i> Soạn thảo văn bản trực tiếp
          </button>
        </div>

        <!-- Quick Text Form (Collapsible) -->
        <div id="quick-text-form" class="hidden mt-3 bg-slate-50 p-3 rounded-xl border border-slate-200">
          <input type="text" id="quick-title" placeholder="Tiêu đề tài liệu (VD: Bang_gia_dich_vu_moi)" class="w-full text-xs px-3 py-2 border border-slate-200 rounded-lg mb-2 focus:outline-none focus:border-teal-500 bg-white">
          <textarea id="quick-content" rows="3" placeholder="Nhập nội dung quy trình, giờ làm việc, bảng giá hoặc hướng dẫn bệnh nhân..." class="w-full text-xs p-3 border border-slate-200 rounded-lg focus:outline-none focus:border-teal-500 bg-white"></textarea>
          <div class="flex justify-end space-x-2 mt-2">
            <button onclick="toggleQuickText()" class="text-xs text-slate-500 px-3 py-1.5 hover:text-slate-800">Hủy</button>
            <button onclick="submitQuickText()" class="text-xs bg-teal-600 hover:bg-teal-700 text-white font-medium px-3.5 py-1.5 rounded-lg transition">Lưu & Cập nhật AI</button>
          </div>
        </div>
      </div>

      <!-- Document List (Custom Scroll) -->
      <div class="flex-1 overflow-y-auto p-4 custom-scroll space-y-2.5">
        <div class="flex items-center justify-between text-xs font-semibold text-slate-400 uppercase tracking-wider mb-2">
          <span>Danh sách tài liệu đã nạp</span>
          <span id="doc-count-badge" class="bg-slate-200 text-slate-600 px-2 py-0.5 rounded-full">0</span>
        </div>
        <div id="document-list" class="space-y-2">
          <!-- Dynamic Document Cards -->
        </div>
      </div>

      <!-- Vector Chunks Preview Drawer Trigger -->
      <div class="p-3 border-t border-slate-200 bg-slate-50 flex items-center justify-between text-xs text-slate-500">
        <span class="flex items-center"><i class="fa-solid fa-network-wired text-slate-400 mr-2"></i> Trạng thái Vector P:</span>
        <button onclick="loadChunksModal()" class="text-teal-600 hover:underline font-semibold">Xem chi tiết các Vector Chunks &rarr;</button>
      </div>

    </div>

    <!-- RIGHT PANEL: INTERACTIVE AI VOICE & RAG PLAYGROUND (7 cols) -->
    <div class="col-span-7 flex flex-col h-full bg-slate-50 overflow-hidden">
      
      <!-- Playground Header -->
      <div class="p-4 border-b border-slate-200 bg-white flex items-center justify-between shrink-0">
        <div>
          <h2 class="font-bold text-slate-900 text-sm flex items-center">
            <i class="fa-solid fa-comments text-cyan-600 mr-2"></i>
            Khung Thử Nghiệm Hỏi Đáp & Giọng Nói AI
          </h2>
          <p class="text-xs text-slate-500 mt-0.5">Đặt câu hỏi để kiểm tra khả năng RAG tra cứu tài liệu và phát âm thanh</p>
        </div>
        <div class="flex items-center space-x-2">
          <span id="audio-status" class="hidden text-xs text-teal-600 font-medium items-center">
            <i class="fa-solid fa-volume-high animate-bounce mr-1"></i> Đang phát loa...
          </span>
        </div>
      </div>

      <!-- Quick Suggestion Chips -->
      <div class="px-4 py-2.5 bg-slate-100 border-b border-slate-200 flex items-center space-x-2 overflow-x-auto text-xs shrink-0 custom-scroll">
        <span class="text-slate-400 whitespace-nowrap"><i class="fa-regular fa-lightbulb text-amber-500 mr-1"></i> Câu hỏi mẫu:</span>
        <button onclick="askSample('Bệnh viện mấy giờ mở cửa?')" class="bg-white hover:bg-teal-50 hover:text-teal-700 text-slate-700 px-3 py-1 rounded-full border border-slate-200 whitespace-nowrap transition shadow-2xs">Bệnh viện mấy giờ mở cửa?</button>
        <button onclick="askSample('Khoa cấp cứu ở đâu và hotline là gì?')" class="bg-white hover:bg-teal-50 hover:text-teal-700 text-slate-700 px-3 py-1 rounded-full border border-slate-200 whitespace-nowrap transition shadow-2xs">Khoa cấp cứu ở đâu?</button>
        <button onclick="askSample('Bác sĩ khoa nội là ai?')" class="bg-white hover:bg-teal-50 hover:text-teal-700 text-slate-700 px-3 py-1 rounded-full border border-slate-200 whitespace-nowrap transition shadow-2xs">Bác sĩ khoa nội là ai?</button>
        <button onclick="askSample('Dẫn tôi đến phòng khám nội')" class="bg-white hover:bg-teal-50 hover:text-teal-700 text-slate-700 px-3 py-1 rounded-full border border-slate-200 whitespace-nowrap transition shadow-2xs">Dẫn tôi đến phòng khám nội</button>
      </div>

      <!-- Chat Stream -->
      <div id="chat-stream" class="flex-1 overflow-y-auto p-4 space-y-4 custom-scroll">
        
        <!-- Welcome Message -->
        <div class="flex items-start space-x-3">
          <div class="w-8 h-8 rounded-full bg-teal-600 text-white flex items-center justify-center text-xs shrink-0 shadow-sm">
            <i class="fa-solid fa-robot"></i>
          </div>
          <div class="bg-white border border-slate-200 rounded-2xl rounded-tl-sm p-4 text-xs text-slate-800 shadow-xs max-w-xl">
            <p class="font-semibold text-slate-900 mb-1">Xin chào! Tôi là Trợ lý AI Voice MediAssistRover.</p>
            <p class="text-slate-600">Tôi có thể trả lời mọi thông tin về bệnh viện dựa trên các tài liệu Word, PDF được nạp vào Vector Database ("Vector P"). Bạn hãy thử đặt câu hỏi bằng bàn phím hoặc nhấn nút Micro bên dưới ạ!</p>
          </div>
        </div>

      </div>

      <!-- Input Bar -->
      <div class="p-4 bg-white border-t border-slate-200 shrink-0">
        <form onsubmit="handleSendChat(event)" class="flex items-center space-x-2">
          
          <button type="button" id="btn-browser-mic" onclick="toggleBrowserMic()" class="w-10 h-10 rounded-xl bg-slate-100 hover:bg-slate-200 text-slate-600 hover:text-teal-600 flex items-center justify-center transition shrink-0" title="Nói qua Micro trình duyệt">
            <i class="fa-solid fa-microphone text-base"></i>
          </button>

          <div class="flex-1 relative">
            <input type="text" id="chat-input" placeholder="Nhập câu hỏi của bệnh nhân (VD: Bệnh viện có bảo hiểm y tế không?)..." class="w-full text-xs pl-4 pr-10 py-3 rounded-xl border border-slate-200 focus:outline-none focus:border-teal-500 focus:ring-1 focus:ring-teal-500 bg-slate-50 transition">
          </div>

          <button type="submit" id="btn-send" class="w-10 h-10 rounded-xl bg-teal-600 hover:bg-teal-700 text-white flex items-center justify-center transition shadow-md shadow-teal-600/20 shrink-0">
            <i class="fa-solid fa-paper-plane text-sm"></i>
          </button>
        </form>
      </div>

    </div>

  </div>

  <!-- Chunks Modal -->
  <div id="chunks-modal" class="hidden fixed inset-0 bg-slate-900/40 backdrop-blur-xs z-50 flex items-center justify-center p-6">
    <div class="bg-white rounded-2xl shadow-2xl max-w-3xl w-full max-h-[85vh] flex flex-col overflow-hidden border border-slate-200">
      <div class="p-4 border-b border-slate-200 flex items-center justify-between bg-slate-50">
        <h3 class="font-bold text-sm text-slate-800 flex items-center"><i class="fa-solid fa-cubes text-teal-600 mr-2"></i> Danh sách Vector Points Trong Database ('Vector P')</h3>
        <button onclick="closeChunksModal()" class="text-slate-400 hover:text-slate-600"><i class="fa-solid fa-xmark text-lg"></i></button>
      </div>
      <div id="chunks-container" class="p-4 overflow-y-auto flex-1 custom-scroll space-y-3">
        <!-- Chunks list -->
      </div>
    </div>
  </div>

  <!-- Audio Player Element -->
  <audio id="tts-audio" class="hidden"></audio>

  <!-- JavaScript Logic -->
  <script>
    let isRecording = false;
    let recognition = null;

    // Load initial data
    window.addEventListener('DOMContentLoaded', () => {
      fetchStats();
      fetchDocuments();
      initSpeechRecognition();
    });

    async function fetchStats() {
      try {
        const res = await fetch('/api/stats');
        const data = await res.json();
        document.getElementById('stat-points').innerText = data.vector_points_count;
        document.getElementById('stat-docs').innerText = data.documents_count;
      } catch (err) {
        console.error(err);
      }
    }

    async function fetchDocuments() {
      try {
        const res = await fetch('/api/documents');
        const docs = await res.json();
        const listEl = document.getElementById('document-list');
        document.getElementById('doc-count-badge').innerText = docs.length;
        
        if (docs.length === 0) {
          listEl.innerHTML = `<div class="p-4 text-center text-xs text-slate-400 bg-slate-50 rounded-xl border border-slate-200">Chưa có tài liệu nào. Hãy kéo thả file Word (.docx) vào trên.</div>`;
          return;
        }

        listEl.innerHTML = docs.map(d => {
          let icon = 'fa-file-lines text-slate-500';
          if (d.extension === 'docx') icon = 'fa-file-word text-blue-600';
          if (d.extension === 'pdf') icon = 'fa-file-pdf text-red-600';
          if (d.extension === 'md') icon = 'fa-file-code text-teal-600';

          return `
            <div class="p-3 bg-white border border-slate-200 hover:border-slate-300 rounded-xl flex items-center justify-between transition shadow-2xs group">
              <div class="flex items-center space-x-3 overflow-hidden">
                <i class="fa-solid ${icon} text-xl shrink-0"></i>
                <div class="truncate">
                  <p class="text-xs font-semibold text-slate-800 truncate" title="${d.filename}">${d.filename}</p>
                  <p class="text-[11px] text-slate-400">${d.size_kb} KB &bull; ${d.modified_at}</p>
                </div>
              </div>
              <button onclick="deleteDoc('${d.filename}')" class="text-slate-300 hover:text-red-500 p-1.5 transition rounded-lg hover:bg-red-50" title="Xóa tài liệu">
                <i class="fa-regular fa-trash-can text-sm"></i>
              </button>
            </div>
          `;
        }).join('');
      } catch (err) {
        console.error(err);
      }
    }

    async function handleFileSelect(event) {
      const file = event.target.files[0];
      if (!file) return;
      uploadFile(file);
    }

    // Drag and drop handlers
    const dropZone = document.getElementById('drop-zone');
    dropZone.addEventListener('dragover', (e) => { e.preventDefault(); dropZone.classList.add('border-teal-500', 'bg-teal-50/50'); });
    dropZone.addEventListener('dragleave', () => { dropZone.classList.remove('border-teal-500', 'bg-teal-50/50'); });
    dropZone.addEventListener('drop', (e) => {
      e.preventDefault();
      dropZone.classList.remove('border-teal-500', 'bg-teal-50/50');
      if (e.dataTransfer.files.length > 0) uploadFile(e.dataTransfer.files[0]);
    });

    async function uploadFile(file) {
      const formData = new FormData();
      formData.append('file', file);
      formData.append('auto_reindex', true);

      try {
        const btnReindex = document.getElementById('btn-reindex');
        btnReindex.innerHTML = `<i class="fa-solid fa-spinner animate-spin"></i> Đang nạp Vector P...`;
        const res = await fetch('/api/documents/upload', { method: 'POST', body: formData });
        const data = await res.json();
        
        if (!res.ok) throw new Error(data.detail || 'Lỗi tải lên');
        
        alert(`✅ ${data.message}`);
        fetchStats();
        fetchDocuments();
      } catch (err) {
        alert('❌ ' + err.message);
      } finally {
        document.getElementById('btn-reindex').innerHTML = `<i class="fa-solid fa-bolt"></i> Re-index Vector P`;
        document.getElementById('file-input').value = '';
      }
    }

    function toggleQuickText() {
      const el = document.getElementById('quick-text-form');
      el.classList.toggle('hidden');
    }

    async function submitQuickText() {
      const title = document.getElementById('quick-title').value.trim();
      const content = document.getElementById('quick-content').value.trim();
      if (!title || !content) return alert('Vui lòng điền tiêu đề và nội dung');

      const formData = new FormData();
      formData.append('title', title);
      formData.append('content', content);
      formData.append('auto_reindex', true);

      try {
        const res = await fetch('/api/documents/text', { method: 'POST', body: formData });
        const data = await res.json();
        alert('✅ ' + data.message);
        document.getElementById('quick-title').value = '';
        document.getElementById('quick-content').value = '';
        toggleQuickText();
        fetchStats();
        fetchDocuments();
      } catch (err) {
        alert('❌ ' + err.message);
      }
    }

    async function deleteDoc(filename) {
      if (!confirm(`Bạn có chắc muốn xóa file "${filename}"? AI sẽ cập nhật lại Vector Database ngay.`)) return;
      try {
        const res = await fetch(`/api/documents/${filename}`, { method: 'DELETE' });
        const data = await res.json();
        fetchStats();
        fetchDocuments();
      } catch (err) {
        alert('❌ ' + err.message);
      }
    }

    async function reindexVectorStore() {
      const btn = document.getElementById('btn-reindex');
      btn.innerHTML = `<i class="fa-solid fa-spinner animate-spin"></i> Đang cập nhật...`;
      try {
        const res = await fetch('/api/reindex', { method: 'POST' });
        const data = await res.json();
        alert('⚡ ' + data.message);
        fetchStats();
      } catch (err) {
        alert('❌ ' + err.message);
      } finally {
        btn.innerHTML = `<i class="fa-solid fa-bolt"></i> Re-index Vector P`;
      }
    }

    async function loadChunksModal() {
      const container = document.getElementById('chunks-container');
      container.innerHTML = `<div class="p-6 text-center text-xs text-slate-400"><i class="fa-solid fa-spinner animate-spin mr-2"></i> Đang tải dữ liệu Vector P...</div>`;
      document.getElementById('chunks-modal').classList.remove('hidden');

      try {
        const res = await fetch('/api/chunks?limit=40');
        const chunks = await res.json();
        if (chunks.length === 0) {
          container.innerHTML = `<p class="text-xs text-slate-400 text-center py-4">Chưa có vector chunk nào trong Database.</p>`;
          return;
        }

        container.innerHTML = chunks.map((c, i) => `
          <div class="p-3 bg-slate-50 border border-slate-200 rounded-xl text-xs space-y-1">
            <div class="flex items-center justify-between font-semibold text-slate-700">
              <span>#${i+1} [ID: ${c.id}] - ${c.title}</span>
              <span class="text-[10px] bg-teal-100 text-teal-800 px-2 py-0.5 rounded-full">${c.dimensions} dims</span>
            </div>
            <p class="text-slate-600 line-clamp-3 bg-white p-2 rounded-lg border border-slate-100 font-mono text-[11px]">${c.text}</p>
          </div>
        `).join('');
      } catch (err) {
        container.innerHTML = `<p class="text-xs text-red-500">Lỗi tải dữ liệu: ${err.message}</p>`;
      }
    }

    function closeChunksModal() {
      document.getElementById('chunks-modal').classList.add('hidden');
    }

    function askSample(text) {
      document.getElementById('chat-input').value = text;
      handleSendChat(new Event('submit'));
    }

    async function handleSendChat(e) {
      if (e) e.preventDefault();
      const input = document.getElementById('chat-input');
      const text = input.value.trim();
      if (!text) return;
      input.value = '';

      const stream = document.getElementById('chat-stream');
      
      // Append user bubble
      stream.innerHTML += `
        <div class="flex items-start justify-end space-x-3">
          <div class="bg-teal-600 text-white rounded-2xl rounded-tr-sm p-3.5 text-xs shadow-xs max-w-xl">
            ${text}
          </div>
          <div class="w-8 h-8 rounded-full bg-slate-200 text-slate-600 flex items-center justify-center text-xs shrink-0 font-bold">
            YOU
          </div>
        </div>
      `;

      // Loading bubble
      const loadingId = 'loading-' + Date.now();
      stream.innerHTML += `
        <div id="${loadingId}" class="flex items-start space-x-3">
          <div class="w-8 h-8 rounded-full bg-teal-600 text-white flex items-center justify-center text-xs shrink-0 shadow-sm animate-pulse">
            <i class="fa-solid fa-robot"></i>
          </div>
          <div class="bg-white border border-slate-200 rounded-2xl rounded-tl-sm p-4 text-xs text-slate-500 shadow-xs flex items-center space-x-2">
            <i class="fa-solid fa-circle-notch animate-spin text-teal-600"></i>
            <span>Đang tra cứu Vector Database & suy luận...</span>
          </div>
        </div>
      `;
      stream.scrollTop = stream.scrollHeight;

      // Call API
      const formData = new FormData();
      formData.append('query', text);

      try {
        const res = await fetch('/api/chat', { method: 'POST', body: formData });
        const data = await res.json();
        
        document.getElementById(loadingId).remove();

        let sourcesBadge = '';
        if (data.sources && data.sources.length > 0) {
          sourcesBadge = data.sources.map(s => `
            <span class="inline-flex items-center bg-teal-50 border border-teal-200 text-teal-800 text-[10px] font-medium px-2 py-0.5 rounded-full mr-1.5 mt-1.5">
              <i class="fa-solid fa-quote-left mr-1 text-[8px]"></i> ${s.source} (${(s.score*100).toFixed(0)}%)
            </span>
          `).join('');
        }

        stream.innerHTML += `
          <div class="flex items-start space-x-3">
            <div class="w-8 h-8 rounded-full bg-teal-600 text-white flex items-center justify-center text-xs shrink-0 shadow-sm">
              <i class="fa-solid fa-robot"></i>
            </div>
            <div class="bg-white border border-slate-200 rounded-2xl rounded-tl-sm p-4 text-xs text-slate-800 shadow-xs max-w-xl space-y-2">
              <p class="leading-relaxed text-slate-900 font-medium">${data.answer}</p>
              
              <!-- Inspector details -->
              <div class="pt-2 border-t border-slate-100 flex flex-wrap items-center justify-between text-[11px] text-slate-400">
                <span class="flex items-center"><i class="fa-solid fa-bullseye text-cyan-600 mr-1"></i> Intent: <strong class="text-slate-600 ml-1">${data.intent}</strong></span>
                <span><i class="fa-regular fa-clock mr-1"></i> ${data.duration_ms} ms</span>
              </div>
              
              <div class="flex flex-wrap">${sourcesBadge}</div>
              
              <!-- Action Audio Play Buttons -->
              <div class="pt-1.5 flex items-center space-x-2">
                <button onclick="playTTS(this, '${encodeURIComponent(data.answer)}', false)" class="text-[11px] bg-slate-100 hover:bg-slate-200 text-slate-700 px-2.5 py-1 rounded-md flex items-center space-x-1 transition">
                  <i class="fa-solid fa-volume-high text-teal-600"></i>
                  <span>Nghe trình duyệt</span>
                </button>
                <button onclick="playTTS(this, '${encodeURIComponent(data.answer)}', true)" class="text-[11px] bg-teal-50 hover:bg-teal-100 text-teal-700 px-2.5 py-1 rounded-md flex items-center space-x-1 transition">
                  <i class="fa-solid fa-laptop text-teal-600"></i>
                  <span>Phát ra LOA LAPTOP</span>
                </button>
              </div>
            </div>
          </div>
        `;
        stream.scrollTop = stream.scrollHeight;

      } catch (err) {
        document.getElementById(loadingId).remove();
        stream.innerHTML += `<div class="p-3 text-xs text-red-600 bg-red-50 rounded-xl border border-red-200">Lỗi: ${err.message}</div>`;
      }
    }

    async function playTTS(btn, encodedText, playSpeaker) {
      const text = decodeURIComponent(encodedText);
      const originalText = btn.innerHTML;
      btn.innerHTML = `<i class="fa-solid fa-spinner animate-spin"></i> Đang đọc...`;

      const formData = new FormData();
      formData.append('text', text);
      formData.append('play_speaker', playSpeaker);

      try {
        const res = await fetch('/api/tts', { method: 'POST', body: formData });
        if (!res.ok) throw new Error('Không thể tổng hợp giọng đọc');

        if (!playSpeaker) {
          const blob = await res.blob();
          const audioUrl = URL.createObjectURL(blob);
          const audio = document.getElementById('tts-audio');
          audio.src = audioUrl;
          audio.play();
        } else {
          alert('🔊 Đã phát câu trả lời qua LOA LAPTOP!');
        }
      } catch (err) {
        alert('Lỗi TTS: ' + err.message);
      } finally {
        btn.innerHTML = originalText;
      }
    }

    async function triggerLiveMic() {
      try {
        const res = await fetch('/api/test-live-mic', { method: 'POST' });
        const data = await res.json();
        alert('🎙️ Đã kích hoạt chế độ Kiểm thử Mic & Loa Laptop!\nLoa laptop sẽ chào bạn và mở mic thu âm trong giây lát.');
      } catch (err) {
        alert('Lỗi: ' + err.message);
      }
    }

    function initSpeechRecognition() {
      if ('webkitSpeechRecognition' in window || 'SpeechRecognition' in window) {
        const SpeechRec = window.SpeechRecognition || window.webkitSpeechRecognition;
        recognition = new SpeechRec();
        recognition.lang = 'vi-VN';
        recognition.continuous = false;
        recognition.interimResults = false;

        recognition.onstart = () => {
          isRecording = true;
          document.getElementById('btn-browser-mic').classList.add('bg-red-500', 'text-white', 'animate-pulse');
        };

        recognition.onresult = (event) => {
          const transcript = event.results[0][0].transcript;
          document.getElementById('chat-input').value = transcript;
          handleSendChat(new Event('submit'));
        };

        recognition.onerror = (e) => {
          console.warn('Speech recognition error:', e);
          stopBrowserMic();
        };

        recognition.onend = () => {
          stopBrowserMic();
        };
      }
    }

    function toggleBrowserMic() {
      if (!recognition) return alert('Trình duyệt của bạn không hỗ trợ Web Speech API. Hãy gõ bàn phím hoặc nhấn nút "Test Mic & Loa Laptop" ở góc phải.');
      if (isRecording) {
        recognition.stop();
        stopBrowserMic();
      } else {
        recognition.start();
      }
    }

    function stopBrowserMic() {
      isRecording = false;
      document.getElementById('btn-browser-mic').classList.remove('bg-red-500', 'text-white', 'animate-pulse');
    }
  </script>
</body>
</html>
"""


@app.get("/", response_class=HTMLResponse)
def index_view():
    return HTML_DASHBOARD


def start_server(host: str = "0.0.0.0", port: int = 7860):
    import uvicorn
    uvicorn.run(app, host=host, port=port)


if __name__ == "__main__":
    start_server()
