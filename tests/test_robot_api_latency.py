"""Offline regressions: exact FAQ, pooled client, timings and immutable turn replay."""
from types import SimpleNamespace as NS
from unittest.mock import Mock, patch
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from src.model_client import ReusableOpenAIClient
from src.rag.rag_pipeline import HospitalRAGPipeline
from src.rag.vector_store import HospitalVectorStore
from src.rag.embeddings import LocalOfflineEmbeddings
from src.rag.document_loader import Document


def make_store():
    store = HospitalVectorStore(LocalOfflineEmbeddings(dimensions=384))
    store.add_documents([Document('Giờ khám từ 7 giờ đến 17 giờ.',
        {'source': 'reviewed-fixture', 'approved': True, 'visibility': 'PUBLIC', 'questions': ['Giờ khám?']})])
    return store


def test_approved_exact_faq_with_history_never_searches_or_generates():
    store = make_store()
    rag = HospitalRAGPipeline(store)
    with patch.object(store, 'similarity_search') as search, patch.object(rag, '_generate_answer') as generate:
        result = rag.answer_question('  GIỜ khám? ', history=[{'role': 'user', 'content': 'Xin chào'}])
    assert result['answer'] == store.points[0].text
    assert result['answer_mode'] == 'approved_faq'
    assert 'generation_ms' not in result['timings_ms']
    search.assert_not_called()
    generate.assert_not_called()


@pytest.mark.parametrize('metadata', [dict(approved=False), dict(visibility='PRIVATE'), dict(source='')])
def test_unreviewed_or_private_faq_cannot_use_shortcut(metadata):
    store = make_store()
    store.points[0].metadata.update(metadata)
    rag = HospitalRAGPipeline(store, llm_provider='offline', score_threshold=-1)
    with patch.object(rag, '_generate_answer', return_value='Generated answer') as generate:
        result = rag.answer_question('Giờ khám?', history=[{'role': 'user', 'content': 'Chào'}])
    generate.assert_called_once()
    assert result['answer_mode'] == 'generated'


def test_answer_cache_metrics_describe_current_call_and_history_isolated():
    store = make_store()
    rag = HospitalRAGPipeline(store, llm_provider='offline', score_threshold=-1)
    with patch.object(rag, '_generate_answer', return_value='Câu trả lời') as generate:
        first = rag.answer_question('Thời gian làm việc?')
        second = rag.answer_question('Thời gian làm việc?')
        assert first['answer_mode'] == 'generated'
        assert second['answer_mode'] == 'answer_cache'
        assert 'generation_ms' not in second['timings_ms']
        rag.answer_question('Thời gian làm việc?', history=[{'role': 'user', 'content': 'Cấp cứu?'}])
        assert generate.call_count == 2
        store.points[0].metadata['approved'] = False
        rag.answer_question('Thời gian làm việc?')
        assert generate.call_count == 3


def test_provider_client_reused_and_closed_without_network():
    with patch('openai.OpenAI') as create:
        pool = ReusableOpenAIClient('test-key', timeout=20)
        first = pool.get()
        assert pool.get() is first
        create.assert_called_once_with(api_key='test-key', timeout=20, max_retries=0)
        pool.close()
        first.close.assert_called_once()
        pool.close()
        assert pool.get() is create.return_value
        assert create.call_count == 2


def test_preload_client_constructs_pool_without_generation():
    with patch('openai.OpenAI') as create:
        rag = HospitalRAGPipeline(make_store(), openai_api_key='test-key')
        rag.preload_client()
        assert rag._openai.initialized
        create.return_value.chat.completions.create.assert_not_called()
        rag.close()


def test_wake_preload_loads_once_and_does_not_decode(tmp_path, monkeypatch):
    from src.web import wake
    import sys
    for filename in wake.MODEL_FILES.values():
        (tmp_path/filename).write_bytes(b'fixture')
    monkeypatch.setenv('AI_WAKE_ENABLED', 'true')
    monkeypatch.setenv('AI_WAKE_MODEL_PATH', str(tmp_path))
    monkeypatch.setenv('AI_WAKE_PHRASE', 'Chào cậu')
    monkeypatch.setattr(wake, '_recognizer', None)
    monkeypatch.setattr(wake, '_model_path', None)
    constructor = Mock()
    recognizer = constructor.from_transducer.return_value
    recognizer.create_stream.return_value.result.text = 'Chào cậu'
    with patch.dict(sys.modules, {'sherpa_onnx': NS(OfflineRecognizer=constructor)}):
        assert wake.preload()['loaded'] is True
        recognizer.decode_stream.assert_not_called()
        assert wake.detect(b'\xe8\x03'*16000)['detected'] is True
        constructor.from_transducer.assert_called_once()
        recognizer.decode_stream.assert_called_once()


