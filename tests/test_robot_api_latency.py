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
