"""TTS client – synthesizes speech via the Backend's OpenAI TTS service."""
from __future__ import annotations

import logging
import os
import re
import time
from collections import OrderedDict
from typing import Any

from src.audio.player import play_wav

log = logging.getLogger("mar_voice")


class SpeechClient:
    """Request TTS from the Backend or directly via OpenAI, then play through the robot speaker."""

    def __init__(
        self,
        backend_client: Any,
        speed_wpm: int = 150,
        openai_api_key: str = "",
    ) -> None:
        self._client = backend_client
        self._speed_wpm = speed_wpm
        self._openai_api_key = openai_api_key
        self._audio_cache = OrderedDict()
        self._local = None
        if os.getenv("TTS_PROVIDER") == "piper":
            from src.local_providers import PiperSpeech
            self._local = PiperSpeech()

    def speak(self, text: str, language: str = "vi", block: bool = True) -> bool:
        """Synthesize *text* and play through the local speaker.

        Returns True if audio was successfully played.
        """
        if not text or not text.strip():
            return False

        key = (text, language, self._speed_wpm)
        if self._local is not None:
            if key in self._audio_cache:
                self._audio_cache.move_to_end(key)
                play_wav(self._audio_cache[key])
                return True
            audio = self._local.synthesize(text)
            self._remember(key, audio)
            play_wav(audio)
            return True
        started = time.monotonic()
        log.info("[TTS] Synthesizing: '%s'", text[:80])
        # The backend owns its voice/settings-aware cache. Consult it each time
        # so a repeated sentence can immediately use a newly selected voice.
        # Only fixed local Piper audio is cached in this process.
        try:
            wav_bytes = self._client.synthesize_speech(
                text=text,
                language=language[:2],
                speed_wpm=self._speed_wpm,
            )
            if wav_bytes:
                log.info("[LATENCY] TTS to playback: %.3fs", time.monotonic() - started)
                play_wav(wav_bytes)
                return True
        except Exception as exc:
            log.debug("[TTS] Backend synthesis not available: %s", exc)

        # 2. Direct OpenAI TTS Fallback
        if self._openai_api_key:
            try:
                from openai import OpenAI
                client = OpenAI(api_key=self._openai_api_key)
                res = client.audio.speech.create(
                    model="tts-1",
                    voice="coral",
                    input=text,
                    response_format="wav",
                )
                wav_bytes = res.read()
                if wav_bytes:
                    log.info("[LATENCY] TTS to playback: %.3fs", time.monotonic() - started)
                    play_wav(wav_bytes)
                    return True
            except Exception as exc:
                log.warning("[TTS] Direct OpenAI synthesis failed: %s", exc)

        log.warning("[TTS] Unable to synthesize audio through any provider")
        return False

    def _remember(self, key, audio):
        # Bound memory even for long recordings (16 MiB total).
        if len(audio) > 16 * 1024 * 1024:
            return
        self._audio_cache[key] = audio
        while sum(map(len, self._audio_cache.values())) > 16 * 1024 * 1024:
            self._audio_cache.popitem(last=False)

    def speak_fallback(self, text: str) -> None:
        """Best-effort speech: try backend TTS, fall back to console print."""
        for sentence in re.split(r"(?<=[.!?])\s+", text.strip()):
            if sentence and not self.speak(sentence):
                log.info("[TTS-FALLBACK] Printing instead: %s", sentence)
                print(f"\n🔊 Robot says: {sentence}\n")
