"""Audio capture from microphone with Voice Activity Detection."""
from __future__ import annotations

import io
import logging
import time
import wave
from collections import deque
from typing import Callable

import numpy as np

log = logging.getLogger("mar_voice")

# Lazy import sounddevice to allow headless test environments.
_sd = None


def _ensure_sd():
    global _sd
    if _sd is None:
        import sounddevice as sd  # noqa: WPS433
        _sd = sd
    return _sd


class MicrophoneCapture:
    """Capture audio chunks from the default input device.

    Accumulates PCM-16 audio and returns a complete WAV byte buffer when
    the speaker stops talking (silence detected for *silence_ms*).
    """

    def __init__(
        self,
        sample_rate: int = 16_000,
        channels: int = 1,
        chunk_ms: int = 30,
        silence_ms: int = 1500,
        max_sec: int = 15,
        min_speech_ms: int = 500,
        energy_threshold: float = 0.01,
    ) -> None:
        self.sample_rate = sample_rate
        self.channels = channels
        self.chunk_samples = int(sample_rate * chunk_ms / 1000)
        self.silence_chunks = int(silence_ms / chunk_ms)
        self.max_chunks = int(max_sec * 1000 / chunk_ms)
        self.min_speech_chunks = int(min_speech_ms / chunk_ms)
        self.energy_threshold = energy_threshold

    def record_until_silence(
        self,
        on_speech_start: Callable[[], None] | None = None,
        on_vad_status: Callable[[bool], None] | None = None,
    ) -> bytes | None:
        """Block until speech is detected, then record until silence.

        Returns a WAV byte buffer or *None* if no speech was detected
        within *max_sec*.
        """
        sd = _ensure_sd()
        chunks: list[np.ndarray] = []
        pre_buffer: deque[np.ndarray] = deque(maxlen=6)  # ~180ms pre-speech buffer
        silent_count = 0
        speech_started = False
        speech_chunk_count = 0

        log.info("[MIC] Listening … (say something)")

        with sd.InputStream(
            samplerate=self.sample_rate,
            channels=self.channels,
            dtype="int16",
            blocksize=self.chunk_samples,
        ) as stream:
            for _ in range(self.max_chunks):
                data, _ = stream.read(self.chunk_samples)
                chunk = data.copy()

                energy = np.abs(chunk.astype(np.float32) / 32768.0).mean()
                is_speech = energy > self.energy_threshold

                if on_vad_status:
                    on_vad_status(is_speech)

                if is_speech:
                    if not speech_started:
                        speech_started = True
                        if on_speech_start:
                            on_speech_start()
                        log.info("[MIC] Speech detected (energy=%.4f)", energy)
                        # Add pre-speech audio buffer so initial consonants are intact
                        chunks.extend(pre_buffer)
                    silent_count = 0
                    speech_chunk_count += 1
                    chunks.append(chunk)
                elif speech_started:
                    silent_count += 1
                    chunks.append(chunk)  # keep trailing silence
                    if silent_count >= self.silence_chunks:
                        # If the sound was just a momentary click (< 250ms), ignore and keep listening!
                        if speech_chunk_count < (250 // (self.chunk_samples * 1000 // self.sample_rate)):
                            log.debug("[MIC] Ignored transient noise (%d chunks), resuming listening", speech_chunk_count)
                            speech_started = False
                            silent_count = 0
                            speech_chunk_count = 0
                            chunks.clear()
                            continue
                        break
                else:
                    pre_buffer.append(chunk)

        if not speech_started or speech_chunk_count < 5:
            log.info("[MIC] No meaningful speech captured")
            return None

        pcm = np.concatenate(chunks)
        wav_buffer = io.BytesIO()
        with wave.open(wav_buffer, "wb") as wf:
            wf.setnchannels(self.channels)
            wf.setsampwidth(2)  # 16-bit
            wf.setframerate(self.sample_rate)
            wf.writeframes(pcm.tobytes())

        wav_bytes = wav_buffer.getvalue()
        duration = len(pcm) / self.sample_rate
        log.info("[MIC] Captured %.1fs of audio (%d bytes WAV)", duration, len(wav_bytes))
        return wav_bytes
