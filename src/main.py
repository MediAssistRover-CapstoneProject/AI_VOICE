#!/usr/bin/env python3
"""MediAssistRover AI Voice Assistant – Entry Point.

Usage:
    # Voice mode (with microphone):
    python -m src.main

    # Text mode (for testing without mic):
    python -m src.main --text

    # Custom backend URL:
    python -m src.main --backend http://192.168.1.100:8080
"""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

from dotenv import load_dotenv

# Load .env from project root
_ROOT = Path(__file__).resolve().parent.parent
load_dotenv(_ROOT / ".env", override=False)

from src.utils.logger import setup_logger
from src.voice_engine import VoiceEngine


def main() -> None:
    parser = argparse.ArgumentParser(
        description="MediAssistRover AI Voice Assistant"
    )
    parser.add_argument(
        "--text", action="store_true",
        help="Run in text-input mode (no microphone required)"
    )
    parser.add_argument(
        "--backend", default=os.getenv("BACKEND_URL", "http://localhost:8080"),
        help="Backend API base URL"
    )
    parser.add_argument(
        "--robot-code", default=os.getenv("ROBOT_CODE", "ROBOT_MAR_01"),
        help="Robot code for device authentication"
    )
    parser.add_argument(
        "--stt", default=os.getenv("STT_PROVIDER", "whisper"),
        choices=["whisper", "google", "local"],
        help="Speech-to-Text provider"
    )
    parser.add_argument(
        "--log-level", default=os.getenv("LOG_LEVEL", "INFO"),
        help="Logging level"
    )
    args = parser.parse_args()

    setup_logger(level=args.log_level)

    engine = VoiceEngine(
        backend_url=args.backend,
        robot_code=args.robot_code,
        device_secret=os.getenv("DEVICE_SECRET", ""),
        stt_provider=args.stt,
        openai_api_key=os.getenv("OPENAI_API_KEY", ""),
        google_credentials=os.getenv("GOOGLE_APPLICATION_CREDENTIALS"),
    )

    if args.text:
        engine.run_text_mode()
    else:
        engine.run()


if __name__ == "__main__":
    main()
