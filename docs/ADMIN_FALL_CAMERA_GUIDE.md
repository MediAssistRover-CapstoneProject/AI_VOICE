# Camera admin và kết nối Raspberry Pi

## Dùng laptop trước

1. Backend phải chạy tại cổng8080 và frontend5173. Đăng nhập ADMIN.
2. Trong `D:\AI_VOic22222`, chạy:

```powershell
.\run_system.ps1 -Profile local -NoVoice
```

3. Vào `http://localhost:5173/admin/fall-camera`, mục **Tự động hóa → Camera & AI té ngã**.
4. Chọn Camera laptop, chọn tỷ lệ cắt phần trên nếu muốn, rồi bấm **Bật camera và AI**. Chấp nhận quyền camera.
5. Để thấy các khớp, đứng xa đủ để có hai hông và ít nhất một chân đủ gối/mắt cá trong ảnh. Đừng đánh giá khả năng phát hiện chỉ bằng việc nhìn thấy ảnh live.
6. Bấm **Dừng camera** trước khi đổi nguồn hoặc tỷ lệ cắt. Chuyển tab trình duyệt/rời trang cũng dừng camera. Không mở webcam cùng lúc trong dashboard Vision cũ.

Mặc định Vision không tự mở webcam khi khởi động. Launcher chỉ tự chạy worker nguồn cũ khi truyền `-Source` rõ ràng hoặc dùng `-LiveEvents`. Nếu đã có worker cũ chiếm camera, dừng worker đó trước khi dùng camera browser.

Frontend dùng `VITE_VISION_API_BASE_URL`, mặc định `http://127.0.0.1:8001`. Vision dùng `VISION_ADMIN_ORIGINS`, mặc định localhost5173/127.0.0.1:5173. Ở môi trường HTTPS phải cung cấp Vision qua HTTPS/proxy được bảo vệ; browser có thể chặn mixed-content HTTP.

## Kết nối camera Pi thật để xem/thử

Trên Pi, dùng mã streamer cùng repo:

```bash
export PI_CAMERA_SOURCE=0
export PI_STREAM_PORT=8090
python3 -m src.vision.tools.pi_camera_streamer
```

USB dùng index0 hoặc `/dev/video0`. CSI dùng `PI_CAMERA_SOURCE=csi` khi bản OpenCV có GStreamer/libcamerasrc; không phải mọi bản OpenCV đều hỗ trợ CSI. Xác minh stream bằng `http://IP_PI:8090/health` và `/video`.

Trên máy chạy Vision, cấu hình `.env` gốc (dùng IP thực của Pi):

```dotenv
VISION_ROBOT_STREAM_URL=http://192.168.1.15:8090/video
VISION_CAMERA_ALLOWED_HOSTS=192.168.1.15
```

Khởi động lại Vision, bấm Kiểm tra kết nối trên trang, chọn **Camera Raspberry Pi**, rồi Bật camera và AI. Ảnh đi qua cùng model và luật như laptop. Địa chỉ không nhận từ input browser; cần whitelist ở server để tránh truy cập URL tùy ý. Giữ camera trong LAN được bảo vệ; streamer mẫu chưa có TLS/authentication.

## Chuyển sang robot vận hành và cảnh báo staff

Đây là bước riêng với trang thử. Cấu hình `BACKEND_URL`, `ROBOT_CODE`, `DEVICE_SECRET` của robot ở server, robot có map hiện hành và staff đủ quyền. Thử simulation trước:

```powershell
.\run_system.ps1 -Profile integrated -Source "http://192.168.1.15:8090/video" -NoVoice
```

Chỉ khi đã nghiệm thu camera/robot và người nhận thử mới dùng `-LiveEvents`. File video cục bộ bị chặn với cờ live để tránh phát lại sự kiện thành cảnh báo hiện thời. URL mạng vẫn phải là camera thật đã xác minh, không phải máy chủ phát video cũ.

Model chạy trên máy Vision hiện tại, Pi chỉ truyền hình. Muốn chạy inference trên chính Pi5 cần đo lại dependency/ONNX/FPS và cấu hình máy chủ; không coi độ trễ đo trên laptop là kết quả Pi. Trang admin dùng Account JWT; luồng gửi sự kiện robot dùng Device JWT. Hai loại token không thay thế nhau.

## Ý nghĩa kết quả

- LOW_VISIBILITY/NO_DATA/STALE: không đủ bằng chứng hoặc mất dữ liệu, không có nghĩa an toàn.
- CALIBRATING_LOWER_BODY: cần quan sát mốc đứng trước khi đánh giá mất trụ.
- SUSPECTED_FALL: nghi ngã cần người xác minh, không phải chẩn đoán.
- confidence=null: chưa hiệu chuẩn, giao diện không tự đổi thành phần trăm accuracy.
- Phiên admin không gửi sự kiện staff; không có nút bật cảnh báo thật trong trang thử.

Sơ đồ đầy đủ và kế hoạch dữ liệu huấn luyện: [AI_SYSTEM_FLOWS_AND_ROADMAP.md](AI_SYSTEM_FLOWS_AND_ROADMAP.md).
