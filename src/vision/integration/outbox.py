"""Durable SQLite queue; each attempt reuses the same event ID and body."""
from __future__ import annotations

from datetime import datetime, timezone
import json
from pathlib import Path
import sqlite3
import threading
import time
from typing import Any

from .payload import canonical_json


class VisionOutbox:
    def __init__(self, path: str | Path):
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        self._db = sqlite3.connect(str(path), timeout=5, check_same_thread=False)
        self._db.row_factory = sqlite3.Row
        with self._db:
            self._db.execute("PRAGMA journal_mode=WAL")
            self._db.execute("PRAGMA synchronous=FULL")
            self._db.execute("""CREATE TABLE IF NOT EXISTS vision_outbox (
                event_id TEXT PRIMARY KEY, draft_json TEXT NOT NULL,
                simulation INTEGER NOT NULL DEFAULT 1,
                payload_json TEXT, state TEXT NOT NULL DEFAULT 'PENDING',
                attempts INTEGER NOT NULL DEFAULT 0, next_attempt REAL NOT NULL DEFAULT 0,
                last_error TEXT, receipt_json TEXT, created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )""")
            # Compatibility with outboxes created before profile isolation.
            columns = {row[1] for row in self._db.execute("PRAGMA table_info(vision_outbox)")}
            if "simulation" not in columns:
                self._db.execute("ALTER TABLE vision_outbox ADD COLUMN simulation INTEGER NOT NULL DEFAULT 1")
                for row in self._db.execute("SELECT event_id,draft_json FROM vision_outbox").fetchall():
                    draft = json.loads(row[1])
                    self._db.execute("UPDATE vision_outbox SET simulation=? WHERE event_id=?",
                                     (int(draft.get("simulation", True) is True), row[0]))
            self._db.execute("CREATE INDEX IF NOT EXISTS ix_vision_outbox_due ON vision_outbox(state,next_attempt)")

    @staticmethod
    def _now() -> str:
        return datetime.now(timezone.utc).isoformat()

    def enqueue(self, draft: dict[str, Any]) -> bool:
        body = canonical_json(draft)
        with self._lock, self._db:
            existing = self._db.execute("SELECT draft_json FROM vision_outbox WHERE event_id=?", (draft["eventId"],)).fetchone()
            if existing:
                if existing[0] != body:
                    raise ValueError("eventId reused with different content")
                return False
            now = self._now()
            self._db.execute("INSERT INTO vision_outbox(event_id,draft_json,simulation,created_at,updated_at) VALUES (?,?,?,?,?)",
                             (draft["eventId"], body, int(draft["simulation"]), now, now))
            return True

    def due(self, now: float | None = None, *, simulation_only: bool = False) -> dict[str, Any] | None:
        with self._lock:
            row = self._db.execute("SELECT * FROM vision_outbox WHERE state='PENDING' AND next_attempt<=? AND (?=0 OR simulation=1) ORDER BY created_at,event_id LIMIT 1",
                                   (time.time() if now is None else now, int(simulation_only))).fetchone()
            return dict(row) if row else None

    def bind(self, event_id: str, payload: dict[str, Any]) -> dict[str, Any]:
        """The first authenticated robot identity is retained across restarts."""
        with self._lock, self._db:
            row = self._db.execute("SELECT payload_json FROM vision_outbox WHERE event_id=?", (event_id,)).fetchone()
            if row is None:
                raise KeyError(event_id)
            if row[0] is not None:
                return json.loads(row[0])
            self._db.execute("UPDATE vision_outbox SET payload_json=?,updated_at=? WHERE event_id=?",
                             (canonical_json(payload), self._now(), event_id))
            return payload

    def delivered(self, event_id: str, receipt: dict[str, Any]) -> None:
        with self._lock, self._db:
            self._db.execute("UPDATE vision_outbox SET state='DELIVERED',attempts=attempts+1,last_error=NULL,receipt_json=?,updated_at=? WHERE event_id=? AND state='PENDING'",
                             (canonical_json(receipt), self._now(), event_id))

    def failed(self, event_id: str, error: str, *, permanent: bool, delay: float) -> None:
        with self._lock, self._db:
            self._db.execute("UPDATE vision_outbox SET state=?,attempts=attempts+1,next_attempt=?,last_error=?,updated_at=? WHERE event_id=? AND state='PENDING'",
                             ("REJECTED" if permanent else "PENDING", time.time()+delay, error[:200], self._now(), event_id))

    def get(self, event_id: str) -> dict[str, Any] | None:
        with self._lock:
            row = self._db.execute("SELECT * FROM vision_outbox WHERE event_id=?", (event_id,)).fetchone()
            return dict(row) if row else None

    def counts(self, *, simulation_only: bool = False) -> dict[str, int]:
        with self._lock:
            result = {"pending": 0, "delivered": 0, "rejected": 0}
            for state, count in self._db.execute("SELECT state,COUNT(*) FROM vision_outbox WHERE (?=0 OR simulation=1) GROUP BY state", (int(simulation_only),)):
                result[state.lower()] = count
            return result

    def held_live(self) -> int:
        with self._lock:
            return self._db.execute("SELECT COUNT(*) FROM vision_outbox WHERE state='PENDING' AND simulation=0").fetchone()[0]

    def close(self) -> None:
        with self._lock:
            self._db.close()
