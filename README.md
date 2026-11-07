# MediAssistRover — AI Voice, FAQ và kết nối robot

AI Voice trả lời thông tin bệnh viện bằng FAQ đã duyệt hoặc tài liệu có nguồn. Repo còn chứa BE Java, camera/té ngã và cầu nối ROS 2. Raspberry Pi đảm nhiệm mic, loa và chương trình phần cứng; không cần chạy toàn bộ BE trên Pi.

Tài liệu mô tả **bản cập nhật 07/11/2026**, phát hành trên nhánh `main`, phát triển tiếp từ `81c1508`. Đồng đội cập nhật cả code và tài liệu trong commit mới; việc push lên GitHub không tự triển khai lên server hoặc robot.

## Đọc theo nhu cầu

- [Đối chiếu: đã có từ repo, đã sửa thêm gì, số đo và giới hạn](docs/VOICE_CHANGELOG_2026-11-07.md).
- [Cài lại và nối Raspberry Pi với AI, BE, mic và loa](docs/RASPBERRY_PI_VOICE_SETUP.md).
- [Chạy cả BE bằng Docker Compose](backend/RUN_ALL.md).
- [Nhập, duyệt và thử FAQ](docs/FAQ_GUIDE.md).
- [Đọc độ trễ và tối ưu từng bước](docs/voice-latency.md).
- [Voice + Vision trên laptop](docs/UNIFIED_SYSTEM_GUIDE.md); [camera quản trị](docs/ADMIN_FALL_CAMERA_GUIDE.md).

## Những cập nhật chính

- **Kho FAQ riêng `data/faqs.json`:** nhiều cách hỏi cho một đáp án, duyệt/tắt/sửa/xóa/thử khớp. Câu công khai đã duyệt được trả trực tiếp, bỏ qua model sinh đáp án.
- **Sửa FAQ trượt khi có lịch sử:** phép khớp không phụ thuộc history; cache đáp án AI vẫn phân biệt history và thay đổi nguồn.
- **Cập nhật FAQ không cần Re-index/restart:** ghi file nguyên tử, kiểm tra revision khi lưu, nhận thay đổi ở lượt tiếp theo. Các tiến trình phải dùng cùng file/thư mục mount.
- **Gộp BE và Voice/FAQ:** Compose chạy Java, Python headless, MySQL và Qdrant. FE gọi Java; không phải mở web Voice/Studio để dùng FAQ.
- **Dùng lại kết nối:** tái sử dụng OpenAI SDK/HTTP pool của Python, tùy chọn chuẩn bị client/model wake trước, bổ sung thời gian từng chặng và chế độ trả lời của API robot.
- **Thêm cách hỏi mic thực nhận:** `wi-fi`/`wifi`, “bệnh viện có xe lăn không”/“có cho mượn xe lăn không”. Không dùng fuzzy matching để tự đoán câu y tế gần giống.
- **Gói bàn giao Pi:** tối ưu VAD, đuôi im lặng, tốc độ đọc, WAV và công cụ đo nằm ở **RASPPI_MAR**, không nằm trong container Voice. Xem [deploy/pi](deploy/pi/README.md).

API robot HTTPS, wake tiếng Việt, RAG/cache cũ và ROS bridge đã có từ repo; không tính toàn bộ là chức năng mới. Chi tiết nằm trong [bản đối chiếu](docs/VOICE_CHANGELOG_2026-11-07.md).

## Chạy BE + Voice/FAQ trên máy tính

Máy đã cấu hình `backend/.env`, có Docker đang chạy:

```powershell
cd D:\AI_VOic22222\backend
docker compose up -d --build
docker compose ps
```

Bản clone mới cần tạo `.env`, khóa riêng và tài khoản theo [RUN_ALL.md](backend/RUN_ALL.md). Không dùng lại mật khẩu/danh tính robot của người khác từ README.

- BE: `http://127.0.0.1:8080`; kiểm tra `GET /actuator/health`.
- Python Voice/FAQ: `http://voice:8000` **chỉ trong mạng Compose**, không mở cổng 8000 ra host.
- MySQL/Qdrant giữ dữ liệu trong volume; thư mục `data/` được mount vào Python.
- FE ở repo **FE_WEB_MAR**, chạy riêng, đặt `VITE_API_BASE_URL` tới BE trên. Cần bản FE có phần quản lý FAQ mới.
- Kiosk có trong BE: `http://127.0.0.1:8080/assistant-kiosk.html`. Phải bật cờ trợ lý và khai báo robot được phép. Chạy container không tự cấp quyền điều khiển robot.

Dừng và chạy lại, giữ dữ liệu:

```powershell
docker compose stop
docker compose start
```

Sau sửa code, chạy lại `docker compose up -d --build`. `down -v` xóa volume database, không dùng để dừng thông thường.

## Dữ liệu bệnh viện lấy ở đâu?

Luồng **FE/kiosk → Java**:

1. Java hỏi Python xem có khớp FAQ đã duyệt không.
2. Không khớp: Java tìm tài liệu/bài thông tin trong **MySQL/Qdrant của BE đang chạy**, rồi nhờ AI soạn câu có nguồn.
3. Java không có nguồn: thử `data/hospital_vector_store.json` của Python cho câu hỏi công khai phù hợp. Không có bằng chứng nguồn thì Java từ chối câu ngoài phạm vi.

Luồng **Pi native → API robot 7861** dùng FAQ/vector trên máy AI, không đi qua Java `AssistantService` để tìm đáp án. BE vẫn thực hiện STT, TTS, lịch sử và yêu cầu hành động của Pi.

