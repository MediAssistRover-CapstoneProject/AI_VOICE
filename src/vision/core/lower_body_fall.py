"""Experimental lower-body fall rules, not a trained or calibrated classifier.

Coordinates use the existing MediaPipe indexing (also supplied by the YOLO
adapter). Relative leg geometry rejects uniform image translation/scale;
it does NOT compensate perspective changes, camera rotation or parallax.
"""
import math
import os
from collections import deque


def visible_point(points, index, threshold=0.5):
    """Never use an off-image, missing, nonfinite or zero-confidence joint."""
    if index >= len(points) or points[index] is None:
        return None
    p = points[index]
    def value(key, default):
        return p.get(key, default) if isinstance(p, dict) else getattr(p, key, default)
    try:
        x, y, visibility = (float(value(k, d)) for k, d in
                            (("x", float("nan")), ("y", float("nan")), ("visibility", 0.0)))
    except (ValueError, TypeError):
        return None
    if not all(math.isfinite(v) for v in (x, y, visibility)):
        return None
    if visibility < threshold or not (0 < x < 1 and 0 < y < 1):
        return None
    return x, y


def _angle_vertical(a, b):
    return math.degrees(math.atan2(abs(a[0] - b[0]), max(abs(a[1] - b[1]), 1e-6)))


def lower_body_features(points, width, height, visibility=0.5):
    indices = (23, 24, 25, 26, 27, 28)
    joints = {i: visible_point(points, i, visibility) for i in indices}
    missing = [i for i, p in joints.items() if p is None]
    # Both hips anchor tracking. One complete observed leg is sufficient;
    # never fill a missing knee/ankle using the other leg or model guesses.
    sides = [("left", 23, 25, 27), ("right", 24, 26, 28)]
    sides = [s for s in sides if all(joints[i] is not None for i in s[1:])]
    if joints[23] is None or joints[24] is None or not sides:
        return None, "MISSING_LOWER_JOINTS:" + ",".join(map(str, missing))
    pixel = {i: (p[0] * width, p[1] * height) for i, p in joints.items() if p is not None}
    lengths, support, shin_angles, leg_angles, knees = [], [], [], [], []
    per_leg = {}
    for side, hip, knee, ankle in sides:
        h, k, a = pixel[hip], pixel[knee], pixel[ankle]
        thigh, shin = math.dist(h, k), math.dist(k, a)
        if min(thigh, shin) < max(4.0, height * 0.015):
            return None, "DEGENERATE_LEG_GEOMETRY"
        length = thigh + shin
        lengths.append(length)
        support.append((a[1] - h[1]) / length)
        shin_angles.append(_angle_vertical(k, a))
        leg_angles.append(_angle_vertical(h, a))
        per_leg[side] = {"support_ratio": support[-1], "leg_angle_deg": leg_angles[-1]}
        cosine = ((h[0]-k[0])*(a[0]-k[0]) + (h[1]-k[1])*(a[1]-k[1])) / (thigh*shin)
        knees.append(math.degrees(math.acos(max(-1., min(1., cosine)))))
    # Reject severe one-leg localization errors rather than inventing motion.
    if max(lengths) / min(lengths) > 2.0:
        return None, "INCONSISTENT_LEG_LENGTHS"
    xs, ys = zip(*pixel.values())
    return {
        "support_ratio": sum(support) / len(sides),
        "leg_angle_deg": sum(leg_angles) / len(sides),
        "shin_angle_deg": sum(shin_angles) / len(sides),
        "min_shin_angle_deg": min(shin_angles),
        "knee_angle_deg": sum(knees) / len(sides),
        "aspect_ratio": (max(xs)-min(xs)) / max(max(ys)-min(ys), 1.),
        "hip_y_normalized": (joints[23][1]+joints[24][1]) / 2,
        "visible_legs": len(sides),
        "per_leg": per_leg,
    }, None


