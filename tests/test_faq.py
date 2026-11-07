"""FAQ publication, exact routing and live edits; all files live under tmp_path."""
import importlib
import json
from pathlib import Path
from unittest.mock import patch
from uuid import UUID, uuid4

from fastapi import FastAPI
from fastapi.testclient import TestClient
import pytest

from src.rag.faq_store import FAQFields, FAQStore, FAQConflict, FAQDataError
from src.rag.document_loader import Document
from src.rag.embeddings import LocalOfflineEmbeddings
from src.rag.vector_store import HospitalVectorStore
from src.rag.rag_pipeline import HospitalRAGPipeline
from src.web.faq_api import create_faq_router


def fields(**changes):
    data=dict(questions=['Wifi bệnh viện tên gì?', 'Mật khẩu wifi là gì?'],
        answer='Tên mạng UNIT_TEST_WIFI.', source='unit-test-source.md', approved=True)
    data.update(changes)
    return FAQFields(**data)


@pytest.fixture
def source(tmp_path):
    return FAQStore(tmp_path/'faqs.json')


def add(source, **changes):
    data=dict(questions=['Wifi bệnh viện tên gì?', 'Mật khẩu wifi là gì?'],
        answer='Tên mạng UNIT_TEST_WIFI.', source='unit-test-source.md', approved=True)
    data.update(changes)
    return source.upsert(FAQFields(**data), source.revision)


def pipeline(source):
    store=HospitalVectorStore(LocalOfflineEmbeddings(dimensions=384))
    store.add_documents([Document('Thông tin wifi dự phòng trong tài liệu.', {'source':'test.md'})])
    return HospitalRAGPipeline(store, llm_provider='offline', score_threshold=-1, faq_store=source)


def test_publication_and_exact_match_without_fuzzy_negation(source):
    snapshot=add(source, approved=False)
    assert snapshot['active']==0
    assert source.match('Wifi bệnh viện tên gì?') is None
    identity=snapshot['items'][0]['id']
    source.upsert(fields(),source.revision,identity=UUID(identity))
    assert source.match('  WIFI bệnh viện tên gì! ')['answer']=='Tên mạng UNIT_TEST_WIFI.'
    assert source.match('Bệnh viện không có wifi?') is None
    assert source.match('Mật khẩu wifi là gì')['answer']=='Tên mạng UNIT_TEST_WIFI.'
    row=FAQFields(**{**fields().model_dump(),'visibility':'INTERNAL'})
    source.upsert(row,source.revision,UUID(identity))
    assert source.match('Wifi bệnh viện tên gì?') is None


def test_new_reader_reloads_edit_and_preserves_other_entries(source):
    first=add(source)
    reader=FAQStore(source.path)
    assert reader.match('Wifi bệnh viện tên gì?')['answer']=='Tên mạng UNIT_TEST_WIFI.'
    item=first['items'][0]
    updated=FAQFields(**{**fields().model_dump(),'answer':'Tên mạng UNIT_TEST_UPDATED.'})
    source.upsert(updated,source.revision,UUID(item['id']))
    assert reader.match('Wifi bệnh viện tên gì?')['answer']=='Tên mạng UNIT_TEST_UPDATED.'
    add(source,questions=['Quầy tiếp đón ở đâu?'],answer='Tầng kiểm thử.')
    assert reader.snapshot()['total']==2


def test_conflicting_or_stale_write_never_overwrites_file(source):
    revision=source.revision
    first=add(source)
    raw=source.path.read_bytes()
    with pytest.raises(FAQConflict):source.upsert(fields(),revision)
    with pytest.raises(FAQDataError):source.upsert(fields(),first['revision'])
    assert source.path.read_bytes()==raw


def test_bad_file_does_not_serve_stale_approved_answer(source):
    add(source)
    assert source.match('Wifi bệnh viện tên gì?')
    source.path.write_text('{broken',encoding='utf-8')
    with pytest.raises(FAQDataError):source.match('Wifi bệnh viện tên gì?')


