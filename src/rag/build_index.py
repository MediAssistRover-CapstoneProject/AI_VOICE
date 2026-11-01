#!/usr/bin/env python3
"""Build and update the Hospital Vector Database ('Vector P').

Ingests:
1. Word documents (.docx), PDFs (.pdf), Markdown (.md) in data/documents
2. Live hospital information articles from Backend MySQL API (if available)

Outputs:
data/hospital_vector_store.json
"""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

from dotenv import load_dotenv

# Root path resolution
ROOT = Path(__file__).resolve().parent.parent.parent
if str(ROOT) not in sys.path: sys.path.insert(0, str(ROOT))
load_dotenv(ROOT / ".env", override=False)

from src.api import BackendClient
from src.rag.chunker import VietnameseTextSplitter
from src.rag.document_loader import Document, DocumentLoader
from src.rag.embeddings import create_embeddings
from src.rag.vector_store import HospitalVectorStore
from src.utils.logger import setup_logger

log = setup_logger("mar_rag_builder")


def build_hospital_index(
    documents_dir: Path | str | None = None,
    output_path: Path | str | None = None,
    sync_backend: bool = True,
    embedding_provider: str = "local",
) -> HospitalVectorStore:
    doc_dir = Path(documents_dir) if documents_dir else ROOT / "data" / "documents"
    out_file = Path(output_path) if output_path else ROOT / "data" / "hospital_vector_store.json"

    log.info("=" * 60)
    log.info("[BUILD-INDEX] 📚 Building Hospital Vector Database ('Vector P')")
    log.info("[BUILD-INDEX] Documents folder: %s", doc_dir)
    log.info("[BUILD-INDEX] Output vector DB: %s", out_file)
    log.info("[BUILD-INDEX] Embedding provider: %s", embedding_provider)
    log.info("=" * 60)

    loader = DocumentLoader(doc_dir)
    raw_docs: list[Document] = []

    # 1. Load local documents (.docx, .pdf, .md, .txt)
    local_docs = loader.load_directory()
    raw_docs.extend(local_docs)
    log.info("[BUILD-INDEX] Loaded %d local document files", len(local_docs))

    # 2. Try loading from Backend MySQL API if requested
    if sync_backend:
        backend_url = os.getenv("BACKEND_URL", "http://localhost:8080")
        robot_code = os.getenv("ROBOT_CODE", "ROBOT_MAR_01")
        device_secret = os.getenv("DEVICE_SECRET", "")
        if device_secret:
            try:
                client = BackendClient(backend_url, robot_code, device_secret, timeout=5.0)
                be_docs = loader.load_from_backend(client)
                raw_docs.extend(be_docs)
                client.close()
            except Exception as exc:
                log.info("[BUILD-INDEX] Backend not reachable (%s), using local docs only", exc)

    if not raw_docs:
        log.warning("[BUILD-INDEX] No documents found! Aborting.")
        sys.exit(1)

    # 3. Text Chunking
    splitter = VietnameseTextSplitter(chunk_size=400, chunk_overlap=80)
    chunks = splitter.split_documents(raw_docs)
    log.info("[BUILD-INDEX] Total document chunks created: %d", len(chunks))

    # 4. Generate Embeddings & Build Vector Store
    embeddings = create_embeddings(
        provider=embedding_provider,
        openai_api_key=os.getenv("OPENAI_API_KEY", ""),
        gemini_api_key=os.getenv("GEMINI_API_KEY", ""),
    )

    vector_store = HospitalVectorStore(embeddings=embeddings, persist_path=out_file)
    # Clear old points and add freshly chunked docs
    vector_store.points = []
    vector_store.add_documents(chunks)
    vector_store.save(out_file)

    log.info("[BUILD-INDEX] ✅ Successfully indexed %d Vector Points (Vector P) into %s",
             len(vector_store.points), out_file.name)

    # 5. Quick Test Search
    test_queries = [
        "Bệnh viện mấy giờ mở cửa?",
        "Khoa cấp cứu ở đâu?",
        "Chi phí khám bệnh bao nhiêu?",
    ]
    log.info("\n--- KIỂM THỬ TRUY VẤN VECTOR SEARCH ---")
    for q in test_queries:
        res = vector_store.similarity_search(q, top_k=1)
        if res:
            log.info("❓ '%s' \n  ↳ [Score: %.3f] %s", q, res[0].score, res[0].document.page_content[:100].replace('\n', ' '))
        else:
            log.warning("❓ '%s' -> Không tìm thấy", q)

    return vector_store


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Build Hospital Vector DB ('Vector P')")
    parser.add_argument("--docs", default=str(ROOT / "data" / "documents"), help="Path to documents folder")
    parser.add_argument("--output", default=str(ROOT / "data" / "hospital_vector_store.json"), help="Output JSON path")
    parser.add_argument("--embeddings", default=os.getenv("EMBEDDING_PROVIDER", "local"), choices=["local", "openai", "gemini"])
    parser.add_argument("--no-backend", action="store_true", help="Skip syncing from backend MySQL")
    args = parser.parse_args()

    build_hospital_index(
        documents_dir=args.docs,
        output_path=args.output,
        sync_backend=not args.no_backend,
        embedding_provider=args.embeddings,
    )
