# Độ trễ Voice: đã giảm gì và đo thế nào?

## Đối chiếu production ngày 19/11/2026 và hướng hoàn thiện

Phạm vi: companion native Pi tại `192.168.1.137`, AI Voice trên EC2 media.
Log ban đầu bên dưới lấy từ release `19f325ed17dba37e3e909eba0f8e397439b4758c`.
Các ghi chú 07/11 ở phần dưới là lịch sử, không mô tả đầy đủ đường đi direct speech hiện tại.

### Bản đã triển khai và kiểm tra ngày 19/11

- BE đã triển khai bản build theo nội dung `f20be37f822641939fdd9c0bb36be60a0de1da3f`;
  AI Voice `a6f6c1590089d9410347f0ac44d16b6a5d32e194`. Đây là mã băm nội dung
  bản cài thủ công, không phải Git commit. Source còn ở local, cần push để lần CI sau giữ bản sửa.
- Pi đã cài đúng 5 file production: `voice_wake.py`, `voice_client.py`, `voice_chat.py`,
  `voice_runtime.py`, `media_client.py`. Không cài file test; giữ stack dừng, không phát lệnh bánh.
- Giữ `M A`, các cách đọc `em a`, `mờ a`. Thu liên tục trong lúc chờ inference;
  chỉ giữ hai candidate trong RAM, loại candidate chờ quá 2,5 giây. Khi nhường workflow,
  dừng/reap `arecord` và hủy kết nối audio đang chờ.
- Lọc DC/tiếng ù bằng high-pass 70 Hz trước VAD. Ngưỡng onset là
  `max(0.004, nền_nonspeech × 10^(6/20))`; nền thích nghi từ các khung không có lời nói.
  Sau onset dùng ngưỡng release thấp hơn để giữ âm nhẹ. Đây không phải tách người nói
  hoặc khử mọi loại tiếng ồn; tiếng lớn vẫn phải được ASR nhận đúng toàn bộ wake phrase.
- Client HTTPS tái sử dụng tối đa hai kết nối mỗi origin, không tự phát lại POST lỗi mạng.
  Profile lấy từ catalog voice hoặc `/api/v1/pi/speech/profile`, hỗ trợ ETag/304;
  voice/speed/instructions/revision được chuyển tới AI. Quyền và điều kiện tạo MOVE vẫn kiểm tra trực tiếp ở BE.
- Endpoint mới `/api/robot/speech/synthesize/stream`: PCM16 mono 24 kHz,
  Nginx tắt buffering, Pi chờ buffer 120 ms rồi phát nguyên câu. Chỉ fallback WAV khi
  chưa bắt đầu phát; không đọc lại một câu đã phát dở. Giữ lease tới khi provider thread
  thật sự kết thúc, kể cả khi client ngắt trước khi nhận header.
- Chỉ cache hai câu công khai chào/tạm biệt theo đầy đủ profile, ở RAM. Câu trả lời riêng
  không cache. Log chứa request ID và timing; không ghi âm hay transcript wake.
- Kiểm tra: 40 kiểm tra AI trong bộ CI đạt; 6 kiểm tra BE đạt; Pi 27 kiểm tra,
  1 bỏ qua do thiếu fixture. Các kiểm tra bổ sung nằm trong file test sẵn có.
- API thật từ Pi: profile `nova`, `1.2`, revision `7c3211a5664056bc`; conditional GET trả 304.
  Một câu mới: nhận header 1.714 s, đủ buffer đầu 2.088 s, nhận hết 2.176 s, 116150 byte PCM.
  Đây là một mẫu truyền tải, không phải p95 hoặc thời gian âm thanh thực phát ra loa.
  Lời chào sau prewarm đọc buffer RAM đầu trong dưới 1 ms. High-pass đo khoảng
  0.413 ms/khung 30 ms trên Pi. Chưa có lượt gọi thật có nhãn sau bản vá nên chưa kết luận recall.

Wake vẫn dùng ASR tiếng Việt hiện có; chưa thay thành KWS chuyên dụng hoặc hứa độ nhạy như Siri.
Với câu gọi ngắn `M A`, cần đo các lượt nói thật và số wake nhầm trong tiếng nền trước khi hiệu chỉnh thêm.

### Bằng chứng từ lượt thử chiều

