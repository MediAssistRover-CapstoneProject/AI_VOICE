# Đánh giá voice AI/RAG và tích hợp robot — 02/11/2026

## Kết luận đã kiểm tra từ source

`AI_VOICE` là ứng dụng tích hợp AI, chưa phải bộ model đã tải để chạy offline.
Không tìm thấy trọng số `.gguf/.onnx/.safetensors/.pt/.bin` trong repository.

| Thành phần | Source hiện tại | Ý nghĩa |
|---|---|---|
| STT Python | `src/stt/__init__.py`: `WhisperSTT` gọi API; fallback Google | Tên Whisper không có nghĩa inference local |
| LLM Python | `src/rag/rag_pipeline.py`: OpenAI/Gemini hoặc trích câu offline | Offline extractor không phải LLM |
| Embedding Python | `LocalOfflineEmbeddings`: hashing n-gram 384 chiều | Nhẹ, offline, nhưng không phải semantic embedding đã huấn luyện |
| TTS Python | `src/tts/__init__.py`: BE hoặc API, cache WAV | Chưa có provider TTS neural local |
| Java trong `backend/` | `OpenAiGateway` gọi URL provider cố định | Chưa có adapter Ollama/llama.cpp/faster-whisper local |
| Pi bridge | thu âm, HTTP, phát WAV, xác nhận action | Có thể tận dụng, không phát `/cmd_vel` |

Không coi các tên model mặc định trong Python/Java là đã được thử thành công:
hai nhánh cấu hình đang dùng tên khác nhau. Cần kiểm tra khả dụng với provider
và thống nhất cấu hình trước khi bật voice thật.

## Robot đang có gì

Pi đang dùng `speech.engine=be-tts`, tải WAV từ BE rồi phát qua ALSA/aplay.
Có `espeak-ng` làm lựa chọn phát offline; đây không phải model hội thoại/RAG.
Trong hai vị trí model dự án và cache Hugging Face đã kiểm tra không có trọng số.
Đợt kiểm tra này `/proc/asound/cards` trong MentorPi chỉ hiện HDMI; cấu hình vẫn
trỏ `plughw:CARD=Device,DEV=0`. Cần gắn/kiểm tra lại USB microphone/loa trước khi
test voice. Không thể kết luận firmware bên trong phụ kiện từ source Python.

## Chọn nơi chạy

Ưu tiên Pi chỉ thu/phát, VAD/PTT và điều khiển robot. Chạy inference local trên
laptop/mini PC cùng LAN; BE vẫn điều phối, lưu dữ liệu nghiệp vụ vào Aiven.
Máy đã kiểm tra: Ryzen 7 5800H, RAM 15,9 GiB, RTX 3060 Laptop 6144 MiB VRAM.
Đây là cấu hình có thể bắt đầu thử model nhỏ; chưa benchmark inference thực tế.

Một bộ ứng viên để đo:

- STT: faster-whisper `small` đa ngôn ngữ, thử INT8/INT8-FP16 theo thiết bị.
  Không dùng biến thể `.en` cho tiếng Việt. Nếu quá tải thì thử `base` và đo lại
  nhận dạng tên khoa/phòng; không tự đánh đổi chất lượng chỉ theo FPS.
- LLM: Qwen3-4B GGUF Q4_K_M qua llama.cpp, context khởi điểm 2–4k,
  câu trả lời ngắn và tắt thinking. File quantized khoảng 2,5 GB không phải tổng
  RAM/VRAM runtime; phải cộng KV cache, STT và hệ điều hành.
- Retrieval: benchmark BGE-M3 trên CPU, nạp model một lần, index sẵn; có thể dùng
  Qdrant local. Chọn semantic embedding khác thì phải rebuild toàn bộ index.
- TTS: trước mắt giữ WAV cache của BE; nếu cần offline, thử Piper giọng `vi_VN`
  và đánh giá phát âm tên thuốc/khoa. Kiểm tra license từng voice và engine.

Không nên chạy đồng thời LLM + STT lớn + SLAM + Nav2 trên Pi 5 8 GB đã từng
nghẽn CPU/swap. Chạy được model không đồng nghĩa đáp ứng độ trễ khi robot di chuyển.

## Luồng cần xây để tích hợp

1. Pi PTT/VAD -> audio 16 kHz mono -> voice service trên LAN.
2. STT -> intent. Câu chào/thông báo cố định dùng template và WAV cache.
3. Câu hỏi thông tin -> retrieval index đã có -> LLM trả lời kèm nguồn.
   Index xây lúc upload/cập nhật tài liệu, không đọc lại toàn bộ cloud DB mỗi lượt hỏi.
