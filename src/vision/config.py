"""Vision settings share the repository .env and device identity with Voice."""
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MODEL_DIR = ROOT / "models" / "vision"
RUNTIME_DIR = ROOT / "data" / "vision"


def configure_environment():
    from dotenv import load_dotenv
    load_dotenv(ROOT / ".env", override=False)
    os.environ["VIDEO_SOURCE"] = os.getenv("VISION_SOURCE", "0")
    os.environ["FALL_MODE"] = os.getenv("VISION_MODE", "lower_body")
    if os.getenv("VISION_MODEL"):
        os.environ["YOLO_POSE_MODEL"] = os.environ["VISION_MODEL"]
    for legacy, key in (("ENABLE_HAND", "VISION_ENABLE_HAND"),
                        ("ENABLE_FACE", "VISION_ENABLE_FACE"),
                        ("ENABLE_SEIZURE", "VISION_ENABLE_SEIZURE")):
        os.environ[legacy] = os.getenv(key, "false")
    os.environ.setdefault("AI_EVERY", "1")
    os.environ.setdefault("FALL_LOG_PATH", str(RUNTIME_DIR / "logs" / "observations.jsonl"))
