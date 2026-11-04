# Kết quả tích hợp trên máy này

- Đã đưa mã Vision, trọng số và kiểm thử vào cùng repo Voice/Backend.
- Đã cài `.venv-vision` riêng từ bộ thư viện Vision đang chạy trên máy; không cần
  đường dẫn mã nguồn MAR_Fall_AI cũ khi chạy model. `.venv-voice` dùng các thư viện
  Voice đã có của Python 3.11 trên máy, với NumPy 1.26; không đổi phiên bản gốc.
- Có `setup_system.ps1` để cài lại các môi trường theo requirements khi cần.
- Đã kiểm tra 121 bài Python, 76 bài Backend tập trung vào Vision/security/MQTT,
  25 bài JavaScript kiosk/staff; đều qua. Backend biên dịch main/test với Java 21.
- Đã chạy Voice Studio + Vision + trang điều phối cùng lúc trên cổng thử riêng,
  đọc video cục bộ bằng môi trường mới. Vision đạt trạng thái RUNNING, delivery
  tắt ở local; Voice API hoạt động; dừng supervisor trả mã 0.
- Sự kiện giả lập được kiểm tra với REST client giả, không gửi tới nhân viên thật.

Các kiểm tra ban đầu ở trên chưa xác minh giao dịch MySQL thật, Docker stack đang triển khai,
điện thoại staff hay độ chính xác phát hiện ngã của camera robot thấp. Backend cần
được rebuild/restart từ mã trong `backend/` để có endpoint Vision mới. Bật
`-BackendCompose` trong launcher khi muốn quản lý stack đó từ dự án.

`.env`, thư viện tri thức Voice và các thay đổi Voice/Backend đang có của người
dùng không bị thay bằng bản MAR_Fall_AI cũ. File được cập nhật đã được sao lưu
tại `D:\MAR_Fall_AI\integration_backup` trước khi chép.

Chạy `run_system.ps1 -Check` để kiểm tra thư viện/file; local để thử camera;
integrated để lưu quan sát mô phỏng; thêm `-LiveEvents` khi muốn dùng luồng tạo
sự cố thật sau khi đã xác minh môi trường vận hành. Không tự bật lệnh robot.

## Cập nhật 04/11/2026: camera admin và MySQL

- Thêm Tự động hóa → Camera & AI té ngã (`/admin/fall-camera`) vào frontend thực D:\UI_Wed\FE_WEB_MAR.
- Dịch vụ Vision cổng8001 nhận JPEG camera browser qua API ADMIN; cũng đọc camera Pi MJPEG từ cấu hình server có allowlist. Preview không gửi sự kiện đến staff.
- Model thật đã xử lý ảnh từ clip có sẵn và luồng JPEG browser giả lập. Cắt54% vùng trên trước inference cũng đọc được khớp ở một frame, nhưng chưa chứng minh phát hiện được sự kiện ngã camera thấp.
- Đã kiểm tra HTTP MJPEG loopback dùng chính streamer Pi và model: RUNNING, tìm được1 người. Đây là kiểm tra giao thức, không phải kết nối phần cứng Pi thật.
- 50 kiểm thử Vision và17 launcher đạt; 5 JS staff đạt. Frontend build và luồng camera browser/dừng/crop/mobile đã kiểm tra.
- Ba kiểm thử MySQL 8.4 Testcontainer với full Spring/MockMvc đạt: simulation, retry sau JSON roundtrip, khác body409, thiếu map422, FK live và STAFF đọc DELIVERED rồi ACK. MQTT/scheduler/bootstrap tắt trong bài test. Không ghi vào database vận hành hoặc gửi thông báo tới nhân viên thật.
- Chưa train camera thấp vì chưa có video/nhãn đúng robot; chưa nghiệm thu staff trên điện thoại hoặc push nền.

Xem `AI_SYSTEM_FLOWS_AND_ROADMAP.md` và `ADMIN_FALL_CAMERA_GUIDE.md` để đọc sơ đồ trước/sau, cách truyền dữ liệu, cấu hình Pi và kế hoạch còn lại.