def test_letter_wake_tolerates_tone_only_when_enabled_and_never_matches_a_prefix(monkeypatch):
    from src.web import wake
    monkeypatch.setenv('AI_WAKE_IGNORE_TONE', 'true')
    expected = {wake.matching_key(value) for value in ('M A', 'em a', 'mờ a')}
    for text in ('M A', 'EM À', 'mờ a'):
        assert wake.matching_key(text) in expected
    for text in ('AN A', 'điểm A', 'em a dẫn tôi đi', ''):
        assert wake.matching_key(text) not in expected
    monkeypatch.setenv('AI_WAKE_IGNORE_TONE', 'false')
    assert wake.matching_key('EM À') != wake.matching_key('em a')


def test_wake_empty_recognition_has_correlated_metrics_without_retaining_transcript(tmp_path, monkeypatch):
    from src.web import wake
    monkeypatch.setenv('AI_WAKE_ENABLED', 'true')
    monkeypatch.setenv('AI_WAKE_MODEL_PATH', str(tmp_path))
    recognizer = Mock()
    recognizer.create_stream.return_value.result.text = ''
    monkeypatch.setattr(wake, '_load_recognizer', lambda _: recognizer)
    request_id = str(uuid4())
    result = wake.detect(b'\xe8\x03' * 16000, request_id)
    assert result['reason'] == 'EMPTY_RECOGNITION'
    assert result['request_id'] == request_id
    assert result['queue_ms'] >= 0 and result['decode_ms'] >= 0
    assert 'text' not in result and 'text' not in wake.status()['last_decision']


def test_wake_api_preserves_correlation_and_rejects_invalid_pcm(client, monkeypatch):
    from src.web import wake
    http, _ = client
    monkeypatch.setenv('AI_WAKE_ENABLED', 'true')
    request_id = str(uuid4())
    headers = dict(HEADERS, **{'Content-Type': 'application/octet-stream',
                               'X-Audio-Format': 'pcm_s16le_16000_mono', 'X-Request-ID': request_id})
    # Silence exits before loading model weights, but still has full diagnostics.
    response = http.post('/api/robot/wake', content=b'\0\0' * 16000, headers=headers)
    assert response.status_code == 200
    assert response.headers['X-Request-ID'] == response.json()['request_id'] == request_id
    assert response.json()['reason'] == 'LOW_AUDIO_ENERGY'
    assert response.headers['Cache-Control'] == 'no-store'
    assert http.post('/api/robot/wake', content=b'\0', headers=headers).status_code == 422


def test_wake_cancel_keeps_single_inference_lease_until_worker_finishes(monkeypatch):
    import asyncio
    import threading
    from src.web import robot_api as api, wake
    from starlette.requests import Request
    entered, release = threading.Event(), threading.Event()
    capacity = api.threading.BoundedSemaphore(1)
    monkeypatch.setattr(api, 'wake_capacity', capacity)
    monkeypatch.setattr(api, 'authorize', lambda _: None)
    def detect(*_):
        entered.set()
        assert release.wait(2)
        return dict(request_id=str(uuid4()), decode_ms=1)
    monkeypatch.setattr(wake, 'detect', detect)
    async def scenario():
        async def receive():
            return dict(type='http.request',body=b'\0\0'*16000,more_body=False)
        request=Request(dict(type='http',method='POST',path='/api/robot/wake',
                             headers=[(b'content-type',b'application/octet-stream'),
                                      (b'x-audio-format',b'pcm_s16le_16000_mono')]), receive)
        task=asyncio.create_task(api.wake(request))
        try:
            for _ in range(100):
                if entered.is_set():break
                await asyncio.sleep(.005)
            assert entered.is_set()
            task.cancel()
            with pytest.raises(asyncio.CancelledError):await task
            assert not capacity.acquire(blocking=False)
        finally:
            release.set()
        for _ in range(100):
            if capacity.acquire(blocking=False):
                capacity.release()
                return
            await asyncio.sleep(.005)
        raise AssertionError('Wake capacity was not released')
    asyncio.run(scenario())


def test_rag_generation_reuses_provider_client_between_different_questions():
    with patch('openai.OpenAI') as create:
        create.return_value.chat.completions.create.return_value = NS(choices=[NS(message=NS(content='Trả lời.'))])
        rag = HospitalRAGPipeline(make_store(), openai_api_key='test-key')
        assert rag._generate_answer('Câu 1', 'Ngữ cảnh') == 'Trả lời.'
        assert rag._generate_answer('Câu 2', 'Ngữ cảnh') == 'Trả lời.'
        assert create.call_count == 1
        assert create.return_value.chat.completions.create.call_count == 2
        rag.close()


