import io
import wave
from unittest.mock import Mock, patch
import httpx
from src.api import BackendClient


def wav():
    output = io.BytesIO()
    with wave.open(output, 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000); w.writeframes(b'\x00\x00' * 160)
    return output.getvalue()


def test_current_be_binary_wav_is_returned_without_json_decoding():
    client = BackendClient.__new__(BackendClient)
    data = wav()
    client.post = Mock(return_value=httpx.Response(200, content=data, headers={'Content-Type': 'audio/wav'}))
    assert client.synthesize_speech('Xin chao') == data
    assert client.post.call_count == 1


def test_pending_honors_bounded_retry_after_then_plays_ready_wav():
    client = BackendClient.__new__(BackendClient)
    data = wav()
    client.post = Mock(side_effect=[httpx.Response(202, json={'state': 'PENDING'}, headers={'Retry-After': '2'}),
                                    httpx.Response(200, content=data, headers={'Content-Type': 'audio/wav'})])
    with patch('time.sleep') as sleep:
        assert client.synthesize_speech('Xin chao') == data
        sleep.assert_called_once_with(2.0)


def test_non_wave_bytes_are_not_forwarded_to_audio_device():
    client = BackendClient.__new__(BackendClient)
    client.post = Mock(return_value=httpx.Response(200, content=b'not audio', headers={'Content-Type': 'audio/wav'}))
    assert client.synthesize_speech('Xin chao') is None
