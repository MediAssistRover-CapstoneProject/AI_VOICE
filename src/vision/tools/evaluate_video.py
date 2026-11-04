"""Finite, deterministic local replay using VIDEO time, never backend/MQTT.

Exports observations for inspection and later annotation/training. This command
does not train a model or claim accuracy when no ground truth is supplied.
"""
import argparse
from ..config import MODEL_DIR
from collections import Counter
import json
import math
from pathlib import Path
import time

from ..core.fall_session import FallSession
from ..core.lower_body_fall import lower_body_features
from ..core.pose_adapter import yolo_to_mp_landmarks


def score_events(events, falls, duration, grace=1.0):
    """One-to-one event matching; repeated alarms in one fall count as extras."""
    for fall in falls:
        if not (0 <= fall["start_sec"] <= fall["end_sec"] <= duration):
            raise ValueError("Label must satisfy 0 <= start_sec <= end_sec <= video duration")
    matched, delays, false_alarms = set(), [], 0
    for event in sorted(events, key=lambda e: e["sample_time_sec"]):
        t = event["sample_time_sec"]
        index = next((i for i, fall in enumerate(falls) if i not in matched
                      and fall["start_sec"] <= t <= fall["end_sec"]+grace), None)
        if index is None:
            false_alarms += 1
        else:
            matched.add(index)
            delays.append(round(t-falls[index]["start_sec"], 3))
    return {"labeled_falls": len(falls), "detected_falls": len(matched),
            "missed_falls": len(falls)-len(matched), "unmatched_alerts": false_alarms,
            "recall": len(matched)/len(falls) if falls else None,
            "unmatched_alerts_per_video_hour": false_alarms/(duration/3600) if duration > 0 else None,
            "detection_delays_sec": delays, "matching_grace_sec": grace}


def main():
    parser = argparse.ArgumentParser(description="Danh gia video te nga offline; khong ket noi BE")
    parser.add_argument("--video", required=True)
    parser.add_argument("--output", required=True, help="New output folder for this run")
    parser.add_argument("--model", default=str(MODEL_DIR / "yolov8n-pose.onnx"))
    parser.add_argument("--mode", choices=("lower_body", "full_body", "auto"), default="lower_body")
    parser.add_argument("--every", type=int, default=1, help="Infer every N frames")
    parser.add_argument("--fps", type=float, help="Override missing/incorrect source FPS")
    parser.add_argument("--max-seconds", type=float, default=0)
    parser.add_argument("--labels", help='JSON: {"falls": [{"start_sec": 1.0, "end_sec": 2.5}]}')
    parser.add_argument("--annotate", action="store_true", help="Write annotated.mp4")
    args = parser.parse_args()
    for filename in (args.video, args.model):
        if not Path(filename).is_file():
            parser.error("File not found: " + filename)
    if args.every < 1 or args.max_seconds < 0:
        parser.error("--every >= 1; --max-seconds >= 0")
    output = Path(args.output)
    if output.exists() and any(output.iterdir()):
        parser.error("Use an empty output folder for each evaluation")
    labels = json.loads(Path(args.labels).read_text(encoding="utf-8")) if args.labels else None
    import cv2
    from ultralytics import YOLO
    model = YOLO(args.model, task="pose")
    cap = cv2.VideoCapture(args.video)
    if not cap.isOpened():
        parser.error("Cannot open video")
    fps = args.fps if args.fps is not None else cap.get(cv2.CAP_PROP_FPS)
    if not math.isfinite(fps) or fps <= 0:
        cap.release()
        parser.error("Invalid source FPS: supply --fps")
    output.mkdir(parents=True, exist_ok=True)
    session = FallSession(mode=args.mode, log_path=output / "observations.jsonl")
    writer = None
    counts, reasons = Counter(), Counter()
    events, frame_index, pose_count, valid_count = [], 0, 0, 0
    snapshot = {"fall_state": "NO_DATA", "results": []}
    started = time.perf_counter()
    try:
        while True:
            t = frame_index / fps
            if args.max_seconds and t >= args.max_seconds:
                break
            ok, frame = cap.read()
            if not ok:
                break
            h, w = frame.shape[:2]
            scale = min(1., 800/max(w, h))
            frame = cv2.resize(frame, (max(2, int(w*scale)), max(2, int(h*scale))))
            h, w = frame.shape[:2]
            if frame_index % args.every == 0:
                predictions = model(frame, imgsz=800, conf=0.14, rect=True, verbose=False)
                poses = []
                if predictions and predictions[0].keypoints is not None:
                    kpts = predictions[0].keypoints
                    for i in range(len(kpts)):
                        poses.append(yolo_to_mp_landmarks(kpts.xy[i].tolist(),
                                     kpts.conf[i].tolist() if kpts.conf is not None else None, w, h))
                for pose in poses:
                    features, reason = lower_body_features(pose, w, h)
                    pose_count += 1
                    valid_count += features is not None
                    if reason:
                        reasons[reason] += 1
                snapshot = session.update(poses, w, h, t)
                counts[snapshot["fall_state"]] += 1
                events.extend(snapshot["events"])
            if args.annotate:
                from ..core.drawing import draw_pose
                for result in snapshot["results"]:
                    draw_pose(frame, poses[result["pose_index"]], label=f"#{result['person_id']} {result['state']}")
                cv2.putText(frame, f"t={t:.2f}s {snapshot['fall_state']}", (12, 26),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.55, (0, 180, 255), 2)
                if writer is None:
                    writer = cv2.VideoWriter(str(output / "annotated.mp4"), cv2.VideoWriter_fourcc(*"mp4v"), fps, (w, h))
                    if not writer.isOpened():
                        raise RuntimeError("Cannot create annotated.mp4")
                writer.write(frame)
            frame_index += 1
    finally:
        session.close()
        cap.release()
        if writer:
            writer.release()
    duration = frame_index / fps
    summary = {"video": str(Path(args.video).resolve()), "mode": args.mode,
               "model": str(Path(args.model).resolve()), "source_fps": fps,
               "duration_sec": duration, "frames_read": frame_index,
               "inference_samples": sum(counts.values()), "state_counts": dict(counts),
               "pose_proposals": pose_count, "valid_lower_body_observations": valid_count,
               "valid_lower_body_fraction": valid_count/pose_count if pose_count else None,
               "visibility_failures": dict(reasons), "events": events,
               "processing_seconds": round(time.perf_counter()-started, 3),
               "evaluation": score_events(events, labels["falls"], duration) if labels is not None else
                             {"status": "UNLABELED", "note": "No ground truth: no accuracy/recall claim."}}
    (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=True, indent=2))


if __name__ == "__main__":
    main()
