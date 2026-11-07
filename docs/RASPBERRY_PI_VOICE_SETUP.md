# Cài lại AI Voice và kết nối Raspberry Pi, mic, loa

Đối chiếu code 07/11/2026. Hướng dẫn này chưa được chạy trên Pi thật trong đợt tối ưu; máy phát triển hiện không có robot. Không coi các bước cấu hình đã viết là nghiệm thu phần cứng.

## 1. Chọn đúng luồng trước khi cài

**A — Pi native của RASPPI_MAR:** ưu tiên nếu robot đang chạy stack mapping/navigation của nhóm. Pi thu âm → BE STT → AI Python HTTPS 7861 → BE TTS → Pi phát loa. Companion nhường mic/loa khi navigation có job. Phần VAD 600 ms, đọc 170 WPM và bộ đo mới thuộc luồng này.

**B — ROS bridge của AI_VOICE (`mentorpi_ws/`):** Pi thu WAV → BE Java `/api/v1/pi/assistant` → Java gọi Python headless khi cần → tải WAV và phát. Không cần gọi 7861 trực tiếp. Luồng này cần BE đã có endpoint assistant, hiện không có trong OpenAPI server DuckDNS được kiểm tra.

Chọn một luồng. Không khởi động native chat, ROS bridge, demo voice của hãng và VoiceEngine PC cùng chiếm mic/loa. Không tạo MQTT worker thứ hai chỉ để chạy hội thoại.

## 2. Những địa chỉ và file khác nhau

- **BE URL:** ví dụ `https://mediassistrover.duckdns.org`, dùng STT/TTS, lịch sử, token và navigation. Nếu dùng BE local cho Pi, cần địa chỉ HTTPS mà Pi truy cập được.
- **AI URL của luồng A:** ví dụ `https://192.168.1.20:7861`, là máy chạy `src.web.robot_api`. IP ví dụ phải thay bằng IP thật và phải nằm trong chứng chỉ.
- **Python `http://voice:8000`:** tên nội bộ Docker ở luồng B/FE, Pi không gọi trực tiếp được.
- **Studio `127.0.0.1:7860`:** tùy chọn cho người nhập tài liệu/FAQ trên máy AI, không phải API chat của Pi.
- **`localhost` trên Pi là Pi**, không phải laptop. Không chép nguyên URL localhost của máy phát triển vào Pi.
- **Device JWT:** do BE cấp cho đúng robot; dùng với `/api/v1/pi/...`.
- **`AI_ROBOT_API_TOKEN`:** token riêng của AI 7861, trùng nội dung file token AI trên Pi.
- **`VOICE_SERVICE_TOKEN`:** Java ↔ Python headless trong Compose. Không phải Device JWT hoặc token 7861.

Trên Pi, `/home/ubuntu/mar-secrets/voice-ai.json` là đường dẫn cấu hình riêng, không phải URL. Đổi tên user/thư mục cho đúng robot. Không gửi OpenAI key, CA private key hoặc server private key lên Pi.

## 3. Nhận đúng code trước khi cài

AI_VOICE và RASPPI_MAR là hai repo. Bạn chỉ nhận được thay đổi đã được người bàn giao commit/push lên nhánh được chỉ định.

Trước khi pull, xem `git status --short`; sao lưu cấu hình riêng và xử lý thay đổi của người vận hành trước. Không reset hoặc chép đè worker/hardware config để chạy theo ví dụ.

Với clone mới, lấy nhánh `main` có bản cập nhật này:

```sh
git clone --branch main https://github.com/MediAssistRover-CapstoneProject/AI_VOICE.git
git clone https://github.com/MediAssistRover-CapstoneProject/RASPPI_MAR.git
```

Repo RASPPI_MAR có thư mục con `PI_MAR/` chứa `pi/` và `tools/`. Sau clone, các lệnh runtime trong tài liệu chạy từ `RASPPI_MAR/PI_MAR`, hoặc thư mục triển khai tương đương đã được nhóm cài trên robot; đừng chạy nhầm từ Git root chỉ chứa `PI_MAR/`.

