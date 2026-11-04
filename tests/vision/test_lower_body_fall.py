"""Behavioral regression tests. Synthetic poses are not real-world validation."""
import json
import math
from pathlib import Path
import tempfile
import unittest

from src.vision.tools.evaluate_video import score_events
from src.vision.core.fall_detector import FallDetector, MultiPersonFallTracker
from src.vision.core.fall_session import FallSession
from src.vision.core.lower_body_fall import visible_point
from src.vision.core.pose_adapter import yolo_to_mp_landmarks


def legs(angle=0, shin_angle=None, offset_x=0, offset_y=0, scale=1):
    """Two 160px legs, all upper-body joints deliberately absent."""
    points = [{"x": 0., "y": 0., "visibility": 0.} for _ in range(33)]
    shin_angle = angle if shin_angle is None else shin_angle
    for hip, knee, ankle, side in ((23, 25, 27, -1), (24, 26, 28, 1)):
        x, y = 200+side*15, 90
        coords = [(x, y)]
        for a in (angle, shin_angle):
            x += 80*math.sin(math.radians(a))
            y += 80*math.cos(math.radians(a))
            coords.append((x, y))
        for i, (x, y) in zip((hip, knee, ankle), coords):
            points[i] = {"x": (x*scale+offset_x)/640,
                         "y": (y*scale+offset_y)/480, "visibility": 0.95}
    return points


def fall_pose(t, offset_x=0):
    progress = max(0, min(1, (t-1)/0.4))
    return legs(80*progress, offset_y=140*progress, offset_x=offset_x)


def simulate(fn, seconds=4, mode="lower_body"):
    detector = FallDetector(mode=mode)
    samples = [detector.update(fn(i/20), 640, 480, i/20) for i in range(int(seconds*20))]
    return [s for s in samples if s[2]], [s[0] for s in samples]


