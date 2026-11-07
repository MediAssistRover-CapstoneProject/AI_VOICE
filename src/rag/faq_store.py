"""Reviewed FAQ source, independent of generated vectors; atomic edits and live reload."""
from __future__ import annotations

from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import tempfile
import threading
import unicodedata
from typing import Literal
from uuid import UUID, uuid4

from pydantic import BaseModel, ConfigDict, Field, StrictBool, field_validator, ValidationError

MAX_BYTES = 4 * 1024 * 1024


def question_key(text: str) -> str:
    # ASR punctuation/case/Unicode variants are harmless. Keep accents,
    # numbers, negation and every actual word; no fuzzy or semantic matching.
    return ' '.join(unicodedata.normalize('NFC', text).casefold().split()).strip(' .!?。')


def faq_allowed(text: str, intent: str) -> bool:
    from src.intent import endpoint_destination
    return (intent not in ('EMERGENCY_SOS', 'GREETING', 'FAREWELL', 'SELF_INTRO')
            and endpoint_destination(text) is None)


class FAQDataError(ValueError):
    pass


class FAQConflict(FAQDataError):
    pass


class FAQNotFound(FAQDataError):
    pass


class FAQFields(BaseModel):
    model_config = ConfigDict(extra='forbid', str_strip_whitespace=True)
    questions: list[str] = Field(min_length=1, max_length=20)
    answer: str = Field(min_length=1, max_length=500)
    source: str = Field(min_length=1, max_length=500)
    source_excerpt: str = Field(default='', max_length=2000)
    approved: StrictBool = False
    visibility: Literal['PUBLIC', 'INTERNAL'] = 'PUBLIC'

    @field_validator('questions')
    @classmethod
    def validate_questions(cls, values):
        result = [value.strip() for value in values]
        keys = [question_key(value) for value in result]
        if any(not key or len(value) > 200 for key, value in zip(keys, result)):
            raise ValueError('Mỗi cách hỏi phải có nội dung và tối đa 200 ký tự.')
        if len(keys) != len(set(keys)):
            raise ValueError('Các cách hỏi đang trùng nhau sau khi bỏ dấu câu cuối.')
        return result


class FAQRecord(FAQFields):
    id: UUID
    updated_at: datetime


class FAQDocument(BaseModel):
    model_config = ConfigDict(extra='forbid')
    version: Literal[1] = 1
    faqs: list[FAQRecord] = Field(default_factory=list, max_length=500)


class FAQStore:
    def __init__(self, path: str | Path):
        self.path = Path(path)
        self.lock = threading.RLock()
        self._signature = None
        self._document = None
        self._revision = ''
        self._matches = {}

    def _load(self):
        try:
            stat = self.path.stat()
            signature = (stat.st_mtime_ns, stat.st_size, stat.st_ino)
            if stat.st_size > MAX_BYTES:
                raise FAQDataError('File FAQ vượt quá dung lượng cho phép.')
            if self._document is not None and signature == self._signature:
                return self._document
            raw = self.path.read_bytes()
        except FileNotFoundError:
            signature = ('missing',)
            raw = b'{"version":1,"faqs":[]}'
        except OSError:
            raise FAQDataError('Không đọc được file FAQ.') from None
        try:
            document = FAQDocument.model_validate_json(raw)
            self._validate_unique(document)
        except (ValidationError, ValueError):
            raise FAQDataError('File FAQ không hợp lệ; kiểm tra nội dung và các câu hỏi bị trùng.') from None
        matches = {}
        for entry in document.faqs:
            if entry.approved and entry.visibility == 'PUBLIC':
                for question in entry.questions:
                    matches[question_key(question)] = entry
        self._document = document
        self._signature = signature
        self._revision = hashlib.sha256(raw).hexdigest()
        self._matches = matches
        return document

    @staticmethod
    def _validate_unique(document):
        ids = set(); questions = set()
        for entry in document.faqs:
            if entry.id in ids:
                raise FAQDataError('ID FAQ bị trùng.')
            ids.add(entry.id)
            for question in entry.questions:
                key = question_key(question)
                if key in questions:
                    raise FAQDataError('Cách hỏi này đã nằm trong một FAQ khác.')
                questions.add(key)

    def snapshot(self):
        with self.lock:
            document = self._load()
            return dict(revision=self._revision, items=document.model_dump(mode='json')['faqs'],
                total=len(document.faqs), active=sum(e.approved and e.visibility == 'PUBLIC' for e in document.faqs),
                storage_file=str(self.path.resolve()))

    @property
    def revision(self):
        with self.lock:
            self._load()
            return self._revision

    def match(self, question):
        with self.lock:
            self._load()
            entry = self._matches.get(question_key(question))
            if entry is None:
                return None
            return dict(answer=entry.answer, sources=[dict(source=entry.source, score=1., faq_id=str(entry.id))],
                confidence=1., context_used=entry.answer, answer_mode='approved_faq', faq_id=str(entry.id))

    def _save(self, records, revision):
        self._load()
        if revision != self._revision:
            raise FAQConflict('FAQ đã thay đổi. Tải lại danh sách trước khi lưu.')
        if len(records) > 500:
            raise FAQDataError('Kho FAQ hiện hỗ trợ tối đa 500 câu trả lời.')
        document = FAQDocument(faqs=records)
        self._validate_unique(document)
        raw = json.dumps(document.model_dump(mode='json'), ensure_ascii=False, indent=2).encode('utf-8')
        if len(raw) > MAX_BYTES:
            raise FAQDataError('File FAQ vượt quá dung lượng cho phép.')
        self.path.parent.mkdir(parents=True, exist_ok=True)
        fd, temporary = tempfile.mkstemp(prefix='.faqs-', suffix='.json', dir=self.path.parent)
        try:
            with os.fdopen(fd, 'wb') as stream:
                stream.write(raw);stream.flush();os.fsync(stream.fileno())
            os.replace(temporary, self.path)
        finally:
            if os.path.exists(temporary):os.unlink(temporary)
        self._signature = None
        return self.snapshot()

    def upsert(self, fields: FAQFields, revision: str, identity: UUID | None = None):
        with self.lock:
            records = list(self._load().faqs)
            if identity is not None and not any(e.id == identity for e in records):
                raise FAQNotFound('Không tìm thấy FAQ.')
            record = FAQRecord(**fields.model_dump(), id=identity or uuid4(), updated_at=datetime.now(timezone.utc))
            records = [record if e.id == record.id else e for e in records]
            if identity is None:records.append(record)
            return self._save(records, revision)

    def delete(self, identity: UUID, revision: str):
        with self.lock:
            records = self._load().faqs
            if not any(e.id == identity for e in records):
                raise FAQNotFound('Không tìm thấy FAQ.')
            return self._save([e for e in records if e.id != identity], revision)
