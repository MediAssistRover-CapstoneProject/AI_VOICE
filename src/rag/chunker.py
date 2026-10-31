"""Text splitter and chunker for Vietnamese hospital documents.

Splits documents into semantically coherent chunks with overlap to ensure
contextual continuity for vector embedding and retrieval.
"""
from __future__ import annotations

import re
from typing import Sequence

from src.rag.document_loader import Document


class VietnameseTextSplitter:
    """Splits texts by paragraphs and sentences, with character length bounds and overlap."""

    def __init__(
        self,
        chunk_size: int = 500,
        chunk_overlap: int = 80,
    ) -> None:
        if chunk_overlap >= chunk_size:
            raise ValueError("chunk_overlap must be less than chunk_size")
        self.chunk_size = chunk_size
        self.chunk_overlap = chunk_overlap

    def split_text(self, text: str) -> list[str]:
        """Split a string into smaller chunks with overlap."""
        if not text or not text.strip():
            return []

        # Split into paragraphs first
        paragraphs = [p.strip() for p in text.split("\n\n") if p.strip()]
        chunks: list[str] = []
        current_chunk: list[str] = []
        current_len = 0

        for para in paragraphs:
            # If paragraph itself is too large, split by sentences
            if len(para) > self.chunk_size:
                sentences = re.split(r"(?<=[.!?])\s+", para)
                for sent in sentences:
                    sent = sent.strip()
                    if not sent:
                        continue
                    if current_len + len(sent) + 1 > self.chunk_size and current_chunk:
                        chunk_str = " ".join(current_chunk)
                        chunks.append(chunk_str)
                        # Keep overlap from the end of current chunk
                        overlap_len = 0
                        new_start = len(current_chunk)
                        for idx in range(len(current_chunk) - 1, -1, -1):
                            overlap_len += len(current_chunk[idx]) + 1
                            if overlap_len <= self.chunk_overlap:
                                new_start = idx
                            else:
                                break
                        current_chunk = current_chunk[new_start:]
                        current_len = sum(len(s) + 1 for s in current_chunk)

                    current_chunk.append(sent)
                    current_len += len(sent) + 1
            else:
                if current_len + len(para) + 2 > self.chunk_size and current_chunk:
                    chunk_str = "\n".join(current_chunk)
                    chunks.append(chunk_str)
                    current_chunk = []
                    current_len = 0

                current_chunk.append(para)
                current_len += len(para) + 2

        if current_chunk:
            chunks.append("\n".join(current_chunk))

        return chunks

    def split_documents(self, documents: Sequence[Document]) -> list[Document]:
        """Split a sequence of Document objects into chunked Documents."""
        chunked_docs: list[Document] = []
        for doc in documents:
            chunks = self.split_text(doc.page_content)
            for idx, chunk in enumerate(chunks):
                meta = doc.metadata.copy()
                meta["chunk_id"] = idx
                meta["total_chunks"] = len(chunks)
                chunked_docs.append(Document(page_content=chunk, metadata=meta))
        return chunked_docs