class LowerBodyTests(unittest.TestCase):
    def test_fall_without_shoulders(self):
        events, states = simulate(fall_pose)
        self.assertEqual(len(events), 1)
        self.assertIn("CHECKING", states)
        self.assertEqual(events[0][1]["detection_mode"], "lower_body")

    def test_auto_falls_back_to_lower_body(self):
        self.assertEqual(len(simulate(fall_pose, mode="auto")[0]), 1)

    def test_missing_shoulders_rejected_in_explicit_full_body(self):
        event, states = simulate(fall_pose, mode="full_body")
        self.assertFalse(event)
        self.assertEqual(set(states), {"LOW_VISIBILITY"})

    def test_sitting_fast_with_vertical_shins(self):
        def sitting(t):
            return legs(min(90, max(0, (t-1)*225)), shin_angle=0)
        self.assertFalse(simulate(sitting)[0])

    def test_squatting_and_recovery(self):
        def squatting(t):
            progress = max(0, math.sin((t-1)*math.pi)) if 1 < t < 2 else 0
            return legs(100*progress, shin_angle=-30*progress)
        self.assertFalse(simulate(squatting)[0])

    def test_kneeling_with_vertical_shins(self):
        self.assertFalse(simulate(lambda t: legs(min(120, max(0, (t-1)*300)), shin_angle=0))[0])

    def test_slow_horizontal_transition(self):
        self.assertFalse(simulate(lambda t: legs(max(0, min(80, (t-1)*16))), seconds=8)[0])

    def test_already_horizontal_requires_baseline(self):
        events, states = simulate(lambda t: legs(80))
        self.assertFalse(events)
        self.assertEqual(set(states), {"INSUFFICIENT_EVIDENCE"})

    def test_uniform_camera_translation_not_fall(self):
        self.assertFalse(simulate(lambda t: legs(offset_y=70*math.sin(t*7)))[0])

    def test_uniform_zoom_not_fall(self):
        self.assertFalse(simulate(lambda t: legs(scale=1+0.15*math.sin(t*5)))[0])

    def test_missing_ankle_breaks_confirmation(self):
        def occluded(t):
            pose = fall_pose(t)
            if t >= 1.5:
                pose[27]["visibility"] = pose[28]["visibility"] = 0
            return pose
        events, states = simulate(occluded)
        self.assertFalse(events)
        self.assertIn("LOW_VISIBILITY", states)

    def test_single_complete_leg_can_detect_with_longer_confirmation(self):
        def one_leg(t):
            pose = fall_pose(t)
            pose[25]["visibility"] = pose[27]["visibility"] = 0
            return pose
        events, _ = simulate(one_leg)
        self.assertEqual(len(events), 1)
        self.assertEqual(events[0][1]["observation_quality"], "SINGLE_LEG")
        self.assertGreaterEqual(events[0][1]["confirmation_elapsed_sec"], 0.975)

    def test_alternating_visible_leg_does_not_create_motion(self):
        def alternating(t):
            pose = legs(20, shin_angle=-10)
            pose[27 if int(t*20) % 2 else 28]["visibility"] = 0
            return pose
        self.assertFalse(simulate(alternating)[0])

    def test_brief_missing_points_preserve_reference_but_not_confirmation(self):
        detector = FallDetector(mode="lower_body")
        for i in range(20):
            detector.update(legs(), 640, 480, i/20)
        missing = legs()
        missing[27]["visibility"] = missing[28]["visibility"] = 0
        detector.update(missing, 640, 480, 1.)
        state, metrics, _ = detector.update(legs(40), 640, 480, 1.1)
        self.assertTrue(metrics["baseline_ready"])
        self.assertEqual(metrics["support_loss_per_sec"], 0.)

    def test_one_bad_frame_cannot_create_fall(self):
        self.assertFalse(simulate(lambda t: legs(80) if 1.1 <= t < 1.15 else legs())[0])

    def test_recovery_before_confirmation(self):
        def recovery(t):
            return fall_pose(t) if t < 1.7 else legs()
        self.assertFalse(simulate(recovery)[0])

    def test_large_time_gap_no_synthetic_drop(self):
        detector = FallDetector(mode="lower_body")
        for i in range(20):
            detector.update(legs(), 640, 480, i/20)
        samples = [detector.update(legs(80), 640, 480, 5+i/20) for i in range(40)]
        self.assertFalse(any(s[2] for s in samples))

    def test_duplicate_timestamp_does_not_advance_confirmation(self):
        detector = FallDetector(mode="lower_body")
        detector.update(legs(), 640, 480, 1.)
        state, metrics, triggered = detector.update(legs(80), 640, 480, 1.)
        self.assertEqual(metrics["reason"], "NON_INCREASING_TIMESTAMP")
        self.assertFalse(triggered)

    def test_out_of_frame_nan_and_zero_visibility_rejected(self):
        for key, value in (("x", 0.), ("y", 1.1), ("x", float("nan")), ("visibility", 0.)):
            pose = legs()
            pose[23][key] = value
            self.assertIsNone(visible_point(pose, 23))

    def test_no_pose_resets_tracking_motion(self):
        tracker = MultiPersonFallTracker(mode="lower_body")
        for i in range(20):
            tracker.update([legs()], 640, 480, i/20)
        tracker.update([], 640, 480, 1.0)
        results, _, _, _ = tracker.update([legs(80)], 640, 480, 1.1)
        self.assertEqual(results[0]["state"], "INSUFFICIENT_EVIDENCE")

    def test_expired_track_gets_new_id(self):
        tracker = MultiPersonFallTracker(mode="lower_body")
        first = tracker.update([legs()], 640, 480, 0.)[0][0]["person_id"]
        second = tracker.update([legs()], 640, 480, 3.)[0][0]["person_id"]
        self.assertNotEqual(first, second)

    def test_missing_hips_not_tracked_at_origin(self):
        tracker = MultiPersonFallTracker(mode="lower_body")
        pose = legs()
        pose[23]["visibility"] = 0
        results, state, triggered, _ = tracker.update([pose], 640, 480, 0.)
        self.assertEqual(state, "LOW_VISIBILITY")
        self.assertFalse(results)
        self.assertFalse(tracker.tracks)

    def test_two_simultaneous_falls_both_logged(self):
        session = FallSession(mode="lower_body")
        events = []
        for i in range(80):
            t = i/20
            events.extend(session.update([fall_pose(t), fall_pose(t, 200)], 640, 480, t)["events"])
        self.assertEqual(len(events), 2)
        self.assertEqual(len({e["person_id"] for e in events}), 2)

    def test_long_lying_does_not_retrigger_after_cooldown(self):
        self.assertEqual(len(simulate(fall_pose, seconds=28)[0]), 1)

    def test_adapter_missing_confidence_not_reliable(self):
        points = yolo_to_mp_landmarks([[100, 100]]*17, None, 640, 480)
        self.assertIsNone(visible_point(points, 23))

    def test_jsonl_records_observations_and_no_images(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/"session.jsonl"
            session = FallSession(mode="lower_body", log_path=path)
            session.update([legs()], 640, 480, 0.)
            session.close()
            record = json.loads(path.read_text(encoding="utf-8"))
            self.assertIn("23", record["observed_poses"][0])
            self.assertEqual(record["results"][0]["state"], "CALIBRATING_LOWER_BODY")
            self.assertNotIn("image", record)

    def test_event_scoring_one_to_one(self):
        events = [{"sample_time_sec": t} for t in (2.0, 2.1, 8.0)]
        score = score_events(events, [{"start_sec": 1., "end_sec": 2.}], 10.)
        self.assertEqual(score["detected_falls"], 1)
        self.assertEqual(score["unmatched_alerts"], 2)


if __name__ == "__main__":
    unittest.main()