@pytest.fixture
def client(monkeypatch):
    from src.web import robot_api as api
    monkeypatch.setenv('AI_ROBOT_API_TOKEN', 'offline-test-token')
    monkeypatch.setenv('OPENAI_API_KEY', 'offline-test-key')
    store = make_store()
    rag = HospitalRAGPipeline(store, llm_provider='offline')
    monkeypatch.setattr(api, 'store', store)
    monkeypatch.setattr(api, 'rag', rag)
    monkeypatch.setattr(api, 'cache', api.OrderedDict())
    with TestClient(api.app) as http:
        yield http, api


def body(text='Giờ khám?'):
    return dict(session_id=str(uuid4()), turn_id=str(uuid4()), text=text,
                history=[dict(role='user', content='Xin chào')])


HEADERS = {'Authorization': 'Bearer offline-test-token'}


def test_api_faq_stage_metrics_and_exact_replay_contract(client):
    http, api = client
    request = body()
    with patch.object(api.rag, '_generate_answer') as generate:
        first = http.post('/api/robot/chat', json=request, headers=HEADERS)
        replay = http.post('/api/robot/chat', json=request, headers=HEADERS)
    assert first.status_code == replay.status_code == 200
    result = first.json()
    assert result == replay.json()  # Original processing_ms/timings remain immutable.
    assert result['answer_mode'] == 'approved_faq'
    assert result['metrics_scope'] == 'ai_server_only'
    assert result['model'] is None
    assert result['action'] is None
    assert 'rag_generation_ms' not in result['timings_ms']
    assert all(value >= 0 for value in result['timings_ms'].values())
    assert 'ai_total;dur=' in replay.headers['Server-Timing']
    assert replay.headers['X-AI-Cache'] == 'turn_replay'
    generate.assert_not_called()
    request['text'] = 'Câu khác'
    assert http.post('/api/robot/chat', json=request, headers=HEADERS).status_code == 409


def test_api_auth_and_busy_are_preserved(client):
    http, api = client
    assert http.post('/api/robot/chat', json=body()).status_code == 401
    assert api.capacity.acquire(blocking=False)
    try:
        assert http.post('/api/robot/chat', json=body(), headers=HEADERS).status_code == 429
    finally:
        api.capacity.release()


def test_speech_stream_prefetches_one_chunk_and_releases_provider_lease(client, monkeypatch):
    http, api = client
    monkeypatch.setenv('AI_DIRECT_SPEECH_ENABLED', 'true')
    monkeypatch.setattr(api.speech_client, 'api_key', 'offline-test-key')
    provider = Mock()
    context = provider.audio.speech.with_streaming_response.create.return_value
    chunks = [b'\x01\x00' * 1024, b'\x02\x00' * 1024]
    observed = []
    def audio():
        for chunk in chunks:
            observed.append(chunk)
            yield chunk
    context.__enter__ = Mock(return_value=NS(iter_bytes=lambda **_: audio()))
    context.__exit__ = Mock(return_value=False)
    monkeypatch.setattr(api.speech_client, 'get', lambda: provider)
    profile = dict(voice='nova', speedMultiplier=1.2, instructions='Read naturally.', revision='be-profile-1')
    body = api.SpeechText(text='Một câu riêng không được cache.', speech_profile=profile)
    assert api.speech_capacity.acquire(blocking=False)
    stream = api.PcmSpeechStream(body, str(uuid4())).open()
    assert observed == chunks[:1]  # HTTP can start before the remainder is generated.
    assert stream.first == chunks[0] and stream.next() == chunks[1]
    assert stream.next() is None
    stream.close()
    stream.close()  # Disconnect cleanup + response background cleanup are idempotent.
    assert api.speech_capacity.acquire(blocking=False)
    api.speech_capacity.release()
    context.__exit__.assert_called_once()
    assert provider.audio.speech.with_streaming_response.create.call_args.kwargs['response_format'] == 'pcm'


def test_speech_stream_auth_profile_validation_and_provider_failure_cleanup(client, monkeypatch):
    http, api = client
    monkeypatch.setenv('AI_DIRECT_SPEECH_ENABLED', 'true')
    monkeypatch.setattr(api.speech_client, 'api_key', 'offline-test-key')
    assert http.post('/api/robot/speech/synthesize/stream', json={'text': 'Chào'}).status_code == 401
    profile = dict(voice='nova', speedMultiplier=1.2, instructions='Read naturally.', revision='be-profile-1')
    payload = dict(text='Chào bạn.', speech_profile=profile)
    provider = Mock()
    context = provider.audio.speech.with_streaming_response.create.return_value
    context.__enter__ = Mock(side_effect=RuntimeError('provider unavailable'))
    context.__exit__ = Mock()
    monkeypatch.setattr(api.speech_client, 'get', lambda: provider)
    response = http.post('/api/robot/speech/synthesize/stream', json=payload, headers=HEADERS)
    assert response.status_code == 502
    assert api.speech_capacity.acquire(blocking=False)
    api.speech_capacity.release()
    payload['speech_profile']['speedMultiplier'] = 8
    assert http.post('/api/robot/speech/synthesize/stream', json=payload, headers=HEADERS).status_code == 422
    assert api.speech_capacity.acquire(blocking=False)
    api.speech_capacity.release()


