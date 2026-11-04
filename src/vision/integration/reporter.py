"""Asynchronous Device-JWT delivery using the same backend account as Voice."""
from __future__ import annotations

import json
import logging
import os
from pathlib import Path
import threading
from typing import Any, Callable

from .outbox import VisionOutbox
from .payload import bind_identity, normalize_event

log = logging.getLogger("mar_vision_delivery")
ENDPOINT = "/api/v1/pi/vision/detections"


def _bool(name: str, default: bool) -> bool:
    return os.getenv(name, str(default)).strip().lower() in {"1", "true", "yes", "on"}


class VisionReporter:
    """Compatible with the camera publisher interface, with durable REST ACKs.

    Disabled mode creates neither a backend client nor an outbox. Enabled mode
    persists observations before attempting authentication. Staff alerts remain
    controlled by the event's immutable simulation flag.
    """
    def __init__(self, *, enabled: bool | None = None, simulation: bool | None = None,
                 outbox_path: str | Path | None = None,
                 client_factory: Callable[[], Any] | None = None, start_worker: bool = True):
        self.enabled = _bool("VISION_DELIVERY_ENABLED", False) if enabled is None else enabled
        self.simulation = _bool("VISION_SIMULATION", True) if simulation is None else simulation
        self._client_factory = client_factory
        self._client = None
        self._stop = threading.Event()
        self._wake = threading.Event()
        self._thread = None
        self._last_error: str | None = None
        self._last_receipt: dict[str, Any] | None = None
        self._closed = False
        self._outbox = None
        if not self.enabled:
            return
        root = Path(__file__).resolve().parents[3]
        filename = "simulation-events.sqlite3" if self.simulation else "live-events.sqlite3"
        path = outbox_path or os.getenv("VISION_OUTBOX_PATH") or root / "data" / "vision" / "outbox" / filename
        self._outbox = VisionOutbox(path)
        if start_worker:
            self._thread = threading.Thread(target=self._worker, name="vision-rest-delivery", daemon=True)
            self._thread.start()

    def _get_client(self):
        if self._client is None:
            if self._client_factory:
                self._client = self._client_factory()
            else:
                from src.api import BackendClient
                robot_code = os.getenv("ROBOT_CODE", "").strip()
                device_secret = os.getenv("DEVICE_SECRET", "").strip()
                if not robot_code or not device_secret:
                    raise ValueError("Missing device credentials")
                self._client = BackendClient(os.getenv("BACKEND_URL", "http://localhost:8080"),
                                             robot_code, device_secret, timeout=3.0)
        return self._client

    def emit_event(self, event: dict[str, Any] | None) -> None:
        if not self.enabled or not event or self._closed:
            return
        try:
            draft = normalize_event(event, simulation=self.simulation)
            self._outbox.enqueue(draft)
            self._wake.set()
        except (ValueError, TypeError):
            self._last_error = "INVALID_LOCAL_EVENT"
            log.warning("Vision event was not queued: invalid or conflicting local event")

    def emit_status(self, snapshot: dict[str, Any]) -> None:
        """Frame telemetry stays local; it is not a persisted emergency event."""

    def process_once(self) -> bool:
        """Attempt one due event. Public for deterministic offline integration tests."""
        if not self.enabled or self._closed:
            return False
        # Even an explicitly shared path must not replay pending live events
        # when the user starts the default simulation profile.
        row = self._outbox.due(simulation_only=self.simulation)
        if row is None:
            return False
        event_id = row["event_id"]
        try:
            client = self._get_client()
            payload = json.loads(row["payload_json"]) if row["payload_json"] else self._outbox.bind(
                event_id, bind_identity(json.loads(row["draft_json"]), client.robot_id))
            response = client.post(ENDPOINT, json=payload)
            response.raise_for_status()
            receipt = response.json()
            if not (isinstance(receipt, dict) and receipt.get("accepted") is True
                    and receipt.get("observationPersisted") is True
                    and receipt.get("eventId") == event_id
                    and receipt.get("simulation") is payload["simulation"]):
                raise ValueError("Invalid backend persistence receipt")
            self._outbox.delivered(event_id, receipt)
            self._last_error = None
            self._last_receipt = {k: receipt.get(k) for k in
                                  ("eventId", "duplicate", "simulation", "incidentId", "alertId", "receivedAt")}
        except Exception as exc:
            response = getattr(exc, "response", None)
            status = getattr(response, "status_code", None)
            waiting_for_configuration = False
            if status == 422:
                try:
                    body = response.json()
                    waiting_for_configuration = (isinstance(body, dict)
                        and str(body.get("message", "")).startswith("VISION_MAP_UNAVAILABLE"))
                except Exception:
                    pass
            # Auth failures may recover after credentials/token rotation. Never
            # replay invalid payloads or conflicts as though they were transient.
            permanent = (status is not None and 400 <= status < 500
                         and status not in {401, 403, 408, 429} and not waiting_for_configuration)
            if status in {401, 403} and self._client is not None:
                # BackendClient acquires a fresh JWT on its next post.
                if hasattr(self._client, "_token"):
                    self._client._token = None
                    self._client._token_expires_at = 0.0
            code = "VISION_MAP_UNAVAILABLE" if waiting_for_configuration else (f"HTTP_{status}" if status is not None else type(exc).__name__)
            self._last_error = code
            delay = min(60., 2. ** min(row["attempts"], 6))
            self._outbox.failed(event_id, code, permanent=permanent, delay=delay)
            log.warning("Vision delivery %s: %s", "rejected" if permanent else "will retry", code)
        return True

    def _worker(self) -> None:
        try:
            while not self._stop.is_set():
                if not self.process_once():
                    self._wake.wait(0.5)
                    self._wake.clear()
        finally:
            self._cleanup()

    def status(self) -> dict[str, Any]:
        available = self._outbox is not None and not self._closed
        counts = self._outbox.counts(simulation_only=self.simulation) if available else {"pending": 0, "delivered": 0, "rejected": 0}
        return {"transport": "rest", "enabled": self.enabled, "simulation": self.simulation,
                **counts, "held_live": self._outbox.held_live() if available and self.simulation else 0,
                "last_error": self._last_error, "last_receipt": self._last_receipt}

    def _cleanup(self) -> None:
        self._closed = True
        if self._outbox is not None:
            self._outbox.close()
        if self._client is not None and hasattr(self._client, "close"):
            self._client.close()

    def close(self) -> None:
        if self._closed:
            return
        self._stop.set()
        self._wake.set()
        if self._thread is not None:
            self._thread.join(timeout=4.)
        else:
            self._cleanup()
        self._closed = True
