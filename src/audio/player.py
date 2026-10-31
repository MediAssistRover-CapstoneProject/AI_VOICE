"""Audio playback – play WAV bytes through the default output device."""
from __future__ import annotations

import io
import logging
import wave

import numpy as np

log = logging.getLogger("mar_voice")


def play_wav(wav_bytes: bytes) -> None:
    """Play raw WAV bytes through the default speaker."""
    import sounddevice as sd

    with wave.open(io.BytesIO(wav_bytes), "rb") as wf:
        sr = wf.getframerate()
        ch = wf.getnchannels()
        frames = wf.readframes(wf.getnframes())
        dtype = np.int16 if wf.getsampwidth() == 2 else np.uint8
        audio = np.frombuffer(frames, dtype=dtype)
        if ch > 1:
            audio = audio.reshape(-1, ch)

    log.info("[SPEAKER] Playing %.1fs audio", len(audio) / sr)
    sd.play(audio, samplerate=sr)
    sd.wait()
    log.info("[SPEAKER] Playback complete")
