"""A lazily created, process-local SDK client with a reusable connection pool."""
import threading


class ReusableOpenAIClient:
    def __init__(self, api_key, timeout):
        self.api_key = api_key
        self.timeout = timeout
        self._client = None
        self._lock = threading.Lock()

    def get(self):
        with self._lock:
            if self._client is None:
                from openai import OpenAI
                self._client = OpenAI(api_key=self.api_key, timeout=self.timeout, max_retries=0)
            return self._client

    @property
    def initialized(self):
        return self._client is not None

    def close(self):
        with self._lock:
            if self._client is not None:
                self._client.close()
                self._client = None
