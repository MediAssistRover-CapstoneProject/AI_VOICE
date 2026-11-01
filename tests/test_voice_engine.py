"""Tests for VoiceEngine core logic."""
from unittest.mock import MagicMock, patch
from src.intent import IntentResult


def test_intent_dispatch_greeting():
    """Verify that GREETING intent returns a welcome message."""
    from src.voice_engine import VoiceEngine

    with patch.object(VoiceEngine, "__init__", lambda self, **kw: None):
        engine = VoiceEngine.__new__(VoiceEngine)
        engine._greetings = {"welcome": "Xin chào!"}
        engine._escort_prompts = {}
        engine._query = MagicMock()
        engine._config = {"language": "vi-VN"}
        engine._rooms = None
        engine._backend = MagicMock()

        intent = IntentResult("GREETING", 0.95, {}, "Xin chào")
        result = engine._dispatch_intent(intent)
        assert "chào" in result.lower()


def test_intent_dispatch_unknown_fallback():
    """UNKNOWN intent should try hospital query as fallback."""
    from src.voice_engine import VoiceEngine

    with patch.object(VoiceEngine, "__init__", lambda self, **kw: None):
        engine = VoiceEngine.__new__(VoiceEngine)
        engine._greetings = {"not_understood": "Tôi chưa hiểu."}
        engine._escort_prompts = {}
        engine._query = MagicMock()
        engine._query.answer.return_value = "chưa tìm thấy thông tin"
        engine._rag = MagicMock()
        engine._rag.answer_question.return_value = {"confidence": 0, "answer": "Tôi không trả lời thông tin này được."}
        engine._config = {"language": "vi-VN"}
        engine._rooms = None
        engine._backend = MagicMock()

        intent = IntentResult("UNKNOWN", 0.3, {}, "abc")
        result = engine._dispatch_intent(intent)
        assert result == "Tôi không trả lời thông tin này được."
