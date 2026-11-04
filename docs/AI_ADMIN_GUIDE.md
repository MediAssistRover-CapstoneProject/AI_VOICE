# Quản trị AI Voice

Đăng nhập ứng dụng tại `http://localhost:5173/login` bằng tài khoản ADMIN. Vào **Tự động hóa → Giọng nói & kịch bản** rồi chọn thẻ **Giọng AI** hoặc **Dữ liệu AI**. Giao diện sử dụng phiên đăng nhập hiện tại và tự gia hạn token qua lớp API chung; không cần dán JWT riêng. Backend vẫn kiểm tra quyền ADMIN cho mọi thao tác quản trị.

Trang cũ `http://localhost:8080/knowledge-admin.html` chuyển hướng về trang trên. Cấu hình đích qua `AI_ADMIN_UI_URL` khi triển khai ở địa chỉ khác.

## Giọng nói

- Chọn Nam/Cedar, Nữ/Coral hoặc một kiểu giọng khác; dùng **Nghe thử AI** trước khi lưu.
- Điều chỉnh tốc độ 0,75–1,50 lần và phong cách điềm tĩnh, thân thiện hoặc trang trọng.
- **Kiosk sử dụng → Giọng AI**: laptop/kiosk dùng cùng lựa chọn giọng AI như robot. Cần chờ dịch vụ tạo âm thanh lần đầu.
- **Giọng trên máy**: kiosk dùng SpeechSynthesis và các giọng đã cài trên thiết bị; phát nhanh hơn. Tốc độ vẫn áp dụng, phong cách AI chỉ áp dụng cho giọng AI.
- **Lưu giọng nói** áp dụng từ lượt nói tiếp theo; không cắt ngang câu đang phát và không cần khởi động lại.
- **Tải lại cấu hình** lấy cấu hình mới nhất nếu admin khác vừa thay đổi. Số revision ngăn ghi đè thay đổi đồng thời.

Cấu hình lưu ở bảng `AiVoiceSettings` (Flyway V40). Kiosk đọc lại trước mỗi lượt nói. Cache WAV có giới hạn dung lượng và phân biệt cấu hình giọng; giọng mới không dùng lại âm thanh của giọng cũ. Robot ROS dùng backend sẽ nhận giọng mới ở lần tổng hợp tiếp theo. Python sử dụng backend không giữ bản WAV cũ theo câu chữ. Piper chạy cục bộ và nhánh TTS dự phòng trực tiếp có cấu hình riêng, không chịu quản lý giọng từ trang này.

## Dữ liệu AI

1. Nhập tiêu đề, danh mục, phạm vi và nội dung; hoặc chọn DOCX/PDF/JPG/PNG tối đa 25 MB.
2. Bấm **Lưu và cập nhật AI**. Nếu có tệp, nội dung được lấy từ tệp.
3. Đợi trạng thái **Sẵn sàng**. Trang tự làm mới khi tài liệu đang xử lý; thời gian tùy dung lượng và dịch vụ tạo vector/OCR.
4. **Xem** hiển thị nội dung trích xuất và các đoạn nguồn. **Sửa** cho phép chỉnh văn bản hoặc tải tệp thay thế; mỗi lần sửa tạo phiên bản mới.
5. **Xóa khỏi AI** loại tài liệu khỏi nguồn trả lời, giữ lịch sử trích dẫn. Chọn bộ lọc **Đã xóa khỏi AI**, rồi **Khôi phục** để xử lý lại.

Kiosk chỉ tra cứu tài liệu công khai hợp lệ, đã sẵn sàng. Nội bộ dành cho luồng nhân viên có quyền. Nội dung mới cập nhật MySQL và Qdrant; không huấn luyện lại mô hình. Kho JSON của Python là một nguồn riêng, không tự đồng bộ với trang này.

## Kiểm thử ngay trong ứng dụng

Vào **Tự động hóa → Kiểm thử AI** tại `http://localhost:5173/admin/ai-test`.

