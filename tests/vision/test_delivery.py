"""Offline contract/outbox tests. All backend calls use fakes."""
from __future__ import annotations

import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

from src.vision.integration.outbox import VisionOutbox
from src.vision.integration.payload import bind_identity, normalize_event
from src.vision.integration.reporter import ENDPOINT, VisionReporter

EVENT_ID = "98a2c9ba-89d7-4e8b-84a6-0c3642c02c5a"
ROBOT_ID = "d751e52e-f00b-44aa-88cc-e9984249f652"
SESSION_ID = "0a118aac-c4ec-4007-bb0f-84fa35e6ec36"


def event():
    return {"id": EVENT_ID, "type": "SUSPECTED_FALL", "time": "2026-11-03T08:00:00+00:00",
            "algorithm": "lower_body_rules_v1", "confidence": None, "person_id": 3,
            "session_id": SESSION_ID, "sample_time_sec": 2.4,
            "metrics": {"support_ratio": .12, "support_drop": .7, "visible_legs": 1,
                        "baseline_ready": True, "detection_mode": "lower_body",
                        "confidence": None, "reason": "RAPID_SUPPORT_LOSS_AND_PERSISTENT_LEG_COLLAPSE"}}


class Response:
    def __init__(self, body=None, status_code=200):
        self.status_code = status_code
        self.body = body

    def raise_for_status(self):
        if self.status_code >= 400:
            error = RuntimeError("Do not expose response content containing possible secrets")
            error.response = self
            raise error

    def json(self):
        return self.body


class Client:
    def __init__(self, statuses=None, bad_receipt=False):
        self.robot_id = ROBOT_ID
        self.statuses = list(statuses or [])
        self.bad_receipt = bad_receipt
        self.posts = []
        self.closed = False
        self._token = "test-token"

    def post(self, path, *, json):
        self.posts.append((path, copy.deepcopy(json)))
        status = self.statuses.pop(0) if self.statuses else 200
        body = {"eventId": json["eventId"], "accepted": True, "duplicate": len(self.posts) > 1,
                "observationPersisted": True, "simulation": json["simulation"],
                "incidentId": None if json["simulation"] else "incident-1",
                "alertId": None if json["simulation"] else "alert-1",
                "receivedAt": "2026-11-03T08:00:01Z"}
        if self.bad_receipt:
            body["observationPersisted"] = False
        return Response(body, status)

    def close(self):
        self.closed = True


class DeliveryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "outbox.sqlite3"

    def reporter(self, client=None, simulation=True):
        client = client or Client()
        result = VisionReporter(enabled=True, simulation=simulation, outbox_path=self.path,
                                client_factory=lambda: client, start_worker=False)
        self.addCleanup(result.close)
        return result, client

    def test_disabled_never_creates_backend_client_or_outbox(self):
        factory = Mock(side_effect=AssertionError("Network client created in local mode"))
        reporter = VisionReporter(enabled=False, outbox_path=self.path, client_factory=factory)
        reporter.emit_event(event())
        self.assertFalse(reporter.process_once())
        self.assertFalse(self.path.exists())
        self.assertEqual(reporter.status()["pending"], 0)
        factory.assert_not_called()
        reporter.close()

    def test_lower_body_metrics_and_nullable_confidence_survive_normalization(self):
        draft = normalize_event(event(), simulation=True)
        self.assertEqual(draft["metrics"], event()["metrics"])
        self.assertIsNone(draft["confidence"])
        self.assertEqual(draft["sessionId"], SESSION_ID)
        self.assertEqual(draft["sampleTimeSec"], 2.4)
        self.assertNotIn("robotId", draft)
        self.assertEqual(bind_identity(draft, ROBOT_ID)["robotId"], ROBOT_ID)

    def test_raw_images_and_keypoints_are_not_sent(self):
        source = event()
        source["metrics"].update(frame="private-image", landmarks=[1, 2, 3])
        draft = normalize_event(source, simulation=True)
        self.assertNotIn("frame", draft["metrics"])
        self.assertNotIn("landmarks", draft["metrics"])

    def test_invalid_metrics_and_ids_rejected_before_enqueue(self):
        for mutate in (lambda e: e.update(id="invalid"),
                       lambda e: e["metrics"].update(support_ratio=float("nan")),
                       lambda e: e["metrics"].update(reason="x"*257),
                       lambda e: e["metrics"].update(support_ratio=True),
                       lambda e: e["metrics"].update(baseline_ready=1),
                       lambda e: e["metrics"].update(reason=5),
                       lambda e: e.update(confidence=1.1),
                       lambda e: e.update(time="2026-11-03T08:00:00")):
            source = event()
            mutate(source)
            with self.assertRaises(ValueError):
                normalize_event(source, simulation=True)

    def test_simulation_cannot_be_downgraded_by_event(self):
        source = event()
        source["simulation"] = False
        self.assertTrue(normalize_event(source, simulation=True)["simulation"])
        source["simulation"] = True
        self.assertTrue(normalize_event(source, simulation=False)["simulation"])
        source.pop("simulation")
        self.assertFalse(normalize_event(source, simulation=False)["simulation"])

    def test_enqueue_is_durable_before_auth(self):
        factory = Mock(side_effect=AssertionError("Should not authenticate during emit"))
        reporter = VisionReporter(enabled=True, simulation=True, outbox_path=self.path,
                                  client_factory=factory, start_worker=False)
        reporter.emit_event(event())
        factory.assert_not_called()
        self.assertEqual(reporter.status()["pending"], 1)
        reporter.close()
        reopened = VisionOutbox(self.path)
        self.assertEqual(json.loads(reopened.get(EVENT_ID)["draft_json"])["eventId"], EVENT_ID)
        reopened.close()

    def test_duplicate_same_event_does_not_send_twice(self):
        reporter, client = self.reporter()
        reporter.emit_event(event())
        reporter.emit_event(event())
        reporter.process_once()
        reporter.emit_event(event())
        self.assertFalse(reporter.process_once())
        self.assertEqual(len(client.posts), 1)
        self.assertEqual(reporter.status()["delivered"], 1)

    def test_conflicting_event_id_does_not_replace_original(self):
        outbox = VisionOutbox(self.path)
        draft = normalize_event(event(), simulation=True)
        outbox.enqueue(draft)
        with self.assertRaisesRegex(ValueError, "different content"):
            outbox.enqueue({**draft, "algorithm": "changed"})
        self.assertEqual(json.loads(outbox.get(EVENT_ID)["draft_json"])["algorithm"], "lower_body_rules_v1")
        outbox.close()

    def test_success_requires_matching_persistence_receipt(self):
        reporter, client = self.reporter(Client(bad_receipt=True))
        reporter.emit_event(event())
        reporter.process_once()
        self.assertEqual(reporter.status()["delivered"], 0)
        self.assertEqual(reporter.status()["pending"], 1)
        self.assertEqual(reporter.status()["last_error"], "ValueError")

    def test_live_success_retains_incident_and_alert_receipts(self):
        reporter, client = self.reporter(simulation=False)
        reporter.emit_event(event())
        reporter.process_once()
        self.assertEqual(client.posts[0][0], ENDPOINT)
        self.assertEqual(client.posts[0][1]["robotId"], ROBOT_ID)
        self.assertFalse(client.posts[0][1]["simulation"])
        self.assertEqual(reporter.status()["last_receipt"]["alertId"], "alert-1")

    def test_transient_failure_retries_identical_bound_payload_after_restart(self):
        client = Client(statuses=[503])
        reporter, _ = self.reporter(client)
        reporter.emit_event(event())
        with patch("src.vision.integration.outbox.time.time", return_value=1000):
            reporter.process_once()
        first = copy.deepcopy(client.posts[0][1])
        reporter.close()
        next_client = Client()
        next_client.robot_id = "94cba8ea-874c-46c2-85f2-405b3360275e"
        resumed, _ = self.reporter(next_client)
        with patch("src.vision.integration.outbox.time.time", return_value=1002):
            resumed.process_once()
        self.assertEqual(next_client.posts[0][1], first)
        self.assertEqual(resumed.status()["delivered"], 1)

    def test_permanent_validation_failure_is_retained_for_review(self):
        reporter, client = self.reporter(Client(statuses=[400]))
        reporter.emit_event(event())
        reporter.process_once()
        self.assertEqual(reporter.status()["rejected"], 1)
        self.assertEqual(reporter.status()["last_error"], "HTTP_400")
        self.assertFalse(reporter.process_once())

    def test_auth_failure_is_retryable_and_invalidates_token(self):
        reporter, client = self.reporter(Client(statuses=[401]))
        reporter.emit_event(event())
        reporter.process_once()
        self.assertEqual(reporter.status()["pending"], 1)
        self.assertIsNone(client._token)
        self.assertEqual(reporter.status()["last_error"], "HTTP_401")

    def test_missing_robot_map_waits_for_configuration_instead_of_discarding(self):
        client = Client()
        client.post = Mock(return_value=Response({"message": "VISION_MAP_UNAVAILABLE: configure robot current map"}, 422))
        reporter, _ = self.reporter(client)
        reporter.emit_event(event())
        reporter.process_once()
        self.assertEqual(reporter.status()["pending"], 1)
        self.assertEqual(reporter.status()["rejected"], 0)
        self.assertEqual(reporter.status()["last_error"], "VISION_MAP_UNAVAILABLE")

    def test_simulation_profile_never_drains_existing_live_events_even_shared_path(self):
        live, _ = self.reporter(simulation=False)
        live.emit_event(event())
        live.close()
        simulated, client = self.reporter(simulation=True)
        self.assertFalse(simulated.process_once())
        self.assertEqual(client.posts, [])
        self.assertEqual(simulated.status()["pending"], 0)
        self.assertEqual(simulated.status()["held_live"], 1)

    def test_live_profile_preserves_source_simulation_events(self):
        live, client = self.reporter(simulation=False)
        simulated_event = event()
        simulated_event["simulation"] = True
        live.emit_event(simulated_event)
        live.process_once()
        self.assertTrue(client.posts[0][1]["simulation"])
        self.assertEqual(live.status()["delivered"], 1)

    def test_legacy_outbox_migration_does_not_reclassify_live_payloads(self):
        import sqlite3
        draft = normalize_event(event(), simulation=False)
        db = sqlite3.connect(self.path)
        db.execute("CREATE TABLE vision_outbox(event_id TEXT PRIMARY KEY,draft_json TEXT,payload_json TEXT,state TEXT,attempts INTEGER,next_attempt REAL,last_error TEXT,receipt_json TEXT,created_at TEXT,updated_at TEXT)")
        db.execute("INSERT INTO vision_outbox VALUES(?,?,NULL,'PENDING',0,0,NULL,NULL,'now','now')", (EVENT_ID, json.dumps(draft)))
        db.commit()
        db.close()
        outbox = VisionOutbox(self.path)
        self.assertIsNone(outbox.due(simulation_only=True))
        self.assertEqual(outbox.held_live(), 1)
        outbox.close()


if __name__ == "__main__":
    unittest.main()