4. TTS theo câu -> stream/phát WAV đầu tiên; hỗ trợ hủy khi người dùng nói tiếp.
5. Nếu yêu cầu dẫn đường: trả action có schema, room/endpoint ID thật, cần xác nhận;
   BE tạo service request/workflow MOVE chuẩn với idempotency. LLM không tự phát vận
   tốc, SQL hoặc thay đổi zone.
6. Mỗi robot chỉ một chủ sở hữu loa; dùng hàng đợi audio có ưu tiên thông báo robot.
   Không mở thêm node camera/chassis/joystick từ launch voice.
7. Tách process/executor, timeout, queue và giới hạn concurrency AI khỏi MQTT/Nav2.
   Ghi stage timing và turn ID, không log secret/audio/transcript không cần thiết.

Cần thêm provider interfaces (STT/LLM/embedding/TTS), adapter local, model config,
health/readiness và warmup; streaming/cancellation; checkpoint index/version;
CA/JWT của BE và retry có giới hạn; bộ kiểm thử tiếng Việt/tên phòng/nguồn RAG.
Đây là các phần chưa được triển khai trong bản sửa độ trễ robot này.

## Chặn tích hợp hiện tại cần xử lý trước

- BE đang chạy/Aiven có `V38__navigation_planned_path.sql`; `AI_VOICE/backend`
  có `V38__knowledge_rag_and_voice_assistant.sql`. Không thay thế backend đang chạy
  hoặc sửa Flyway history/checksum. Hợp nhất feature trên đúng source BE và thêm
  migration mới chưa dùng, kiểm tra mọi migration trước V38 cũng khớp.
- Không chạy thêm một BE với cùng MQTT client ID/robot lane. Voice inference
  service không cần sở hữu MQTT điều khiển robot.
- `src/api/__init__.py` cũ gọi `.json()` cả khi BE trả `200 audio/wav`.
  Đã sửa nhận WAV trực tiếp và chỉ parse JSON cho response pending/legacy;
  retry theo Retry-After có giới hạn. Có 3 kiểm thử contract riêng.
- Python `_handle_escort` hiện còn TODO lấy mapId và chưa gọi request_escort;
  `_handle_sos` chỉ trả lời bằng chữ nhưng nói như đã liên hệ cấp cứu. Không dùng
  hai handler này làm bằng chứng đã dispatch. Cần sửa phản hồi theo ACK/action
  receipt thật trước khi thử dẫn bệnh nhân; hiện chỉ đánh giá, chưa bật voice action.
- Adapter Python dùng httpx cần cấu hình trust CA phù hợp trước khi gọi BE HTTPS
  tự ký. Không dùng `verify=False`. Phiên bản bridge ROS có tham số CA riêng.
- README có vài dependency dùng trong source nhưng chưa liệt kê đầy đủ trong
  requirements (ví dụ FastAPI, multipart, SpeechRecognition). Chuẩn hóa môi trường
  theo entrypoint thật trước khi chạy; không cài toàn bộ lên Pi chỉ để thử GUI.

## Đo độ trễ

Tách `end_of_speech -> STT -> retrieval -> first_token -> first_audio` và thời gian
chờ queue. Chạy ít nhất tập câu có tên khoa/phòng, nhiễu motor và đồng thời MOVE;
báo p50/p95, lỗi STT, tỷ lệ câu có nguồn, CPU/RAM/VRAM, heartbeat gap và scan age.
Mục tiêu thực nghiệm ban đầu: phản hồi âm thanh đầu tiên trong 2–3 giây sau khi
nói xong cho câu hỏi ngắn với model đã warm; đây là mục tiêu, chưa phải số đo hay
cam kết. WAN/provider lạnh, câu dài, echo và model lớn có thể chậm hơn nhiều.

Hiện tại chưa tải model, chưa thay stack voice trên Pi, chưa benchmark latency
STT/LLM/TTS và chưa merge backend AI vào BE vận hành.

## Nguồn chính thức tham khảo

- https://github.com/SYSTRAN/faster-whisper — CPU INT8 và GPU inference.
- https://github.com/ggml-org/whisper.cpp — lựa chọn STT nhẹ, có Raspberry Pi.
- https://huggingface.co/Qwen/Qwen3-4B-GGUF — quantization, non-thinking, runtime.
- https://huggingface.co/BAAI/bge-m3 — semantic embedding đa ngôn ngữ.
- https://github.com/OHF-Voice/piper1-gpl — neural TTS local.
- https://huggingface.co/rhasspy/piper-voices/tree/main/vi/vi_VN — voice tiếng Việt.
