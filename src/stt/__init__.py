"""Speech-to-Text abstraction and implementations."""
from __future__ import annotations

import abc
import io
import logging
from pathlib import Path

log = logging.getLogger("mar_voice")


class STTBase(abc.ABC):
    """Abstract Speech-to-Text interface."""

    @abc.abstractmethod
    def transcribe(self, wav_bytes: bytes, language: str = "vi") -> str | None:
        """Convert WAV audio bytes to text. Returns None on failure."""


class WhisperSTT(STTBase):
    """OpenAI Whisper API for Speech-to-Text with automatic Google fallback."""

    def __init__(self, api_key: str, model: str = "whisper-1") -> None:
        self._api_key = api_key
        self._model = model

    def transcribe(self, wav_bytes: bytes, language: str = "vi") -> str | None:
        if self._api_key:
            try:
                from openai import OpenAI
                client = OpenAI(api_key=self._api_key)

                audio_file = io.BytesIO(wav_bytes)
                audio_file.name = "recording.wav"

                result = client.audio.transcriptions.create(
                    model=self._model,
                    file=audio_file,
                    language=language[:2],  # "vi" from "vi-VN"
                    response_format="text",
                )
                text = result.strip() if isinstance(result, str) else str(result).strip()
                if text:
                    log.info("[STT-Whisper] Transcribed: '%s'", text[:80])
                    return text
            except Exception as exc:
                log.warning("[STT-Whisper] Call failed: %s, trying Google fallback", exc)

        # Fallback to SpeechRecognition (free Web Speech API)
        return _transcribe_google_free(wav_bytes, language)


def _transcribe_google_free(wav_bytes: bytes, language: str = "vi-VN") -> str | None:
    try:
        import speech_recognition as sr
        r = sr.Recognizer()
        with sr.AudioFile(io.BytesIO(wav_bytes)) as source:
            audio_data = r.record(source)
            text = r.recognize_google(audio_data, language="vi-VN")
            if text and text.strip():
                log.info("[STT-GoogleFree] Transcribed: '%s'", text[:80])
                return text.strip()
    except Exception as exc:
        log.warning("[STT-GoogleFree] Transcription failed: %s", exc)
    return None


class GoogleSTT(STTBase):
    """Google Cloud Speech-to-Text v2."""

    def __init__(self, credentials_path: str | None = None, model: str = "latest_long") -> None:
        self._credentials_path = credentials_path
        self._model = model

    def transcribe(self, wav_bytes: bytes, language: str = "vi-VN") -> str | None:
        try:
            from google.cloud import speech

            if self._credentials_path:
                client = speech.SpeechClient.from_service_account_json(self._credentials_path)
            else:
                client = speech.SpeechClient()

            audio = speech.RecognitionAudio(content=wav_bytes)
            config = speech.RecognitionConfig(
                encoding=speech.RecognitionConfig.AudioEncoding.LINEAR16,
                sample_rate_hertz=16000,
                language_code=language,
                model=self._model,
                enable_automatic_punctuation=True,
            )

            response = client.recognize(config=config, audio=audio)
            if response.results:
                text = response.results[0].alternatives[0].transcript.strip()
                log.info("[STT-Google] Transcribed: '%s'", text[:80])
                return text or None
            return None
        except Exception as exc:
            log.error("[STT-Google] Transcription failed: %s", exc)
            return _transcribe_google_free(wav_bytes, language)


def create_stt(provider: str, **kwargs) -> STTBase:
    """Factory to create the appropriate STT implementation."""
    if provider.lower() == "whisper":
        return WhisperSTT(
            api_key=kwargs.get("api_key", ""),
            model=kwargs.get("model", "whisper-1"),
        )
    elif provider.lower() == "google":
        return GoogleSTT(
            credentials_path=kwargs.get("credentials_path"),
            model=kwargs.get("model", "latest_long"),
        )
    else:
        raise ValueError(f"Unknown STT provider: {provider}")
