# 🎙️ MediAssistRover AI Voice Assistant (AI_VOICE)

> **Phân hệ Trợ lý Giọng nói Tiếng Việt thông minh cho Robot Bệnh viện MediAssistRover**  
> Tích hợp trực tiếp với cơ sở dữ liệu bệnh viện (`HospitalInformationArticle` & `HospitalInformation` trong MySQL) và Backend Spring Boot 3.5 qua Device JWT Bearer Authentication.

---

## 🌟 1. Tổng quan Kiến trúc

AI Voice Module biến Robot MediAssistRover thành một trợ lý giao tiếp trực tiếp với bệnh nhân và người nhà tại sảnh bệnh viện:
- **Kiến trúc RAG & Vector Database ("Vector P"):** Tự động đọc và vector hóa toàn bộ tài liệu Word (`.docx`), PDF (`.pdf`), Markdown (`.md`) và dữ liệu bài viết từ MySQL Database (`HospitalInformationArticle`) thành các Vector Points ($P \in \mathbb{R}^d$), lưu trữ bền vững tại `data/hospital_vector_store.json`.
- **Nghe hiểu tiếng Việt:** Chuyển đổi giọng nói thành văn bản thông qua Whisper STT hoặc Google Cloud STT với thuật toán Voice Activity Detection (VAD) nhận diện khi bệnh nhân bắt đầu và kết thúc câu nói.
- **Phân loại ý định 2 tầng:**
  - **Tầng 1 (Sub-millisecond):** Keyword & Regex Pattern Matching siêu tốc tiếng Việt cho các yêu cầu thường gặp (hỏi thông tin, dẫn đường, cấp cứu, chào hỏi).
  - **Tầng 2 (LLM Fallback):** OpenAI GPT-4o-mini xử lý các câu hỏi phức tạp hoặc mơ hồ.
- **Truy xuất thông tin Bệnh viện thời gian thực:** Kết nối với Backend Spring Boot (`GET /api/v1/hospital-information` & `HospitalInformationQueryService`) để trả lời giờ làm việc, chi phí, thủ tục, khoa phòng, bác sĩ, bảo hiểm y tế.
- **Tạo yêu cầu Dẫn đường (Escort Request):** Khi bệnh nhân yêu cầu *"Dẫn tôi đến phòng khám nội"*, hệ thống tự động bóc tách thực thể phòng (`P_KHAM_NOI_01`) và kích hoạt quy trình dẫn đường trên Robot.
- **Báo động Khẩn cấp (SOS):** Khi phát hiện tiếng kêu cứu, ngất, té ngã, hệ thống kích hoạt chuông báo và điều phối điều dưỡng khẩn cấp.
- **Phản hồi bằng Giọng nói Tự nhiên (TTS):** Sử dụng `OpenAiTtsService` (`gpt-4o-mini-tts`, giọng `coral`) của Backend với bộ nhớ đệm WAV cache trên đĩa để robot trả lời tức thì.

---

## 📁 2. Cấu trúc Thư mục Source Code

```text
d:\AI_VOICE\
├── .env.example               # Mẫu cấu hình môi trường
├── requirements.txt           # Danh mục thư viện Python
├── README.md                  # Hướng dẫn tổng quan
├── run_voice.ps1              # Script khởi động nhanh PowerShell
├── run_voice.bat              # Script khởi động nhanh Windows CMD
├── config/
│   └── voice_config.json      # Cấu hình âm thanh, STT, intent, prompts
├── src/
│   ├── __init__.py
│   ├── main.py                # Entry point (hỗ trợ cả --text và voice mode)
│   ├── voice_engine.py        # Bộ điều phối luồng Voice Pipeline toàn diện
│   ├── rag/                   # TẦNG RAG & VECTOR DATABASE ('VECTOR P')
│   │   ├── __init__.py
│   │   ├── document_loader.py # Đọc file Word (.docx), PDF, Markdown, MySQL API
│   │   ├── chunker.py         # Cắt đoạn thông minh tiếng Việt (chunk_size: 400)
│   │   ├── embeddings.py      # OpenAI / Gemini / Local Subword N-Gram Embeddings
│   │   ├── vector_store.py    # Vector Database bền vững, tính Cosine Similarity
│   │   ├── rag_pipeline.py    # Prompt Augmentation & Sinh câu trả lời TTS
│   │   └── build_index.py     # Script nạp và cập nhật cơ sở dữ liệu Vector P
│   ├── api/                   # Tầng giao tiếp Backend Spring Boot
│   │   ├── __init__.py        # BackendClient (Quản lý Device JWT, Speech, Query)
│   │   └── voice_reporter.py  # VoiceReporter (Lưu VoiceInteraction & VoiceCommand)
│   ├── audio/                 # Tầng thu âm và phát loa
│   │   ├── __init__.py        # MicrophoneCapture (VAD, tự ngắt khi im lặng)
│   │   └── player.py          # SoundDevice WAV playback
│   ├── intent/                # Tầng phân loại ý định
│   │   └── __init__.py        # IntentClassifier (Regex + Bóc tách thực thể + LLM)
│   ├── query/                 # Tầng tra cứu dữ liệu
│   │   └── __init__.py        # HospitalQueryHandler (Kết nối DB bệnh viện)
│   ├── stt/                   # Tầng Speech-to-Text
│   │   └── __init__.py        # STTBase, WhisperSTT, GoogleSTT
│   ├── tts/                   # Tầng Text-to-Speech
│   │   └── __init__.py        # SpeechClient (Gọi BE TTS, phát âm thanh)
│   └── utils/                 # Tiện ích chung
│       ├── __init__.py
│       ├── envelope.py        # Bộ đóng gói PiEnvelope v1.0 chuẩn hợp đồng BE
│       └── logger.py          # Logging chuẩn với Rich
├── data/
│   ├── documents/             # Nơi chứa tài liệu Word (.docx), PDF, Markdown
│   │   ├── so_tay_benh_nhan_mediassist.docx
│   │   └── quy_trinh_kham_va_bac_si.md
│   └── hospital_vector_store.json # Cơ sở dữ liệu Vector P bền vững
├── tests/                     # 34 Unit Tests tự động (34/34 Passed)
│   ├── test_intent.py         # Test nhận diện ý định tiếng Việt (20 test cases)
│   ├── test_rag.py            # Test RAG Pipeline & Vector DB (9 test cases)
│   ├── test_query.py          # Test tra cứu dữ liệu bệnh viện
│   └── test_voice_engine.py   # Test dispatching luồng Engine
└── docs/
    ├── AI_VOICE_ARCHITECTURE_AND_INTEGRATION_GUIDE.md  # Tài liệu tích hợp hệ thống
    └── RAG_VECTOR_PIPELINE_ARCHITECTURE.md             # Tài liệu chuyên sâu về RAG & Vector P
```

