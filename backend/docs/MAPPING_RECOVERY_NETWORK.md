# Đóng job map, khôi phục Pi và đổi mạng

Ngày chuẩn bị: 24/10/2026. Các lệnh chia theo nơi chạy; không chạy `docker` ở trong container.

## 1. Địa chỉ đang dùng

| Thành phần | Địa chỉ |
| --- | --- |
| SSH Pi | `pi@192.168.137.230` |
| BE mà Pi gọi qua hotspot Windows | `https://192.168.137.1:8443` |
| Trang điều khiển trên laptop | `http://127.0.0.1:8080/mapping.html` |
| Swagger trên laptop | `http://127.0.0.1:8080/swagger-ui/index.html` |
| Camera Hiwonder trên Pi | `http://192.168.137.230:8080/stream?topic=/ascamera/camera_publisher/rgb0/image&type=mjpeg&width=640&height=480&quality=60` |

Cổng 8080 của laptop và cổng 8080 của Pi là hai máy khác nhau. Video cần camera/driver/server trên Pi đang chạy.

Job `d585634f-bc55-4d3a-9655-776534b8a115` đã được đóng `CANCELLED`, phiên BE `OPERATOR_RECONCILED`, lúc 14:03:01 ngày 24/10/2026 (+7). Không hủy lại job này. SAVE trước đó thất bại `MAP_UPLOAD_AUTH_REFRESH_FAILED`; STOP cleanup chưa được gửi. Giao dịch đóng đã giữ nguyên kết quả thực tế, bản đồ cục bộ và audit.

## 2. SSH và khởi động bình thường

**PowerShell trên laptop:**

```powershell
ssh pi@192.168.137.230
```

**HOST Pi, sau khi SSH:**

```sh
sudo docker start MentorPi
sudo docker exec -it -u ubuntu -w /home/ubuntu/mediassist-mqtt-2026-10-08 MentorPi zsh
```

**Trong container MentorPi:**

```sh
cd /home/ubuntu/mediassist-mqtt-2026-10-08
zsh tools/robot.zsh run
```

Giữ terminal này mở. Chờ `BE MQTT session ready` và BE thấy robot `IDLE`, `dispatchReady=true` trước khi tạo job. Chỉ chạy một stack/worker. Nếu VS Code đã attach vào MentorPi, mở terminal trong container rồi chạy từ `cd`, không chạy `docker exec` bên trong nữa.

## 3. Đóng map đúng khi vẫn còn hoạt động

Trên trang điều khiển: dừng tự chạy/đưa joystick về giữa, rồi **Lưu map** và đợi chuỗi `SAVE → COMMIT_MAP → STOP` hoàn tất. Bấm **Hủy job** chỉ hủy nhiệm vụ; không thay thế thao tác lưu.

Swagger: `POST /api/v1/jobs/{jobId}/cancel`, body:

```json
{"reason":"Dung phien quet de doi mang"}
```

Giữ Pi và BE chạy để Pi nhận STOP. Đợi job terminal và phiên map đóng. Nếu trả `Job is already terminal`, job đó đã kết thúc; kiểm tra job khác đang `RECOVERY_REQUIRED` thay vì hủy lại.

## 4. Khi job recovery hoặc mất mạng: đóng cục bộ trên Pi

Chỉ dùng khi không thể kết thúc bình thường. Đưa cần về giữa, bảo đảm robot đứng yên. Trong terminal `robot.zsh run`, bấm **Ctrl+C** và chờ toàn bộ `STOP ...` xong. Hoặc mở terminal **container** khác:

```sh
cd /home/ubuntu/mediassist-mqtt-2026-10-08
zsh tools/robot.zsh stop
```

Lệnh `stop` mới gửi yêu cầu dừng; cần đợi terminal chạy stack báo dừng xong. Kiểm tra:

```sh
zsh tools/robot.zsh inspect
pgrep -af 'robot_stack.py.*(run|bench)|mediassist.worker|slam_toolbox'
```

Chỉ khi worker, bộ tự chạy và SLAM cũ đã dừng, đóng journal mapping:

```sh
pi/.venv/bin/python tools/reconcile_mapping.py --config pi/config/mqtt.worker.json --robot-and-slam-stopped --reason 'Da dung robot va SLAM; dong phien map gian doan'
```

`--robot-and-slam-stopped` là xác nhận của người vận hành, không phải lệnh tự dừng motor/SLAM. Tool giữ map đã lưu và journal; không xóa `pi/state`, SQLite hoặc `session.json`.

Nếu BE còn yêu cầu biên nhận reconcile cho một **operation đã chạy**, lấy đúng `jobStepId` từ `GET /api/v1/jobs/{jobId}/operations/recovery`, rồi chạy (thay UUID bằng operation đã chạy của đúng job):

```sh
pi/.venv/bin/python tools/reconcile_mapping.py --config pi/config/mqtt.worker.json --robot-and-slam-stopped --job-step-id UUID_OPERATION_DA_CHAY --reason 'Da kiem tra robot dung va SLAM cu da dung'
```