- `data/faqs.json`: nguồn FAQ, class đọc/ghi `src/rag/faq_store.py`.
- `data/documents/`: Word/PDF/Markdown đầu vào, không được tự đọc lại mỗi lượt hỏi.
- `data/hospital_vector_store.json`: chỉ mục đã tạo. Sửa Word cần lập chỉ mục lại; Python headless trong Compose cần restart sau khi thay chỉ mục vì nạp vector lúc startup.
- Tài liệu upload qua FE/Java lưu trong volume `knowledge-files`, MySQL và Qdrant; không tự xuất thành file vector Python hoặc FAQ.

Dữ liệu mẫu trong repo phục vụ dự án. Nhóm phải xác nhận đúng bệnh viện, phiên bản, giá, giờ khám và liên hệ trước vận hành; bật FAQ không tự biến tài liệu mẫu thành thông tin của bệnh viện thực.

## Server đang triển khai

- [Swagger](https://mediassistrover.duckdns.org/swagger-ui/index.html).
- [Map và điều khiển](https://mediassistrover.duckdns.org/mapping.html).

**Không tự đồng bộ server vào AI local.** Kiểm tra 07/11/2026: API public `GET /api/v1/public/hospital-information` trả 0 mục; OpenAPI chưa công bố các endpoint `/assistant` như BE local. Chưa xác minh dữ liệu nội bộ vì cần quyền riêng.

Client Python cũ chỉ đọc trang đầu `/api/v1/hospital-information`, chưa lấy `/{hospitalId}/articles`, và gửi `search` không có trong hợp đồng danh sách nhân viên của server. Đổi `BACKEND_URL` đơn thuần chưa sửa các điểm này. Không coi FAQ local hay BE mới là đã deploy.

`mapping.html` phục vụ bản đồ/nhiệm vụ. Các bài `articles` mới là nguồn cần nối đầy đủ nếu muốn đồng bộ thông tin bệnh viện server vào AI.

## Mic/loa và hội thoại Pi

Chọn **một** chương trình quản lý mic/loa:

1. **Pi đang chạy navigation RASPPI_MAR:** dùng companion native của repo đó. Cần AI HTTPS 7861, BE STT/TTS và Device JWT; thử `tools/voice_audio.py` rồi bật `--voice-chat` trong đúng phiên navigation. Đây là luồng được tối ưu VAD/WAV trong đợt này.
2. **ROS bridge `mentorpi_ws/`:** gửi WAV tới Java `/api/v1/pi/assistant`, không gọi trực tiếp 7861. Cần BE đã có endpoint trợ lý và bật Pi bridge. Ngưỡng VAD/tốc độ ở bridge khác native; không áp số 600 ms/170 WPM cho bridge.

Theo [hướng dẫn Pi](docs/RASPBERRY_PI_VOICE_SETUP.md) để kiểm tra phần cứng, dependencies, token/CA/TLS, thiết bị và nghe thử. **Pull AI_VOICE không tự cập nhật RASPPI_MAR hoặc tải model wake.**

## Số đo và giới hạn

Đã truy vết hai lượt trong DB local:

- Xe lăn: trước thêm alias, gọi model và BE ghi **8.461 ms**. Thử sau qua API kiosk: **26 ms HTTP**, BE **8 ms**, `model=null`.
- Wi-Fi: trước **3.903 ms** ở BE khi gọi model. Sau thêm alias: **28 ms HTTP**, BE **9 ms**, `model=null`.

Đây là lượt đo riêng trên Windows, không phải p50/p95 hay cam kết tốc độ. Lượt sau gửi **text**, chưa gồm nhận giọng nói, chờ im lặng, tạo/tải WAV hoặc phát loa. FAQ giảm bước tìm/sinh đáp án, không loại bỏ các bước còn lại.

Kiểm tra lại 07/11/2026: **100 bài Python AI đạt**, **31 bài Pi đạt, 1 bỏ qua** do thiếu fixture BE. Java `AssistantServiceTest` có 7 bài đạt ở lượt kiểm chứng Compose. Chưa có robot để chứng nhận mic/loa, wake, mạng hoặc phần cứng thật.

## Thư mục cần biết

- `src/service/`: Python headless cho Compose/RPC Java.
- `src/web/robot_api.py`, `src/web/wake.py`: API Pi native và wake tùy chọn trên máy AI.
- `src/web/app.py`, `run_web.ps1`, `run_faq.ps1`: Studio laptop tùy chọn 7860, nhập tài liệu/FAQ và thử riêng.
- `src/rag/`: FAQ, tài liệu, vector và pipeline đáp án.
- `src/model_client.py`, `src/latency.py`: pool kết nối và đồng hồ đo.
- `src/main.py`, `src/voice_engine.py`: mic/loa PC cũ, không phải companion Pi.
- `backend/`: Java, xác thực, database, knowledge ingestion, STT/TTS, Compose.
- `mentorpi_ws/`: ROS bridge có sẵn; `deploy/pi/`: gói bàn giao native Pi.
- `src/vision/`, `models/vision/`: camera/té ngã, không nằm trong đường FAQ.

## Kiểm thử Voice

Trong môi trường đã cài `requirements.txt`:

```sh
python -m pytest tests/test_intent.py tests/test_rag.py tests/test_voice_latency.py tests/test_integration_readiness.py tests/test_voice_engine.py tests/test_robot_api_latency.py tests/test_tts_admin_settings.py tests/test_faq.py tests/test_faq_launcher.py tests/test_system_launcher.py tests/test_headless_voice.py tests/test_backend_local_launcher.py -q
```

`.env` của từng thành phần chứa cấu hình riêng. Giữ OpenAI key ở máy AI/BE; Pi chỉ cần token thiết bị, token AI và CA công khai tương ứng. Không đưa khóa bí mật vào repo hoặc ảnh hướng dẫn.