- Trang mở thẳng giao diện **mic và giọng nói** trên laptop, dùng cùng mã điều phối kiosk. Bấm Bật voice để xin quyền mic, đặt câu hỏi và nghe qua loa; Dừng giọng nói hoặc rời trang để kết thúc thu/phát. Không có biểu mẫu hỏi bằng chữ hoặc nút bắt đầu phiên mới. Phần kiểm thử không hiển thị nút xác nhận điều phối nhiệm vụ vật lý.
- **Tải lại cấu hình kiểm thử**: cập nhật thông tin giọng và số tài liệu công khai sẵn sàng sau khi sửa dữ liệu.
- Kiểm thử dùng nguồn PUBLIC giống kiosk. Dữ liệu INTERNAL không được công khai chỉ vì người kiểm thử là admin.

Frontend được cập nhật tại `D:\UI_Wed\FE_WEB_MAR`: `AiWorkspace.jsx`, `aiWorkspace.css`, các thẻ BackendWorkspace, navigation/screenRoutes/AdminApp và lớp client hỗ trợ multipart FormData. Backend cung cấp API như trước; các tài nguyên quản trị HTML/JS/CSS riêng đã được bỏ.

## API

- ADMIN: `GET/PUT /api/v1/admin/assistant/voice`, `POST /api/v1/admin/assistant/voice/preview`.
- ADMIN: `GET/POST /api/v1/admin/knowledge/documents`; `GET/PUT/DELETE /{id}`; `GET /{id}/editor`; `POST /{id}/reindex`.
- Kiosk: `GET /api/v1/public/assistant/voice-settings`.
- Kiosk cloud: `POST /api/v1/public/assistant/sessions/{id}/speech`, chỉ nhận `{turnId}` của phiên hoặc `null` cho lời chào cố định. Không nhận văn bản tùy ý. Kiểm tra phiên còn hạn, quyền sở hữu và trạng thái trước cả khi dùng cache.

## Kiểm tra đã thực hiện

- 42 kiểm thử backend: cấu hình, chống ghi đè, truy cập audio, cache, CRUD, trích DOCX, tìm nguồn và TTS.
- 20 kiểm thử kiosk: đổi cấu hình từ lượt tiếp theo, hai chế độ giọng, ngắt lời, hủy yêu cầu và chặn phản hồi đến trễ.
- Giao diện Edge với API giả lập: kết nối, sửa giọng, CRUD, upload, đăng xuất và hiển thị trên màn hình nhỏ.
- API localhost thực: từ chối yêu cầu quản trị không đăng nhập, lưu cấu hình, tạo WAV Coral/Cedar, cache lời chào, thêm/sửa/xóa/khôi phục nội dung và tải DOCX đến READY. Dữ liệu thử dùng phạm vi INTERNAL và được lưu trữ sau kiểm tra; cấu hình gốc được khôi phục.

Trong mẫu đo localhost: lưu và đọc lại cấu hình 78 ms; tạo mẫu giọng khoảng 3,17–3,49 giây; lời chào cloud lần đầu 2,78 giây, lần dùng cache 16 ms. Đây là số đo mẫu, không phải cam kết thời gian toàn bộ hội thoại. Chưa kiểm tra trực tiếp loa/mic của robot vật lý.

## Kiểm tra sau khi chuyển giao diện

Frontend production build thành công. Đã kiểm tra bằng Edge trên trang quản trị thật: lưu giọng bằng phiên đăng nhập, thêm/sửa nội dung, DOCX multipart, xóa/khôi phục, hỏi AI có nguồn và thời gian, chuyển sang thử mic, dừng tiếng nói, rời chế độ và bố cục màn hình nhỏ. Mic/loa dùng giả lập trong kiểm thử trình duyệt; chưa kiểm tra phần cứng. 20 kiểm thử hồi quy kiosk đạt. Đường dẫn cũ trả 303 về frontend.
