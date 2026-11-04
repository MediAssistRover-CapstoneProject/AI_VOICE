# MediAssist: AI Voice, AI té ngã và Backend trong một dự án

## Phân chia thư mục

- `src/`: AI Voice hiện có, giữ các entrypoint `src.main`, `src.web.app`, RAG,
  STT/TTS và client Device JWT để tương thích với các lệnh đang dùng.
- `src/vision/`: AI té ngã/camera; detector, theo dõi, xử lý video và kết nối BE.
  Entry web: `src.vision.web.app:app`.
- `src/platform/`: điều phối các tiến trình và trang mở chung; không chứa model
  nhận diện hay nghiệp vụ database.
- `backend/`: Spring Boot và migration/database dùng chung của Voice + Vision.
- `config/voice_config.json`: hành vi hội thoại hiện có.
- `config/system.json`: cổng, interpreter và nguồn camera của bộ khởi chạy chung.
- `data/documents/`, `data/hospital_vector_store.json`: kiến thức phục vụ hỏi đáp.
- `data/vision/`: dữ liệu vận hành Vision; không đưa sự cố hay điểm cơ thể vào
  Vector P của Voice.
- `models/vision/`: trọng số nhận diện dùng ở máy chạy Vision.
- `mentorpi_ws/`: cầu nối phần cứng ROS 2 hiện có.
- `tests/`: kiểm thử Voice, Vision và bộ khởi chạy; không phải dữ liệu train.

Các tiến trình dùng chung mã robot và Backend nhưng có môi trường Python riêng.
Điều này tránh ép NumPy/OpenCV/MediaPipe của Vision vào bộ phụ thuộc Voice đang
dùng NumPy <2. Không di chuyển các module Voice đã có chỉ để thay tên thư mục.

## Cấu hình chung

Giữ `.env` ở thư mục gốc. Những tên dùng chung: `BACKEND_URL`, `ROBOT_CODE`,
`DEVICE_SECRET`. Không sao chép `.env` của dự án cũ vào thư mục Vision và không
đưa khóa API/mật khẩu vào JSON hoặc đối số lệnh.

`config/system.json` mặc định:

- Trang mở chung: `http://127.0.0.1:7800`.
- Voice Studio: `http://127.0.0.1:7860`.
- Vision: `http://127.0.0.1:8001`.
- Backend: URL từ `BACKEND_URL`, mặc định `http://localhost:8080`.

File `.env` được launcher đọc theo cú pháp KEY=value đơn giản; môi trường đã
đặt bên ngoài được ưu tiên. Không thực thi shell hoặc mở rộng `${...}`.
Backend Compose tiếp tục dùng cấu hình `.env` riêng trong `backend/` cho DB,
JWT và các dịch vụ bên trong; không tự thay các giá trị bí mật đó.

## Môi trường Python

Tạo môi trường Voice và Vision riêng, cài requirements tương ứng. Ví dụ Windows:

```powershell
cd D:\AI_VOic22222
py -3.11 -m venv .venv-voice
.\.venv-voice\Scripts\python.exe -m pip install -r requirements.txt
py -3.11 -m venv .venv-vision
.\.venv-vision\Scripts\python.exe -m pip install -r requirements-vision.txt
```

Launcher tìm `.venv-voice`/`.venv-vision` trước, rồi `.venv`/`venv`, sau cùng là
Python đang chạy launcher. Có thể chỉ định `-VoicePython` và `-VisionPython`
hoặc cấu hình trường `python` trong `config/system.json`. Không cần tạo lại
môi trường nếu đã có interpreter phù hợp.

## Kiểm tra trước khi chạy

```powershell
.\run_system.ps1 -Check
.\run_system.ps1 -Check -Profile integrated
```

`check` kiểm tra kế hoạch, file model Vision và thư viện bằng tiến trình Python ngắn; không mở
camera/mic, không tải model, không gọi xác thực, không gửi sự kiện và không bật
Docker. Mã thoát 1 nghĩa thiếu thư viện/interpreter hoặc thiếu device secret cho
profile integrated. Kết quả chỉ nêu đã có cấu hình danh tính hay chưa, không in
giá trị secret.

