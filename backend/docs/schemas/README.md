# Bộ contract tham chiếu Phase 0

Ngày baseline: 12/10/2026. Draft JSON Schema: **2020-12**.

**Nguồn:** [giao tiếp Markdown](../01_Giao_tiep_va_toan_bo_field.md), [input/output Markdown](../02_Input_Output_11_Step_Type.md) và [quyết định Phase 0](../phase0-contract-baseline.md). Schema Pi dựng từ tài liệu, **chưa đối chiếu schema gốc/code Pi**. Runtime đóng gói 8 schema trong [resources](../../src/main/resources/contracts/mar-step-2/): common, Pi input/output, BE contract, callback và ba schema MQTT. Phase 12 kiểm manifest bằng bundled common/artifact và receipt bằng DTO HTTP; schema mapping-artifact riêng phục vụ kiểm offline. Schema 2 giữ runtime cũ; schema 3 mở subset BE và action MQTT được cấu hình. UNLOCK/AI/SPEAK còn gate; MAPPING mở qua cấu hình opt-in và capability Pi, NOTIFY đã nối lưu Alert. Xem [Phase 1–2](../phase1-2-implementation.md), [Phase 3–4](../phase3-4-implementation.md) và [Phase 5–7](../phase5-7-implementation.md).

**Cập nhật Phase 8–9:** CANCEL_STEP đã nối runtime và NOTIFY schema 3 đã mở; BE NOTIFYInput thêm metadata tùy chọn `ack_policy`/`recipients`, chỉ chạy ở BE. Contract Pi không đổi. Fixture hiện 131 (88 positive/43 negative). Xem [Phase 8–9](../phase8-9-implementation.md) về scope/transaction, timeout, stop evidence và Alert ACK policy.

`$id` dùng domain `.invalid` làm identity, không phải địa chỉ tải schema. Mọi `$ref` được resolve từ bộ file local. Không phát sinh request mạng trong script kiểm chứng.

## Các file và cách dùng

| File | Dữ liệu được validate | Trạng thái |
|---|---|---|
| [common.schema.json](common.schema.json) | Thư viện UUID, timestamp, pose, endpoint, item, mapping session, artifact | Dựng từ handoff |
| [mqtt-command.schema.json](mqtt-command.schema.json) | Object COMMAND thật: EXECUTE_STEP hoặc CANCEL_STEP | Pi wire, chưa xác nhận gốc |
| [mqtt-be-response.schema.json](mqtt-be-response.schema.json) | Object HELLO_ACK, EVENT_ACK, SERVER_HEARTBEAT; không bọc payload | Pi wire, chưa xác nhận gốc |
| [mqtt-pi-message.schema.json](mqtt-pi-message.schema.json) | Event/heartbeat envelope, presence riêng, MAP_PREVIEW riêng | Pi wire, chưa xác nhận gốc |
| [pi-step-inputs.schema.json](pi-step-inputs.schema.json) | Wrapper tham chiếu `{stepType,params}`; `$defs` chứa params từng loại | Pi input, chưa xác nhận gốc |
| [pi-step-outputs.schema.json](pi-step-outputs.schema.json) | Wrapper tham chiếu `{stepType,result}`; `$defs` chứa successful output | Pi output, chưa xác nhận gốc |
| [be-step-contracts.json](be-step-contracts.json) | Wrapper tham chiếu `{stepType,input,output?}` cho bước BE | Thiết kế BE schema 3 |
| [staff-step-response.schema.json](staff-step-response.schema.json) | Body callback UI; Idempotency-Key nằm ở header | Validator/API BE Phase 4; UI ngoài repo chưa nghiệm thu |
| [mapping-artifact.schema.json](mapping-artifact.schema.json) | Manifest artifact hoặc receipt JSON upload | BE Phase 12, opt-in; Pi chưa nghiệm thu |

Các wrapper `stepType/params/result/input/output` của thư viện **không phải wire mới**. EXECUTE_STEP dùng `step.params`; output Pi nằm tại `STEP_RESULT.payload.result`. BE phải lấy StepType từ command/JobStep để chọn output schema; STEP_RESULT không tự có field StepType.

