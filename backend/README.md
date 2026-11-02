# MediAssistRover Backend

Java 21, Spring Boot 3.5, MySQL 8.4, MQTT/TLS. Giữ luồng robot từ Downloads và gộp nghiệp vụ GitHub main `0dc215c58b8308ca3dda7290c14b131e2e2046df`.

## Chạy

Giữ `.env` và chứng chỉ hiện tại. Môi trường mới dùng `.env.example`, điền database/JWT/MQTT và tạo chứng chỉ HTTPS.

```powershell
docker compose config --quiet
docker compose --profile production up -d --build mysql qdrant backend reverse-proxy
docker compose --profile production restart reverse-proxy
docker compose logs --tail 80 backend
```

Swagger: http://localhost:8080/swagger-ui/index.html. UI: `mapping.html`, `knowledge-admin.html`, `assistant-kiosk.html`, `assistant-staff.html`. Sao lưu DB trước khi áp dụng migration; không xóa volume database.

## OpenAI / bản đồ

Trong `.env`: `TTS_ENABLED=true`, `OPENAI_API_KEY=<key riêng>`, `OPENAI_TTS_MODEL=gpt-realtime-2.1-mini`, `OPENAI_TTS_VOICE=coral`. Key chỉ ở BE. Backend nhận PCM16 24 kHz từ Realtime API, đóng gói WAV và cache trong volume `speech-cache`.

- POST /api/v1/pi/speech/prepare: ADMIN chuẩn bị âm thanh, body text/languageCode/speedWpm.
- POST /api/v1/speech-jobs: tạo job phát loa; Pi tải WAV từ BE qua HTTPS.
- POST /api/v1/maps/import: multipart floorId/mapCode/versionNo/mapName/yaml/pgm; map DRAFT.
- POST/PUT /api/v1/endpoints: cấu hình endpoint.
- GET /api/v1/pi/maps/{mapId}/bundle: YAML/PGM/metadata; DEVICE chỉ tải map được gán.

Giữ 11 step types. BE xử lý nghiệp vụ; Pi chạy handler robot. MOVE cần map đã nạp và profile ACK. Tạo workflow không tự tạo handler phần cứng mới.

## Knowledge RAG và voice assistant

1. Bật `ASSISTANT_ENABLED=true`, điền `OPENAI_API_KEY`, chạy thêm Qdrant.
2. Đăng nhập ADMIN và mở `/knowledge-admin.html` để upload PDF, DOCX, JPG hoặc PNG. Tệp tối đa 25 MB; pipeline tự extract/OCR, chunk, embedding và chuyển `READY`.
3. Chỉ bật `ASSISTANT_PUBLIC_ENABLED` sau khi điền UUID robot vào `ASSISTANT_PUBLIC_ROBOT_IDS`. API Guest có rate limit và action cần nonce xác nhận 60 giây.
4. `ASSISTANT_VOICE_ACTIONS_ENABLED` và `ASSISTANT_PI_BRIDGE_ENABLED` mặc định tắt; chỉ bật sau hardware acceptance.
5. Kiosk tự lấy robot mặc định từ `ASSISTANT_PUBLIC_DEFAULT_ROBOT_ID` (hoặc robot đầu tiên trong allowlist), tự mở Guest session và chờ `Hello MAR`. Lần đầu vẫn cần cấp quyền microphone; nút `Bắt đầu thủ công` phát lời giới thiệu và nghe ngay. Sau `ASSISTANT_IDLE_TIMEOUT_SECONDS` giây im lặng, MAR phát lời kết rồi quay lại chờ wake word.
6. `ASSISTANT_GENERAL_KNOWLEDGE_ENABLED`, `ASSISTANT_HYBRID_RETRIEVAL_ENABLED` và `ASSISTANT_AUTOMATIC_DEDUPLICATION_ENABLED` điều khiển riêng fallback kiến thức chung, RRF vector/keyword và lưu duy nhất bản READY mới nhất. Câu trả lời GENERAL luôn có cảnh báo và không tạo navigation/escort/SOS.

API mới:

- `/api/v1/admin/knowledge/documents`: upload, xem OCR/chunks, re-index, archive.
- `/api/v1/public/assistant/**`: kiosk Guest, chỉ nguồn PUBLIC.
- `/api/v1/assistant/**`: STAFF/ADMIN, cho phép nguồn INTERNAL.
- `/api/v1/pi/assistant/**`: DEVICE JWT cho `mediassist_voice_bridge`.

MySQL giữ metadata/text/citation/audit, volume `knowledge-files` giữ file gốc, Qdrant giữ vector. Audio đầu vào chỉ được xử lý trong bộ nhớ và không lưu trong backend. Transcript, câu trả lời và nguồn được xóa sau 90 ngày.

Package ROS 2 nằm ở `../mentorpi_ws/src/mediassist_voice_bridge`; xem `../mentorpi_ws/README.md` để chạy trên MentorPi M1.

Lệnh upload Pi, cấu hình HTTPS và script API nằm trong README duy nhất của gói MAR_OpenAI_Final_2026_10_20 trong Downloads. Source BE này đã được sửa; container đang chạy chỉ đổi sau khi recreate.
