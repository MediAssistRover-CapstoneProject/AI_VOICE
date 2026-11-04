# Nơi lưu dữ liệu Voice và Vision

## Dữ liệu trên máy AI

- `models/vision/`: trọng số YOLO/MediaPipe. Không phải dữ liệu bệnh nhân.
- `data/vision/logs/observations.jsonl`: các quan sát camera đã xử lý, khớp và
  trạng thái theo thời gian; không phải bằng chứng đã lưu Backend.
- `data/vision/outbox/simulation-events.sqlite3`: hàng đợi quan sát mô phỏng.
- `data/vision/outbox/live-events.sqlite3`: hàng đợi sự kiện thật. Chỉ được gửi
  trong profile được chỉ định sự kiện thật. Chế độ mô phỏng không xả hàng đợi này.
- Video dùng để thử không tự sao chép vào repo, cũng không tự lưu video liên tục.
- `data/documents/` và `data/hospital_vector_store.json`: kiến thức Voice/RAG;
  giữ riêng với nhật ký nhận diện và sự cố.

Một sự kiện được lưu vào outbox trước khi gửi. Chỉ chuyển sang delivered khi BE
trả receipt đúng eventId, accepted=true và observationPersisted=true. Khi gửi lại,
giữ nguyên ID, body và cờ simulation. Lỗi dữ liệu 4xx được giữ trạng thái rejected
để xem lại; lỗi kết nối được thử lại có giới hạn nhịp. Chưa có chính sách tự xóa
outbox/log; cần quản trị dung lượng và thời hạn lưu theo môi trường triển khai.

## Một Backend dùng chung

Voice và Vision dùng `BACKEND_URL`, `ROBOT_CODE`, `DEVICE_SECRET` ở `.env` gốc.
Vision gọi REST `POST /api/v1/pi/vision/detections` bằng Device JWT. Backend tự
kiểm tra robot, token, bản đồ hiện hành, loại sự kiện và thông số.

- Mô phỏng: lưu `DetectionEvent`, không tạo sự cố hoặc báo động staff.
- Sự kiện thật: một giao dịch lưu `DetectionEvent` → `EmergencyIncident` →
  `Alert` → `AlertRecipient` cho các tài khoản đủ điều kiện.
- Voice tiếp tục lưu `VoiceInteraction` và `VoiceCommand` theo luồng hiện có.
- Retry cùng ID/nội dung trả receipt duplicate, không tạo thêm sự cố. Dùng lại
  ID với nội dung khác bị từ chối. Không tự gán bản đồ bất kỳ khi robot chưa có map.

Nhánh nhận diện thân dưới chưa có xác suất hiệu chuẩn nên confidence có thể null.
Điểm/metrics được bảo toàn; các trường tọa độ mét không được bịa từ ảnh 2D.

## Staff

Mở Backend `/vision-staff.html`, đăng nhập STAFF/ADMIN. Trang đọc API Alert theo
quyền tài khoản, cập nhật mỗi 4 giây và báo sự kiện OPEN mới kể cả severity WARNING.
Tiếp nhận sẽ gọi API acknowledge hiện có. Thông báo là nghi té ngã cần xác minh.
Trang không công bố phần trăm chính xác ghi cứng hay vị trí robot giả.

Đây là trang staff web trong repo hợp nhất; ứng dụng điện thoại ở thư mục khác
chưa được sửa và chưa có push khi app đã tắt. Kết quả quan sát mô phỏng không
xuất hiện trong hộp thư cảnh báo thật.

## Giới hạn và kiểm chứng

Việc ghép hệ thống không phải huấn luyện lại model. Thử nghiệm ảnh bị cắt từ hông
xuống trước đây chưa phát hiện ổn định. Cần video thật của camera robot để đánh
giá bỏ sót/báo nhầm. Không coi code tích hợp hay unit test đạt là model đã đạt.

Hand SOS/co giật là nhánh demo tùy chọn, vẫn giữ simulation trên sự kiện gốc;
không tự nâng thành sự cố thật khi bật live fall delivery.

Chưa chạy giao dịch MySQL hoặc gửi cảnh báo tới staff thật trong lần tích hợp.
Các kiểm thử đã chạy dùng mock, dữ liệu tạm và video cục bộ. Mở profile integrated
vẫn mặc định mô phỏng; đọc hướng dẫn UNIFIED_SYSTEM_GUIDE trước khi bật LiveEvents.
