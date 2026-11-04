"""Fall detection modes: legacy full-body rules and experimental lower-body rules.

The description below applies to the legacy full-body branch. See
docs/VISION_STORAGE_AND_DELIVERY.md for the integrated camera branch and limits.

Đặc điểm cải tiến:
1. Phân biệt té ngã với các hoạt động bình thường:
   - Cúi người (Bending): Thân nghiêng nhưng hông ở vị trí cao, không có gia tốc rơi.
   - Ngồi ghế (Sitting): Hông hạ xuống nhưng thân vẫn thẳng đứng (< 35 độ).
   - Nằm từ từ (Lying down slowly): Thân nằm ngang nhưng không có gia tốc rơi nhanh.
   - Té ngã thật (Fall): Rơi nhanh đột ngột (hip_speed > threshold) -> thân lật ngang -> nằm bất động ở sàn.
2. Tính toán điểm tin cậy (confidence score: 0.50 - 0.98), phục vụ luồng WF_FALL_DETECTION_RESPONSE.
3. Cấu hình linh hoạt qua biến môi trường (FALL_ANGLE_THRESHOLD, FALL_SPEED_THRESHOLD...).
"""
import math
import os
from .lower_body_fall import LowerBodyFallDetector, visible_point


class FullBodyFallDetector:
    def __init__(self):
        self.angle_threshold = float(os.getenv("FALL_ANGLE_THRESHOLD", "50.0"))
        self.speed_threshold = float(os.getenv("FALL_SPEED_THRESHOLD", "0.20"))
        self.confirm_seconds = float(os.getenv("FALL_CONFIRM_SEC", "0.5"))
        self.cooldown_seconds = float(os.getenv("FALL_COOLDOWN_SEC", "20.0"))
        self.alert_duration = float(os.getenv("FALL_ALERT_DURATION", "7.0"))

        self.prev = None
        self.motion_until = 0.0
        self.peak_speed = 0.0
        self.candidate_since = None
        self.cooldown_until = 0.0
        self.alert_until = 0.0
        self.last_confidence = 0.0

    def reset_pose(self):
        self.prev = None
        self.candidate_since = None
        self.motion_until = 0.0
        self.peak_speed = 0.0

    def update(self, points, width, height, now):
        joints = [11, 12, 23, 24]

        # Kiểm tra visibility (hỗ trợ cả MediaPipe Landmark object và dict)
        for i in joints:
            p = points[i] if i < len(points) else None
            if p is None:
                self.reset_pose()
                return "LOW_VISIBILITY", {}, False
            vis = getattr(p, "visibility", None)
            if vis is None and isinstance(p, dict):
                vis = p.get("visibility", 1.0)
            if vis is not None and vis < 0.5:
                self.reset_pose()
                return "LOW_VISIBILITY", {}, False

        def get_pt(p):
            if isinstance(p, dict):
                return float(p.get("x", 0.0)), float(p.get("y", 0.0))
            return float(p.x), float(p.y)

        s11_x, s11_y = get_pt(points[11])
        s12_x, s12_y = get_pt(points[12])
        h23_x, h23_y = get_pt(points[23])
        h24_x, h24_y = get_pt(points[24])

        shoulder_x = (s11_x + s12_x) / 2
        shoulder_y = (s11_y + s12_y) / 2
        hip_x = (h23_x + h24_x) / 2
        hip_y = (h23_y + h24_y) / 2

        # Góc thân người (torso angle) so với phương thẳng đứng (0 = đứng thẳng, 90 = nằm ngang)
        dx = (shoulder_x - hip_x) * width
        dy = (shoulder_y - hip_y) * height
        angle = math.degrees(math.atan2(abs(dx), max(abs(dy), 0.001)))

        # Aspect ratio của bounding box cơ thể (rộng / cao)
        # Đứng: width < height (ratio < 0.65). Ngã ngang: ratio >= 0.95
        key_indices = [11, 12, 23, 24, 25, 26, 27, 28]
        valid_pts = [get_pt(points[i]) for i in key_indices if i < len(points)]
        xs = [p[0] for p in valid_pts]
        ys = [p[1] for p in valid_pts]
        box_w = (max(xs) - min(xs)) * width if xs else 1
        box_h = (max(ys) - min(ys)) * height if ys else 1
        aspect_ratio = round(box_w / max(box_h, 1.0), 2)

        hip_speed = 0.0
        angular_speed = 0.0

        if self.prev is not None:
            old_t, old_hip_y, old_angle = self.prev
            dt = now - old_t

            if dt > 0.015:
                # Vận tốc rơi theo trục Y (dương = rơi xuống sàn)
                hip_speed = (hip_y - old_hip_y) / dt
                angular_speed = abs(angle - old_angle) / dt

                # Phát hiện rơi nhanh hoặc xoay lật đột ngột
                rapid_drop = hip_speed > self.speed_threshold
                rapid_rotation = angular_speed > 85.0 and hip_speed > 0.05

                if rapid_drop or rapid_rotation:
                    self.motion_until = now + 1.8
                    self.peak_speed = max(self.peak_speed, hip_speed)

        self.prev = (now, hip_y, angle)

        # Tiêu chí tư thế ngang: góc >= ngưỡng HOẶC tỷ lệ khung bao ngang >= 0.95
        is_horizontal = (angle >= self.angle_threshold) or (aspect_ratio >= 0.95 and angle >= 40.0)

        # Tiêu chí độ cao thấp: hông ở nửa dưới khung hình (gần mặt sàn)
        # Loại trừ trường hợp cúi nhặt đồ nhưng hông vẫn ở vị trí đứng cao
        is_low_elevation = hip_y > 0.45

        suspected = False

        # Chỉ kích hoạt nếu có giai đoạn rơi nhanh trước đó VÀ hiện tại đang nằm ngang gần sàn
        if is_horizontal and is_low_elevation and now < self.motion_until:
            if self.candidate_since is None:
                self.candidate_since = now

            immobility_duration = now - self.candidate_since

            # Xác nhận tư thế nằm bất động sau va chạm trong khoảng confirm_seconds
            if immobility_duration >= self.confirm_seconds and now >= self.cooldown_until:
                suspected = True
                self.cooldown_until = now + self.cooldown_seconds
                self.alert_until = now + self.alert_duration
                self.candidate_since = None

                # Điểm tin cậy (Confidence Score 0.55 - 0.98)
                score_angle = 0.35 * min(1.0, max(0.0, angle / 75.0))
                score_speed = 0.35 * min(1.0, max(0.0, self.peak_speed / 0.40))
                score_aspect = 0.20 * min(1.0, max(0.0, aspect_ratio / 1.1))
                score_duration = 0.10 * min(1.0, immobility_duration / 1.0)
                self.last_confidence = round(
                    min(0.98, max(0.55, score_angle + score_speed + score_aspect + score_duration)), 2
                )
                self.peak_speed = 0.0
        else:
            if not is_horizontal:
                self.candidate_since = None
                self.peak_speed = 0.0

        if now < self.alert_until:
            state = "SUSPECTED_FALL"
        elif self.candidate_since is not None:
            state = "CHECKING"
        elif is_horizontal and is_low_elevation:
            state = "HORIZONTAL_POSTURE"
        elif angle >= 40.0:
            state = "BENDING_OR_LEANING"
        else:
            state = "POSE_DETECTED"

        metrics = {
            "torso_angle_deg": round(angle, 2),
            "hip_y_normalized": round(hip_y, 4),
            "hip_speed_normalized_per_sec": round(hip_speed, 3),
            "angular_speed_deg_per_sec": round(angular_speed, 2),
            "aspect_ratio": aspect_ratio,
            "confidence": self.last_confidence if state == "SUSPECTED_FALL" else None,
            "phase": state,
        }

        return state, metrics, suspected


