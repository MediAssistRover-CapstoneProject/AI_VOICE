# Vision detection ingestion contract

`POST /api/v1/pi/vision/detections`, authenticated with the existing Device JWT.
The Python vision service uses the same device identity provider as Voice.

Request (camelCase):

```json
{
  "eventId": "41f5cbdb-78b4-4de3-8273-7766f00986e1",
  "robotId": "f2d604db-f205-47be-a5f6-4a96bddc20b2",
  "eventType": "SUSPECTED_FALL",
  "detectedAt": "2026-11-03T08:00:00Z",
  "algorithm": "lower_body_rules_v1",
  "confidence": null,
  "personId": 1,
  "sessionId": "55ab14bb-cf54-4a16-86ca-3ab924a6b693",
  "sampleTimeSec": 2.4,
  "metrics": {
    "detection_mode": "lower_body",
    "visible_legs": 1,
    "support_ratio": 0.2,
    "baseline_ready": true,
    "reason": "RAPID_SUPPORT_LOSS_AND_PERSISTENT_LEG_COLLAPSE"
  },
  "simulation": true
}
```

`simulation` must be explicit. A true value persists only `DetectionEvent`,
with no staff incident, Alert or AlertRecipient. A false value additionally
creates `EmergencyIncident` and invokes existing `AlertService` to create the
linked Alert and recipients, in the same transaction. Fall/seizure remain
WARNING; gesture SOS is CRITICAL. This endpoint does not send mobile push.

Successful response after the transaction commits:

```json
{
  "eventId": "41f5cbdb-78b4-4de3-8273-7766f00986e1",
  "accepted": true,
  "duplicate": false,
  "observationPersisted": true,
  "simulation": true,
  "incidentId": null,
  "alertId": null,
  "receivedAt": "2026-11-03T08:00:01Z"
}
```

Live responses carry incidentId and alertId. An identical retry returns the
original ids/time with duplicate=true. The UUID is global; changed robot or
content, including switching simulation to live, is a 409 conflict. Never reuse
a simulation UUID to create a live alert. Per-robot row locking serializes
concurrent retries; a cross-robot UUID race is stopped by the DetectionEvent
primary key and rolls back the entire losing transaction (409).

The robot must have a current map. No arbitrary map fallback or object location
is invented. Stored locationBasis is UNKNOWN, x_m/y_m stay null. Missing current
map produces 422 with message prefix VISION_MAP_UNAVAILABLE; retain the pending
event for operator configuration/retry. No eligible active staff/admin recipient
causes the live transaction to fail/roll back; it must not be reported delivered.

Validation: event UUID/robot UUID/timestamp/algorithm/metrics/simulation required;
robot identity, token version/code/expiry checked against the current robot.
eventType is SUSPECTED_FALL, GESTURE_SOS or SUSPECTED_SEIZURE. Confidence is
nullable, otherwise 0..1. personId positive if supplied. sampleTimeSec finite
and nonnegative. Timestamp cannot be more than five seconds in the future.

Metrics are scalar-only, bounded to 16 KiB, with strings up to 256 characters
and finite numeric magnitudes up to 1,000,000. Unknown keys are rejected rather
than silently discarded. Allowed keys:

```
torso_angle_deg hip_y_normalized hip_speed_normalized_per_sec
angular_speed_deg_per_sec aspect_ratio confidence phase detection_mode algorithm
confidence_kind reason support_ratio leg_angle_deg shin_angle_deg
min_shin_angle_deg knee_angle_deg visible_legs support_drop support_loss_per_sec
pose_angular_speed_deg_per_sec baseline_ready confirmation_elapsed_sec
observation_quality angle_deg hip_speed angular_speed_deg horizontal_duration_sec
```

baseline_ready is boolean; phase/detection_mode/algorithm/confidence_kind/reason/
observation_quality are strings; other non-null values are numbers. Null metric
values are preserved. Full request JSON is retained in DetectionEvent; lower-body
metrics are also copied into Alert.data_json for staff presentation.

Failure status follows existing API handlers: 400 for malformed/missing bound
fields, 401 for authentication failures, 403 for wrong device identity/current
session, 409 for identity conflicts, 422 for business validation/configuration.

Tests: `mvn -Dtest=VisionEventServiceTest,PiVisionControllerSecurityTest test`.
These tests use repository/service mocks and a real method-security proxy; no
database, MQTT broker or live staff notification is used. Real-DB transactional
behavior still requires the project's MySQL integration-test environment.