Nếu bản RASPPI_MAR nhận về chưa có `pi/mediassist/voice_metrics.py` và `tools/voice_latency.py`, xem [gói vá native Pi](../deploy/pi/README.md). Gói dựa trên `e8d1ed6`, không tự cài firmware, ROS hoặc thay cấu hình robot.

## 4. Chuẩn bị máy AI cho luồng A

### 4.1. Python và dữ liệu

Trên Windows, ở root AI_VOICE, ví dụ bản chính hiện tại:

```powershell
cd D:\AI_VOic22222
py -3.11 -m venv .venv-voice
.\.venv-voice\Scripts\python.exe -m pip install -r requirements.txt
```

Nếu venv đã có và hoạt động thì dùng lại, không cần tạo lại. Không cài Vision hoặc toàn bộ model local lên Pi chỉ để phát/thu audio.

Nếu chưa có `.env`, sao chép `.env.example`; nếu đã có thì bổ sung trường thiếu thay vì ghi đè. Các mục cần kiểm tra trên **máy AI**:

```dotenv
BACKEND_URL=https://mediassistrover.duckdns.org
AI_ROBOT_API_TOKEN=TOKEN_NGAU_NHIEN_RIENG_TOI_THIEU_32_KY_TU
OPENAI_API_KEY=KHOA_CUA_MAY_AI_NEU_DUNG_RAG
AI_RESPONSE_MODEL=MODEL_NHOM_DA_DUOC_CAP_QUYEN
AI_INTENT_MODEL=MODEL_PHAN_GIAI_DIEM_DEN_DA_DUOC_CAP_QUYEN
AI_REASONING_EFFORT=low
EMBEDDING_PROVIDER=local
AI_PRELOAD_CLIENTS=true
AI_WAKE_ENABLED=false
AI_WAKE_PRELOAD=false
```

Các chữ viết hoa mô tả là placeholder, phải thay; không chép chúng như khóa/model thật. Token AI phải ngẫu nhiên riêng, không dùng mật khẩu tài khoản BE. `BACKEND_URL` không tự đồng bộ hospital articles vào RAG.

Có thể dùng chế độ FAQ_ONLY khi có FAQ công khai đã duyệt và chưa đặt OpenAI key. `/health` READY chỉ báo có cấu hình/dữ liệu tối thiểu; nó không chứng minh key có quyền gọi model, STT/TTS hoặc phần cứng đã hoạt động.

Đảm bảo máy AI có `data/faqs.json`, `data/hospital_vector_store.json` và các tài liệu đã kiểm tra. Pi native lấy đáp án từ **máy AI này**. Không đặt kỳ vọng tài liệu upload lên một BE khác tự xuất hiện ở đây.

### 4.2. HTTPS và chứng chỉ

Nếu đã có CA/server certificate hợp lệ của nhóm, dùng lại và kiểm tra tên/IP. `run_robot_api.ps1` cần các file sau tại `%LOCALAPPDATA%\MediAssistRover\voice-ai`:

- `ca.pem`: CA công khai, Pi được nhận bản này.
- `server.pem`, `server.key`: chứng chỉ và private key của máy AI.

Nếu cần tạo **mới** cho mạng LAN, repo có công cụ dùng lại được. Ví dụ IP AI `192.168.1.20`:

```powershell
$voiceTLS = Join-Path $env:LOCALAPPDATA 'MediAssistRover\voice-ai'
.\.venv-voice\Scripts\python.exe -m pip install cryptography
.\.venv-voice\Scripts\python.exe backend/deploy/create_mapping_tls.py --server-name 192.168.1.20 --server-name 127.0.0.1 --directory $voiceTLS
```

Công cụ tạo `ca.pem`, `ca-key.pem`, `fullchain.pem`, `privkey.pem`. Trong lần cài mới, đặt bản sao đúng tên launcher cần:

```powershell
Copy-Item (Join-Path $voiceTLS 'fullchain.pem') (Join-Path $voiceTLS 'server.pem')
Copy-Item (Join-Path $voiceTLS 'privkey.pem') (Join-Path $voiceTLS 'server.key')
```

Đừng chép đè cặp certificate/key đang chạy nếu chưa xác định chúng thuộc CA nào. Khi IP đổi, cần chứng chỉ mới chứa IP/tên mới; không bỏ kiểm tra TLS hoặc dùng `curl -k` để coi là đã kết nối đúng.

Khởi động API:

```powershell
.\run_robot_api.ps1
```

Giữ terminal này mở. Cho phép kết nối TCP 7861 từ mạng/địa chỉ robot trong firewall máy AI. Không cần mở cổng database. API 7861 **không được bật bởi Compose mặc định**; Compose đã gộp phần headless phục vụ Java, không gộp API native này.

Từ Pi kiểm tra:

```sh
curl --fail --cacert /home/ubuntu/mar-secrets/voice-ai-ca.pem https://192.168.1.20:7861/health
```

Kỳ vọng `state=READY`, đúng `faq_active`, `vector_count`. Health không cần token; gọi `/api/robot/chat` vẫn cần token riêng.

### 4.3. Wake tiếng Việt tùy chọn

Dùng `--start-now` để thử hội thoại trước, không phụ thuộc wake. Sau đó chọn:

- `serial`: frame wake do firmware WonderEcho gửi qua `/dev/wonderecho`. Không cam kết firmware hiểu “Hello MAR” hoặc “Chào cậu” nếu chưa thử.
- `local_ai`: Pi gửi PCM lên máy AI nhận dạng wake; tên này không có nghĩa model chạy trên Pi.

Nếu chọn `local_ai`, cài trên máy AI `sherpa-onnx==1.13.8`, cung cấp model tương thích gồm `encoder.int8.onnx`, `decoder.onnx`, `joiner.int8.onnx`, `tokens.txt`; đặt `AI_WAKE_MODEL_PATH`, `AI_WAKE_ENABLED=true`, `AI_WAKE_PHRASE=Chào cậu`. Sau khi kiểm tra model mới bật `AI_WAKE_PRELOAD=true`. Model không nằm trong Git; pull code không tự tải nó. Phrase bên Pi phải giống máy AI.

## 5. Chuẩn bị BE

Luồng A dùng các endpoint đã có trong hợp đồng server được kiểm tra:

- `POST /api/v1/pi/auth/token`.
- `GET /api/v1/pi/speech/status`.
- `POST /api/v1/pi/speech/transcribe`: WAV → transcript.
- `POST /api/v1/pi/speech/synthesize`: text → WAV/202 chờ tạo.
- `/api/v1/pi/voice/...`: lịch sử và catalog/yêu cầu navigation.

Có endpoint không chứng minh provider đã cấu hình hoặc Device JWT của robot có quyền. Dùng công cụ `voice_audio.py status` bên dưới để kiểm tra.

Nếu BE đã deploy đang điều phối robot, dùng đúng BE đó và không bật một BE/MQTT dispatcher khác giành cùng robot. Nếu cần BE local cho thử nghiệm, theo [RUN_ALL.md](../backend/RUN_ALL.md). Native VoiceClient yêu cầu HTTPS; default local `http://127.0.0.1:8080` chưa đủ cho Pi. Có thể cấu hình profile reverse proxy TLS trong Compose; certificate phải chứa tên/IP Pi truy cập và `PUBLIC_BASE_URL` phải đúng HTTPS origin. Cổng 443 trên host phải khả dụng.

Các cờ assistant Java và Pi bridge ở phần B không thay thế cấu hình STT/TTS của luồng A. Không bật quyền voice actions chỉ để thử hỏi đáp.

## 6. Chuẩn bị Pi: môi trường và mic/loa