def test_stream_public_audio_cache_includes_effective_profile_not_only_revision(client, monkeypatch):
    http, api = client
    monkeypatch.setenv('AI_DIRECT_SPEECH_ENABLED', 'true')
    monkeypatch.setattr(api.speech_client, 'api_key', 'offline-test-key')
    monkeypatch.setattr(api, 'public_speech_cache', {})
    provider = Mock()
    context = provider.audio.speech.with_streaming_response.create.return_value
    pcm = b'\x01\x00' * 3000
    context.__enter__ = Mock(return_value=NS(iter_bytes=lambda **_: iter([pcm])))
    context.__exit__ = Mock(return_value=False)
    monkeypatch.setattr(api.speech_client, 'get', lambda: provider)
    payload = dict(text=api.INTRO, speech_profile=dict(voice='nova', speedMultiplier=1.2,
                   instructions='Read naturally.', revision='same-revision'))
    request_id = str(uuid4())
    headers = dict(HEADERS, **{'X-Request-ID': request_id})
    first = http.post('/api/robot/speech/synthesize/stream', json=payload, headers=headers)
    cached = http.post('/api/robot/speech/synthesize/stream', json=payload, headers=HEADERS)
    assert first.status_code == cached.status_code == 200
    assert first.content == cached.content == pcm
    assert first.headers['X-Audio-Format'] == 'pcm_s16le_24000_mono'
    assert first.headers['X-Request-ID'] == request_id
    assert first.headers['X-Speech-Profile-Revision'] == 'same-revision'
    assert cached.headers['X-AI-Cache'] == 'public_speech'
    assert provider.audio.speech.with_streaming_response.create.call_count == 1
    payload['speech_profile']['instructions'] = 'Changed profile instructions.'
    assert http.post('/api/robot/speech/synthesize/stream', json=payload, headers=HEADERS).status_code == 200
    assert provider.audio.speech.with_streaming_response.create.call_count == 2


def test_speech_stream_disconnect_before_body_starts_still_releases_lease(client):
    import asyncio
    from starlette.requests import ClientDisconnect
    _, api = client
    assert api.speech_capacity.acquire(blocking=False)
    stream = api.PcmSpeechStream(api.SpeechText(text='Chào bạn.'), str(uuid4()))
    response = api.SpeechStreamingResponse(stream, media_type='audio/pcm')
    async def disconnected_send(_):
        raise OSError('client disconnected before response headers')
    async def receive():
        return {'type': 'http.disconnect'}
    with pytest.raises(ClientDisconnect):
        asyncio.run(response({'type': 'http', 'asgi': {'spec_version': '2.4'}}, receive, disconnected_send))
    assert stream.closed and not stream.complete
    assert api.speech_capacity.acquire(blocking=False)
    api.speech_capacity.release()


@pytest.mark.parametrize('operation', ['open', 'next'])
def test_cancelled_speech_caller_keeps_lease_until_provider_thread_exits(client, monkeypatch, operation):
    import asyncio
    import threading
    _, api = client
    provider = Mock()
    context = provider.audio.speech.with_streaming_response.create.return_value
    started, release = threading.Event(), threading.Event()
    def blocked():
        started.set()
        assert release.wait(2), 'test did not release provider'
    def audio():
        yield b'\x01\x00' * 1024
        blocked()
        yield b'\x02\x00' * 1024
    def enter():
        if operation == 'open':blocked()
        return NS(iter_bytes=lambda **_: audio())
    context.__enter__ = Mock(side_effect=enter)
    context.__exit__ = Mock(return_value=False)
    monkeypatch.setattr(api.speech_client, 'get', lambda: provider)
    assert api.speech_capacity.acquire(blocking=False)
    stream = api.PcmSpeechStream(api.SpeechText(text='Câu trả lời riêng.'), str(uuid4()))
    if operation == 'next':stream.open()
    async def exercise():
        task = asyncio.create_task(stream.run(getattr(stream, operation)))
        assert await asyncio.to_thread(started.wait, 1)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):await task
        stream.close()
        assert not stream.closed  # A late __enter__/read still owns the lease.
        assert not api.speech_capacity.acquire(blocking=False)
        release.set()
        for _ in range(100):
            if api.speech_capacity.acquire(blocking=False):
                api.speech_capacity.release()
                break
            await asyncio.sleep(.01)
        else:pytest.fail('provider worker did not release its lease')
        assert stream.closed and stream.active == 0
    try:asyncio.run(exercise())
    finally:release.set()
    context.__exit__.assert_called_once()