- Trong khoảng log Pi 14:59–19:05 giờ Việt Nam: 608 candidate wake được gửi;
  server ghi 607 `NO_PHRASE_MATCH`, một `MATCH` lúc 17:32:54. Candidate gồm cả
  tiếng nền, nên đây không phải số lần người dùng chủ động thử hay phép đo recall.
- Pi loại 135 đoạn `TOO_LONG` trước khi gửi; hai đoạn ghi nhận clipping.
- Wake inference: trung vị 102 ms, p95 238 ms, tối đa 916 ms. Không có
  `LOW_AUDIO_ENERGY` trong cửa sổ này. PCM đã tới AI; chưa xác định được nhận sai
  cách phát âm hay đoạn thu chứa tiếng khác vì không lưu audio/transcript wake.
- Wake hiện dùng Zipformer tiếng Việt 30M int8, nhận dạng nguyên đoạn rồi so bằng
  toàn bộ chuỗi chuẩn hóa với `M A`, `em a`, `mờ a`. Đây chưa phải bộ keyword spotting.
- Một lượt: Pi STT 4.014 s, còn worker STT trên AI 1.504 s. Khâu answer phía Pi
  4.920 s, trong khi AI xử lý navigation proposal khoảng 1 ms. Các khoảng chênh
  bao gồm truyền tải, hàng đợi và các bước BE/client; không phải thời gian LLM thuần.
- TTS câu mới: first byte phía provider 1.224 s, nhận đủ audio 2.226 s, Pi bắt
  đầu phát sau 5.046 s. Endpoint hiện gom đủ WAV rồi mới trả, Pi nhận đủ rồi phát.
- AI và BE hiện đều `nova`, tốc độ `1.2`. AI chat/intent là `gpt-6-luna`;
  TTS code dùng `gpt-4o-mini-tts`. Không có bằng chứng cần đổi model chat để sửa wake.
- Snapshot sau lượt thử: Voice dùng khoảng 212 MiB; máy còn khoảng 2.8 GiB RAM
  khả dụng. Đây không phải phép đo CPU/RAM tại thời điểm tải cao.

### Thứ tự thực hiện đề xuất

1. **Chẩn đoán và capture wake:** thêm request ID xuyên Pi/AI, các thời gian
   capture/upload/queue/inference/response; phân biệt ASR rỗng với không khớp.
   Giữ metrics, không lưu mặc định âm thanh hoặc lời nói nền. Đo riêng cách đọc
   “mờ a”, “em a”, “em ay” trong yên tĩnh và tiếng ồn; chỉ số cần có là recall
   trên các lượt có nhãn, tự wake/giờ và độ trễ tới lời chào.
2. **Giảm bỏ sót:** đệm đầu/đuôi âm thanh và lọc nhiễu nhẹ trước VAD; giữ thu
   liên tục vào bộ đệm RAM có giới hạn khi request đang xử lý để tránh khoảng mù.
   Không chỉ nâng trần 4 giây hoặc so chuỗi con tùy ý. Đánh giá KWS streaming có
   hỗ trợ cách phát âm cần dùng; kiểm chứng mô hình/ngôn ngữ trước khi thay ASR.
   Giữ các model hội thoại và TTS trên EC2 hiện tại.
3. **Giảm round trip:** client HTTP có pooling/keepalive; cache catalog/profile
   theo map/session/revision và vô hiệu khi đổi phiên. Chuẩn bị lời chào công khai.
   BE vẫn xác nhận quyền, phiên và tạo job; không để AI tự điều khiển motor.
4. **Phát lời đáp sớm:** endpoint audio streaming riêng, Pi phát khi đủ buffer
   nhỏ; ưu tiên độ liền câu, không tổng hợp từng token. Đo WAV/PCM streaming trước;
   đánh giá Opus khi truyền tải trên mạng là nút thắt. Giữ đường WAV cũ làm tương thích.
   Đồng bộ voice/speed/instructions từ một speech profile có revision của BE.
5. **Rollout từng lớp:** capture/wake trước, HTTP/caching sau, audio streaming cuối.
   Ghi rõ fallback đang dùng, thử vài tình huống có nhãn; không chạy model hội thoại
   cho mọi tiếng nền, không hứa độ trễ cố định trước khi đo lại trên robot.

