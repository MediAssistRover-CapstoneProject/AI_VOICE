import httpx
import pytest
from src.rag.knowledge_resolver import KnowledgeResolver


def test_service_contract_and_no_redirect(monkeypatch):
    monkeypatch.setenv('BACKEND_URL', 'https://backend.example')
    monkeypatch.setenv('KNOWLEDGE_SERVICE_TOKEN', 'x' * 32)
    resolver = KnowledgeResolver()
    def handle(request):
        assert request.url.path == '/api/v1/internal/knowledge/resolve'
        assert request.headers['X-Knowledge-Token'] == 'x' * 32
        return httpx.Response(200, json=[dict(fileId='file-test', pipelineVersion=2, text='Nghỉ Chủ nhật.')])
    resolver._http = httpx.Client(transport=httpx.MockTransport(handle))
    assert resolver.resolve('vs_test', ['file-test'])['file-test']['text'] == 'Nghỉ Chủ nhật.'
    resolver.close()


def test_missing_configuration_and_wrong_file_fail_closed(monkeypatch):
    monkeypatch.delenv('BACKEND_URL', raising=False)
    monkeypatch.delenv('KNOWLEDGE_SERVICE_TOKEN', raising=False)
    with pytest.raises(RuntimeError): KnowledgeResolver().resolve('vs_test', ['file-test'])
    monkeypatch.setenv('BACKEND_URL', 'https://backend.example')
    monkeypatch.setenv('KNOWLEDGE_SERVICE_TOKEN', 'x' * 32)
    resolver = KnowledgeResolver()
    resolver._http = httpx.Client(transport=httpx.MockTransport(lambda request: httpx.Response(200,
        json=[dict(fileId='file-other',pipelineVersion=2,text='Wrong hospital')])) )
    with pytest.raises(ValueError): resolver.resolve('vs_test', ['file-test'])
    resolver.close()


def test_version_three_item_is_accepted(monkeypatch):
    monkeypatch.setenv('BACKEND_URL', 'https://backend.example')
    monkeypatch.setenv('KNOWLEDGE_SERVICE_TOKEN', 'x' * 32)
    resolver = KnowledgeResolver()
    resolver._http = httpx.Client(transport=httpx.MockTransport(lambda request: httpx.Response(200,
        json=[dict(fileId='file-new', pipelineVersion=3, text='Giờ khám: 08:00–18:00')])))
    assert resolver.resolve('vs_test', ['file-new'])['file-new']['pipelineVersion'] == 3
    resolver.close()
