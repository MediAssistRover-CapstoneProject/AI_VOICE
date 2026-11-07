"""Private Compose RPC for Java BE: reviewed FAQ first, then local hospital RAG."""
import hmac
import os
import re
from pathlib import Path
from typing import Literal
from fastapi import FastAPI,Request,HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel,Field
from src.intent import IntentClassifier
from src.rag import HospitalVectorStore,HospitalRAGPipeline,create_embeddings
from src.rag.faq_store import FAQStore,FAQDataError,faq_allowed
from src.service.faq_api import create_faq_router

ROOT=Path(__file__).resolve().parents[2]
faqs=FAQStore(ROOT/'data/faqs.json')
store=HospitalVectorStore(create_embeddings('local'),ROOT/'data/hospital_vector_store.json')
rag=HospitalRAGPipeline(store,openai_api_key=os.getenv('OPENAI_API_KEY',''),
    llm_model=os.getenv('AI_RESPONSE_MODEL','gpt-4o-mini'),require_model=True,faq_store=faqs)
classifier=IntentClassifier(use_llm_fallback=False)
app=FastAPI(docs_url=None,redoc_url=None,openapi_url=None)

@app.middleware('http')
async def authenticate(request:Request,call_next):
    if request.url.path!='/health':
        token=os.getenv('VOICE_SERVICE_TOKEN','')
        if not token or not hmac.compare_digest(request.headers.get('authorization',''),'Bearer '+token):
            return JSONResponse(status_code=401,content={'detail':'VOICE_SERVICE_AUTH_REQUIRED'})
    return await call_next(request)

@app.get('/health')
def health():
    try:stats=faqs.snapshot()
    except FAQDataError:raise HTTPException(503,'FAQ_DATA_UNAVAILABLE')
    if not os.getenv('VOICE_SERVICE_TOKEN'):raise HTTPException(503,'VOICE_SERVICE_TOKEN_MISSING')
    return dict(status='UP',faq_active=stats['active'],vector_count=len(store.points))

class Question(BaseModel):
    text:str=Field(min_length=1,max_length=4000)
    history:list[dict]=Field(default_factory=list,max_length=8)
    faq_only:bool=False
    internal:bool=False

@app.post('/internal/answer')
def answer(q:Question):
    intent=classifier.classify(q.text)
    if re.search(r'\b(stop|cancel|dừng|hủy|huỷ)\b',q.text,re.I):return {'handled':False}
    if not faq_allowed(q.text,intent.intent):return {'handled':False}
    try:
        result=rag.lookup_faq(q.text)
        if result is None:
            if q.faq_only or q.internal or intent.intent=='ESCORT_REQUEST':return {'handled':False}
            result=rag.answer_question(q.text,history=q.history)
    except FAQDataError:raise HTTPException(503,'FAQ_DATA_UNAVAILABLE')
    except RuntimeError:raise HTTPException(503,'VOICE_MODEL_UNAVAILABLE')
    return dict(handled=True,answer=result['answer'],sources=result['sources'],context=result.get('context_used',''),
        answer_mode=result['answer_mode'],model=rag.llm_model if result['answer_mode'] in ('generated','answer_cache') else None)

app.include_router(create_faq_router(faqs))

@app.on_event('shutdown')
def close():rag.close()
