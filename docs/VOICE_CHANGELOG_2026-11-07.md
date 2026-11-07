# Đối chiếu thay đổi Voice — 07/11/2026

## Mốc tham chiếu

Các mốc trước khi đóng gói bản phát hành:

- `303bd99`: Git HEAD cũ ở bản D đang dùng làm bản chính.
- `53a1c29`, `81c1508`: hai commit main mới hơn; `origin/main` tại `81c1508` khi kiểm tra.
- Bản C đã pull `81c1508` trước đó; các file Voice/FAQ được đưa sang D. HEAD D vẫn `303bd99`, nhiều thay đổi chưa thành commit.

“Có file trên máy”, “đã commit”, “đã push”, “đã deploy” là bốn trạng thái khác nhau. Bản bàn giao này đưa các thay đổi đã kiểm tra lên `main` tiếp sau `81c1508`, giữ những cập nhật có sẵn từ main. Chưa triển khai lên Pi/server DuckDNS.

Trước khi phát hành, một số file đã có trên main còn untracked ở D do chuyển giữa các bản cài. Đã đối chiếu cả file trên đĩa và baseline khi đóng gói, không xem chúng là chức năng bị xóa. Trạng thái HEAD D cũ phía trên là mốc kiểm tra trước phát hành, không phải commit mà đồng đội cần checkout.

## Những phần đã có từ repo

- RAG đọc Word/PDF/Markdown, vector local, model sinh câu có ngữ cảnh.
- Cache Python TTL 300 giây, tối đa 128 mục; nhánh FAQ metadata đã tồn tại.
- BE Java assistant sessions/turns, STT/TTS, MySQL/Qdrant, upload/OCR/tách đoạn, phân quyền và xác nhận hành động.
- ROS bridge `mentorpi_ws/`, voice history, Vision và bộ khởi chạy laptop.
- Từ `53a1c29`/`81c1508`: API robot HTTPS `src/web/robot_api.py`, wake tiếng Việt `src/web/wake.py`, launcher `run_robot_api.ps1`, giải tên endpoint theo catalog, `completion_options` cho model/effort.

Các phần trên không được tính toàn bộ là chức năng mới của đợt tối ưu.

## Đã bổ sung/sửa trong AI_VOICE

### 1. Kho FAQ nhập và duyệt được

File: `src/rag/faq_store.py`, `src/web/faq_api.py`, `src/web/static/faq.html`, `data/faqs.json`, `tests/test_faq.py`.

- FAQ độc lập với chỉ mục vector sinh lại; gồm alias, answer, nguồn, trích đoạn, trạng thái duyệt/phạm vi và thời gian cập nhật.
- Chỉ `approved=true`, `visibility=PUBLIC`, có nguồn mới trả thẳng câu đã duyệt.
- Thêm/sửa/tắt/xóa/thử FAQ, kiểm tra độ dài và alias trùng; revision chống ghi đè từ tab cũ.
- Ghi nguyên tử và reload ở lượt sau; dữ liệu JSON lỗi không được coi như câu đã duyệt hợp lệ.
- Re-index không xóa FAQ. Bản nháp/nội bộ không trộn vào tìm kiếm công khai.

### 2. Sửa FAQ bị trượt và cache

File: `src/rag/rag_pipeline.py`, `src/web/app.py`, `src/web/robot_api.py`, `src/voice_engine.py`.

- Tách FAQ lookup khỏi khóa cache có history; trước đây history có thể làm FAQ metadata trượt dù đúng câu.
- Kiểm tra FAQ trước vector/model, gắn FAQStore vào các entry point.
- Cache AI vẫn phân biệt history; đổi vector/revision FAQ thì xóa cache liên quan.
- Giữ dấu tiếng Việt, số, phủ định; không tự coi “đúng chủ đề” là khớp.
- Thêm alias mic thực nhận cho `wi-fi` và câu xe lăn thiếu “cho mượn”, giữ nguyên answer/nguồn.

### 3. Dùng lại client và đo từng chặng

File: `src/model_client.py`, `src/latency.py`, pipeline RAG, API robot, wake và `tools/benchmark_voice_local.py`.

