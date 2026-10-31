"""Document loader for Hospital RAG system.

Supports:
- Microsoft Word documents (.docx) via python-docx
- PDF documents (.pdf) via pypdf
- Markdown (.md) and plain text (.txt) files
- Direct ingestion from Backend MySQL database via API
"""
from __future__ import annotations

import logging
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

log = logging.getLogger("mar_voice")


@dataclass
class Document:
    page_content: str
    metadata: dict[str, Any] = field(default_factory=dict)


class DocumentLoader:
    """Load hospital documents from local files and Backend API."""

    def __init__(self, documents_dir: str | Path | None = None) -> None:
        self.documents_dir = Path(documents_dir) if documents_dir else None

    def load_file(self, file_path: str | Path) -> list[Document]:
        """Load a single file (.docx, .pdf, .md, .txt)."""
        path = Path(file_path)
        if not path.is_file():
            log.warning("[LOADER] File not found: %s", path)
            return []

        ext = path.suffix.lower()
        if ext == ".docx":
            return self._load_docx(path)
        elif ext == ".pdf":
            return self._load_pdf(path)
        elif ext in (".md", ".txt"):
            return self._load_text(path)
        else:
            log.warning("[LOADER] Unsupported file extension: %s", ext)
            return []

    def load_directory(self, dir_path: str | Path | None = None) -> list[Document]:
        """Load all supported documents from a directory recursively."""
        target_dir = Path(dir_path) if dir_path else self.documents_dir
        if not target_dir or not target_dir.is_dir():
            log.warning("[LOADER] Directory not found: %s", target_dir)
            return []

        documents: list[Document] = []
        for file in target_dir.rglob("*"):
            if file.is_file() and file.suffix.lower() in (".docx", ".pdf", ".md", ".txt"):
                try:
                    docs = self.load_file(file)
                    documents.extend(docs)
                    log.info("[LOADER] Loaded %d docs from %s", len(docs), file.name)
                except Exception as exc:
                    log.error("[LOADER] Error loading %s: %s", file.name, exc)
        return documents

    def load_from_backend(self, backend_client: Any) -> list[Document]:
        """Ingest hospital FAQ and articles from Backend MySQL API."""
        docs: list[Document] = []
        try:
            # Query the first 100 hospital information items
            resp = backend_client.get("/api/v1/hospital-information", params={"page": 0, "size": 100})
            data = resp.json()
            content = data.get("content", []) if isinstance(data, dict) else data

            for item in content:
                title = item.get("title", "")
                category = item.get("infoCategory", item.get("category", "GENERAL"))
                body = item.get("content", "")
                keywords = item.get("keywordsJson", [])
                article_id = item.get("hospitalInformationId", item.get("articleId", ""))

                text = f"Tiêu đề: {title}\nChuyên mục: {category}\nNội dung: {body}"
                if keywords and isinstance(keywords, list):
                    text += f"\nTừ khóa: {', '.join(keywords)}"

                docs.append(
                    Document(
                        page_content=text.strip(),
                        metadata={
                            "source": "MySQL_Database",
                            "article_id": str(article_id),
                            "title": title,
                            "category": category,
                        },
                    )
                )
            log.info("[LOADER] Ingested %d articles from Backend Database", len(docs))
        except Exception as exc:
            log.warning("[LOADER] Failed to ingest from Backend API: %s", exc)
        return docs

    # ──────────────────────────────────────────────────────────────────
    # Private Parsers
    # ──────────────────────────────────────────────────────────────────

    def _load_docx(self, path: Path) -> list[Document]:
        import docx

        doc = docx.Document(str(path))
        full_text: list[str] = []
        for p in doc.paragraphs:
            text = p.text.strip()
            if text:
                full_text.append(text)

        # Also parse tables if any
        for table in doc.tables:
            for row in table.rows:
                row_text = " | ".join(cell.text.strip() for cell in row.cells if cell.text.strip())
                if row_text:
                    full_text.append(row_text)

        joined = "\n\n".join(full_text)
        if not joined:
            return []

        return [
            Document(
                page_content=joined,
                metadata={
                    "source": str(path.name),
                    "file_type": "docx",
                    "file_path": str(path),
                },
            )
        ]

    def _load_pdf(self, path: Path) -> list[Document]:
        import pypdf

        docs: list[Document] = []
        with open(path, "rb") as f:
            reader = pypdf.PdfReader(f)
            for page_idx, page in enumerate(reader.pages):
                text = page.extract_text() or ""
                if text.strip():
                    docs.append(
                        Document(
                            page_content=text.strip(),
                            metadata={
                                "source": str(path.name),
                                "page": page_idx + 1,
                                "file_type": "pdf",
                            },
                        )
                    )
        return docs

    def _load_text(self, path: Path) -> list[Document]:
        text = path.read_text(encoding="utf-8", errors="ignore").strip()
        if not text:
            return []
        return [
            Document(
                page_content=text,
                metadata={
                    "source": str(path.name),
                    "file_type": path.suffix.lstrip("."),
                },
            )
        ]
