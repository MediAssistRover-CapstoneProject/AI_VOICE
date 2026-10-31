"""Centralized logging configuration."""
from __future__ import annotations

import logging
import sys

# Safeguard Windows console for UTF-8 Vietnamese output
try:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

from rich.logging import RichHandler


def setup_logger(name: str = "mar_voice", level: str = "INFO") -> logging.Logger:
    logger = logging.getLogger(name)
    if logger.handlers:
        return logger
    handler = RichHandler(rich_tracebacks=True, show_path=False, markup=True,
                          log_time_format="[%H:%M:%S]")
    handler.setLevel(getattr(logging, level.upper(), logging.INFO))
    logger.addHandler(handler)
    logger.setLevel(getattr(logging, level.upper(), logging.INFO))
    return logger


log = setup_logger()