- Dùng lại SDK/HTTP pool ở các nhánh RAG/phân giải điểm đến Python, đóng khi API dừng.
- `AI_PRELOAD_CLIENTS=true` chuẩn bị SDK/pool; không gọi provider trả phí để warm. Chưa tối ưu toàn bộ HTTPS transport Pi.
- `AI_WAKE_PRELOAD=true` tùy chọn nạp model wake trước, cần đủ dependency/model.
- Thêm `answer_mode`, `timings_ms`, `metrics_scope=ai_server_only`, `Server-Timing`, `X-AI-Cache` ở API 7861.
- Replay session/turn giữ body gốc; thời gian replay hiện tại ở header, không diễn giải `processing_ms` gốc thành thời gian request mới.
- Headless Java RPC chưa xuất đầy đủ breakdown STT/TTS; không tuyên bố mọi entry point đều có cùng telemetry.

### 4. UI và launcher FAQ

File: `src/platform/faq_launcher.py`, `run_faq.*`, `run_web.*`, `src/platform/runtime.py`, `dashboard.html`.

- Studio có màn hình FAQ và `/api/runtime` để xem nơi lưu thực.
- Ưu tiên `.venv-voice`, dùng lại đúng project, không vô tình ghi FAQ sang checkout khác cùng cổng.
- Dashboard có liên kết FAQ. Studio 7860 tùy chọn, không bắt buộc cho Compose mới.

### 5. Chạy chung Java + Python + database

File: `backend/compose.yaml`, `Dockerfile.voice`, `requirements.voice.txt`, `.dockerignore`, `src/service/`, `VoiceServiceGateway.java`, `AssistantFaqController.java`, `AssistantService.java`.

- Một Compose cho Java, Python headless, MySQL, Qdrant; dependency health trước startup Java.
- Python chỉ ở mạng Docker, yêu cầu `VOICE_SERVICE_TOKEN`; Java kiểm tra quyền ADMIN cho CRUD/test FAQ.
- Bind mount `../data`; lưu FAQ qua Java ghi trực tiếp file trên host.
- Java hỏi FAQ trước retrieval/model, giữ citation và `model=null` khi khớp.
- Không khớp thì Java tìm DB/Qdrant; không có nguồn mới thử Python RAG cho nội dung công khai phù hợp, không đẩy yêu cầu nội bộ vào nhánh đó.
- Không thay quyền xác nhận/dispatch hoặc phát vận tốc từ LLM. Các lệnh dừng/hủy, SOS, chào và đề nghị dẫn đi không được biến thành FAQ điều khiển.
- Launcher host Java `run_backend_local.ps1` đã thêm trước đó vẫn có; hiện ưu tiên Compose. Không chạy hai cách cùng cổng.

### 6. Sửa cách thử

`test_live_voice.py` dùng chung xử lý chat; Studio nói rõ xem trước ý định, chưa tạo job hoặc gửi SOS. Giữ entity `destination_room` cho phòng cũ bên cạnh endpoint catalog, tránh làm hỏng VoiceEngine cũ.

## Những thay đổi ở repo khác

### FE_WEB_MAR

Thêm `FaqPanel.jsx`, CRUD/test trong `voiceApi.js` và nối vào màn hình quản trị. Pull AI_VOICE chỉ có API, không tự có FE mới. Cần cập nhật FE riêng hoặc dùng Studio tùy chọn. Build FE đạt không đồng nghĩa đã thử UI/mic/loa trên robot.

### RASPPI_MAR / PI_MAR

Phát triển từ `e8d1ed6` trong checkout Pi riêng, chưa cài robot:

- VAD từ 810 xuống 600 ms: giảm ngưỡng 210 ms khi có im lặng liên tục, không phải tổng mọi lượt đã giảm 210 ms.
- Giữ đuôi WAV 150 ms: giảm khoảng 21.120 byte PCM16 mono 16 kHz so với đuôi 810 ms trong điều kiện tương ứng, giữ tiếng nói.
- Loại tiếng động ngắn để tránh chờ tới trần 15 giây.
- Giọng đọc native 150 → 170 WPM, chỉnh được, không thay vận tốc robot.
- Chỉnh âm lượng bằng mảng PCM thay vì vòng byte/mẫu; giữ kiểm tra WAV, endian/hash và hủy phát.
- Thêm `VOICE_TIMING`, các bước thu/STT/AI/TTS/poll/loa/lịch sử; `--no-transcripts` cho phép đo không ghi lời nói.
- TTS tôn trọng `Retry-After`. Chưa chuyển sang audio streaming.

