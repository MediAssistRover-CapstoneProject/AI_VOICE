# Nhập và áp dụng FAQ

## Cách dùng mặc định: FE → Java → Python trong Compose

Chạy theo [RUN_ALL.md](../backend/RUN_ALL.md). Không cần bật Studio/web Voice 7860.

FE_WEB_MAR phải có giao diện FAQ mới, `VITE_API_BASE_URL` đúng BE và đăng nhập ADMIN. Mở quản lý FAQ/giọng nói; tùy bản FE là `/admin/voice-scripts` hoặc nhóm AI chứa `FaqPanel`. Giao diện ở repo FE, không tự có sau khi chỉ pull AI_VOICE.

Java có `/api/v1/admin/assistant/faqs`: GET/POST, PUT/DELETE `/{id}`, POST `/test`. Java kiểm tra ADMIN rồi gọi Python nội bộ. Không gửi token nội bộ ra FE.

File lưu: `<AI_VOICE>/data/faqs.json`; bản chính trên máy phát triển là `D:\AI_VOic22222\data\faqs.json`, mount thành `/app/data/faqs.json`. Đây là dữ liệu JSON, **không phải class**. Class đọc/ghi là `src/rag/faq_store.py`.

## Nhập một câu

1. Bấm thêm hoặc chọn bản có sẵn.
2. Điền câu hỏi chính và các cách nói tương đương, mỗi dòng một câu.
3. Điền đáp án robot đọc, tối đa 500 ký tự, nguồn xác nhận và trích đoạn để đối chiếu.
4. Chọn công khai nếu dùng cho bệnh nhân; kiểm tra đúng bệnh viện/phiên bản.
5. Đánh dấu đã duyệt rồi lưu. Bản nháp/nội bộ chưa được trả trực tiếp cho bệnh nhân.
6. Thử đúng alias tại chức năng test. `matched=true`, `answer_mode=approved_faq`, `model_called=false` xác nhận không gọi model để sinh đáp án.

UI bỏ duyệt khi sửa để kiểm tra lại. Nếu revision xung đột, tải danh sách mới trước khi lưu; không ghi đè cập nhật từ người khác.

## Khớp câu thực tế

Chỉ bỏ khác biệt hoa/thường, khoảng trắng, dấu câu cuối và biểu diễn Unicode. Giữ dấu tiếng Việt, từ, số và phủ định. **Không phải fuzzy/khớp theo chủ đề.**

Ví dụ đã gặp: “Bệnh viện có cho mượn xe lăn không?” chưa bao phủ “bệnh viện có xe lăn không”; `wifi` chưa bao phủ `wi-fi`. Đã thêm đúng hai alias mic thực nhận và giữ nguyên answer/nguồn.

Không khớp có thể đi qua RAG/model, nên vẫn tốn nhiều giây. Đọc transcript thực để thêm alias có cùng ý nghĩa; không dùng câu thiếu ngữ cảnh như “bao nhiêu”, “ở đó” làm alias độc lập.

## Dữ liệu hiện có

Kiểm tra 07/11/2026: bản D có 5 FAQ đang bật — Wi-Fi, nhà thuốc, tiếp đón, xét nghiệm, xe lăn. Ban đầu chúng là bản nháp từ `so_tay_tra_cuu_benh_vien_mediassist.docx`; cập nhật code giữ nguyên trạng thái người dùng đã duyệt.

Đây là dữ liệu dự án, không tự chứng minh thông tin bệnh viện thực. Các Word có nội dung khác nhau về giờ khám, giá và hotline; cần chọn nguồn chính thức trước khi thêm FAQ cho các mục đó. Không nhập dữ liệu bệnh nhân hoặc bí mật vào FAQ công khai.

## Cập nhật có hiệu lực khi nào?

- FAQ lưu trực tiếp và được đọc lại ở lượt sau; không cần Re-index/restart.
- FAQ riêng với `data/hospital_vector_store.json`; tái tạo vector không xóa FAQ.
- Mỗi checkout/server có file riêng. Sửa bản C không tự đổi D hoặc DuckDNS.
- Studio 7860, API native 7861 và Compose chỉ dùng cùng FAQ khi cùng dữ liệu/mount đúng `../data`.
- Replay cùng session/turn trả kết quả gốc theo idempotency; muốn kiểm tra nội dung vừa đổi phải tạo lượt mới.
- JSON lỗi báo lỗi dữ liệu, không dùng âm thầm bản đã duyệt cũ như thể file mới hợp lệ.

Reload FAQ không có nghĩa reload mọi dữ liệu: Python headless nạp vector lúc startup; đổi vector cần restart `voice`. Studio/robot API có luồng cập nhật index riêng.

## Studio tùy chọn nếu chưa có FE mới

Trên máy AI có `.venv-voice`/dependencies:

```powershell
cd D:\AI_VOic22222
.\run_faq.ps1
```

Mở `http://127.0.0.1:7860/faq`; `run_faq.bat` cũng dùng được. Launcher dùng lại Studio đúng project, báo lỗi nếu cổng thuộc checkout khác. Xem nơi lưu trên trang hoặc `GET /api/runtime`.

Studio local không cần Java để nhập FAQ, không tự chạy BE/MQTT/robot. Đóng launcher không dừng Compose.

## FAQ giảm phần nào?

Java khớp FAQ trước DB/Qdrant/model; Pi native khớp ở 7861 trước vector/model. FAQ không phải lệnh điều khiển hoặc cách bỏ qua xác nhận hành động.

Pi vẫn thu âm/chốt câu/STT/gửi text/TTS/phát loa. Số test FAQ vài ms không phải giao tiếp bằng mic/loa end-to-end. Xem [độ trễ](voice-latency.md) và [cài Pi](RASPBERRY_PI_VOICE_SETUP.md).