`be-step-contracts.json` cho phép thiếu output để kiểm input trước thực thi. Nếu có output, đó phải là kết quả đã hoàn tất; instruction đang chờ không được đưa vào như output thành công. Để kiểm riêng output, dùng `$defs/<StepType>Output` và kiểm tiếp theo resolved input.

## Giới hạn schema và kiểm tra runtime bắt buộc

- Bật format checker cho UUID/date-time; không dùng `format` chỉ như annotation. JSON parser phải từ chối NaN/Infinity và key trùng.
- Schema kiểm hình dạng và miền giá trị, không thay quyền staff, scope DB, deadline, session readiness, maxVisits hoặc transaction/dedup.
- Kiểm UUID map/endpoint/zone tồn tại và đúng robot/map; đối chiếu UUID/hash/profile Pi trước MOVE.
- Kiểm timeout với duration/window/profile START, snapshot STEP_STARTED với command và output với resolved input. Ví dụ schema có thể nhận timeout 5s/duration 10s riêng rẽ; runtime phải chặn quan hệ này.
- Với output sensing AI, cho phép fields mở rộng trong observation/metrics vì tài liệu nói worker trả nguyên dữ liệu producer. Phải kiểm version/source và giữ JSON gốc; không suy thêm mật độ/người/m²/té ngã.
- Header/footer frame preview không được kiểm giải nén bằng JSON Schema: Phase 12 kiểm Base64, zlib, width×height, max_cells và occupancy tại MappingPreviewService. `contentEncoding` chỉ mô tả cách mã hóa.
- CONTROL_HARDWARE output mô tả profile bàn giao ID4/1500/2400 với controller_ack/verified=false. Profile khác cần contract được xác nhận lại. Authorization STAFF_VERIFIED có shape dành sẵn, không chứng minh Pi đã kiểm quyền.
- STEP_ERROR do restart được phép thiếu result/physicalStopConfirmed; không được coi field thiếu là đã dừng. COMMAND_ACK replay result là snapshot journal khác output handler.
- Event STEP_RESULT chỉ kiểm envelope/payload shape ở đây. Handler Phase 7 phải chọn Pi output schema tương ứng và kiểm ngữ nghĩa trước ACK/chuyển bước.
- MappingSession cho phép null hoặc object rỗng/chưa đủ field theo handoff. Lifecycle quan hệ session/job/artifact phải kiểm runtime.

## Kiểm chứng offline

Chạy từ root repo bằng Python 3.12 (không thay dependency Java/runtime):

```powershell
python -m pip install --target target/phase0-python-deps -r docs/schemas/requirements-test.txt
$env:PYTHONPATH = (Resolve-Path target/phase0-python-deps).Path
python docs/schemas/verify_contracts.py
```

Dependency chỉ dành cho kiểm chứng schema. Script dùng [Draft202012Validator và FormatChecker](https://python-jsonschema.readthedocs.io/en/stable/validate/) cùng [Registry chứa tài nguyên local](https://python-jsonschema.readthedocs.io/en/stable/referencing/).

Fixture ở [contract-cases.json](fixtures/contract-cases.json) dùng UUID/timestamp **minh họa, không dùng để gửi robot**. Có input/output cho 11 StepType, sub-action sensing/mapping, message MQTT và callback; có negative cases chặn cả Job, DISPATCH, `$ref`, sai hình dạng, dữ liệu cũ và xác nhận giả.

Script kiểm schema bằng meta-schema, mọi local ref, duplicate key, format, coverage khớp enum StepType thực tế và fixture positive/negative; không kết nối broker/Pi hay xác nhận phần cứng thật.

Phần BE mapping/upload/STOP cleanup: [bàn giao Phase 12](../phase12-implementation.md). Trạng thái IMPLEMENTED_BE_SCHEMA_3_ARTIFACT chỉ chứng minh phần BE đã có code/test, không xác nhận SLAM/upload hoặc schema gốc Pi.

Phase 13 nối output CROWD_DENSITY/RESTRICTED_ZONE với monitoring và profile model/producer tại BE, gate AI mặc định tắt. Không đổi contract Pi hoặc thêm FALL_DETECTION. REST extension vị trí người/heatmap là contract BE riêng đang chờ producer ngoài repo, xem [bàn giao Phase 13](../phase13-implementation.md).