class FallDetector:
    """Select explicit lower_body/full_body mode or visibility-based auto.

    Lower camera deployments should force lower_body: a pose network may
    hallucinate high-confidence shoulders outside the observed upper body.
    """
    def __init__(self, mode=None):
        self.mode = mode or os.getenv("FALL_MODE", "auto")
        if self.mode not in {"auto", "full_body", "lower_body"}:
            raise ValueError("FALL_MODE must be auto, full_body or lower_body")
        self.full = FullBodyFallDetector()
        self.lower = LowerBodyFallDetector()
        self.active = None
        self.last_sample = None

    def reset_pose(self):
        self.full.reset_pose()
        self.lower.reset_pose()
        self.last_sample = None

    def update(self, points, width, height, now):
        if width <= 0 or height <= 0 or not math.isfinite(now):
            self.reset_pose()
            return "INSUFFICIENT_EVIDENCE", {"reason": "INVALID_FRAME_OR_TIME"}, False
        mode = self.mode
        if mode == "auto":
            mode = "full_body" if all(visible_point(points, i) for i in (11, 12, 23, 24)) else "lower_body"
        if self.last_sample is not None and now <= self.last_sample:
            return "INSUFFICIENT_EVIDENCE", {"reason": "NON_INCREASING_TIMESTAMP"}, False
        if self.active != mode or (self.last_sample is not None and now-self.last_sample > self.lower.max_gap):
            self.reset_pose()
        self.active, self.last_sample = mode, now
        detector = self.lower if mode == "lower_body" else self.full
        if mode == "full_body" and not all(visible_point(points, i) for i in (11, 12, 23, 24)):
            self.full.reset_pose()
            return "LOW_VISIBILITY", {"detection_mode": mode, "reason": "MISSING_SHOULDERS_OR_HIPS"}, False
        state, metrics, triggered = detector.update(points, width, height, now)
        metrics.setdefault("detection_mode", mode)
        metrics.setdefault("algorithm", "full_body_rules_v2")
        metrics.setdefault("confidence_kind", "heuristic_score")
        metrics.setdefault("phase", state)
        return state, metrics, triggered


