"""Core hosted-knowledge flows, with all provider calls mocked."""
from types import SimpleNamespace as NS
from unittest.mock import Mock, patch
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from src.rag.rag_pipeline import HospitalRAGPipeline
from src.rag.vector_store import HospitalVectorStore
from src.rag.embeddings import LocalOfflineEmbeddings

STORE = 'vs_6af8249d9d44819182d2ad94213eacfa'
HEADERS = {'Authorization': 'Bearer knowledge-test-token'}


@pytest.fixture
def pipeline():
    store = HospitalVectorStore(LocalOfflineEmbeddings(dimensions=384))
    rag = HospitalRAGPipeline(store, openai_api_key='mock-key', llm_model='gpt-4o-mini',
                              require_model=True, openai_vector_store_id=STORE)
    client = Mock()
    client.with_options.return_value = client
    client.post.return_value = {'data': [{'file_id': 'file-test', 'filename': 'document.txt',
        'attributes': {'title': 'Giờ khám.txt'}, 'score': .9,
        'content': [{'type': 'text', 'text': 'Giờ khám từ 7 giờ đến 17 giờ.'}]}]}
    client.chat.completions.create.return_value = NS(choices=[NS(message=NS(content='Giờ khám từ 7 giờ đến 17 giờ.'))])
    with patch.object(rag._openai, 'get', return_value=client):
        yield rag, client


def test_search_and_generation_use_store_and_return_sources_without_answer_cache(pipeline):
    rag, client = pipeline
    first = rag.answer_question('Giờ khám?')
    rag.answer_question('Giờ khám?')
    assert client.post.call_count == 2
    assert client.post.call_args.args[0] == '/vector_stores/' + STORE + '/search'
    assert client.post.call_args.kwargs['options']['headers']['OpenAI-Beta'] == 'assistants=v2'
    assert first['answer_mode'] == 'vector_store'
    assert first['sources'][0]['file_id'] == 'file-test'
    assert first['sources'][0]['source'] == 'Giờ khám.txt'
    assert 'Giờ khám từ 7 giờ' in client.chat.completions.create.call_args.kwargs['messages'][0]['content']


def test_no_context_and_provider_failure_are_explicit(pipeline):
    rag, client = pipeline
    client.post.return_value = {'data': []}
    assert rag.answer_question('Giờ khám?')['answer_mode'] == 'no_context'
    client.chat.completions.create.assert_not_called()
    client.post.side_effect = RuntimeError('private upstream response')
    with pytest.raises(RuntimeError, match='^AI_KNOWLEDGE_UNAVAILABLE$'):
        rag.answer_question('Giờ khám?')


@pytest.fixture
def api_client(monkeypatch, pipeline):
    from src.web import robot_api as api
    rag, provider = pipeline
    monkeypatch.setenv('AI_ROBOT_API_TOKEN', 'knowledge-test-token')
    monkeypatch.setenv('OPENAI_API_KEY', 'mock-key')
    monkeypatch.setenv('AI_PRELOAD_CLIENTS', 'false')
    monkeypatch.setenv('AI_WAKE_PRELOAD', 'false')
    monkeypatch.setattr(api, 'vector_store_id', STORE)
    monkeypatch.setattr(api, 'published', None)
    monkeypatch.setattr(api, 'rag', rag)
    monkeypatch.setattr(api, 'store', rag.vector_store)
    monkeypatch.setattr(api, 'faq_store', None)
    monkeypatch.setattr(api, 'cache', api.OrderedDict())
    with TestClient(api.app) as http:
        yield http, provider


def question(text):
    return dict(session_id=str(uuid4()), turn_id=str(uuid4()), text=text,
                hospital_information_id=str(uuid4()), destinations=[])


def test_robot_answer_tts_contract_replay_and_health(api_client):
    http, provider = api_client
    request = question('Giờ khám?')
    first = http.post('/api/robot/chat', json=request, headers=HEADERS)
    assert first.status_code == 200
    result = first.json()
    assert result['speech_text'] == result['answer']
    assert 0 < len(result['speech_text']) <= 500
    assert result['sources'][0]['file_id'] == 'file-test'
    assert result['action'] is None
    assert http.post('/api/robot/chat', json=request, headers=HEADERS).json() == result
    provider.post.assert_called_once()
    assert http.get('/health').json()['state'] == 'READY'
    provider.post.side_effect = RuntimeError('provider unavailable')
    assert http.post('/api/robot/chat', json=question('Giờ khám?'), headers=HEADERS).status_code == 503


def test_navigation_sos_and_auth_keep_existing_routes(api_client):
    http, provider = api_client
    assert http.post('/api/robot/chat', json=question('Giờ khám?')).status_code == 401
    destination = str(uuid4())
    request = question('Đưa tôi đến điểm A')
    request['destinations'] = [dict(endpointId=destination, endpointCode='A', endpointName='A')]
    result = http.post('/api/robot/chat', json=request, headers=HEADERS).json()
    assert result['action']['endpoint_id'] == destination
    result = http.post('/api/robot/chat', json=question('cứu tôi'), headers=HEADERS).json()
    assert result['intent'] == 'EMERGENCY_SOS'
    assert result['action'] is None
    provider.post.assert_not_called()
