"""Recognize the complete Vietnamese wake utterance on the AI host."""
from __future__ import annotations

import os
import logging
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
_statistics_lock = threading.Lock()
_counts = {'processed': 0, 'detected': 0, 'low_energy': 0, 'no_match': 0}
_last_decision = None


def normalize(text: str) -> str:
    text = unicodedata.normalize('NFC', text).casefold()
    return ' '.join(re.sub(r'[^\w\s]', ' ', text).split())


def matching_key(text: str) -> str:
    """Optional tone tolerance for ASR's letter names; still match a whole phrase."""
    value = normalize(text)
    if os.getenv('AI_WAKE_IGNORE_TONE', 'false').lower() == 'true':
        value = ''.join(char for char in unicodedata.normalize('NFD', value)
                        if unicodedata.category(char) != 'Mn').replace('đ', 'd')
    return value


def settings() -> tuple[bool, str, tuple[str, ...], Path]:
    data = Path(os.getenv('LOCALAPPDATA') or os.getenv('XDG_DATA_HOME') or Path.home() / '.local/share')
    default = data / 'MediAssistRover' / 'voice-ai' / 'models' / MODEL_NAME
    aliases = tuple(part.strip() for part in os.getenv('AI_WAKE_ALIASES', '').split(',') if part.strip())
    return (
        os.getenv('AI_WAKE_ENABLED', 'false').lower() == 'true',
        os.getenv('AI_WAKE_PHRASE', 'M A').strip(),
        aliases,
        Path(os.getenv('AI_WAKE_MODEL_PATH') or default),
    )


def model_exists(path: Path) -> bool:
    return all((path / filename).is_file() for filename in MODEL_FILES.values())


def status() -> dict:
    enabled, phrase, aliases, model_path = settings()
    with _statistics_lock:
        counts = dict(_counts)
        decision = dict(_last_decision) if _last_decision is not None else None
    return dict(enabled=enabled, phrase=phrase, aliases=list(aliases), engine=ENGINE,
                configured=bool(normalize(phrase)) and model_exists(model_path),
                loaded=_recognizer is not None and _model_path == model_path,
                statistics=counts, last_decision=decision)


def _record_decision(result: dict, reason: str) -> dict:
    """Only acoustic metrics and decisions; never retain audio or decoded text."""
    global _last_decision
    result['reason'] = reason
    with _statistics_lock:
        _counts['processed'] += 1
        _counts[{'MATCH': 'detected', 'LOW_AUDIO_ENERGY': 'low_energy',
                 'NO_PHRASE_MATCH': 'no_match'}[reason]] += 1
        _last_decision = {key: result[key] for key in
                          ('detected', 'reason', 'audio_ms', 'rms', 'processing_ms')}
    logging.getLogger('uvicorn.error').info(
        'WAKE_DECISION detected=%s reason=%s audio_ms=%d rms=%.5f processing_ms=%d',
        result['detected'], reason, result['audio_ms'], result['rms'], result['processing_ms'])
    return result


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
    expected = {matching_key(value) for value in phrases}
    if '' in expected or any(len(value) > 80 for value in phrases):
        raise RuntimeError('VOICE_WAKE_CONFIG_INVALID')

    samples = np.frombuffer(pcm, dtype='<i2').astype(np.float32) / 32768.0
    energy = float(np.mean(samples * samples))
    result = dict(detected=False, phrase=phrase, engine=ENGINE, processing_ms=0,
                  audio_ms=round(len(samples) * 1000 / SAMPLE_RATE), rms=round(energy ** .5, 5))
    if energy < .003 ** 2:
        return _record_decision(result, 'LOW_AUDIO_ENERGY')

    started = time.monotonic()
    with _lock:
        recognizer = _load_recognizer(model_path)
        stream = recognizer.create_stream()
        stream.accept_waveform(SAMPLE_RATE, samples)
        recognizer.decode_stream(stream)
        result['detected'] = matching_key(stream.result.text) in expected
    result['processing_ms'] = round((time.monotonic() - started) * 1000)
    return _record_decision(result, 'MATCH' if result['detected'] else 'NO_PHRASE_MATCH')
