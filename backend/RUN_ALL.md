# Chạy BE và Voice/FAQ cùng nhau

## Máy clone mới

Ở `backend`, sao chép `.env.example` thành `.env` **chỉ nếu chưa có file riêng**. Thay các placeholder bằng mật khẩu MySQL, JWT secret, tài khoản ADMIN và `VOICE_SERVICE_TOKEN` ngẫu nhiên riêng. Không chép cấu hình bí mật của máy người khác vào Git.

`VOICE_SERVICE_TOKEN` bắt buộc cho Java ↔ Python Compose, khác token AI 7861 và Device JWT. Đặt key/model trong **backend/.env** nếu dùng STT/TTS/sinh đáp án; `.env` root của Python host không tự truyền vào Compose này.

Kiosk text cần `ASSISTANT_ENABLED=true`, `ASSISTANT_PUBLIC_ENABLED=true`, `ASSISTANT_PUBLIC_ROBOT_IDS` là UUID robot đã có trong DB; có thể đặt `ASSISTANT_PUBLIC_DEFAULT_ROBOT_ID`. ROS bridge cần thêm `ASSISTANT_PI_BRIDGE_ENABLED=true` và Device JWT. Muốn âm thanh cần cấu hình TTS/provider phù hợp. Giữ `ASSISTANT_VOICE_ACTIONS_ENABLED=false` khi chỉ hỏi đáp.

Mẫu không seed robot/database hoàn chỉnh; tạo qua quy trình quản trị của nhóm. Không dùng UUID ví dụ như robot đã tồn tại.

## Máy đã cấu hình

Mở Docker Desktop trước, rồi chạy trong PowerShell:

```powershell
cd D:\AI_VOic22222\backend
docker compose up -d --build
```

Lệnh này dựng và chạy bốn dịch vụ: Java BE, Python Voice/FAQ, MySQL và Qdrant. BE chờ các dịch vụ phụ thuộc sẵn sàng. Không cần bật trang Voice/Studio 7860. FE hiện có vẫn chạy riêng và gọi BE tại http://localhost:8080 theo cấu hình FE.

Trong FE quản trị, mở `/admin/voice-scripts` để quản lý FAQ. FE gửi yêu cầu có tài khoản ADMIN qua Java BE; Java gọi Python trong mạng Docker riêng. FAQ lưu trực tiếp tại `D:\AI_VOic22222\data\faqs.json`. Chỉ câu đã duyệt và công khai mới được dùng trả lời nhanh. Sửa FAQ không cần dựng lại container.

Đường dẫn D là máy phát triển; nơi khác lưu `<repo>/data/faqs.json`. FE mới có thể đặt FAQ ở nhóm AI riêng, API vẫn `/api/v1/admin/assistant/faqs`. Cần cập nhật repo FE để có UI mới; kiosk thử có tại `http://127.0.0.1:8080/assistant-kiosk.html`, Studio nhập FAQ là tùy chọn.

Luồng hội thoại: FE → Java BE → kiểm tra FAQ Python → nếu không khớp, tìm nguồn kiến thức và xử lý AI → Java trả kết quả cho FE. Các kiểm tra quyền và xác nhận hành động của Java vẫn áp dụng. Các tính năng trợ lý/giọng nói vẫn tuân theo cờ cấu hình trong `backend/.env`; chạy container không tự bật mọi quyền điều khiển robot.

Khóa nội bộ `VOICE_SERVICE_TOKEN` đã được cấu hình trong `.env` trên máy này. Không công khai file đó. Python dùng `AI_RESPONSE_MODEL` (mặc định `gpt-4o-mini`), Java dùng `OPENAI_RESPONSE_MODEL`; các nhánh gọi AI cần `OPENAI_API_KEY` hợp lệ. Mic/loa do FE hoặc chương trình trên robot đảm nhiệm, không phải thiết bị âm thanh của container. Chương trình phần cứng trên Pi vẫn chạy trên Pi.

Kiểm tra và xem log:

```powershell
docker compose ps
docker compose logs -f backend voice
```

Dừng cả bộ, giữ nguyên dữ liệu:

```powershell
docker compose stop
```

Khởi động lại không dựng ảnh:

```powershell
docker compose start
```

Khi sửa code, chạy lại `docker compose up -d --build`. Không dùng `down -v` nếu muốn giữ dữ liệu MySQL/Qdrant. Bản FE cũng cần chạy hoặc build lại để có giao diện FAQ mới.

Sau tái tạo vector Python, chạy `docker compose restart voice` vì headless nạp vector lúc startup; FAQ tự reload không cần lệnh này.

## Kết nối Pi

Compose mặc định gộp Python **headless cho Java**, không mở API native HTTPS 7861, không truy cập mic/loa Pi hoặc tự bật worker. Chọn đúng luồng trong [hướng dẫn Pi](../docs/RASPBERRY_PI_VOICE_SETUP.md).

BE mặc định bind loopback HTTP 8080. Native Pi cần HTTPS/reverse proxy với chứng chỉ đúng tên/IP LAN. Profile `production` có sẵn: cấu hình `TLS_CERT_DIR`, `HTTPS_PORT`, `PUBLIC_BASE_URL` trước khi chạy `docker compose --profile production up -d --build`. Cài CA công khai đúng trên Pi và kiểm tra cổng host không bị chiếm. Không tắt TLS verify để coi là đã kết nối.
