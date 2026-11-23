"""BE is the publication authority; OpenAI search alone is not publication proof."""
import os
from urllib.parse import urlsplit
import httpx


class KnowledgeResolver:
    def __init__(self):
        self.origin = os.getenv('BACKEND_URL', '').rstrip('/')
        self.token = os.getenv('KNOWLEDGE_SERVICE_TOKEN', '')
        self._http = None

    def resolve(self, store, file_ids):
        origin = urlsplit(self.origin)
        local_http = origin.scheme == 'http' and origin.hostname in ('localhost', '127.0.0.1', '::1')
        if ((origin.scheme != 'https' and not local_http) or not origin.hostname or origin.username
                or origin.password or origin.path not in ('', '/') or origin.query or origin.fragment
                or len(self.token) < 32):
            raise RuntimeError('AI_KNOWLEDGE_UNAVAILABLE')
        if not file_ids:
            return {}
        if len(file_ids) > 20:
            raise ValueError('Too many knowledge files')
        if self._http is None:
            self._http = httpx.Client(timeout=5, follow_redirects=False, trust_env=False)
        response = self._http.post(self.origin + '/api/v1/internal/knowledge/resolve',
                                   headers={'X-Knowledge-Token': self.token},
                                   json={'vectorStoreId': store, 'fileIds': file_ids})
        response.raise_for_status()
        if len(response.content) > 2_000_000:
            raise ValueError('Oversized publication response')
        rows = response.json()
        if not isinstance(rows, list):
            raise ValueError('Invalid publication response')
        result = {}
        for row in rows:
            if not isinstance(row, dict) or row.get('fileId') not in file_ids or row['fileId'] in result:
                raise ValueError('Invalid publication file')
            version, text = row.get('pipelineVersion'), row.get('text')
            if version not in (1, 2, 3) or (version >= 2 and (not isinstance(text, str) or not text.strip() or len(text) > 20000)):
                raise ValueError('Invalid published content')
            result[row['fileId']] = row
        return result

    def close(self):
        if self._http is not None:
            self._http.close()
            self._http = None