class MultiPersonFallTracker:
    """Theo dõi và phát hiện té ngã độc lập cho từng người trong khung hình (Multi-person)."""

    def __init__(self, max_distance=0.35, timeout_sec=2.0, mode=None):
        self.max_dist = max_distance
        self.timeout = timeout_sec
        self.tracks = {}
        self.next_person_id = 1
        self.mode = mode

    def update(self, all_poses, width, height, now):
        # Expire BEFORE matching: a reappearing person must not inherit old motion.
        for tid in list(self.tracks):
            if now - self.tracks[tid]["seen"] > self.timeout:
                del self.tracks[tid]
        if not all_poses:
            for track in self.tracks.values():
                track["detector"].reset_pose()
            to_del = [tid for tid, t in self.tracks.items() if now - t["seen"] > self.timeout]
            for tid in to_del:
                del self.tracks[tid]
            return [], "NO_PERSON", False, None

        centers = []
        for pose in all_poses:
            hips = [visible_point(pose, i) for i in (23, 24)]
            centers.append(((hips[0][0]+hips[1][0])/2, (hips[0][1]+hips[1][1])/2)
                           if all(hips) else None)

        assigned = {}
        unassigned_poses = {i for i, center in enumerate(centers) if center is not None}

        for tid, t in list(self.tracks.items()):
            best_idx = None
            best_d = self.max_dist
            for i in unassigned_poses:
                d = math.hypot(centers[i][0] - t["pos"][0], centers[i][1] - t["pos"][1])
                if d < best_d:
                    best_d = d
                    best_idx = i
            if best_idx is not None:
                assigned[tid] = best_idx
                unassigned_poses.remove(best_idx)
                self.tracks[tid]["pos"] = centers[best_idx]
                self.tracks[tid]["seen"] = now
            elif now - t["seen"] > self.timeout:
                del self.tracks[tid]
            else:
                # Gaps/occlusion break the confirmation chain, even below timeout.
                t["detector"].reset_pose()

        for i in unassigned_poses:
            tid = self.next_person_id
            self.next_person_id += 1
            self.tracks[tid] = {
                "detector": FallDetector(mode=self.mode),
                "pos": centers[i],
                "seen": now,
                "person_id": tid
            }
            assigned[tid] = i

        results = []
        any_triggered = False
        triggered_info = None
        has_fall = False
        has_checking = False
        has_horizontal = False

        for tid, pose_idx in sorted(assigned.items(), key=lambda x: x[0]):
            detector = self.tracks[tid]["detector"]
            person_id = self.tracks[tid]["person_id"]
            pose = all_poses[pose_idx]

            state, metrics, triggered = detector.update(pose, width, height, now)

            if triggered:
                any_triggered = True
                triggered_info = {
                    "person_id": person_id,
                    "metrics": metrics,
                    "center": centers[pose_idx]
                }

            if state == "SUSPECTED_FALL":
                has_fall = True
            elif state == "CHECKING":
                has_checking = True
            elif state == "HORIZONTAL_POSTURE":
                has_horizontal = True

            results.append({
                "person_id": person_id,
                "pose_index": pose_idx,
                "state": state,
                "metrics": metrics,
                "triggered": triggered,
                "center": centers[pose_idx]
            })

        if has_fall:
            global_state = "SUSPECTED_FALL"
        elif has_checking:
            global_state = "CHECKING"
        elif has_horizontal:
            global_state = "HORIZONTAL_POSTURE"
        elif any(r["state"] == "UNSTABLE_POSTURE" for r in results):
            global_state = "UNSTABLE_POSTURE"
        elif any(c is None for c in centers) or any(r["state"] == "LOW_VISIBILITY" for r in results):
            global_state = "LOW_VISIBILITY"
        elif any(r["state"] == "INSUFFICIENT_EVIDENCE" for r in results):
            global_state = "INSUFFICIENT_EVIDENCE"
        elif any(r["state"] == "CALIBRATING_LOWER_BODY" for r in results):
            global_state = "CALIBRATING_LOWER_BODY"
        elif results:
            global_state = f"{len(results)}_PEOPLE_DETECTED"
        else:
            global_state = "NO_PERSON"

        return results, global_state, any_triggered, triggered_info
