"""Bounded retrieval from the deployment's single OpenAI knowledge store."""
import re
from typing import Any


class OpenAIVectorStore:
    def __init__(self, vector_store_id, client):
        if not re.fullmatch(r'vs_[A-Za-z0-9]+', vector_store_id):
            raise ValueError('Invalid OPENAI_VECTOR_STORE_ID')
        self.vector_store_id = vector_store_id
        self.client = client

    def search(self, question):
        try:
            # The SDK's generic HTTP method also works with existing 1.x installs.
            result = self.client.get().with_options(timeout=5).post(
                '/vector_stores/' + self.vector_store_id + '/search',
                cast_to=dict[str, Any],
                options={'headers': {'OpenAI-Beta': 'assistants=v2'}},
                body={'query': question, 'max_num_results': 5,
                      'ranking_options': {'ranker': 'auto', 'score_threshold': 0.2}},
            )
            matches = result['data']
            if not isinstance(matches, list):
                raise ValueError('Invalid vector search result')
            contexts = []
            sources = []
            for match in matches[:5]:
                text = '\n'.join(part['text'] for part in match.get('content', [])
                                 if part.get('type') == 'text' and isinstance(part.get('text'), str)).strip()
                if not text:
                    continue
                file_id = match['file_id']
                if not isinstance(file_id, str) or not file_id.startswith('file-'):
                    raise ValueError('Invalid source file ID')
                attributes = match.get('attributes') or {}
                title = str(attributes.get('title') or match.get('filename') or file_id)[:255]
                contexts.append('[' + title + ']: ' + text[:2400])
                sources.append({'source': title, 'score': float(match.get('score', 0)),
                                'file_id': file_id})
            return '\n---\n'.join(contexts), sources
        except Exception:
            # Provider errors may include request data; expose a fixed application code.
            raise RuntimeError('AI_KNOWLEDGE_UNAVAILABLE') from None
