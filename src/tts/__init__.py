"""TTS client – synthesizes speech via the Backend's OpenAI TTS service."""
from __future__ import annotations

import logging
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

    def speak(self, text: str, language: str = "vi", block: bool = True) -> bool:
        """Synthesize *text* and play through the local speaker.

        Returns True if audio was successfully played.
        """
        if not text or not text.strip():
            return False

        log.info("[TTS] Synthesizing: '%s'", text[:80])
        # 1. Try Backend Speech Synthesis first
        try:
            wav_bytes = self._client.synthesize_speech(
                text=text,
                language=language[:2],
                speed_wpm=self._speed_wpm,
            )
            if wav_bytes:
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
                    play_wav(wav_bytes)
                    return True
            except Exception as exc:
                log.warning("[TTS] Direct OpenAI synthesis failed: %s", exc)

        log.warning("[TTS] Unable to synthesize audio through any provider")
        return False

    def speak_fallback(self, text: str) -> None:
        """Best-effort speech: try backend TTS, fall back to console print."""
        if not self.speak(text):
            log.info("[TTS-FALLBACK] Printing instead: %s", text)
            print(f"\n🔊 Robot says: {text}\n")
