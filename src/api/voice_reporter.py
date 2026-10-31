"""Voice interaction reporter – records interactions & commands to Backend."""
from __future__ import annotations

import logging
import uuid
from datetime import datetime, timezone
from typing import Any

from src.utils.envelope import build_envelope

log = logging.getLogger("mar_voice")


class VoiceReporter:
    """Manages the lifecycle of a single VoiceInteraction with the Backend."""

    def __init__(self, backend_client: Any, robot_id: str) -> None:
        self._client = backend_client
        self._robot_id = robot_id
        self._interaction_id: str | None = None
        self._sequence_no = 0

    @property
    def interaction_id(self) -> str | None:
        return self._interaction_id

    def start_interaction(self, language: str = "vi-VN") -> str:
        """Open a new VoiceInteraction record in the backend."""
        interaction_id = str(uuid.uuid4())
        envelope = build_envelope(
            robot_id=self._robot_id,
            message_type="VOICE_INTERACTION_STARTED",
            payload={
                "languageCode": language,
                "startedAt": datetime.now(timezone.utc).isoformat(),
            },
        )
        # Override messageId to serve as the interaction primary key.
        envelope["messageId"] = interaction_id
        try:
            self._client.post_voice_interaction_start(envelope)
            self._interaction_id = interaction_id
            self._sequence_no = 0
            log.info("[REPORTER] Interaction started: %s", interaction_id)
        except Exception as exc:
            log.warning("[REPORTER] Failed to start interaction: %s", exc)
            self._interaction_id = interaction_id  # proceed locally
        return interaction_id

    def record_command(
        self,
        raw_text: str,
        intent_code: str,
        command_type: str,
        confidence: float,
        parameters: dict | None = None,
    ) -> str | None:
        """Append a recognised VoiceCommand to the current interaction."""
        if self._interaction_id is None:
            return None
        self._sequence_no += 1
        command_id = str(uuid.uuid4())
        now = datetime.now(timezone.utc).isoformat()
        envelope = build_envelope(
            robot_id=self._robot_id,
            message_type="VOICE_COMMAND",
            payload={
                "voiceInteractionId": self._interaction_id,
                "sequenceNo": self._sequence_no,
                "rawText": raw_text,
                "normalizedText": raw_text.strip().lower(),
                "intentCode": intent_code,
                "confidenceScore": round(confidence, 4),
                "commandType": command_type,
                "parameters": parameters or {},
                "status": "RECEIVED",
                "receivedAt": now,
                "processedAt": None,
            },
        )
        envelope["messageId"] = command_id
        try:
            self._client.post_voice_command(envelope)
            log.info("[REPORTER] Command recorded: %s (%s)", intent_code, command_id[:8])
        except Exception as exc:
            log.warning("[REPORTER] Failed to record command: %s", exc)
        return command_id

    def complete_interaction(
        self,
        status: str = "COMPLETED",
        detected_intent: str | None = None,
        summary: str | None = None,
    ) -> None:
        """Close the current VoiceInteraction."""
        if self._interaction_id is None:
            return
        envelope = build_envelope(
            robot_id=self._robot_id,
            message_type="VOICE_INTERACTION_COMPLETED",
            payload={
                "status": status,
                "endedAt": datetime.now(timezone.utc).isoformat(),
                "detectedIntent": detected_intent,
                "transcriptSummary": summary,
                "audioUri": None,
            },
        )
        try:
            self._client.post_voice_interaction_complete(
                self._interaction_id, envelope
            )
            log.info("[REPORTER] Interaction completed: %s → %s", self._interaction_id[:8], status)
        except Exception as exc:
            log.warning("[REPORTER] Failed to complete interaction: %s", exc)
        finally:
            self._interaction_id = None