def test_faq_skips_search_and_model_with_conversation_history(source):
    add(source)
    rag=pipeline(source)
    with patch.object(rag.vector_store,'similarity_search') as search,patch.object(rag,'_generate_answer') as model:
        answer=rag.answer_question('Wifi bệnh viện tên gì?',history=[{'role':'user','content':'Xin chào'}])
    assert answer['answer_mode']=='approved_faq'
    search.assert_not_called();model.assert_not_called()


def test_faq_change_invalidates_prior_generated_cache(source):
    rag=pipeline(source)
    with patch.object(rag,'_generate_answer',return_value='Câu trong tài liệu.') as generate:
        assert rag.answer_question('Wifi bệnh viện tên gì?')['answer_mode']=='generated'
        first=add(source)
        assert rag.answer_question('Wifi bệnh viện tên gì?')['answer']=='Tên mạng UNIT_TEST_WIFI.'
        source.delete(UUID(first['items'][0]['id']),source.revision)
        assert rag.answer_question('Wifi bệnh viện tên gì?')['answer_mode']=='generated'
        assert generate.call_count==2


@pytest.fixture
def editor(source):
    app=FastAPI();app.include_router(create_faq_router(source))
    with TestClient(app) as client:yield client


def test_editor_create_approve_update_test_delete(editor):
    revision=editor.get('/api/faqs').json()['revision']
    body={**fields().model_dump(),'approved':False,'revision':revision}
    created=editor.post('/api/faqs',json=body)
    assert created.status_code==200
    current=created.json();identity=current['items'][0]['id']
    assert editor.post('/api/faqs/test',json={'question':body['questions'][0]}).json()['matched'] is False
    body.update(approved=True,revision=current['revision'])
    current=editor.put('/api/faqs/'+identity,json=body).json()
    result=editor.post('/api/faqs/test',json={'question':'Mật khẩu wifi là gì'}).json()
    assert result['matched'] and result['model_called'] is False
    assert result['answer_mode']=='approved_faq'
    assert editor.delete('/api/faqs/'+identity,params={'revision':current['revision']}).json()['active']==0


def test_editor_rejects_invalid_duplicate_stale_and_cross_origin(editor):
    revision=editor.get('/api/faqs').json()['revision']
    body={**fields().model_dump(),'revision':revision}
    assert editor.post('/api/faqs',json={**body,'source':'   '}).status_code==422
    assert editor.post('/api/faqs',json={**body,'approved':'true'}).status_code==422
    assert editor.post('/api/faqs',json={**body,'answer':'x'*501}).status_code==422
    assert editor.post('/api/faqs',json=body,headers={'Origin':'https://unrelated.invalid'}).status_code==403
    current=editor.post('/api/faqs',json=body).json()
    assert editor.post('/api/faqs',json=body).status_code==409
    body['revision']=current['revision']
    assert editor.post('/api/faqs',json=body).status_code==400


def robot_client(source, monkeypatch, tmp_path):
    from src.web import robot_api as robot
    store=HospitalVectorStore(LocalOfflineEmbeddings())
    rag=HospitalRAGPipeline(store,llm_provider='openai',require_model=True,faq_store=source)
    monkeypatch.setattr(robot,'store',store);monkeypatch.setattr(robot,'rag',rag)
    monkeypatch.setattr(robot,'faq_store',source);monkeypatch.setattr(robot,'cache',robot.OrderedDict())
    monkeypatch.setattr(robot,'INDEX',tmp_path/'unused-index.json');monkeypatch.setattr(robot,'index_revision',0)
    monkeypatch.delenv('OPENAI_API_KEY',raising=False)
    monkeypatch.setenv('AI_ROBOT_API_TOKEN','unit-test-token')
    return TestClient(robot.app),rag


def request_body(text):
    return dict(session_id=str(uuid4()),turn_id=str(uuid4()),text=text)