Luồng native giả định MentorPi Linux aarch64, ROS 2 Humble và phần cứng của nhóm đã được cài đúng. Bản Pi hiện dùng Python 3.10 trong môi trường MentorPi; khác Python 3.11 ở máy AI.

Vào đúng môi trường/container MentorPi đang sở hữu phần cứng, dùng user vận hành `ubuntu`. Thư mục có thể khác ví dụ `/home/ubuntu/mediassist-mqtt-2026-10-08`; luôn dùng nơi thực tế chứa `pi/` và `tools/` của RASPPI_MAR.

```sh
cd /home/ubuntu/mediassist-mqtt-2026-10-08
uname -m
python3 --version
arecord -l
aplay -l
```

Nếu ALSA chưa có và môi trường cho phép cài package: `sudo apt-get install alsa-utils`. Nếu dùng container, `/dev/snd` phải được truyền vào và user cần quyền nhóm audio. Không cài thêm hệ ROS khác đè bản hãng chỉ để chạy voice.

Đã có `pi/.venv` thì kiểm tra dùng lại. Bản Pi có offline bundle đầy đủ có thể chạy `bash tools/install_pi_offline.sh`; script yêu cầu aarch64/Python 3.10 và `offline/virtualenv.pyz`, `offline/wheels`, lockfile. Không hứa lệnh offline chạy được khi chỉ có clone thiếu bundle.

Nếu cài online trong image đã có ROS và Python 3.10:

```sh
python3 -m venv --system-site-packages pi/.venv
pi/.venv/bin/python -m pip install -r pi/requirements.txt
```

Nếu máy thiếu module venv, cài package venv phù hợp image trước. Source môi trường ROS của image khi chạy công cụ navigation; không cần OpenAI SDK hoặc model LLM trên Pi cho native chat.

### 6.1. Kiểm tra âm thanh thô trước mạng/AI

Lấy tên card từ `arecord -L`, `aplay -L`; thay placeholder trong lệnh:

```sh
arecord -D 'plughw:CARD=TEN_CARD_MIC,DEV=0' -f S16_LE -r 16000 -c 1 -d 5 /dev/shm/mar-mic-test.wav
aplay -D 'plughw:CARD=TEN_CARD_LOA,DEV=0' /dev/shm/mar-mic-test.wav
```

Nói một câu thử không chứa dữ liệu bệnh nhân. Nếu không thu/nghe được chính file này, xử lý thiết bị, cổng USB, âm lượng/mute, quyền hoặc tiến trình chiếm thiết bị trước. AI không sửa được mic chưa thu ra tiếng.

Luồng native hiện dùng **`speech.device` chung cho thu và phát**. `auto` chỉ chọn được khi có đúng một card USB phù hợp. Nếu mic và loa khác card, ghép một thiết bị ALSA duplex; thêm vào cấu hình ALSA cá nhân đang có, không ghi đè toàn bộ `.asoundrc`:

```text
pcm.mar_duplex {
    type asym
    playback.pcm "plughw:CARD=TEN_CARD_LOA,DEV=0"
    capture.pcm "plughw:CARD=TEN_CARD_MIC,DEV=0"
}
```

Sau đó thử lại bằng `-D mar_duplex` và đặt `speech.device` là `mar_duplex`. ROS bridge phần B có input/output riêng; không áp tên PortAudio và ALSA như cùng một kiểu định danh.

## 7. Cấu hình private cho native

Chuẩn bị thư mục riêng trên Pi:

```sh
install -d -m 700 /home/ubuntu/mar-secrets
```

Đưa vào đó theo kênh riêng: token AI (chỉ nội dung token, không có tiền tố Bearer), CA công khai của AI, Device JWT/credential của robot và CA BE nếu BE dùng CA riêng. Không đặt token ở đối số CLI hoặc gửi vào group chat.

Ví dụ các file:

