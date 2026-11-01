"""Text-to-Speech (TTS) integration for MediAssistRover."""
from __future__ import annotations

import logging
from collections import OrderedDict
from typing import Any

from src.audio.player import play_wav

log = logging.getLogger("mar_voice")


class SpeechClient:
    """Client for converting text into speech audio.

    Tries the Backend REST API speech synthesis first (Vietnamese neural voice).
    Falls back to direct OpenAI Audio API if configured, then local logging.
    """

    def __init__(
        self,
        backend_client: Any,
        speed_wpm: int = 150,
        openai_api_key: str | None = None,
    ):
        self._client = backend_client
        self._speed_wpm = speed_wpm
        self._openai_api_key = openai_api_key
        self._audio_cache = OrderedDict()

    def speak(self, text: str, language: str = "vi", block: bool = True) -> bool:
        """Synthesize *text* and play through the local speaker.

        Returns True if speech was successfully generated and played.
        """
        if not text or not text.strip():
            return False

        key = (text, language, self._speed_wpm)
        if key in self._audio_cache:
            self._audio_cache.move_to_end(key)
            play_wav(self._audio_cache[key])
            return True
        log.info("[TTS] Synthesizing: '%s'", text[:80])
        # 1. Try Backend Speech Synthesis first
        try:
            wav_bytes = self._client.synthesize_speech(
                text=text,
                language=language,
                speed_wpm=self._speed_wpm,
            )
            if wav_bytes:
                self._remember(key, wav_bytes)
                play_wav(wav_bytes)
                return True
        except Exception as exc:
            log.warning("[TTS] Backend synthesis failed: %s", exc)

        # 2. Direct OpenAI TTS fallback
        if self._openai_api_key:
            try:
                from openai import OpenAI

                client = OpenAI(api_key=self._openai_api_key)
                res = client.audio.speech.create(
                    model="tts-1",
                    voice="alloy",
                    input=text,
                )
                wav_bytes = res.read()
                if wav_bytes:
                    self._remember(key, wav_bytes)
                    play_wav(wav_bytes)
                    return True
            except Exception as exc:
                log.warning("[TTS] OpenAI direct synthesis failed: %s", exc)

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
        if not self.speak(text):
            log.info("[TTS-FALLBACK] Printing instead: %s", text)
            print(f"\n🔊 Robot says: {text}\n")
