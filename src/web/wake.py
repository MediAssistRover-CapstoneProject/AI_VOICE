"""Recognize the complete Vietnamese wake utterance on the AI host."""
from __future__ import annotations

import os
from pathlib import Path
import re
import threading
import time
import unicodedata

SAMPLE_RATE = 16000
MAX_PCM_BYTES = SAMPLE_RATE * 2 * 4
MODEL_NAME = 'sherpa-onnx-zipformer-vi-30M-int8-2026-02-09'
ENGINE = 'zipformer-vi-30m-local'
MODEL_FILES = {
    'encoder': 'encoder.int8.onnx',
    'decoder': 'decoder.onnx',
    'joiner': 'joiner.int8.onnx',
    'tokens': 'tokens.txt',
}
_recognizer = None
_model_path = None
_lock = threading.Lock()


def normalize(text: str) -> str:
    text = unicodedata.normalize('NFC', text).casefold()
    return ' '.join(re.sub(r'[^\w\s]', ' ', text).split())


def settings() -> tuple[bool, str, tuple[str, ...], Path]:
    data = Path(os.getenv('LOCALAPPDATA') or os.getenv('XDG_DATA_HOME') or Path.home() / '.local/share')
    default = data / 'MediAssistRover' / 'voice-ai' / 'models' / MODEL_NAME
    aliases = tuple(part.strip() for part in os.getenv('AI_WAKE_ALIASES', '').split(',') if part.strip())
    return (
        os.getenv('AI_WAKE_ENABLED', 'false').lower() == 'true',
        os.getenv('AI_WAKE_PHRASE', 'Chào cậu').strip(),
        aliases,
        Path(os.getenv('AI_WAKE_MODEL_PATH') or default),
    )


def model_exists(path: Path) -> bool:
    return all((path / filename).is_file() for filename in MODEL_FILES.values())


def status() -> dict:
    enabled, phrase, aliases, model_path = settings()
    return dict(enabled=enabled, phrase=phrase, aliases=list(aliases), engine=ENGINE,
                configured=bool(normalize(phrase)) and model_exists(model_path),
                loaded=_recognizer is not None and _model_path == model_path)


def _load_recognizer(model_path):
    """Caller owns _lock. Kept separate so preload does not decode fake audio."""
    global _recognizer, _model_path
    if _recognizer is None or _model_path != model_path:
        from sherpa_onnx import OfflineRecognizer

        if not model_exists(model_path):
            raise RuntimeError('VOICE_WAKE_MODEL_MISSING')
        files = {key: str(model_path / name) for key, name in MODEL_FILES.items()}
        _recognizer = OfflineRecognizer.from_transducer(
            **files, num_threads=2, sample_rate=SAMPLE_RATE, feature_dim=80,
        )
        _model_path = model_path
    return _recognizer


def preload():
    enabled, _, _, model_path = settings()
    if enabled:
        with _lock:
            _load_recognizer(model_path)
    return status()


def detect(pcm: bytes) -> dict:
    import numpy as np

    global _recognizer, _model_path
    enabled, phrase, aliases, model_path = settings()
    if not enabled:
        raise RuntimeError('VOICE_WAKE_DISABLED')
    if not 6400 <= len(pcm) <= MAX_PCM_BYTES or len(pcm) % 2:
        raise ValueError('WAKE_REQUIRES_PCM16_MONO_16KHZ_0P2_TO_4_SECONDS')
    phrases = (phrase, *aliases)
    expected = {normalize(value) for value in phrases}
    if '' in expected or any(len(value) > 80 for value in phrases):
        raise RuntimeError('VOICE_WAKE_CONFIG_INVALID')

    result = dict(detected=False, phrase=phrase, engine=ENGINE, processing_ms=0)
    samples = np.frombuffer(pcm, dtype='<i2').astype(np.float32) / 32768.0
    if np.mean(samples * samples) < .003 ** 2:
        return result

    started = time.monotonic()
    with _lock:
        recognizer = _load_recognizer(model_path)
        stream = recognizer.create_stream()
        stream.accept_waveform(SAMPLE_RATE, samples)
        recognizer.decode_stream(stream)
        result['detected'] = normalize(stream.result.text) in expected
    result['processing_ms'] = round((time.monotonic() - started) * 1000)
    return result
