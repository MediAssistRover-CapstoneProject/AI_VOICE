"""Admin voice updates must remain visible through the Python speech adapter."""
import sys
from types import SimpleNamespace
from unittest.mock import Mock, call, patch

from src.tts import SpeechClient


def test_repeated_backend_utterance_plays_updated_voice(monkeypatch):
    monkeypatch.delenv('TTS_PROVIDER', raising=False)
    backend = Mock()
    backend.synthesize_speech.side_effect = [b'old-voice-wav', b'new-voice-wav']
    speech = SpeechClient(backend)
    with patch('src.tts.play_wav') as play:
        assert speech.speak('Xin chào', 'vi-VN')
        assert speech.speak('Xin chào', 'vi-VN')
    assert backend.synthesize_speech.call_count == 2
    assert play.call_args_list == [call(b'old-voice-wav'), call(b'new-voice-wav')]
    assert not speech._audio_cache


def test_local_piper_reuses_audio_without_contacting_backend(monkeypatch):
    monkeypatch.setenv('TTS_PROVIDER', 'piper')
    backend = Mock()
    with patch('src.local_providers.PiperSpeech') as piper, patch('src.tts.play_wav') as play:
        piper.return_value.synthesize.return_value = b'local-wav'
        speech = SpeechClient(backend)
        assert speech.speak('Xin chào')
        assert speech.speak('Xin chào')
        piper.return_value.synthesize.assert_called_once_with('Xin chào')
        assert play.call_args_list == [call(b'local-wav'), call(b'local-wav')]
    backend.synthesize_speech.assert_not_called()


def test_direct_fallback_does_not_hide_recovered_backend_voice(monkeypatch):
    monkeypatch.delenv('TTS_PROVIDER', raising=False)
    backend = Mock()
    backend.synthesize_speech.side_effect = [None, b'admin-voice-wav']
    direct = Mock()
    direct.audio.speech.create.return_value.read.return_value = b'fallback-wav'
    constructor = Mock(return_value=direct)
    speech = SpeechClient(backend, openai_api_key='test-only')
    with patch.dict(sys.modules, {'openai': SimpleNamespace(OpenAI=constructor)}), patch('src.tts.play_wav') as play:
        assert speech.speak('Xin chào')
        assert speech.speak('Xin chào')
    assert backend.synthesize_speech.call_count == 2
    direct.audio.speech.create.assert_called_once()
    assert play.call_args_list == [call(b'fallback-wav'), call(b'admin-voice-wav')]
    assert not speech._audio_cache
