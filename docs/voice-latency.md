# Độ trễ Voice: đã giảm gì và đo thế nào?

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
