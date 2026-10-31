"""Embeddings engine for Hospital Vector Database.

Supports:
- OpenAI text-embedding-3-small (1536 dims)
- Google Gemini text-embedding-004 (768 dims)
- Local Fast Embeddings (384 dims, offline, zero external dependencies)
"""
from __future__ import annotations

import abc
import hashlib
import logging
import math
from typing import Sequence

import numpy as np

log = logging.getLogger("mar_voice")


class BaseEmbeddings(abc.ABC):
    """Abstract Base Class for embedding providers."""

    @abc.abstractmethod
    def embed_documents(self, texts: Sequence[str]) -> list[list[float]]:
        """Compute embeddings for a list of document texts."""

    @abc.abstractmethod
    def embed_query(self, text: str) -> list[float]:
        """Compute embedding for a single query text."""


class OpenAIEmbeddings(BaseEmbeddings):
    """OpenAI Embedding model (text-embedding-3-small)."""

    def __init__(self, api_key: str, model: str = "text-embedding-3-small") -> None:
        self.api_key = api_key
        self.model = model

    def embed_documents(self, texts: Sequence[str]) -> list[list[float]]:
        from openai import OpenAI

        client = OpenAI(api_key=self.api_key)
        resp = client.embeddings.create(input=list(texts), model=self.model)
        return [item.embedding for item in resp.data]

    def embed_query(self, text: str) -> list[float]:
        return self.embed_documents([text])[0]


class GeminiEmbeddings(BaseEmbeddings):
    """Google Gemini Embedding model (models/text-embedding-004)."""

    def __init__(self, api_key: str, model: str = "models/text-embedding-004") -> None:
        import google.generativeai as genai

        genai.configure(api_key=api_key)
        self.model = model

    def embed_documents(self, texts: Sequence[str]) -> list[list[float]]:
        import google.generativeai as genai

        embeddings = []
        for text in texts:
            res = genai.embed_content(
                model=self.model,
                content=text,
                task_type="retrieval_document",
            )
            embeddings.append(res["embedding"])
        return embeddings

    def embed_query(self, text: str) -> list[float]:
        import google.generativeai as genai

        res = genai.embed_content(
            model=self.model,
            content=text,
            task_type="retrieval_query",
        )
        return res["embedding"]


class LocalOfflineEmbeddings(BaseEmbeddings):
    """Offline Sub-word N-Gram and Bigram Hashing Vectorizer (384 dimensions).

    Ensures the RAG system and Vector DB function completely offline without
    any internet connection, external API keys, or GPU dependencies.
    Computes L2-normalized vector points (Vector P) suitable for Cosine Similarity search.
    """

    def __init__(self, dimensions: int = 384) -> None:
        self.dimensions = dimensions

    def _hash_vector(self, text: str) -> list[float]:
        import re

        vec = np.zeros(self.dimensions, dtype=np.float32)
        clean = re.sub(r"[^\w\s]", " ", text.lower())
        words = [w for w in clean.split() if w]
        if not words:
            return vec.tolist()

        # 1. Word unigrams
        for word in words:
            h_val = int(hashlib.md5(word.encode("utf-8")).hexdigest(), 16)
            idx = h_val % self.dimensions
            weight = 2.0 + min(len(word) / 5.0, 1.5)
            vec[idx] += weight

        # 2. Word bigrams (vital for Vietnamese compound words like "mở cửa", "cấp cứu")
        for i in range(len(words) - 1):
            bigram = f"{words[i]}_{words[i+1]}"
            b_val = int(hashlib.sha256(bigram.encode("utf-8")).hexdigest(), 16)
            b_idx = b_val % self.dimensions
            vec[b_idx] += 3.5

        # 3. Character 3-grams for subword matching
        for word in words:
            if len(word) >= 3:
                for i in range(len(word) - 2):
                    trigram = word[i : i + 3]
                    tri_h = int(hashlib.sha1(trigram.encode("utf-8")).hexdigest(), 16)
                    tri_idx = tri_h % self.dimensions
                    vec[tri_idx] += 0.5

        # L2 Normalization
        norm = np.linalg.norm(vec)
        if norm > 1e-6:
            vec = vec / norm
        return vec.tolist()

    def embed_documents(self, texts: Sequence[str]) -> list[list[float]]:
        return [self._hash_vector(t) for t in texts]

    def embed_query(self, text: str) -> list[float]:
        return self._hash_vector(text)


def create_embeddings(
    provider: str = "local",
    openai_api_key: str = "",
    gemini_api_key: str = "",
) -> BaseEmbeddings:
    """Factory for selecting the embedding model."""
    prov = provider.lower()
    if prov == "openai" and openai_api_key:
        log.info("[EMBEDDINGS] Using OpenAI text-embedding-3-small")
        return OpenAIEmbeddings(api_key=openai_api_key)
    elif prov == "gemini" and gemini_api_key:
        log.info("[EMBEDDINGS] Using Google Gemini text-embedding-004")
        return GeminiEmbeddings(api_key=gemini_api_key)
    else:
        log.info("[EMBEDDINGS] Using LocalOfflineEmbeddings (Zero-Dependency Offline)")
        return LocalOfflineEmbeddings()