- `voice-ai.token`: bằng giá trị `AI_ROBOT_API_TOKEN` trên máy AI.
- `voice-ai-ca.pem`: bản `ca.pem` công khai của máy AI.
- `be-device.jwt`: Device JWT đúng robot, cần cơ chế refresh.
- `device-secret`: secret thiết bị nếu cấu hình tự refresh, khác token AI.
- `voice-ai.json`: cấu hình hội thoại.

Chỉ tạo file mẫu nếu chưa có:

```sh
cp pi/config/voice-ai.latency.example.json /home/ubuntu/mar-secrets/voice-ai.json
chmod 600 /home/ubuntu/mar-secrets/voice-ai.json /home/ubuntu/mar-secrets/voice-ai.token /home/ubuntu/mar-secrets/be-device.jwt
```

Sửa JSON bằng editor để trỏ đúng địa chỉ/thư mục. Cấu trúc cần có:

```json
{
  "base_url": "https://192.168.1.20:7861",
  "token_file": "/home/ubuntu/mar-secrets/voice-ai.token",
  "ca_file": "/home/ubuntu/mar-secrets/voice-ai-ca.pem",
  "stt_language": "auto",
  "actions_enabled": false,
  "wake_serial": "/dev/wonderecho",
  "wake": {"mode": "serial", "phrase": "Chào cậu"},
  "capture": {"end_silence_ms": 600, "tail_silence_ms": 150, "min_speech_ms": 360, "threshold": 0.008},
  "speech": {"speed_wpm": 170, "volume": 70}
}
```

Người nói chậm/ngắt quãng có thể cần im lặng 900 ms và tốc độ đọc 150–160 WPM. Đây là cấu hình **hội thoại native**, không đổi giọng Studio hoặc vận tốc navigation.

### Worker config của robot

Giữ nguyên `pi/config/mqtt.worker.json` đã commissioning. Các đoạn sau là **trường cần kiểm tra/bổ sung**, không phải file worker đầy đủ để chép đè:

- `robot_id`: UUID robot thật trong BE, không dùng chuỗi tên robot mẫu.
- `mapping.upload_base_url`: HTTPS BE thật; `upload_token_file`: file Device JWT; `upload_ca_file`: CA BE nếu cần.
- `mapping.robot_code` và `mapping.device_secret_file`: nếu dùng cơ chế cấp lại JWT. `upload_token_file` phải tồn tại theo kiểm tra worker; không dùng file secret làm file JWT.
- `speech.enabled=true`, `speech.engine="be-tts"`, `speech.device`: ALSA đã thử; `synthesize_url`: BE `/api/v1/pi/speech/synthesize`; `token_file` cùng Device JWT; `ca_file` tương ứng BE; `poll_interval_sec=0.5` chỉ là fallback nếu BE không gửi Retry-After.

Khối bật companion khi navigation chạy:

```json
"voice_chat": {
  "enabled": true,
  "config_file": "/home/ubuntu/mar-secrets/voice-ai.json",
  "idle_settle_sec": 2,
  "start_timeout_sec": 12,
  "max_turns": 10
}
```

Hai khối `speech` khác nhau: trong worker chọn engine/thiết bị/BE; trong `voice-ai.json` chọn tốc độ/âm lượng hội thoại. Chỉnh đúng file mới có tác dụng.

## 8. Thử native từng lớp

Từ thư mục RASPPI_MAR thực tế:

```sh
pi/.venv/bin/python tools/voice_audio.py devices
pi/.venv/bin/python tools/voice_latency.py check-config /home/ubuntu/mar-secrets/voice-ai.json
pi/.venv/bin/python tools/voice_audio.py --config pi/config/mqtt.worker.json status
```

`check-config` chỉ xác nhận tham số, không kiểm chứng mic/network. `status` dùng Device JWT gọi BE; phải xác minh STT/TTS được bật. Nếu `--config` khác mặc định, nó đứng **trước** tên subcommand.

Thử tạo giọng bằng BE và phát qua thiết bị đã chọn:

```sh
pi/.venv/bin/python tools/voice_audio.py tts --text 'Xin chào, bạn cần MAR giúp gì không?' --speed-wpm 170 --output-device mar_duplex --play
```