---

## 🚀 3. Hướng dẫn Cài đặt & Khởi chạy

### Bước 1: Chuẩn bị môi trường Python 3.11+
```powershell
cd d:\AI_VOICE
py -3.11 -m venv venv
.\venv\Scripts\Activate.ps1
pip install -r requirements.txt
```

### Bước 2: Thiết lập biến môi trường
Tạo file `.env` từ `.env.example`:
```dotenv
BACKEND_URL=http://localhost:8080
ROBOT_CODE=ROBOT_MAR_01
DEVICE_SECRET=6rGL6RPV9VgbVZfmcHXmA2uz-oST8speM1bO1eI1ozg
STT_PROVIDER=whisper
OPENAI_API_KEY=sk-proj-your-api-key
VOICE_LANGUAGE=vi-VN
```

### Bước 3: Chạy Unit Tests để kiểm tra
```powershell
py -3.11 -m pytest tests
# Kết quả: 25 passed in 0.33s
```

### Bước 4: Khởi chạy Trợ lý Voice

**Chế độ 1: Thử nghiệm bằng bàn phím (Text Mode - Không cần Microphone):**
```powershell
py -3.11 -m src.main --text
```
*Nhập câu hỏi như: "Bệnh viện mấy giờ mở cửa?", "Dẫn tôi đến nhà thuốc", "Chi phí khám bệnh ra sao?"*

**Chế độ 2: Chế độ Giọng nói thực tế (Voice Mode qua Microphone & Loa):**
```powershell
py -3.11 -m src.main
```

---

## 🔗 4. Bảng Tra cứu Ý định (Intents) & Hành động

| Intent Code | Câu nói mẫu của bệnh nhân | Hành động của Robot |
|:---|:---|:---|
| `QUERY_HOSPITAL_INFO` | *"Giờ khám bệnh mấy giờ?", "Bệnh viện có bảo hiểm y tế không?", "Khoa nội ở tầng mấy?"* | Query `HospitalInformationArticle` từ MySQL ➔ Tổng hợp câu trả lời ➔ Phát giọng nói TTS |
| `ESCORT_REQUEST` | *"Dẫn tôi đến phòng khám nội", "Nhà thuốc ở đâu?", "Chỉ đường đến xét nghiệm"* | Bóc tách thực thể phòng (`P_KHAM_NOI_01`) ➔ Tạo lệnh dẫn đường ➔ Báo bệnh nhân đi theo |
| `EMERGENCY_SOS` | *"Cứu tôi với!", "Tôi bị ngã", "Khó thở quá, cấp cứu!"* | Bật còi báo động ➔ Gửi Incident SOS về trung tâm điều dưỡng ➔ Phát âm thanh trấn an |
| `GREETING` | *"Xin chào", "Chào robot", "Hello"* | Phát lời chào thân thiện kèm giới thiệu trợ lý |
| `FAREWELL` | *"Cảm ơn robot nhé", "Tạm biệt"* | Phát lời chúc sức khỏe |
| `SELF_INTRO` | *"Bạn là ai?", "Bạn làm được những việc gì?"* | Giới thiệu chức năng robot trong bệnh viện |