class LowerBodyFallDetector:
    def __init__(self):
        self.visibility = float(os.getenv("FALL_MIN_VISIBILITY", "0.5"))
        self.max_gap = float(os.getenv("FALL_MAX_SAMPLE_GAP_SEC", "0.4"))
        self.calibrate_sec = float(os.getenv("LOWER_CALIBRATE_SEC", "0.4"))
        self.drop_speed = float(os.getenv("LOWER_DROP_SPEED", "0.8"))
        self.min_drop = float(os.getenv("LOWER_MIN_SUPPORT_DROP", "0.25"))
        self.collapsed_ratio = float(os.getenv("LOWER_COLLAPSED_RATIO", "0.45"))
        self.shin_angle = float(os.getenv("LOWER_SHIN_ANGLE_DEG", "55"))
        self.confirm_sec = float(os.getenv("LOWER_CONFIRM_SEC", "0.65"))
        self.cooldown_sec = float(os.getenv("FALL_COOLDOWN_SEC", "20"))
        self.alert_sec = float(os.getenv("FALL_ALERT_DURATION", "7"))
        self.cooldown_until = self.alert_until = 0.0
        self.reset_pose()

    def reset_pose(self):
        self.history = deque()
        self.upright_since = None
        self.reference = None
        self.last_valid_time = None
        self.candidate_since = None
        self.motion_until = 0.0
        self.peak_speed = 0.0
        self.armed = True

    def update(self, points, width, height, now):
        features, reason = lower_body_features(points, width, height, self.visibility)
        base = {"detection_mode": "lower_body", "algorithm": "lower_body_rules_v1",
                "confidence_kind": "not_calibrated", "confidence": None}
        if features is None:
            # Short keypoint dropout interrupts confirmation, but not an already
            # observed standing reference/transition. Long dropout erases both.
            if self.last_valid_time is None or now-self.last_valid_time > self.max_gap:
                self.reset_pose()
            self.candidate_since = None
            self.upright_since = None
            self.history.clear()
            return "LOW_VISIBILITY", {**base, "reason": reason, "phase": "LOW_VISIBILITY"}, False
        if self.history and now <= self.history[-1][0]:
            return "INSUFFICIENT_EVIDENCE", {**base, "reason": "NON_INCREASING_TIMESTAMP"}, False
        if self.last_valid_time is not None and now-self.last_valid_time > self.max_gap:
            self.reset_pose()
        self.last_valid_time = now
        self.history.append((now, features))
        while len(self.history) > 2 and now-self.history[0][0] > 0.25:
            self.history.popleft()
        start, previous = self.history[0]
        dt = now-start
        common = previous["per_leg"].keys() & features["per_leg"].keys()
        # Compare each leg with itself: a visibility change must not create a drop.
        support_speed = (sum(previous["per_leg"][s]["support_ratio"]-features["per_leg"][s]["support_ratio"]
                             for s in common) / len(common) / dt) if dt > 0 and common else 0.
        angular_speed = (sum(abs(previous["per_leg"][s]["leg_angle_deg"]-features["per_leg"][s]["leg_angle_deg"])
                             for s in common) / len(common) / dt) if dt > 0 and common else 0.
        upright = (features["support_ratio"] > 0.75 and features["leg_angle_deg"] < 30
                   and features["shin_angle_deg"] < 30 and features["knee_angle_deg"] > 145)
        if upright:
            if self.upright_since is None:
                self.upright_since = now
            if now-self.upright_since >= self.calibrate_sec:
                if self.reference is None:
                    self.reference = {}
                self.reference.update({s: f["support_ratio"] for s, f in features["per_leg"].items()})
                self.armed = True
        else:
            self.upright_since = None
        known = features["per_leg"].keys() & (self.reference or {}).keys()
        drop = max(0., sum(self.reference[s]-features["per_leg"][s]["support_ratio"] for s in known)/len(known)) if known else 0.
        if self.reference is not None and support_speed > self.drop_speed and drop >= self.min_drop:
            self.motion_until = now+1.8
            self.peak_speed = max(self.peak_speed, support_speed)
        collapsed = (features["support_ratio"] < self.collapsed_ratio
                     and features["shin_angle_deg"] >= self.shin_angle
                     and features["min_shin_angle_deg"] >= 40)
        stable = abs(support_speed) < 0.35 and angular_speed < 35
        triggered = False
        if (collapsed and stable and now < self.motion_until and self.reference is not None
                and self.armed and now >= self.cooldown_until):
            if self.candidate_since is None:
                self.candidate_since = now
            # A single visible leg provides weaker evidence: require longer hold.
            if now-self.candidate_since >= self.confirm_sec * (1.5 if features["visible_legs"] == 1 else 1.):
                triggered = True
                self.armed = False
                self.cooldown_until = now+self.cooldown_sec
                self.alert_until = now+self.alert_sec
        else:
            self.candidate_since = None
        if now < self.alert_until:
            state, reason = "SUSPECTED_FALL", "RAPID_SUPPORT_LOSS_AND_PERSISTENT_LEG_COLLAPSE"
        elif self.reference is None:
            state = "CALIBRATING_LOWER_BODY" if upright else "INSUFFICIENT_EVIDENCE"
            reason = "NEED_CONTINUOUS_UPRIGHT_BASELINE"
        elif self.candidate_since is not None:
            state, reason = "CHECKING", "VERIFYING_PERSISTENT_COLLAPSE"
        elif now < self.motion_until and drop >= self.min_drop:
            state, reason = "UNSTABLE_POSTURE", "SUPPORT_LOSS_NOT_CONFIRMED_AS_FALL"
        elif collapsed:
            state, reason = "HORIZONTAL_POSTURE", "LOWER_BODY_HORIZONTAL_WITHOUT_CONFIRMED_TRANSITION"
        else:
            state, reason = "LOWER_BODY_OBSERVING", "OBSERVING_LEG_GEOMETRY"
        metrics = {**base, **{k: round(v, 4) for k, v in features.items() if k != "per_leg"},
                   "observation_quality": "BOTH_LEGS" if features["visible_legs"] == 2 else "SINGLE_LEG",
                   "phase": state, "reason": reason,
                   "support_drop": round(drop, 4),
                   "support_loss_per_sec": round(support_speed, 4),
                   "pose_angular_speed_deg_per_sec": round(angular_speed, 2),
                   "baseline_ready": self.reference is not None,
                   "confirmation_elapsed_sec": round(now-self.candidate_since, 3) if self.candidate_since is not None else 0.,
                   "confidence": None}
        # No invented probability: these rules have not been calibrated on data.
        return state, metrics, triggered
