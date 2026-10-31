"""Backend HTTP client for MediAssistRover API.

Handles Device JWT token acquisition and auto-refresh, matching the
authentication contract of PiVoiceController and PiAuthController.
"""
from __future__ import annotations

import time
import logging
from typing import Any

import httpx

log = logging.getLogger("mar_voice")


class BackendClient:
    """Stateful HTTP client with automatic Device JWT management."""

    def __init__(
        self,
        base_url: str,
        robot_code: str,
        device_secret: str,
        timeout: float = 30.0,
    ) -> None:
        self._base = base_url.rstrip("/")
        self._robot_code = robot_code
        self._device_secret = device_secret
        self._timeout = timeout

        self._token: str | None = None
        self._robot_id: str | None = None
        self._token_expires_at: float = 0.0

        self._client = httpx.Client(
            base_url=self._base,
            timeout=httpx.Timeout(timeout),
            follow_redirects=False,
        )

    # ------------------------------------------------------------------
    # Token management
    # ------------------------------------------------------------------

    @property
    def robot_id(self) -> str:
        """Return the robot UUID obtained during token handshake."""
        if self._robot_id is None:
            self._acquire_token()
        return self._robot_id  # type: ignore[return-value]

    def _acquire_token(self) -> None:
        """POST /api/v1/pi/auth/token  →  Device JWT."""
        log.info("[AUTH] Acquiring device token for %s …", self._robot_code)
        resp = self._client.post(
            "/api/v1/pi/auth/token",
            json={
                "robotCode": self._robot_code,
                "deviceSecret": self._device_secret,
            },
        )
        resp.raise_for_status()
        body = resp.json()
        self._token = body["accessToken"]
        self._robot_id = body["robotId"]
        # Refresh 5 minutes before expiry (tokens last 60 min by default).
        self._token_expires_at = time.monotonic() + 3300
        log.info("[AUTH] Token acquired — robotId=%s", self._robot_id)

    def _ensure_token(self) -> str:
        if self._token is None or time.monotonic() >= self._token_expires_at:
            self._acquire_token()
        return self._token  # type: ignore[return-value]

    def _headers(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self._ensure_token()}"}

    # ------------------------------------------------------------------
    # Generic HTTP helpers
    # ------------------------------------------------------------------

    def get(self, path: str, **kwargs: Any) -> httpx.Response:
        resp = self._client.get(path, headers=self._headers(), **kwargs)
        resp.raise_for_status()
        return resp

    def post(self, path: str, **kwargs: Any) -> httpx.Response:
        resp = self._client.post(path, headers=self._headers(), **kwargs)
        resp.raise_for_status()
        return resp

    # ------------------------------------------------------------------
    # Domain helpers
    # ------------------------------------------------------------------

    def query_hospital_info(self, question: str, language: str = "vi-VN") -> dict:
        """Call HospitalInformationQueryService via the public step contract.

        Uses the DATA_SERVICE step type conceptually but invokes the REST
        endpoint that wraps the same keyword-matching logic.
        """
        resp = self.get(
            "/api/v1/hospital-information",
            params={"search": question, "page": 0, "size": 5},
        )
        return resp.json()

    def synthesize_speech(self, text: str, language: str = "vi", speed_wpm: int = 150) -> bytes | None:
        """POST /api/v1/pi/speech/synthesize  →  WAV bytes.

        The backend uses OpenAiTtsService (gpt-4o-mini-tts, voice=coral).
        Returns cached WAV immediately or polls until READY.
        """
        import time as _time

        for attempt in range(8):
            resp = self.post(
                "/api/v1/pi/speech/synthesize",
                json={"text": text, "languageCode": language, "speedWpm": speed_wpm},
            )
            body = resp.json()
            state = body.get("state", "")
            if state == "READY":
                audio_url = body.get("audioUrl")
                if audio_url:
                    audio_resp = self.get(audio_url)
                    return audio_resp.content
                audio_b64 = body.get("audio")
                if audio_b64:
                    import base64
                    return base64.b64decode(audio_b64)
                return None
            if state == "FAILED":
                log.warning("[TTS] Server synthesis failed: %s", body.get("code"))
                return None
            # PENDING – wait and retry
            _time.sleep(0.8 * (attempt + 1))
        log.warning("[TTS] Synthesis did not complete after retries")
        return None

    def post_voice_interaction_start(self, envelope: dict) -> dict:
        """POST /api/v1/pi/voice/interactions"""
        resp = self.post("/api/v1/pi/voice/interactions", json=envelope)
        return resp.json()

    def post_voice_command(self, envelope: dict) -> dict:
        """POST /api/v1/pi/voice/commands"""
        resp = self.post("/api/v1/pi/voice/commands", json=envelope)
        return resp.json()

    def post_voice_interaction_complete(self, interaction_id: str, envelope: dict) -> dict:
        """POST /api/v1/pi/voice/interactions/{id}/completion"""
        resp = self.post(f"/api/v1/pi/voice/interactions/{interaction_id}/completion", json=envelope)
        return resp.json()

    def get_rooms(self) -> list[dict]:
        """GET /api/v1/public/rooms — no auth required."""
        resp = self._client.get("/api/v1/public/rooms", params={"page": 0, "size": 50})
        resp.raise_for_status()
        data = resp.json()
        return data.get("content", data) if isinstance(data, dict) else data

    def request_escort(self, map_id: str, endpoint_id: str) -> dict:
        """POST /api/v1/pi/requests/escort"""
        resp = self.post(
            "/api/v1/pi/requests/escort",
            json={
                "mapId": map_id,
                "destinationEndpointId": endpoint_id,
                "targetMode": "ENDPOINT",
                "escortMode": "GUIDED",
                "walkingSpeedMps": 0.25,
                "confirmationTimeoutSec": 60,
                "priority": 50,
            },
        )
        return resp.json()

    def close(self) -> None:
        self._client.close()
