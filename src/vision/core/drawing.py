"""Rendering only: importing this module never starts a camera."""
import cv2

SKELETON = [
    # Dau va co
    (0, 11), (0, 12),
    # Than tren va tay
    (11, 12), (11, 13), (13, 15),
    (12, 14), (14, 16),
    (11, 23), (12, 24), (23, 24),
    # Chan
    (23, 25), (25, 27),
    (24, 26), (26, 28)
]



def draw_pose(frame, points, label=None, color=None):
    h, w = frame.shape[:2]
    line_color = color if color is not None else (74, 222, 128)

    for a, b in SKELETON:
        if a >= len(points) or b >= len(points):
            continue
        pa, pb = points[a], points[b]
        vis_a = getattr(pa, "visibility", 1.0)
        vis_b = getattr(pb, "visibility", 1.0)
        if (vis_a is not None and vis_a < 0.20) or (vis_b is not None and vis_b < 0.20):
            continue

        start = (int(pa.x * w), int(pa.y * h))
        end = (int(pb.x * w), int(pb.y * h))
        cv2.line(frame, start, end, line_color, 2)

    for p in points:
        vis = getattr(p, "visibility", 1.0)
        if vis is not None and vis >= 0.20:
            x = int(p.x * w)
            y = int(p.y * h)
            cv2.circle(frame, (x, y), 3, (255, 255, 255), -1)

    if label:
        # Put the label at an observed joint, including hips for low cameras.
        ref_p = next((points[i] for i in (0, 11, 12, 23, 24, 25, 26, 27, 28)
                      if i < len(points) and (getattr(points[i], "visibility", 0) or 0) >= 0.5), None)
        if ref_p is not None:
            if getattr(ref_p, "visibility", 1.0) < 0.20 and len(points) > 12:
                s11_vis = getattr(points[11], "visibility", 0) or 0
                s12_vis = getattr(points[12], "visibility", 0) or 0
                if max(s11_vis, s12_vis) > 0.15:
                    ref_p = points[11] if s11_vis > s12_vis else points[12]
            nx = int(ref_p.x * w)
            ny = max(24, int(ref_p.y * h) - 14)
            (tw, th), _ = cv2.getTextSize(label, cv2.FONT_HERSHEY_SIMPLEX, 0.5, 2)
            cv2.rectangle(frame, (nx - 4, ny - th - 4), (nx + tw + 4, ny + 4), (15, 23, 42), -1)
            cv2.putText(frame, label, (nx, ny), cv2.FONT_HERSHEY_SIMPLEX, 0.5, line_color, 2)
