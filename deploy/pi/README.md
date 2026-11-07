# Gói cập nhật độ trễ cho Pi native

Gói này đi kèm AI_VOICE để đồng đội có thể nhận đúng phần sửa của **RASPPI_MAR** khi nhánh Pi chưa được push. Không áp vào `mentorpi_ws/` và không tự deploy lên robot.

- Repo đích: `https://github.com/MediAssistRover-CapstoneProject/RASPPI_MAR`.
- Baseline: `e8d1ed6` (đầy đủ trong `manifest.json`).
- Bản vá: [RASPPI_MAR-e8d1ed6-voice-latency.patch](RASPPI_MAR-e8d1ed6-voice-latency.patch).
- Bao gồm runtime VAD/WAV/tốc độ/metrics, công cụ đo, test và cấu hình mẫu. Không chứa `.env`, JWT, secret thiết bị hoặc file worker/hardware đang vận hành.
- Đã thử `git apply --check`, apply trên baseline riêng và so sánh nội dung kết quả với các file Pi đã kiểm tra. Bộ test tương ứng: 31 đạt, 1 bỏ qua vì thiếu fixture BE. Chưa chạy phần cứng thật.

## Áp vào bản Pi phù hợp

Nếu nhánh Pi bạn nhận **đã có đủ những thay đổi này**, không áp lại. Repo Git RASPPI_MAR chứa thư mục `PI_MAR/`; các file runtime ở trong đó. Từ `PI_MAR/` (nơi có `pi/` và `tools/`), thay đường dẫn AI_VOICE bằng nơi clone thực tế. `git -C` bên dưới đảm bảo áp từ Git root, không nhầm cấp thư mục:

```sh
git status --short
git rev-parse HEAD
git -C "$(git rev-parse --show-toplevel)" apply --stat /DUONG_DAN/AI_VOICE/deploy/pi/RASPPI_MAR-e8d1ed6-voice-latency.patch
git -C "$(git rev-parse --show-toplevel)" apply --check /DUONG_DAN/AI_VOICE/deploy/pi/RASPPI_MAR-e8d1ed6-voice-latency.patch
```

Khi các file liên quan sạch, đúng baseline hoặc kiểm tra tương thích đạt và đã xem diff, áp một lần:

```sh
git -C "$(git rev-parse --show-toplevel)" apply /DUONG_DAN/AI_VOICE/deploy/pi/RASPPI_MAR-e8d1ed6-voice-latency.patch
git diff --stat
pi/.venv/bin/python -m pytest pi/tools/test_voice_latency.py pi/tools/test_speech.py pi/tools/test_cloud_speech.py -q
```

Nếu venv runtime không cài pytest, dùng môi trường kiểm thử riêng có cùng phụ thuộc, không tạo lại môi trường robot đang hoạt động chỉ để chạy test. Nếu check không đạt, dừng apply và hợp nhất theo từng file; không dùng reset/hard, force hoặc bỏ kiểm tra để ghi đè code của nhóm.

Giữ cấu hình private của robot, chỉ bổ sung các trường trong `pi/config/voice-ai.latency.example.json` sau đối chiếu. Vận hành theo [hướng dẫn Pi](../../docs/RASPBERRY_PI_VOICE_SETUP.md). Patch không cài ROS/ALSA, model, certificate, firmware hoặc MQTT worker.

Để kiểm tra file không bị đổi trong lúc chuyển, xem SHA-256 trong [manifest.json](manifest.json). Sau khi nhóm đã hợp nhất thay đổi vào repo Pi chính, ưu tiên dùng commit đã được review thay cho áp patch lặp lại.
