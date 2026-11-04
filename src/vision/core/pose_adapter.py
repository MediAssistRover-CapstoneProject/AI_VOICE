"""COCO-17 to MediaPipe-33 layout. Missing joints retain confidence zero."""
COCO_TO_MP = {0: 0, 1: 2, 2: 5, 3: 7, 4: 8, 5: 11, 6: 12,
              7: 13, 8: 14, 9: 15, 10: 16, 11: 23, 12: 24,
              13: 25, 14: 26, 15: 27, 16: 28}


class PseudoLandmark:
    __slots__ = ("x", "y", "z", "visibility")

    def __init__(self, x=0., y=0., z=0., visibility=0.):
        self.x, self.y, self.z, self.visibility = x, y, z, visibility


def yolo_to_mp_landmarks(kpts_xy, kpts_conf, img_w, img_h):
    landmarks = [PseudoLandmark() for _ in range(33)]
    for coco, mp in COCO_TO_MP.items():
        if coco < len(kpts_xy):
            x, y = kpts_xy[coco]
            # Unknown confidence must never be treated as a reliable observation.
            confidence = float(kpts_conf[coco]) if kpts_conf is not None else 0.
            landmarks[mp] = PseudoLandmark(float(x)/max(img_w, 1),
                                           float(y)/max(img_h, 1), 0., confidence)
    return landmarks
