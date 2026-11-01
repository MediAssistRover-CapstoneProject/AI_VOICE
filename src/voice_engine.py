"""MediAssistRover AI Voice Engine – orchestrates the full voice pipeline.

Pipeline:  Microphone → STT → Intent → [Query/Escort/SOS] → TTS → Speaker

The engine is designed to run on:
- Robot Raspberry Pi (production)
- Developer PC (testing with microphone)
"""
from __future__ import annotations

import json
import logging
import time
from pathlib import Path
from typing import Any

from src.api import BackendClient
from src.api.voice_reporter import VoiceReporter
from src.audio import MicrophoneCapture
from src.intent import IntentClassifier, IntentResult
from src.query import HospitalQueryHandler
from src.rag import HospitalRAGPipeline, HospitalVectorStore, create_embeddings
from src.stt import STTBase, create_stt
from src.tts import SpeechClient

log = logging.getLogger("mar_voice")

CONFIG_PATH = Path(__file__).resolve().parent.parent / "config" / "voice_config.json"
VECTOR_DB_PATH = Path(__file__).resolve().parent.parent / "data" / "hospital_vector_store.json"


class VoiceEngine:
    """Main AI Voice orchestration engine."""

    def __init__(
        self,
        backend_url: str,
        robot_code: str,
        device_secret: str,
        stt_provider: str = "whisper",
        openai_api_key: str = "",
        google_credentials: str | None = None,
        config_path: str | Path | None = None,
    ) -> None:
        # Load configuration
        cfg_file = Path(config_path) if config_path else CONFIG_PATH
        self._config = self._load_config(cfg_file)
        audio_cfg = self._config.get("audio", {})
        intent_cfg = self._config.get("intent", {})
        tts_cfg = self._config.get("tts", {})

        # Backend client
        self._backend = BackendClient(
            base_url=backend_url,
            robot_code=robot_code,
            device_secret=device_secret,
        )

        # Microphone capture
        self._mic = MicrophoneCapture(
            sample_rate=audio_cfg.get("sample_rate", 16000),
            channels=audio_cfg.get("channels", 1),
            chunk_ms=audio_cfg.get("chunk_duration_ms", 30),
            silence_ms=audio_cfg.get("silence_threshold_ms", 1500),
            max_sec=audio_cfg.get("max_recording_sec", 15),
            min_speech_ms=audio_cfg.get("min_speech_duration_ms", 500),
        )

        # STT
        self._stt: STTBase = create_stt(
            provider=stt_provider,
            api_key=openai_api_key,
            credentials_path=google_credentials,
        )

        # Intent classifier
        self._intent = IntentClassifier(
            confidence_threshold=intent_cfg.get("confidence_threshold", 0.6),
            use_llm_fallback=intent_cfg.get("use_llm_fallback", True),
            openai_api_key=openai_api_key,
            llm_model=intent_cfg.get("llm_model", "gpt-4o-mini"),
        )

        # Hospital query handler (Direct MySQL via Backend API)
        self._query = HospitalQueryHandler(self._backend)

        # Hospital RAG Pipeline & Vector Database ('Vector P')
        embeddings = create_embeddings(
            provider=self._config.get("rag", {}).get("embedding_provider", "local"),
            openai_api_key=openai_api_key,
            gemini_api_key=self._config.get("rag", {}).get("gemini_api_key", ""),
        )
        self._vector_store = HospitalVectorStore(
            embeddings=embeddings,
            persist_path=VECTOR_DB_PATH,
        )
        self._rag = HospitalRAGPipeline(
            vector_store=self._vector_store,
            llm_provider=self._config.get("rag", {}).get("llm_provider", "openai" if openai_api_key else "offline"),
            openai_api_key=openai_api_key,
            gemini_api_key=self._config.get("rag", {}).get("gemini_api_key", ""),
            llm_model=intent_cfg.get("llm_model", "gpt-4o-mini"),
        )

        # TTS client
        self._tts = SpeechClient(
            self._backend,
            speed_wpm=tts_cfg.get("speed_wpm", 150),
            openai_api_key=openai_api_key,
        )

        # Voice reporter (logs interactions to backend DB)
        self._reporter = VoiceReporter(self._backend, self._backend.robot_id)

        # Greetings & prompts
        self._greetings = self._config.get("greetings", {})
        self._escort_prompts = self._config.get("escort_prompts", {})

        # Room cache
        self._rooms: list[dict] | None = None

        log.info("[ENGINE] VoiceEngine initialized (STT=%s)", stt_provider)

    @staticmethod
    def _load_config(path: Path) -> dict:
        if path.exists():
            with open(path, encoding="utf-8") as f:
                return json.load(f)
        log.warning("[ENGINE] Config not found at %s, using defaults", path)
        return {}

    # ──────────────────────────────────────────────────────────────────
    # Main conversation loop
    # ──────────────────────────────────────────────────────────────────

    def run(self) -> None:
        """Run the interactive voice assistant loop (blocking)."""
        log.info("=" * 60)
        log.info("[ENGINE] 🤖 MediAssistRover AI Voice Assistant")
        log.info("[ENGINE] Say something to the robot or press Ctrl+C to exit")
        log.info("=" * 60)

        # Welcome message
        welcome = self._greetings.get(
            "welcome",
            "Xin chào! Tôi là robot hỗ trợ bệnh viện. Bạn cần tôi giúp gì ạ?"
        )
        self._tts.speak_fallback(welcome)

        while True:
            try:
                self._handle_one_turn()
            except KeyboardInterrupt:
                farewell = self._greetings.get("farewell", "Tạm biệt!")
                self._tts.speak_fallback(farewell)
                break
            except Exception as exc:
                log.error("[ENGINE] Unexpected error: %s", exc, exc_info=True)
                error_msg = self._greetings.get("error", "Xin lỗi, hệ thống gặp sự cố.")
                self._tts.speak_fallback(error_msg)
                time.sleep(2)

        self._backend.close()
        log.info("[ENGINE] Voice engine stopped")

    def _handle_one_turn(self) -> None:
        """Process one user utterance: listen → understand → respond."""
        # 1. Record audio
        wav_bytes = self._mic.record_until_silence()
        if wav_bytes is None:
            return  # No speech detected, continue listening

        # 3. Speech-to-Text
        text = self._stt.transcribe(wav_bytes, self._config.get("language", "vi-VN"))
        if not text:
            self._tts.speak_fallback(
                self._greetings.get("not_understood", "Xin lỗi, tôi chưa nghe rõ.")
            )
            return

        log.info("[ENGINE] 🗣  Patient said: '%s'", text)

        # 4. Classify intent
        intent = self._intent.classify(text)
        log.info("[ENGINE] 🎯 Intent: %s (conf=%.2f)", intent.intent, intent.confidence)

        # 6. Handle intent
        response_text = self._dispatch_intent(intent)

        # 7. Speak response
        if response_text:
            self._tts.speak_fallback(response_text)

        # Reporting must not delay the audible answer; keep events ordered.
        self._reporter.start_interaction(self._config.get("language", "vi-VN"))
        # 5. Record command in backend
        self._reporter.record_command(
            raw_text=text,
            intent_code=intent.intent,
            command_type="VOICE_QUERY",
            confidence=intent.confidence,
            parameters=intent.entities,
        )

        # 8. Complete interaction
        self._reporter.complete_interaction(
            "COMPLETED", intent.intent, f"Q: {text[:100]} | A: {(response_text or '')[:100]}"
        )

    # ──────────────────────────────────────────────────────────────────
    # Intent dispatch
    # ──────────────────────────────────────────────────────────────────

    def _dispatch_intent(self, intent: IntentResult) -> str:
        """Route an intent to the appropriate handler and return response text."""
        handlers = {
            "GREETING": self._handle_greeting,
            "FAREWELL": self._handle_farewell,
            "SELF_INTRO": self._handle_self_intro,
            "QUERY_HOSPITAL_INFO": self._handle_query,
            "ESCORT_REQUEST": self._handle_escort,
            "EMERGENCY_SOS": self._handle_sos,
        }
        handler = handlers.get(intent.intent, self._handle_unknown)
        return handler(intent)

    def _handle_greeting(self, intent: IntentResult) -> str:
        return self._greetings.get(
            "welcome",
            "Xin chào! Tôi là robot hỗ trợ bệnh viện MediAssist. Bạn cần tôi giúp gì ạ?"
        )

    def _handle_farewell(self, intent: IntentResult) -> str:
        return self._greetings.get(
            "farewell",
            "Chúc bạn sức khỏe! Nếu cần hỗ trợ, hãy gọi tôi bất cứ lúc nào."
        )

    def _handle_self_intro(self, intent: IntentResult) -> str:
        return (
            "Tôi là MediAssist Rover, robot trợ lý bệnh viện thông minh. "
            "Tôi có thể giúp bạn tra cứu thông tin bệnh viện, dẫn đường đến các phòng khám, "
            "hoặc gọi cấp cứu khi cần thiết. Bạn muốn tôi giúp gì ạ?"
        )

    def _handle_query(self, intent: IntentResult) -> str:
        """Query hospital information using RAG Vector DB ('Vector P') + Backend Fallback."""
        thinking = self._greetings.get("thinking", "Vui lòng chờ trong giây lát.")
        log.info("[ENGINE] %s", thinking)

        # 1. Primary: Hospital RAG Pipeline with Vector Database ('Vector P')
        try:
            rag_result = self._rag.answer_question(intent.raw_text)
            if rag_result.get("confidence", 0.0) >= 0.15 and "chưa tìm thấy" not in rag_result.get("answer", "").lower():
                log.info("[ENGINE] Answered via RAG Vector P (conf=%.3f)", rag_result["confidence"])
                return rag_result["answer"]
        except Exception as exc:
            log.warning("[ENGINE] RAG query failed: %s, falling back to direct MySQL", exc)

        return "Tôi không trả lời thông tin này được."

    def _handle_escort(self, intent: IntentResult) -> str:
        """Handle patient escort requests."""
        destination = intent.entities.get("destination_room")
        if not destination:
            return (
                "Bạn muốn đi đến phòng nào ạ? Bệnh viện có Phòng khám Nội, "
                "Phòng khám Ngoại, Nhà thuốc, Phòng Xét nghiệm, Phòng Cấp cứu, "
                "và Quầy Tiếp đón."
            )

        # Map room code to display name
        room_names = {
            "P_CAP_CUU": "Phòng Cấp cứu",
            "P_KHAM_NOI_01": "Phòng khám Nội 01",
            "P_KHAM_NGOAI_02": "Phòng khám Ngoại 02",
            "P_NHA_THUOC": "Nhà thuốc bệnh viện",
            "P_XET_NGHIEM": "Phòng Xét nghiệm",
            "P_TIEP_DON": "Quầy Tiếp đón",
        }
        display_name = room_names.get(destination, destination)

        # Try to create an escort request via the backend
        try:
            rooms = self._get_rooms()
            target_room = next(
                (r for r in rooms if r.get("roomCode") == destination), None
            )
            if target_room and target_room.get("endpointId"):
                # TODO: Get actual mapId from the backend context
                log.info("[ENGINE] Creating escort request to %s", destination)
                confirm = self._escort_prompts.get("confirm", "").format(
                    destination=display_name
                )
                return confirm or f"Vâng, tôi sẽ dẫn bạn đến {display_name}. Vui lòng đi theo tôi."
        except Exception as exc:
            log.warning("[ENGINE] Escort request failed: %s", exc)

        return (
            f"Bạn muốn đến {display_name} phải không ạ? "
            f"Vui lòng đi theo tôi, tôi sẽ dẫn bạn đến đó."
        )

    def _handle_sos(self, intent: IntentResult) -> str:
        """Handle emergency SOS – critical priority."""
        log.warning("[ENGINE] ⚠️ EMERGENCY SOS TRIGGERED by voice: '%s'", intent.raw_text)
        # The actual SOS incident creation should happen via PiVoiceController
        # or the existing SOS mechanism on the Robot App
        return (
            "Đã kết nối với đội cấp cứu! Xin giữ bình tĩnh, "
            "đội ngũ y tế đang trên đường đến hỗ trợ bạn. "
            "Tôi sẽ ở đây cùng bạn cho đến khi nhân viên y tế đến."
        )

    def _handle_unknown(self, intent: IntentResult) -> str:
        return self._handle_query(intent)

    def _get_rooms(self) -> list[dict]:
        """Fetch and cache the room list."""
        if self._rooms is None:
            try:
                self._rooms = self._backend.get_rooms()
            except Exception:
                self._rooms = []
        return self._rooms


    # ──────────────────────────────────────────────────────────────────
    # Text-only mode (for testing without microphone)
    # ──────────────────────────────────────────────────────────────────

    def run_text_mode(self) -> None:
        """Run in text-input mode for development/testing."""
        log.info("=" * 60)
        log.info("[ENGINE] 🤖 MediAssistRover AI Voice (TEXT MODE)")
        log.info("[ENGINE] Type your question or 'quit' to exit")
        log.info("=" * 60)

        welcome = self._greetings.get("welcome", "Xin chào!")
        print(f"\n🔊 Robot: {welcome}\n")

        while True:
            try:
                user_input = input("🗣  Bạn: ").strip()
                if not user_input:
                    continue
                if user_input.lower() in ("quit", "exit", "q", "thoát"):
                    farewell = self._greetings.get("farewell", "Tạm biệt!")
                    print(f"\n🔊 Robot: {farewell}\n")
                    break

                # Start interaction
                self._reporter.start_interaction(self._config.get("language", "vi-VN"))

                # Classify intent
                intent = self._intent.classify(user_input)
                log.info("[ENGINE] 🎯 Intent: %s (conf=%.2f)", intent.intent, intent.confidence)

                # Record command
                self._reporter.record_command(
                    raw_text=user_input,
                    intent_code=intent.intent,
                    command_type="TEXT_QUERY",
                    confidence=intent.confidence,
                    parameters=intent.entities,
                )

                # Handle intent
                response = self._dispatch_intent(intent)
                print(f"\n🔊 Robot: {response}\n")

                # Complete interaction
                self._reporter.complete_interaction("COMPLETED", intent.intent)

            except KeyboardInterrupt:
                print("\n\n🔊 Robot: Tạm biệt!\n")
                break
            except Exception as exc:
                log.error("[ENGINE] Error: %s", exc, exc_info=True)
                print(f"\n🔊 Robot: Xin lỗi, đã xảy ra lỗi. ({exc})\n")

        self._backend.close()
