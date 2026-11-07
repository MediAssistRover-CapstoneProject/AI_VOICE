import importlib
from unittest.mock import patch
from fastapi.testclient import TestClient
from src.rag.faq_store import FAQStore,FAQFields
from src.rag.rag_pipeline import HospitalRAGPipeline
from src.rag.vector_store import HospitalVectorStore
from src.rag.embeddings import LocalOfflineEmbeddings


def test_headless_auth_faq_and_action_boundaries(tmp_path,monkeypatch):
    monkeypatch.setenv('VOICE_SERVICE_TOKEN','unit-test-token')
    module=importlib.import_module('src.service.voice_api')
    source=FAQStore(tmp_path/'faqs.json')
    source.upsert(FAQFields(questions=['Tên wifi là gì?'],answer='Unit test wifi.',source='fixture.md',approved=True),source.revision)
    rag=HospitalRAGPipeline(HospitalVectorStore(LocalOfflineEmbeddings()),faq_store=source)
    monkeypatch.setattr(module,'faqs',source);monkeypatch.setattr(module,'rag',rag)
    with TestClient(module.app) as client,patch.object(rag,'_generate_answer') as generate:
        assert client.post('/internal/answer',json={'text':'Tên wifi là gì?'}).status_code==401
        headers={'Authorization':'Bearer unit-test-token'}
        response=client.post('/internal/answer',json={'text':'Tên wifi là gì?','faq_only':True},headers=headers)
        assert response.json()['answer_mode']=='approved_faq'
        assert response.json()['model'] is None
        for question in ['Dẫn tôi tới điểm A','Cứu tôi với','Dừng lại','Stop robot']:
            assert client.post('/internal/answer',json={'text':question},headers=headers).json()['handled'] is False
        assert client.post('/internal/answer',json={'text':'Unknown','faq_only':True},headers=headers).json()=={'handled':False}
        assert client.get('/health').json()['status']=='UP'
        generate.assert_not_called()
