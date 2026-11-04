"""Shared local/live processing and JSONL diagnostics; no backend dependency."""
import json
import uuid
from datetime import datetime, timezone
from pathlib import Path

from .fall_detector import MultiPersonFallTracker


class FallSession:
    def __init__(self, mode=None, log_path=None):
        self.tracker = MultiPersonFallTracker(mode=mode)
        self.log = None
        if log_path:
            path = Path(log_path)
            path.parent.mkdir(parents=True, exist_ok=True)
            self.log = path.open("a", encoding="utf-8")
        self.session_id = str(uuid.uuid4())

    def update(self, poses, width, height, sample_time):
        results, state, _, _ = self.tracker.update(poses, width, height, sample_time)
        events = []
        for result in results:
            if not result["triggered"]:
                continue
            metrics = result["metrics"]
            event = {
                "id": str(uuid.uuid4()), "type": "SUSPECTED_FALL",
                "session_id": self.session_id, "person_id": result["person_id"],
                "time": datetime.now(timezone.utc).isoformat(),
                "sample_time_sec": sample_time,
                "confidence": metrics.get("confidence"),
                "algorithm": metrics.get("algorithm"),
                "metrics": metrics,
                "event_text_vi": f"Nguoi #{result['person_id']}: nghi te nga; can xac minh ({metrics.get('detection_mode')}).",
            }
            events.append(event)
        snapshot = {"sample_time_sec": sample_time, "fall_state": state,
                    "results": results, "events": events}
        if self.log:
            joints = (11, 12, 23, 24, 25, 26, 27, 28)
            def serialize(p):
                return ({k: p.get(k) for k in ("x", "y", "visibility")} if isinstance(p, dict)
                        else {k: getattr(p, k, None) for k in ("x", "y", "visibility")})
            self.log.write(json.dumps({"session_id": self.session_id, "width": width,
                                       "height": height, **snapshot,
                                       "observed_poses": [{str(i): serialize(p[i]) for i in joints if i < len(p)} for p in poses]},
                                      ensure_ascii=False, allow_nan=False)+"\n")
            self.log.flush()
        return snapshot

    def close(self):
        if self.log:
            self.log.close()
