"""Validated vision REST contract. Never sends frames or raw keypoints."""
from __future__ import annotations

from datetime import datetime, timezone
import json
import math
import uuid
from typing import Any

METRIC_KEYS = frozenset({
    "torso_angle_deg", "hip_y_normalized", "hip_speed_normalized_per_sec",
    "angular_speed_deg_per_sec", "aspect_ratio", "confidence", "phase",
    "detection_mode", "algorithm", "confidence_kind", "reason", "support_ratio",
    "leg_angle_deg", "shin_angle_deg", "min_shin_angle_deg", "knee_angle_deg",
    "visible_legs", "support_drop", "support_loss_per_sec",
    "pose_angular_speed_deg_per_sec", "baseline_ready", "confirmation_elapsed_sec",
    "observation_quality", "angle_deg", "hip_speed", "angular_speed_deg",
    "horizontal_duration_sec",
})
EVENT_TYPES = frozenset({"SUSPECTED_FALL", "GESTURE_SOS", "SUSPECTED_SEIZURE"})
TEXT_METRIC_KEYS = frozenset({"phase", "detection_mode", "algorithm", "confidence_kind", "reason", "observation_quality"})


def canonical_json(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False)


def _uuid(value: Any, field: str) -> str:
    try:
        return str(uuid.UUID(str(value)))
    except (ValueError, TypeError, AttributeError) as exc:
        raise ValueError(f"{field} must be a UUID") from exc


def _number(value: Any, field: str, *, low: float = -1e6, high: float = 1e6) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"{field} must be a finite number")
    if not math.isfinite(value) or not low <= value <= high:
        raise ValueError(f"{field} is outside its allowed range")
    return value


def normalize_event(event: dict[str, Any], *, simulation: bool) -> dict[str, Any]:
    """Build the immutable event draft; robot identity is bound by auth later.

    Configured simulation mode always wins. An event explicitly tagged as a
    simulation also remains simulated even in a live reporter configuration.
    """
    if not isinstance(event, dict):
        raise ValueError("Event must be an object")
    if not isinstance(simulation, bool) or ("simulation" in event and not isinstance(event["simulation"], bool)):
        raise ValueError("simulation must be a boolean")
    event_id = _uuid(event.get("eventId") or event.get("event_id") or event.get("id"), "eventId")
    event_type = event.get("eventType") or event.get("event_type") or event.get("type")
    event_type = {"HAND_SOS": "GESTURE_SOS", "SOS_SIGNAL": "GESTURE_SOS"}.get(event_type, event_type)
    if event_type not in EVENT_TYPES:
        raise ValueError("Unsupported vision event type")
    observed = event.get("detectedAt") or event.get("detected_at") or event.get("time")
    try:
        detected_at = datetime.fromisoformat(str(observed).replace("Z", "+00:00"))
    except (TypeError, ValueError) as exc:
        raise ValueError("detectedAt must be an ISO timestamp") from exc
    if detected_at.tzinfo is None:
        raise ValueError("detectedAt must contain a timezone")
    algorithm = event.get("algorithm", "unspecified")
    if not isinstance(algorithm, str) or not algorithm.strip() or len(algorithm) > 100:
        raise ValueError("algorithm must be 1..100 characters")
    confidence = event.get("confidence")
    if confidence is not None:
        _number(confidence, "confidence", low=0, high=1)
    person = event.get("personId", event.get("person_id"))
    if person is not None and (isinstance(person, bool) or not isinstance(person, int) or not 1 <= person <= 2147483647):
        raise ValueError("personId must be a positive integer")
    session = event.get("sessionId") or event.get("session_id")
    sample_time = event.get("sampleTimeSec", event.get("sample_time_sec"))
    if sample_time is not None:
        _number(sample_time, "sampleTimeSec", low=0, high=1e12)
    raw_metrics = event.get("metrics", {})
    if not isinstance(raw_metrics, dict):
        raise ValueError("metrics must be an object")
    metrics = {}
    for key, value in raw_metrics.items():
        if key not in METRIC_KEYS:
            continue  # Privacy boundary: never send landmarks/images/arbitrary objects.
        if value is None:
            metrics[key] = value
        elif key in TEXT_METRIC_KEYS:
            if not isinstance(value, str) or len(value) > 256:
                raise ValueError(f"Invalid text metric: {key}")
            metrics[key] = value
        elif key == "baseline_ready":
            if not isinstance(value, bool):
                raise ValueError("baseline_ready must be a boolean")
            metrics[key] = value
        else:
            metrics[key] = _number(value, f"metrics.{key}", low=0 if key == "confidence" else -1e6,
                                   high=1 if key == "confidence" else 1e6)
    if len(canonical_json(metrics).encode("utf-8")) > 16384:
        raise ValueError("metrics exceeds 16KiB")
    return {
        "eventId": event_id, "eventType": event_type,
        "detectedAt": detected_at.astimezone(timezone.utc).isoformat(),
        "algorithm": algorithm, "confidence": confidence,
        "simulation": simulation or event.get("simulation", False),
        "personId": person, "sessionId": _uuid(session, "sessionId") if session else None,
        "sampleTimeSec": sample_time, "metrics": metrics,
    }


def bind_identity(draft: dict[str, Any], robot_id: str) -> dict[str, Any]:
    return {**draft, "robotId": _uuid(robot_id, "robotId")}