Không truyền ID của cleanup STOP chưa từng được gửi: Pi không có operation đó trong journal. Nếu tool báo operation không khớp mapping journal hiện tại, không đổi ID đoán và không xóa journal; cần xem evidence của job cũ.

Sau đó khởi động lại:

```sh
zsh tools/robot.zsh run
```

Giữ cần giữa; chờ HELLO/heartbeat mới. Đóng cục bộ chỉ đóng map trên Pi, **chưa đóng job trên BE**. Tiếp tục mục 5.

## 5. API recovery trên Swagger

Đăng nhập bằng ADMIN/STAFF có quyền với robot/job. Không dùng DEVICE token của Pi làm token Swagger.

### A. Cleanup STOP chưa từng được gửi (trường hợp vừa sửa)

1. `GET /api/v1/jobs/{jobId}/mapping-recovery`.
2. Chỉ tiếp tục khi `selectedUnsentStop=true` và `canReconcile=true`. Dùng nguyên `jobStepId` và `transportSessionId` trong phản hồi mới nhất.
3. `POST /api/v1/jobs/{jobId}/mapping-recovery`, header `Idempotency-Key`: một UUID mới, body:

```json
{
  "jobStepId": "UUID_TU_GET",
  "transportSessionId": "UUID_TU_GET",
  "confirmedStopped": true,
  "outcome": "CONFIRM_CANCELLED",
  "note": "Da kiem tra robot dung; Pi da dong dung phien map"
}
```

`RESUME_AS_FAILED` là outcome tùy chọn để đóng thành FAILED, không khởi động lại nhiệm vụ. Nếu gửi lại do mất phản hồi HTTP, giữ cùng key và cùng body để nhận cùng receipt. Nếu nội dung thay đổi, dùng evidence mới và key mới.

BE kiểm tra heartbeat mới, odometry đứng yên, journal terminal, đúng job/session/artifact, biên nhận dừng của các operation trước, STOP chưa được publish và không có job khác giữ robot. API không tạo một biên nhận STOP giả. Thiếu bằng chứng trả 422 kèm blocker; không thể đóng chỉ vì có dòng `session ready`.

Trang HTML đã nối nút **Đóng job lỗi đã có bằng chứng dừng** vào API này. Sau khi đóng, đợi Pi kết nối HELLO mới và robot IDLE trước khi tạo job.

### B. Operation đã được gửi/chạy

Dùng API sẵn có `GET /api/v1/jobs/{jobId}/operations/recovery`, rồi `POST /api/v1/jobs/{jobId}/operations/{jobStepId}/reconcile` với receipt/command/operationKey đúng theo evidence. Header `Idempotency-Key` là một UUID mới. Body khi BE đã có receipt dừng của Pi:

```json
{
  "commandId": "UUID_COMMAND_TU_EVIDENCE",
  "operationKey": "OPERATION_KEY_TU_EVIDENCE",
  "transportSessionId": "UUID_TRANSPORT_TU_EVIDENCE",
  "evidence": "PI_STOP_CONFIRMED",
  "confirmedStopped": true,
  "outcome": "CONFIRM_CANCELLED",
  "note": "Dong operation gian doan sau khi da doi chieu bien nhan dung cua Pi"
}
```

Nếu dùng biên nhận kiểm tra tại robot do tool Pi gửi, chọn `STAFF_INSPECTED_STOP` thay vì `PI_STOP_CONFIRMED`; BE vẫn kiểm tra biên nhận thật. API mới ở mục A sẽ từ chối nếu STOP có khả năng đã được gửi. Không dùng SQL ép IDLE hoặc ghi `physicalStopConfirmed=true` thủ công.

## 6. Đổi mạng hoặc IP

### Chuẩn bị

Kết thúc job bình thường nếu có thể; sau đó Ctrl+C stack và chờ STOP xong. Giữ BE Docker chạy. Đổi Wi-Fi của Pi bằng cấu hình mạng trên Pi, rồi lấy IPv4 mới từ router/hotspot hoặc terminal HOST Pi:

```sh
hostname -I
ip -4 addr show wlan0
ip route
```

Trên PowerShell laptop, xem IP thực của từng card mạng:

```powershell
Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } | Format-Table InterfaceAlias,IPAddress,PrefixLength
```

Chọn IP BE trên đường mạng mà Pi truy cập được. Khi Pi dùng hotspot của laptop, thường là `192.168.137.1`; không lấy nhầm IP Wi-Fi upstream. IP SSH là địa chỉ Pi, không điền IP Pi vào URL BE.

### A. Mạng hotspot hiện tại: dùng HTTPS 8443

