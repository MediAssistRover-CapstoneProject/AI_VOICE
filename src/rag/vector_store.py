"""Vector Database (Vector Store / Vector Point Pipeline) for MediAssistRover.

Implements an efficient persistent Vector Store ('Vector P') with:
- Sub-millisecond Cosine Similarity Search
- L2-normalized Vector Points (P_i in R^d)
- Metadata filtering (by hospital department, document source, category)
- JSON persistence on local disk
"""
from __future__ import annotations

import json
import logging
import math
import uuid
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Sequence

import numpy as np

from src.rag.document_loader import Document
from src.rag.embeddings import BaseEmbeddings

log = logging.getLogger("mar_voice")


@dataclass
class VectorPoint:
    """A single Vector Point (Vector P) in the embedding space R^d."""

    id: str
    vector: list[float]  # The embedding vector P
    text: str            # Document chunk content
    metadata: dict[str, Any]


@dataclass
class SearchResult:
    document: Document
    score: float  # Cosine similarity score [-1.0, 1.0]
    point_id: str


class HospitalVectorStore:
    """Persistent Vector Database ('Vector P') for Hospital Knowledge."""

    def __init__(
        self,
        embeddings: BaseEmbeddings,
        persist_path: str | Path | None = None,
    ) -> None:
        self.embeddings = embeddings
        self.persist_path = Path(persist_path) if persist_path else None
        self.points: list[VectorPoint] = []
        self._matrix: np.ndarray | None = None

        if self.persist_path and self.persist_path.is_file():
            self.load(self.persist_path)

    def add_documents(self, documents: Sequence[Document]) -> int:
        """Embed and insert a sequence of Document chunks into the Vector Store."""
        if not documents:
            return 0

        texts = [doc.page_content for doc in documents]
        log.info("[VECTOR-P] Generating embeddings for %d document chunks...", len(texts))
        vectors = self.embeddings.embed_documents(texts)

        new_points = []
        for doc, vec in zip(documents, vectors):
            point = VectorPoint(
                id=str(uuid.uuid4()),
                vector=vec,
                text=doc.page_content,
                metadata=doc.metadata,
            )
            new_points.append(point)

        self.points.extend(new_points)
        self._rebuild_matrix()
        log.info("[VECTOR-P] Total vectors in database: %d", len(self.points))

        if self.persist_path:
            self.save(self.persist_path)

        return len(new_points)

    def similarity_search(
        self,
        query: str,
        top_k: int = 3,
        score_threshold: float = 0.15,
        filter_fn: Any | None = None,
    ) -> list[SearchResult]:
        """Perform Vector Cosine Similarity search for a query against all Vector Points (Vector P)."""
        if not self.points:
            log.warning("[VECTOR-P] Vector database is empty!")
            return []

        query_vec = np.array(self.embeddings.embed_query(query), dtype=np.float32)
        q_norm = np.linalg.norm(query_vec)
        if q_norm < 1e-6:
            return []
        query_vec = query_vec / q_norm

        if self._matrix is None or len(self._matrix) != len(self.points):
            self._rebuild_matrix()

        # Dot product with pre-normalized matrix = Cosine Similarity
        # Shape: (N, d) @ (d,) -> (N,)
        scores = np.dot(self._matrix, query_vec)

        # Get top indices
        ranked_indices = np.argsort(scores)[::-1]

        results: list[SearchResult] = []
        for idx in ranked_indices:
            score = float(scores[idx])
            if score < score_threshold:
                break

            pt = self.points[idx]
            if filter_fn and not filter_fn(pt.metadata):
                continue

            results.append(
                SearchResult(
                    document=Document(page_content=pt.text, metadata=pt.metadata),
                    score=round(score, 4),
                    point_id=pt.id,
                )
            )
            if len(results) >= top_k:
                break

        log.info("[VECTOR-P] Query '%s' -> found %d results (top score=%.3f)",
                 query[:40], len(results), results[0].score if results else 0.0)
        return results

    def _rebuild_matrix(self) -> None:
        """Assemble all Vector Points into a normalized numpy 2D matrix for fast vectorized matrix multiply."""
        if not self.points:
            self._matrix = None
            return

        raw_matrix = np.array([pt.vector for pt in self.points], dtype=np.float32)
        # Row-wise L2 normalization
        norms = np.linalg.norm(raw_matrix, axis=1, keepdims=True)
        norms[norms < 1e-6] = 1.0
        self._matrix = raw_matrix / norms

    def save(self, file_path: str | Path) -> None:
        """Persist the vector database to a JSON file on disk."""
        path = Path(file_path)
        path.parent.mkdir(parents=True, exist_ok=True)
        data = {
            "version": "1.0",
            "count": len(self.points),
            "points": [asdict(pt) for pt in self.points],
        }
        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        log.info("[VECTOR-P] Persisted %d vector points to %s", len(self.points), path)

    def load(self, file_path: str | Path) -> None:
        """Load vector points from disk."""
        path = Path(file_path)
        if not path.is_file():
            return
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)

        self.points = [
            VectorPoint(
                id=item["id"],
                vector=item["vector"],
                text=item["text"],
                metadata=item["metadata"],
            )
            for item in data.get("points", [])
        ]
        self._rebuild_matrix()
        log.info("[VECTOR-P] Loaded %d vector points from %s", len(self.points), path)
