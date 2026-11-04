from unittest.mock import Mock, patch
from src.voice_engine import VoiceEngine
from src.intent import IntentResult
from src.api import BackendClient


def engine():
    e = VoiceEngine.__new__(VoiceEngine)
    e._config = {'voice_actions_enabled': True}
    e._backend = Mock()
    return e


def test_escort_requires_confirmation_and_receipt():
    e = engine()
    e._backend.get_rooms.return_value = [{'roomCode': 'R', 'roomId': 'room', 'roomName': 'Nội'}]
    e._backend.get_room_endpoints.return_value = [{'mapId': 'map', 'endpointId': 'point'}]
    answer = e._handle_escort(IntentResult('ESCORT_REQUEST', 1, {'destination_room': 'R'}, 'dẫn đường'))
    assert 'xác nhận' in answer
    e._backend.request_escort.assert_not_called()
    e._backend.request_escort.return_value = {'request': {'serviceRequestId': 'receipt'}}
    assert 'tiếp nhận' in e._dispatch_intent(IntentResult('UNKNOWN', 1, {}, 'xác nhận'))
    e._backend.request_escort.assert_called_once()


def test_sos_failure_never_claims_help_is_on_the_way():
    e = engine()
    e._backend.request_sos.side_effect = RuntimeError('offline')
    assert 'Chưa xác nhận' in e._handle_sos(IntentResult('EMERGENCY_SOS', 1, {}, 'cấp cứu'))


def test_actions_disabled_by_default():
    e = engine()
    e._config = {}
    e._handle_sos(IntentResult('EMERGENCY_SOS', 1, {}, 'cấp cứu'))
    e._backend.request_sos.assert_not_called()


def test_ca_context_is_passed_to_http_client():
    with patch('src.api.ssl.create_default_context') as context, patch('src.api.httpx.Client') as client:
        BackendClient('https://hospital', 'robot', 'secret', ca_cert_path='hospital.pem')
        context.assert_called_once_with(cafile='hospital.pem')
        assert client.call_args.kwargs['verify'] is context.return_value


def test_semantic_index_cannot_silently_load_hash_vectors(tmp_path):
    import json
    from src.rag.vector_store import HospitalVectorStore
    path = tmp_path / 'index.json'
    path.write_text(json.dumps({'points': []}))
    embedding = Mock(identity='sentence-transformers:test')
    import pytest
    with pytest.raises(ValueError, match='rebuild index'):
        HospitalVectorStore(embedding, path)


def test_local_provider_failure_does_not_fall_back_to_cloud():
    from src.rag.rag_pipeline import HospitalRAGPipeline
    from src.rag.vector_store import HospitalVectorStore
    from src.rag.embeddings import LocalOfflineEmbeddings
    import pytest
    with patch('src.local_providers.Ollama') as provider:
        provider.return_value.answer.side_effect = RuntimeError('local down')
        rag = HospitalRAGPipeline(HospitalVectorStore(LocalOfflineEmbeddings()), llm_provider='ollama')
        with pytest.raises(RuntimeError, match='local down'):
            rag._generate_answer('question', 'context')


def test_migration_checker_detects_same_version_different_content(tmp_path):
    from src.check_migrations import compare
    a, b = tmp_path / 'a', tmp_path / 'b'
    a.mkdir(); b.mkdir()
    (a / 'V38__voice.sql').write_text('select 1;')
    (b / 'V38__navigation.sql').write_text('select 2;')
    assert compare(a, b) == ['V38']


def test_python_sources_parse():
    import ast
    from pathlib import Path
    for path in Path('src').rglob('*.py'):
        ast.parse(path.read_text(encoding='utf-8-sig'), filename=str(path))
