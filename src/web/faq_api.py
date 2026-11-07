"""Local Studio FAQ editing and exact-match preview; no provider or robot actions."""
import time
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, Field

from src.intent import IntentClassifier
from src.rag.faq_store import FAQFields, FAQStore, FAQDataError, FAQConflict, FAQNotFound, faq_allowed


class FAQEdit(FAQFields):
    revision: str = Field(pattern=r'^[a-f0-9]{64}$')


class FAQTest(BaseModel):
    question: str = Field(min_length=1, max_length=2000)


def same_origin(request: Request):
    origin = request.headers.get('origin')
    if origin and origin.rstrip('/') != str(request.base_url).rstrip('/'):
        raise HTTPException(403, 'Chỉ sửa FAQ từ Studio đang mở trên máy này.')


def create_faq_router(store: FAQStore):
    router = APIRouter(prefix='/api/faqs', dependencies=[Depends(same_origin)])
    classifier = IntentClassifier(use_llm_fallback=False)

    def execute(call):
        try:return call()
        except FAQNotFound as error:raise HTTPException(404, str(error)) from None
        except FAQConflict as error:raise HTTPException(409, str(error)) from None
        except FAQDataError as error:raise HTTPException(400, str(error)) from None

    @router.get('')
    def list_faqs():
        return execute(store.snapshot)

    @router.post('')
    def create_faq(body: FAQEdit):
        fields = FAQFields.model_validate(body.model_dump(exclude={'revision'}))
        return execute(lambda: store.upsert(fields, body.revision))

    @router.put('/{identity}')
    def update_faq(identity: UUID, body: FAQEdit):
        fields = FAQFields.model_validate(body.model_dump(exclude={'revision'}))
        return execute(lambda: store.upsert(fields, body.revision, identity))

    @router.delete('/{identity}')
    def delete_faq(identity: UUID, revision: str):
        return execute(lambda: store.delete(identity, revision))

    @router.post('/test')
    def test_faq(body: FAQTest):
        started = time.perf_counter()
        intent = classifier.classify(body.question)
        allowed = faq_allowed(body.question, intent.intent)
        match = execute(lambda: store.match(body.question)) if allowed else None
        return dict(matched=match is not None, answer=match['answer'] if match else None,
            sources=match['sources'] if match else [], answer_mode='approved_faq' if match else 'no_faq_match',
            routing='faq' if match else 'normal' if allowed else 'intent_handler',
            model_called=False, processing_ms=round((time.perf_counter()-started)*1000,3))

    return router
