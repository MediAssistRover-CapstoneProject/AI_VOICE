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


def test_real_sdk_decodes_vector_search_json():
    import httpx
    from openai import OpenAI
    from src.rag.openai_vector_store import OpenAIVectorStore

    transport = httpx.MockTransport(lambda request: httpx.Response(200, json={'data': []}))
    with OpenAI(api_key='mock-key', http_client=httpx.Client(transport=transport)) as sdk:
        store = OpenAIVectorStore(STORE, NS(get=lambda: sdk))
        store.resolver = Mock(resolve=Mock(return_value={}))
        assert store.search('MAR LANTERN') == ('', [])


@pytest.fixture
def pipeline():
    store = HospitalVectorStore(LocalOfflineEmbeddings(dimensions=384))
    rag = HospitalRAGPipeline(store, openai_api_key='mock-key', llm_model='gpt-4o-mini',
                              require_model=True, openai_vector_store_id=STORE)
    client = Mock()
    rag.hosted_store.resolver = Mock(resolve=Mock(return_value={
        'file-test': dict(fileId='file-test', title='Giờ khám.txt', pipelineVersion=2,
                          text='Giờ khám từ 7 giờ đến 17 giờ.')}))
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
    assert client.post.call_args.kwargs['body']['rewrite_query'] is True
    assert first['answer_mode'] == 'vector_store'
    assert first['sources'][0]['file_id'] == 'file-test'
    assert first['sources'][0]['source'] == 'Giờ khám.txt'
    assert 'Giờ khám từ 7 giờ' in client.chat.completions.create.call_args.kwargs['messages'][0]['content']


def test_version_three_uses_current_backend_text_not_stale_search_snippet(pipeline):
    rag, client = pipeline
    rag.hosted_store.resolver.resolve.return_value = {
        'file-test': dict(fileId='file-test', title='Giờ khám.txt', pipelineVersion=3,
                          text='Giờ khám từ 8 giờ đến 18 giờ.')}
    rag.answer_question('Giờ khám?')
    prompt = client.chat.completions.create.call_args.kwargs['messages'][0]['content']
    assert 'Giờ khám từ 8 giờ đến 18 giờ.' in prompt
    assert 'Giờ khám từ 7 giờ đến 17 giờ.' not in prompt


def test_no_context_and_provider_failure_are_explicit(pipeline):
    rag, client = pipeline
    client.post.return_value = {'data': []}
    assert rag.answer_question('Giờ khám?')['answer_mode'] == 'no_context'
    client.chat.completions.create.assert_not_called()
    client.post.side_effect = RuntimeError('private upstream response')
    with pytest.raises(RuntimeError, match='^AI_KNOWLEDGE_UNAVAILABLE$'):
        rag.answer_question('Giờ khám?')


def test_paraphrased_question_uses_rewritten_search_and_grounded_speech(pipeline):
    rag, client = pipeline
    rag.hosted_store.resolver.resolve.return_value = {
        'file-test': dict(fileId='file-test', title='Liên hệ.pdf', pipelineVersion=2,
                          text='Tổng đài bệnh viện: 1900 1234.')}
    def search(_path, **kwargs):
        assert kwargs['body']['query'] == 'Số điện thoại liên hệ bệnh viện là gì?'
        assert kwargs['body']['rewrite_query'] is True
        return {'data': [{'file_id': 'file-test', 'filename': 'Liên hệ.pdf', 'score': .89,
                          'content': [{'type': 'text', 'text': 'Tổng đài bệnh viện: 1900 1234.'}]}]}
    client.post.side_effect = search
    client.chat.completions.create.return_value = NS(choices=[NS(message=NS(
        content='Bạn có thể gọi tổng đài bệnh viện theo số 1900 1234.'))])
    answer = rag.answer_question('Số điện thoại liên hệ bệnh viện là gì?')
    assert answer['answer'] == 'Bạn có thể gọi tổng đài bệnh viện theo số 1900 1234.'
    assert answer['sources'][0]['source'] == 'Liên hệ.pdf'


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


def test_unpublished_search_results_never_reach_model(pipeline):
    rag, provider = pipeline
    rag.hosted_store.resolver.resolve.return_value = {}
    result = rag.answer_question('Giờ khám?')
    assert result['answer_mode'] == 'no_context' and result['sources'] == []
    provider.chat.completions.create.assert_not_called()


def test_complete_chunk_preserves_condition_beyond_old_character_cutoff(pipeline):
    rag, provider = pipeline
    content = 'Giờ khám 7 giờ. ' * 180 + 'Ngoại lệ: Chủ nhật không tiếp nhận.'
    rag.hosted_store.resolver.resolve.return_value['file-test']['text'] = content
    rag.answer_question('Chủ nhật có khám không?')
    context = provider.chat.completions.create.call_args.kwargs['messages'][0]['content']
    assert 'Ngoại lệ: Chủ nhật không tiếp nhận.' in context
    assert '[Giờ khám.txt]' not in context


def test_withdrawal_during_generation_discards_answer(pipeline):
    rag, provider = pipeline
    published = rag.hosted_store.resolver.resolve.return_value
    rag.hosted_store.resolver.resolve.side_effect = [published, {}]
    result = rag.answer_question('Giờ khám?')
    assert result['answer_mode'] == 'no_context' and not result['sources']


def test_backend_outage_fails_closed(pipeline):
    rag, provider = pipeline
    rag.hosted_store.resolver.resolve.side_effect = RuntimeError('private service data')
    with pytest.raises(RuntimeError, match='^AI_KNOWLEDGE_UNAVAILABLE$'):
        rag.answer_question('Giờ khám?')
    provider.chat.completions.create.assert_not_called()


def test_withdrawn_answer_is_not_replayed(api_client):
    http, provider = api_client
    from src.web import robot_api as api
    request = question('Giờ khám?')
    assert http.post('/api/robot/chat', json=request, headers=HEADERS).json()['answer_mode'] == 'vector_store'
    api.rag.hosted_store.resolver.resolve.return_value = {}
    result = http.post('/api/robot/chat', json=request, headers=HEADERS).json()
    assert result['answer_mode'] == 'no_context'
    assert result['action'] is None and result['sources'] == []


def test_long_or_truncated_generation_does_not_lose_a_condition_in_speech(pipeline):
    rag, provider = pipeline
    provider.chat.completions.create.return_value = NS(choices=[NS(message=NS(content='Giờ khám 7 giờ. ' * 60 + 'Trừ Chủ nhật.'))])
    result = rag.answer_question('Giờ khám?')
    assert result['answer'] == 'Tôi không trả lời thông tin này được.'
    provider.chat.completions.create.return_value = NS(choices=[NS(finish_reason='length',message=NS(content='Giờ khám 7 giờ, trừ'))])
    with pytest.raises(RuntimeError, match='AI_MODEL_UNAVAILABLE'):
        rag.answer_question('Giờ khám?')