**PowerShell Run as administrator**, stack Pi đã dừng:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
$beIp = '192.168.137.1'
$piIp = '192.168.137.230'
$networkBundle = 'C:\Users\LENOVO\Downloads\MAR_Network_Setup_2026_10_23'
& "$networkBundle\Repair-MarHotspotHttps.ps1" -BeIp $beIp -PiIp $piIp -PublicPort 8443 -Apply
```

Nếu chỉ IP Pi thay đổi, sửa `$piIp` rồi chạy lại khối trên: script cập nhật SSH, firewall theo IP mới, copy CA công khai và kiểm tra từ Pi. Nó chỉ cập nhật URL upload/TTS sau khi HTTPS và DEVICE auth thành công.

Nếu **IP BE cũng thay đổi**, trước hết thêm IP mới vào SAN chứng chỉ, dùng cùng CA:

```powershell
& "$networkBundle\Setup-BeNetwork.ps1" -BeIp $beIp -PiIp $piIp
& "$networkBundle\Repair-MarHotspotHttps.ps1" -BeIp $beIp -PiIp $piIp -PublicPort 8443 -Apply
```

`Setup-BeNetwork.ps1` giữ SAN cũ, thêm IP mới, gia hạn server certificate bằng CA hiện có và reload nginx. Nó chưa đổi URL trên Pi; bước Repair sau đó xác nhận cổng 8443 và cập nhật URL. Không chạy wrapper Update-MarNetwork ngay sau để tránh ghi đè URL về cổng 443.

### B. Wi-Fi LAN khác, cổng 443 truy cập được

PowerShell Administrator, điền hai IP mới thực tế:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
$beIp = Read-Host 'IPv4 laptop BE tren mang Pi truy cap duoc'
$piIp = Read-Host 'IPv4 SSH moi cua Pi'
$networkBundle = 'C:\Users\LENOVO\Downloads\MAR_Network_Setup_2026_10_23'
& "$networkBundle\Update-MarNetwork.ps1" -BeIp $beIp -PiIp $piIp -Apply
```

Wrapper kiểm tra stack dừng, DNS broker/TLS, renew SAN server với CA cũ, firewall443, HTTPS từ Pi và DEVICE auth, rồi backup/đổi upload/TTS origin. Nếu TCP443 vẫn timeout, không coi là hoàn tất: dùng Setup + Repair8443 ở mục A với các IP mới. Không sửa JSON sang HTTP để né lỗi.

### DNS broker khi chuyển mạng

Nếu worker báo `gaierror`, đó là phân giải tên broker, khác lỗi Pi gọi HTTPS BE. Wrapper đổi mạng đã có DNS preflight. Nếu cần sửa riêng trên **HOST Pi** và script còn ở `/tmp`:

```sh
sudo python3 /tmp/repair_pi_container_dns.py --container MentorPi --apply
```

Tool đọc hostname broker từ config đang dùng, kiểm tra DNS host trước và backup DNS container trước khi sửa. Không tự thay broker bằng IP tĩnh. Sau khi `CONTAINER_DNS_REPAIRED`, worker đang chạy có thể tự retry; không mở worker thứ hai. Nếu `/tmp` đã bị dọn sau reboot, copy lại từ laptop trước:

```powershell
scp 'C:\Users\LENOVO\Downloads\MAR_Network_Setup_2026_10_23\repair_pi_container_dns.py' pi@192.168.137.230:/tmp/
```

### Kiểm tra lại rồi chạy

SSH tới IP mới, vào container như mục 2. Xem URL đang lưu (không in token/secret):

```sh
cd /home/ubuntu/mediassist-mqtt-2026-10-08
pi/.venv/bin/python -c 'import json;from pathlib import Path;c=json.loads(Path("pi/config/mqtt.worker.json").read_text(encoding="utf-8-sig"));print("UPLOAD_URL:",c["mapping"].get("upload_base_url"));print("TTS_URL:",c.get("speech",{}).get("synthesize_url"));print("CA:",c["mapping"].get("upload_ca_file"))'
zsh tools/robot.zsh run
```

Cần thấy script mạng đã báo `PI_TO_BE_HTTPS_OK`, `DEVICE_AUTH_OK`, `BE_ORIGIN_UPDATED`, sau đó worker `BE MQTT session ready`. Đổi IP không yêu cầu tạo token admin mới hay đổi robot/device secret: DEVICE token hết hạn sẽ được refresh qua origin mới. CA không đổi khi chỉ gia hạn server bằng CA cũ. URL browser camera đổi theo IP Pi mới.

## 7. File và dữ liệu cần giữ

- BE đang chạy: `C:\Users\LENOVO\Downloads\MAR_EMQX_Demo\BE_MAR`.
- HTML: `src/main/resources/static/mapping.html` của BE; chỉnh source cần build/recreate backend để container nhận.
- Pi: `/home/ubuntu/mediassist-mqtt-2026-10-08/pi/config/mqtt.worker.json`.
- Map/session Pi: đường `mapping.storage_dir` trong config (thường `pi/state/maps`); giữ `.pgm`, `.yaml`, `map.tar.gz`, manifest và journal.
- Job đã đóng trong lần này có bản local ở `pi/state/maps/37a2da3f-fac3-4919-9d6b-d4a5c9e1032a/88ae4e06-5955-41a6-a1d2-5ee34d5e689d/`. Đóng job không biến map chưa upload thành map đã có trên BE.

Bản sửa recovery lần này nằm trên **BE + HTML**; không cần vá runtime Pi để dùng API nếu Pi đã có `tools/reconcile_mapping.py` và cơ chế HELLO/journal hiện tại.