Các sửa đổi này không ở `mentorpi_ws/`. [Gói Pi](../deploy/pi/README.md) cho phép bàn giao khi nhánh RASPPI_MAR chưa xuất bản. Không chạy companion native cùng ROS bridge.

## Số đo: giảm phần nào?

### Các lượt thực qua BE local

DB ghi xe lăn 8.461 ms, Wi-Fi 3.903 ms khi gọi `gpt-6.1-sol`; transcript khác alias nên FAQ trượt. Sau bổ sung alias và thử chính API public assistant:

- Xe lăn: 26 ms HTTP, BE 8 ms.
- Wi-Fi: 28 ms HTTP, BE 9 ms.
- `model=null`, citation đúng FAQ, answer nguyên văn đã lưu.

Các lượt riêng lẻ, không phải benchmark p50/p95. Lượt kiểm chứng gửi text, không gồm STT/TTS/loa. Không cam kết robot trả lời ra tiếng trong 26 ms.

### Benchmark offline đã lưu

Windows/Python 3.11.9, FAQ mẫu với history khác nhau 100 lượt: số lần tìm vector/gọi hàm sinh giả lập 100/100 → 0/0; p50 CPU 0,098 → 0,021 ms. Không đo provider thật ở phép thử này.

Tìm vector 37 đoạn thật: p50 0,163 ms, p95 0,200 ms; không phải bước mất nhiều giây ở lượt gọi model.

Chỉnh âm lượng WAV 5 giây/24 kHz PCM16, mức 70%, 30 lượt: p50 68,190 → 19,190 ms; p95 72,060 → 19,682 ms. Đây là code Pi chạy trên Windows, không phải số đo Pi.

## Kiểm chứng và phần còn thiếu

- Test AI lựa chọn trong README: **100 passed** khi viết tài liệu.
- Test Pi VAD/WAV/cloud speech: **31 passed, 1 skipped** do thiếu fixture BE.
- Java `AssistantServiceTest`: **7 passed** ở lượt kiểm chứng Compose, gồm nhánh FAQ không gọi retrieval/model.
- Compose đã build/run, BE `UP`, dependency healthy; kiểm tra ADMIN → Java → Python → file host và dọn bản ghi thử.
- FE build đạt; chưa xác minh browser trực quan trong đợt này; chưa có Pi để đo mạng/phần cứng/wake/mic/loa end-to-end.

Bằng chứng XML/JSON trên máy phát triển: `D:/MAR_Fall_AI/readme-audit-2026-11-07/`, benchmark cũ `D:/MAR_Fall_AI/voice-latency-2026-11-07/`. Không phải đường dẫn bắt buộc của đồng đội.

Chưa làm:

- Đồng bộ server DuckDNS vào FAQ/vector local; public API đang trả 0 mục không chứng minh DB nội bộ trống.
- Sửa đầy đủ client sync cũ: phân trang, articles, quyền Device JWT, tham số search.
- Deploy endpoint assistant/FAQ mới lên server hoặc cài Pi thật; chưa đổi firmware wake/camera/navigation trong đợt tài liệu.
- Đo STT/TTS/mạng thật; tăng WPM chỉ rút ngắn thời gian đọc.
- Reload vector tự động ở Python headless. Thay vector cần restart; không áp tính năng reload FAQ cho mọi dữ liệu.

## Bàn giao đúng bản

Đồng đội lấy bản `main` có cập nhật này. Trước pull phải xem `git status`, giữ cấu hình riêng và xử lý các thay đổi chưa commit; không reset/pull đè working tree. `.env`, token, private key, audio và dữ liệu runtime không nằm trong gói phát hành.

Cần bản AI_VOICE này, FE mới nếu dùng FAQ trên FE và Pi native đã cập nhật nếu dùng companion. Commit cũ `81c1508` chưa có toàn bộ phần bổ sung; bản vá Pi kèm repo không tự thay thế bước cài trên robot.
