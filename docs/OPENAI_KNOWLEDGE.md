# OpenAI knowledge store cho robot

API quản lý/upload đặt trong repo `BE_MAR`. Hướng dẫn đầy đủ nằm ở
`BE_MAR/docs/OPENAI_KNOWLEDGE.md` trong workspace.

Đặt biến môi trường cho tiến trình `src.web.robot_api:app` rồi khởi động lại dịch vụ cục bộ.

```dotenv
OPENAI_VECTOR_STORE_ID=vs_6af8249d9d44819182d2ad94213eacfa
OPENAI_API_KEY=<key có quyền truy cập vector store>
```

Giữ cấu hình model, token của robot và TTS hiện có. Robot tiếp tục gọi `/api/robot/chat`.
Câu hỏi kiến thức được tìm trong vector store, câu trả lời có `sources[].file_id` và tên tài liệu.
`/health` báo `knowledge_source=OPENAI_VECTOR_STORE`. Với biến store để trống, dùng nguồn kiến thức cũ.
Mỗi câu hỏi mới truy xuất lại nguồn; phát lại cùng lượt hội thoại giữ kết quả đã trả.

Kiểm thử chính bằng mock OpenAI:

```powershell
python -m pytest -q tests/test_openai_knowledge.py tests/test_robot_api_latency.py tests/test_intent.py
```

Triển khai EC2 và thử upload/tổng hợp giọng nói thật thực hiện ở giai đoạn sau.