Nếu không dùng `mar_duplex`, thay bằng tên card đúng. Thử STT từ WAV đã thu:

```sh
pi/.venv/bin/python tools/voice_audio.py stt --file /dev/shm/mar-mic-test.wav --language vi
```

Thử một phiên không chờ wake, chưa chạy companion thường trực:

```sh
pi/.venv/bin/python tools/voice_audio.py chat --ai-config /home/ubuntu/mar-secrets/voice-ai.json --start-now --once --no-transcripts 2>&1 | tee /dev/shm/mar-voice-latency.log
```

Robot chào/bíp rồi mới nói. Hỏi đúng một alias FAQ đã duyệt; thử câu có `wi-fi` và câu xe lăn. Nói “tạm biệt” hoặc Ctrl+C để kết thúc. Với `actions_enabled=false`, không thử coi lời dẫn đi là đã có job di chuyển.

Đọc thời gian:

```sh
pi/.venv/bin/python tools/voice_latency.py summary /dev/shm/mar-voice-latency.log
```

Sau khi chạy được, bỏ `--start-now` để thử wake serial. Chỉ đổi sang `local_ai` khi máy AI đã sẵn model/cấu hình. Chưa xác nhận wake thì không chẩn đoán lỗi chat bằng cách đổi model trả lời.

## 9. Đưa native vào navigation thường trực

Kết thúc CLI chat trước. Trên mapping UI tạo đúng phiên map/navigation và lấy session ID thật. Dùng lệnh của phiên, ví dụ:

```sh
pi/.venv/bin/python tools/run_navigation_session.py --session-id ID_PHIEN_THAT --voice-chat
```

Lệnh này đòi hỏi toàn bộ navigation/hardware/BT/ROS đã cài, không phải lệnh cài robot từ đầu. Không bịa session ID. Companion đợi robot IDLE ổn định 2 giây, nghe wake và nhường âm thanh khi có job. Xem log `pi/state/stack/voice.log`; `VOICE_STATE WAIT_WAKE` là đang chờ gọi, không phải bị treo.

Trong lúc thử hỏi đáp giữ `actions_enabled=false`. Nếu nhóm cần dẫn đường bằng giọng, phải có catalog endpoint/map thật, workflow voice phù hợp và quy trình xác nhận của BE/Pi; AI không tự sinh tọa độ hoặc phát `/cmd_vel`. Ctrl+C chat không hủy job đã được BE nhận; hủy job qua quy trình navigation.

## 10. Lựa chọn B: ROS bridge trong repo này

Phần này thay cho native, không chạy cùng phần A. BE đích phải có `/api/v1/pi/assistant/sessions`, `/sessions/{id}/turns`, `/turns/{id}/audio`.

Trên BE cấu hình `ASSISTANT_ENABLED=true`, `ASSISTANT_PI_BRIDGE_ENABLED=true`, TTS/key hợp lệ và Device JWT đúng robot. Giữ voice actions tắt nếu chỉ thử hỏi đáp. Nếu cần API FAQ/Python headless mới, deploy đúng bản BE/Compose mới trước; server DuckDNS hiện chưa được xác minh có các endpoint này.

Trong `mentorpi_ws/` trên Pi, chuẩn bị `.env` riêng theo `docker-compose.yml`:

```dotenv
MEDIASSIST_BACKEND_URL=https://BE_DA_CO_ASSISTANT
MEDIASSIST_ROBOT_CODE=MA_ROBOT_DA_CAP
MEDIASSIST_DEVICE_SECRET=SECRET_THIET_BI_RIENG
MEDIASSIST_LANGUAGE=vi-VN
ALSA_INPUT_DEVICE=
ALSA_OUTPUT_DEVICE=
MEDIASSIST_WAKE_MODE=serial
WONDERECHO_SERIAL_PORT=/dev/wonderecho
```

