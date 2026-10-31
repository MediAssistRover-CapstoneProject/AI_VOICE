"""PiEnvelope builder – produces payloads compatible with BE_MAR's PiEnvelope<T> DTO."""
from __future__ import annotations

import uuid
from datetime import datetime, timezone
from typing import Any


def build_envelope(
    *,
    robot_id: str,
    message_type: str,
    payload: dict[str, Any],
    correlation_id: str | None = None,
    sequence_no: int = 1,
) -> dict[str, Any]:
    """Build a PiEnvelope JSON-serialisable dict matching the backend contract."""
    now = datetime.now(timezone.utc)
    msg_id = str(uuid.uuid4())
    return {
        "protocolVersion": "1.0",
        "messageId": msg_id,
        "operationKey": f"voice-op-{msg_id[:8]}",
        "correlationId": correlation_id or str(uuid.uuid4()),
        "robotId": robot_id,
        "messageType": message_type,
        "occurredAt": now.isoformat(),
        "sequenceNo": sequence_no,
        "payload": payload,
    }