Chưa cần mua instance mới hoặc train model hội thoại. Nếu KWS hiện có không đạt
được tiếng Việt/Anh đã chọn, mới cân nhắc dữ liệu wake có nhãn để hiệu chỉnh/train.
Không train model wake bằng các candidate chưa biết có thật sự chứa keyword hay không.

Nguồn kỹ thuật:
[Sherpa keyword spotting](https://k2-fsa.github.io/sherpa/onnx/kws/index.html),
[OpenAI streaming TTS và định dạng audio](https://developers.openai.com/api/docs/guides/text-to-speech).

## Ghi chú lịch sử 07/11/2026

Cập nhật 07/11/2026. [Đối chiếu code](VOICE_CHANGELOG_2026-11-07.md) tách main `81c1508`, phần thêm trong AI và bản Pi native từ `e8d1ed6`. Bàn giao theo [hướng dẫn Pi](RASPBERRY_PI_VOICE_SETUP.md) và [gói vá](../deploy/pi/README.md).

## Hai loại thời gian

**Đợi bắt đầu trả lời:** từ khung tiếng nói cuối đến khi bắt đầu phát đáp án. Đây là độ trễ người dùng cảm nhận.

**Hoàn tất lượt:** gồm cả thời gian người dùng nói, xử lý, robot đọc toàn câu và ghi lịch sử. Tăng WPM rút ngắn phần đọc, không trực tiếp làm STT/model nhanh hơn.

`speech_end_to_playback_start_ms` dùng VAD và lúc gọi trình phát; chưa đo trễ vật lý từ gọi `aplay` đến âm thanh ra loa.

## Các bước native Pi

1. **Wake/khởi tạo:** serial phụ thuộc firmware; wake AI nhận dạng trên máy AI. Companion đợi IDLE ổn định 2 giây sau đổi trạng thái, không cộng vào mọi câu. Mở phiên còn kiểm tra BE/AI, lịch sử, catalog và lời chào.
2. **Bíp/thu:** bíp 150 ms, đệm 150 ms và chi phí thiết bị. Thời gian nói thay đổi; trần thu 15 giây/chờ có lời 12 giây không phải thời gian cố định mọi lượt.
3. **Chốt câu:** native cũ 810 ms im lặng, bản vá 600 ms, giữ đuôi WAV 150 ms. Bớt ngưỡng 210 ms khi im lặng liên tục; nói ngắt quãng có thể cần 900 ms.
4. **STT:** gửi WAV PCM16 mono 16 kHz tới BE `/api/v1/pi/speech/transcribe`. `stt_roundtrip_ms` gồm upload/mạng/server/download; `stt_server_ms` do BE báo. Hiệu hai số không phải mạng thuần.
5. **AI:** gửi text/history/catalog tới 7861 `/api/robot/chat`. FAQ bỏ vector/model; chào/tạm biệt dùng câu cố định; không khớp thì RAG/model hoặc intent/catalog.
6. **TTS:** BE `/api/v1/pi/speech/synthesize` trả 200 WAV hoặc 202 đang tạo. Pi tôn trọng Retry-After. Header 2 giây có thể làm phát hiện WAV sẵn sàng chậm gần 2 giây; không chứng minh model tạo WAV đúng 2 giây.
7. **Phát:** chờ khóa audio, kiểm tra hash/WAV, chỉnh âm lượng, chuẩn bị file và `aplay`. Native yêu cầu 170 WPM thay 150, chỉnh được, không đổi vận tốc navigation.
8. **Lịch sử/nghe tiếp:** ghi lịch sử text sau phát, ảnh hưởng khoảng nghỉ tới lượt tiếp theo. Native không lưu WAV người dùng thành kho audio.

Phần đợi phản hồi = chốt câu + STT roundtrip + AI roundtrip + TTS/poll/download + chuẩn bị/mở phát. Mốc tổng và thành phần lồng nhau; không cộng mọi `*_ms` thành tổng mới.

ROS bridge khác native: gửi WAV trực tiếp Java assistant, im lặng 1,2 giây và 150 WPM ở prompt fallback. Không áp thông số native cho bridge.

## Số đo đã có

### Lượt thực qua local BE

Xe lăn thiếu “cho mượn”, Wi-Fi có dấu nối nên trước thêm alias gọi `gpt-6.1-sol`: DB ghi xe lăn **8.461 ms**, Wi-Fi **3.903 ms**. Sau thêm alias, gửi đúng text qua public assistant: xe lăn **26 ms HTTP/8 ms BE**, Wi-Fi **28 ms HTTP/9 ms BE**, `model=null`.

Đây là các lượt riêng trên Windows, không phải p50/p95. Lượt xác minh dùng text, không gồm STT/TTS/loa. “Thời gian tra cứu” ở `kiosk-voice.js` tính từ gửi câu text (có thể gồm tạo/lấy session) đến nhận turn trước phát giọng, không tính toàn bộ từ lúc bệnh nhân nói.

### Offline CPU/ASGI

Python 3.11.9 Windows, không Pi/mic/mạng/provider thật:

- FAQ mẫu 100 history khác: search/generation giả lập 100/100 → 0/0; p50 CPU 0,098 → 0,021 ms.
- Vector 37 đoạn thật: p50 0,163 ms, p95 0,200 ms.
- WAV mẫu 5 giây/24 kHz PCM16, volume 70%, 30 lượt: p50 68,190 → 19,190 ms; p95 72,060 → 19,682 ms. Nhanh 3,55 lần riêng phép tính này, không phải toàn Pi/hệ thống.
- Chào ASGI trong tiến trình 100 lượt sau warm-up: p50 0,956 → 1,046 ms; p95 1,122 → 1,257 ms. Có dao động/chi phí đo, không chứng minh mạng nhanh hơn.

170/150 tăng 13,3% tốc độ yêu cầu; thời lượng lý thuyết cùng câu khoảng 88,2% cũ nếu provider không giới hạn. Cần nghe/đo WAV thật, không cam kết đếm từ tiếng Việt chính xác.

## Code đã tối ưu

- FAQ riêng, lookup sớm, tách history cache, alias thực nhận và reload không Re-index.
- Tái sử dụng SDK/pool AI→provider; tùy chọn preload client/wake. Không khẳng định mọi entry point đã preload hay Pi có pool mới.
- API 7861 có `answer_mode`, `timings_ms`, `metrics_scope`, `Server-Timing`, `X-AI-Cache`; replay giữ body gốc.
- Pi native có VAD/đuôi WAV cấu hình được, loại tiếng động ngắn, volume PCM nhanh và metrics; phải cập nhật cả Pi.
- Compose gộp cách chạy/cấu hình; Docker tự nó không chứng minh request nhanh hơn.

## Đo khi có robot

Sau khi cài, ở thư mục Pi có `pi/`, `tools/`, chưa mở companion thường trực:

```sh
pi/.venv/bin/python tools/voice_latency.py check-config /home/ubuntu/mar-secrets/voice-ai.json
pi/.venv/bin/python tools/voice_audio.py chat --start-now --once --no-transcripts 2>&1 | tee /dev/shm/mar-voice-latency.log
pi/.venv/bin/python tools/voice_latency.py summary /dev/shm/mar-voice-latency.log
```

Với navigation đọc `pi/state/stack/voice.log`. Summary tách thành công/im lặng/lỗi/hủy, tính p50/p95 các trường đã đo. `--no-transcripts` tránh ghi lời nói ở log chẩn đoán, không thay hợp đồng lịch sử BE.

Đo nhiều lượt cùng câu, model/giọng/mạng; tách lạnh/nóng, khớp/trượt FAQ:

- `speech_end_to_capture_end_ms` cao: noise/VAD/thu âm; giảm ngưỡng không cắt mất tiếng.
- `stt_roundtrip_ms` cao: so server time, thử WAV đúng chuẩn, kiểm tra BE/provider/mạng.
- `ai_*generation_ms` cao: FAQ đúng nguồn hoặc thử model/effort có kiểm chứng; vector dưới 1 ms không giải quyết generation hàng giây.
- TTS/poll cao: phối hợp BE, kiểm tra Retry-After/giọng/cache; giảm timeout không làm provider sinh nhanh.
- Phát dài nhưng chờ đầu câu thấp: rút câu hoặc tăng WPM vừa nghe rõ. Streaming audio chưa triển khai trong giao thức WAV/hash này.

Chưa có Pi nên chưa có tổng độ trễ phần cứng để cam kết. Timeout/trần thu/cấu hình không phải benchmark.
