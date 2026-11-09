"""Project the approved hospital article catalog into the robot's RAG index."""
from __future__ import annotations

import hashlib
import json
from urllib.parse import urlsplit
from uuid import UUID

import httpx

from src.rag.chunker import VietnameseTextSplitter
from src.rag.document_loader import Document
from src.rag.faq_store import question_key
from src.rag.vector_store import HospitalVectorStore


class PublishedKnowledge:
    def __init__(self, backend_url: str, hospital_id: str, refresh_sec: int):
        origin = urlsplit(backend_url)
        if (origin.scheme != 'https' or not origin.hostname or origin.username or origin.password
                or origin.path not in ('', '/') or origin.query or origin.fragment):
            raise ValueError('BACKEND_URL must be the HTTPS backend origin for AI_HOSPITAL_ID')
        if not 5 <= refresh_sec <= 300:
            raise ValueError('AI_KNOWLEDGE_REFRESH_SEC must be 5..300 seconds')
        self.hospital_id = str(UUID(hospital_id))
        self.refresh_sec = refresh_sec
        self.url = f'{origin.scheme}://{origin.netloc}/api/v1/public/hospital-information/{self.hospital_id}/articles'

    def index(self, embeddings, current_revision):
        articles = self._articles()
        revision = hashlib.sha256(json.dumps(articles, ensure_ascii=False, sort_keys=True).encode()).hexdigest()
        if revision == current_revision:
            return None

        documents = []
        exact = {}
        questions = set()
        for article in articles:
            article_id = str(UUID(article['articleId']))
            title = article['title'].strip()
            question = article['canonicalQuestion'].strip()
            answer = article['answer'].strip()
            if not title or not question or not answer:
                raise ValueError('Incomplete published article')
            language = article['languageCode'].replace('_', '-').split('-')[0].lower()
            source = dict(source=title, article_id=article_id, score=1.)
            key = (language, question_key(question))
            if key in questions:
                exact.pop(key, None)
            elif len(answer) <= 500:
                exact[key] = dict(answer=answer, sources=[source], answer_mode='approved_article')
            questions.add(key)
            documents.append(Document(f'{title}\n{question}\n{answer}',
                                      dict(source=title, title=title, article_id=article_id,
                                           hospital_id=self.hospital_id, language=language)))

        index = HospitalVectorStore(embeddings)
        chunks = VietnameseTextSplitter(chunk_size=400, chunk_overlap=80).split_documents(documents)
        if index.add_documents(chunks) != len(chunks):
            raise ValueError('Incomplete published article index')
        return revision, index, exact

    def _articles(self):
        articles = []
        with httpx.Client(timeout=5, follow_redirects=False, trust_env=False) as client:
            page, pages, total = 0, 1, None
            while page < pages:
                response = client.get(self.url, params={'page': page, 'size': 100})
                response.raise_for_status()
                data = response.json()
                if page == 0:
                    pages, total = data['totalPages'], data['totalElements']
                    if type(pages) is not int or not 0 <= pages <= 20 or type(total) is not int or total < 0:
                        raise ValueError('Invalid published article page count')
                if data['totalPages'] != pages or data['totalElements'] != total or not isinstance(data['content'], list):
                    raise ValueError('Published article catalog changed during refresh')
                articles.extend(data['content'])
                page += 1

        identities = set()
        for article in articles:
            article_id = str(UUID(article['articleId']))
            if str(UUID(article['hospitalInformationId'])) != self.hospital_id or article_id in identities:
                raise ValueError('Invalid hospital scope or duplicate published article')
            identities.add(article_id)
        if len(articles) != total:
            raise ValueError('Incomplete published article catalog')
        return sorted(articles, key=lambda article: article['articleId'])