## Chạy thử cục bộ

```powershell
.\run_system.ps1 -Profile local -Source "0"
.\run_system.ps1 -Profile local -Source "D:\Videos\robot_camera.mp4"
.\run_system.ps1 -Profile local -Source "http://IP_PI:8081/video" -NoVoice
```

Mở trang 7800 để đi tới Voice Studio, Vision hoặc kiosk/staff Backend. Profile
local ép `VISION_DELIVERY_ENABLED=false`, `VISION_SIMULATION=true` ngay cả khi
biến môi trường cũ đang bật gửi sự kiện. Voice Studio vẫn hoạt động theo cấu
hình Voice hiện có; launcher không biến các lời nói thử thành sự kiện Vision.

Không mặc định bật Voice Engine đọc microphone đồng thời với Voice Studio.
Nếu cần console mic, dừng Studio tương ứng và dùng lệnh `python -m src.main`
hiện có để tránh nhiều chương trình chiếm cùng thiết bị âm thanh.

## Chạy tích hợp với Backend

```powershell
# Backend đã chạy; gửi sự kiện Vision theo chế độ mô phỏng
.\run_system.ps1 -Profile integrated -Source "0"

# Chủ động yêu cầu khởi động cả stack Compose Backend
.\run_system.ps1 -Profile integrated -Source "0" -BackendCompose

# Chỉ dùng khi đã cấu hình robot, xác minh luồng staff và muốn sự kiện thật
.\run_system.ps1 -Profile integrated -Source "0" -LiveEvents
```

Mặc định integrated vẫn đặt `VISION_SIMULATION=true`. `-LiveEvents` mới đặt
false và chỉ được dùng cùng integrated. Tùy chọn này không có nghĩa model đã
được xác nhận đủ chính xác; cần đánh giá bằng video camera thấp thật trước.
Backend phải thực thi chính sách phân biệt sự kiện mô phỏng với cảnh báo thật.

`-BackendCompose` là yêu cầu rõ ràng để chạy `docker compose up --build`; không
có tùy chọn này launcher không tự khởi động DB hoặc các container Backend.
Tắt bằng Ctrl+C: launcher gửi tín hiệu dừng và đợi các tiến trình con; không
thực hiện `docker compose down -v` hoặc xóa dữ liệu.

Có thể dùng trực tiếp Python trên Windows/Linux:

```bash
python -m src.platform check
python -m src.platform run --profile local --source 0
python -m src.platform run --profile integrated --source 0 --live-events
```

## Trạng thái và kiểm thử

Trang chung phản ánh trạng thái tiến trình, không tự suy luận model hay DB đã
sẵn sàng chỉ vì cổng web mở. Mở màn hình từng dịch vụ để xem chất lượng camera,
trạng thái nhận diện và việc Backend tiếp nhận sự kiện. `/api/system` và
`/healthz` của launcher chỉ trả tên tiến trình, URL công khai và trạng thái,
không trả môi trường, đường dẫn secret hoặc đối số chứa mật khẩu.

```powershell
.\.venv-voice\Scripts\python.exe -m pytest tests -q
.\.venv-voice\Scripts\python.exe -m unittest -v tests.test_system_launcher
```

Nếu `tests` không có `__init__.py`, dùng:

```powershell
.\.venv-voice\Scripts\python.exe -m unittest discover -s tests -p test_system_launcher.py -v
```

Các test web Voice hiện có tạo/xóa tài liệu thử và rebuild Vector P; nên chạy
trên bản làm việc kiểm thử có dữ liệu riêng khi cần giữ nguyên kho kiến thức.
Test launcher chỉ dùng mock/tempdir, không khởi động AI hay ghi database thật.

## Giới hạn nhận diện cần giữ rõ

Bộ luật thân dưới cho phép thiếu vai, nhưng vẫn cần điểm hông/chân đáng tin.
Các thử nghiệm trước chưa chứng minh nhận diện ổn định với camera robot thấp.
Việc gom thư mục và nối Backend không làm tăng độ chính xác của mô hình.
