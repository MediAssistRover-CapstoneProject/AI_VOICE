"""Bounded retrieval from the deployment's single OpenAI knowledge store."""
import re
from typing import Any
from src.rag.knowledge_resolver import KnowledgeResolver


class OpenAIVectorStore:
    def __init__(self, vector_store_id, client):
        if not re.fullmatch(r'vs_[A-Za-z0-9]+', vector_store_id):
            raise ValueError('Invalid OPENAI_VECTOR_STORE_ID')
        self.vector_store_id = vector_store_id
        self.client = client
        self.resolver = KnowledgeResolver()

    def sources_current(self, sources):
        try:
            ids = list(dict.fromkeys(s['file_id'] for s in sources))
            return bool(ids) and set(ids) == set(self.resolver.resolve(self.vector_store_id, ids))
        except Exception:
            raise RuntimeError('AI_KNOWLEDGE_UNAVAILABLE') from None

    def search(self, question):
        try:
            # The SDK's generic HTTP method also works with existing 1.x installs.
            result = self.client.get().with_options(timeout=5).post(
                '/vector_stores/' + self.vector_store_id + '/search',
                cast_to=dict[str, Any],
                options={'headers': {'OpenAI-Beta': 'assistants=v2'}},
                body={'query': question, 'max_num_results': 20,
                      'ranking_options': {'ranker': 'auto', 'score_threshold': 0.2}},
            )
            matches = result['data']
            if not isinstance(matches, list):
                raise ValueError('Invalid vector search result')
            contexts = []
            sources = []
            ids = list(dict.fromkeys(m.get('file_id') for m in matches[:20]))
            if any(not isinstance(i, str) or not re.fullmatch(r'file-[A-Za-z0-9_-]+', i) for i in ids):
                raise ValueError('Invalid file ID')
            published = self.resolver.resolve(self.vector_store_id, ids)
            seen = set()
            for match in matches[:20]:
                file_id = match['file_id']
                publication = published.get(file_id)
                if publication is None:
                    continue
                text = '\n'.join(part['text'] for part in match.get('content', [])
                                 if part.get('type') == 'text' and isinstance(part.get('text'), str)).strip()
                if publication['pipelineVersion'] >= 2:
                    text = publication['text']  # Complete BE chunk; never cut off an exception/table row.
                if not text or text in seen:
                    continue
                # Legacy oversized snippets are skipped whole, never silently clipped.
                if len(text) > 20000 or sum(map(len, contexts)) + len(text) > 30000:
                    continue
                if not isinstance(file_id, str) or not file_id.startswith('file-'):
                    raise ValueError('Invalid source file ID')
                attributes = match.get('attributes') or {}
                title = str(publication.get('title') or attributes.get('title') or match.get('filename') or file_id)[:255]
                contexts.append(text)
                seen.add(text)
                sources.append({'source': title, 'score': float(match.get('score', 0)),
                                'file_id': file_id})
                if len(contexts) == 5:
                    break
            return '\n---\n'.join(contexts), sources
        except Exception:
            # Provider errors may include request data; expose a fixed application code.
            raise RuntimeError('AI_KNOWLEDGE_UNAVAILABLE') from None