def test_robot_faq_works_without_provider_and_refreshes_next_turn(source,monkeypatch,tmp_path):
    first=add(source,questions=['Nhà thuốc bệnh viện ở đâu?'],answer='Nhà thuốc ở tầng kiểm thử.')
    client,rag=robot_client(source,monkeypatch,tmp_path)
    headers={'Authorization':'Bearer unit-test-token'}
    with client,patch.object(rag,'_generate_answer') as model,patch.object(rag.vector_store,'similarity_search') as search:
        assert client.get('/health').json()['state']=='READY'
        question=request_body('Nhà thuốc bệnh viện ở đâu?')
        result=client.post('/api/robot/chat',json=question,headers=headers)
        assert result.status_code==200
        assert result.json()['answer_mode']=='approved_faq'
        assert result.json()['action'] is None and result.json()['model'] is None
        changed=FAQFields(**{**fields().model_dump(),'questions':['Nhà thuốc bệnh viện ở đâu?'],'answer':'Đáp án mới đã duyệt.'})
        source.upsert(changed,source.revision,UUID(first['items'][0]['id']))
        assert client.post('/api/robot/chat',json=question,headers=headers).json()==result.json()
        question['turn_id']=str(uuid4())
        assert client.post('/api/robot/chat',json=question,headers=headers).json()['answer']=='Đáp án mới đã duyệt.'
        model.assert_not_called();search.assert_not_called()


def test_faq_only_mode_gives_safe_answer_for_unmatched_question(source,monkeypatch,tmp_path):
    add(source)
    client,rag=robot_client(source,monkeypatch,tmp_path)
    with client,patch.object(rag,'_generate_answer') as model:
        assert client.get('/health').json()['answer_mode']=='FAQ_ONLY'
        result=client.post('/api/robot/chat',json=request_body('Giờ khám bệnh thế nào?'),
            headers={'Authorization':'Bearer unit-test-token'})
    assert result.status_code==200
    assert result.json()['answer_mode']=='faq_only_no_match'
    assert result.json()['sources']==[] and result.json()['model'] is None
    assert result.json()['confidence']==0
    model.assert_not_called()


@pytest.mark.parametrize('text',['Cứu tôi với','Dẫn tôi tới điểm A'])
def test_faq_never_overrides_sos_or_explicit_navigation(text,source,monkeypatch,tmp_path):
    add(source,questions=[text],answer='FAQ không được chọn cho yêu cầu này.')
    client,_=robot_client(source,monkeypatch,tmp_path)
    with client:
        result=client.post('/api/robot/chat',json=request_body(text),headers={'Authorization':'Bearer unit-test-token'})
    assert result.status_code==200
    assert result.json()['answer_mode']!='approved_faq'


def test_document_reindex_keeps_faq_and_does_not_change_real_index(source,monkeypatch,tmp_path):
    studio=importlib.import_module('src.web.app')
    first=add(source)
    original=studio.VECTOR_DB_FILE.read_bytes()
    docs=tmp_path/'documents';docs.mkdir();(docs/'source.md').write_text('Dữ liệu kiểm thử tái lập chỉ mục.',encoding='utf-8')
    monkeypatch.setattr(studio,'DOCS_DIR',docs)
    monkeypatch.setattr(studio,'VECTOR_DB_FILE',tmp_path/'vectors.json')
    monkeypatch.setattr(studio,'vector_store',HospitalVectorStore(LocalOfflineEmbeddings(dimensions=384)))
    before=source.path.read_bytes()
    assert studio._rebuild_index_internal()>0
    assert source.path.read_bytes()==before
    assert source.match('Wifi bệnh viện tên gì?')['answer']=='Tên mạng UNIT_TEST_WIFI.'
    assert (Path(__file__).resolve().parents[1]/'data/hospital_vector_store.json').read_bytes()==original


def test_studio_page_assets_and_faq_answer_use_no_llm(source,monkeypatch):
    studio=importlib.import_module('src.web.app')
    add(source)
    rag=pipeline(source)
    monkeypatch.setattr(studio,'rag_pipeline',rag)
    with TestClient(studio.app) as client,patch.object(studio.intent_classifier,'classify') as classify,patch.object(rag,'_generate_answer') as model:
        assert client.get('/faq').status_code==200
        assert client.get('/assets/faq.js').status_code==200
        result=client.post('/api/chat',data={'query':'Wifi bệnh viện tên gì?'})
    assert result.status_code==200 and result.json()['answer_mode']=='approved_faq'
    classify.assert_not_called();model.assert_not_called()