Input của node capture dùng **sounddevice/PortAudio**, output playback dùng **ALSA `aplay -D`**. Có thể để input mặc định hoặc chọn đúng tên PortAudio; không mặc định tên `plughw` nào cũng hợp lệ cho sounddevice.

```sh
cd mentorpi_ws
MACHINE_TYPE=MentorPi_Mecanum sh preflight.sh
docker compose up -d --build voice-bridge
docker compose logs -f voice-bridge
```

Compose hiện mount bắt buộc `/dev/wonderecho`. Nếu robot không có thiết bị đó, file Compose phải bỏ mapping tương ứng và chọn wake/manual phù hợp; chỉ đổi biến `MEDIASSIST_WAKE_MODE` không làm device mapping biến mất. Vosk fallback cần model được pin/checksum; không tự có sau pull.

Nếu BE dùng CA riêng, cấu hình `MEDIASSIST_CA_CERT_HOST`, `MEDIASSIST_CA_CERT_CONTAINER` và `MEDIASSIST_CA_CERT` khớp mount. Khi dùng chứng chỉ public hợp lệ có thể dùng trust store hệ thống.

Thử bắt đầu thủ công và dừng container:

```sh
docker compose exec voice-bridge bash -lc 'source /opt/ros/humble/setup.bash && source /ws/install/setup.bash && ros2 service call /mediassist_voice/start_conversation std_srvs/srv/Trigger "{}"'
docker compose stop voice-bridge
```

Bridge hiện dùng khoảng im lặng 1,2 giây trong capture và 150 WPM ở prompt fallback. Bản vá tối ưu native không thay các giá trị này. Bridge không tự publish motor command và không thay thế stack navigation.

## 11. Chẩn đoán chậm và lỗi thường gặp

- **FAQ khớp khi gõ nhưng voice vẫn chậm:** đọc transcript thật và `answer_mode`/`model`. Thêm alias tương đương sau khi kiểm tra; `wifi` và `wi-fi` là hai chuỗi khác trong bộ khớp hiện tại.
- **FAQ đúng, vẫn đợi ra tiếng:** so `speech_end_to_capture_end_ms`, `stt_roundtrip_ms`, `ai_roundtrip_ms`, các trường TTS/poll và mốc bắt đầu playback. FAQ không xóa thời gian STT/TTS/mạng.
- **HTTP 401/403 từ AI:** kiểm tra token AI; từ BE: kiểm tra Device JWT/robot/quyền. Không tráo hai token.
- **TLS/certificate mismatch:** tên/IP phải nằm trong SAN, Pi có đúng public CA, đồng hồ hệ thống đúng. Không tắt verify.
- **Không gọi được AI:** kiểm tra IP AI, firewall 7861, tiến trình `run_robot_api.ps1`, `/health`. Không dùng localhost của laptop ở Pi.
- **Mic/loa busy hoặc tiếng bị vọng:** dừng chương trình audio trùng, kiểm tra khóa thiết bị và chế độ chờ phát xong mới nghe. Không chạy nhiều listener để thử.
- **Native báo HTTPS required:** URL BE/AI phải HTTPS; cổng 8080 HTTP của Compose không đáp ứng luồng này nếu chưa qua TLS proxy.
- **Không thấy FAQ mới:** xác nhận đang sửa đúng file trên đúng máy AI. Pi native 7861 và Java headless phải dùng đúng thư mục dữ liệu; một checkout C không tự đồng bộ với D/server.
- **`VOICE_TIMING` chưa có:** kiểm tra đã áp bản cập nhật RASPPI_MAR, không chỉ pull AI_VOICE.

Nghiệm thu với robot: nghe WAV thô rõ → BE status/STT/TTS đạt → AI health và FAQ đúng → thử chat thủ công → đo ít nhất nhiều lượt cùng điều kiện → thử wake → mới đưa vào companion. Kết quả cần ghi phiên bản code, mạng và loại mic/loa; không dùng số đo text 26 ms làm cam kết giọng nói end-to-end.
