"""Unit tests for Hospital RAG Pipeline and Vector Store ('Vector P')."""
import pytest
from pathlib import Path
from src.rag.chunker import VietnameseTextSplitter
from src.rag.document_loader import Document, DocumentLoader
from src.rag.embeddings import LocalOfflineEmbeddings
from src.rag.vector_store import HospitalVectorStore
from src.rag.rag_pipeline import HospitalRAGPipeline


@pytest.fixture
def sample_docs():
    return [
        Document(
            page_content="Bệnh viện MediAssist mở cửa từ 07h00 đến 17h00 các ngày trong tuần. Cấp cứu trực 24/24.",
            metadata={"title": "Giờ khám bệnh", "category": "SCHEDULE"},
        ),
        Document(
            page_content="Khoa Cấp cứu P_CAP_CUU nằm tại Tầng 1 sảnh chính. Hotline cấp cứu khẩn cấp là 1900 6868.",
            metadata={"title": "Vị trí Cấp cứu", "category": "LOCATION"},
        ),
        Document(
            page_content="Bệnh viện áp dụng Bảo hiểm Y tế BHYT thông tuyến. Khám thường giá 150.000 VNĐ.",
            metadata={"title": "Chi phí và BHYT", "category": "PRICING"},
        ),
    ]


@pytest.fixture
def vector_store(sample_docs):
    embeddings = LocalOfflineEmbeddings(dimensions=384)
    store = HospitalVectorStore(embeddings=embeddings)
    store.add_documents(sample_docs)
    return store


class TestChunker:
    def test_split_text(self):
        splitter = VietnameseTextSplitter(chunk_size=100, chunk_overlap=20)
        text = "Đoạn văn thứ nhất nói về bệnh viện.\n\nĐoạn văn thứ hai nói về bác sĩ và điều dưỡng.\n\nĐoạn văn thứ ba nói về thuốc."
        chunks = splitter.split_text(text)
        assert len(chunks) >= 2
        assert all(len(c) <= 150 for c in chunks)

    def test_split_documents(self, sample_docs):
        splitter = VietnameseTextSplitter(chunk_size=60, chunk_overlap=15)
        chunked = splitter.split_documents(sample_docs)
        assert len(chunked) >= len(sample_docs)
        assert all(isinstance(d, Document) for d in chunked)
        assert "chunk_id" in chunked[0].metadata


class TestEmbeddings:
    def test_local_embeddings_dimension_and_norm(self):
        emb = LocalOfflineEmbeddings(dimensions=64)
        vec = emb.embed_query("Bệnh viện khám bệnh ở đâu?")
        assert len(vec) == 64
        import numpy as np
        norm = np.linalg.norm(vec)
        assert abs(norm - 1.0) < 1e-4

    def test_similarity_same_text(self):
        emb = LocalOfflineEmbeddings(dimensions=128)
        v1 = emb.embed_query("phòng khám nội")
        v2 = emb.embed_query("phòng khám nội")
        import numpy as np
        sim = np.dot(v1, v2)
        assert sim > 0.99


class TestVectorStore:
    def test_similarity_search_retrieves_relevant_chunk(self, vector_store):
        results = vector_store.similarity_search("Mấy giờ mở cửa?", top_k=1)
        assert len(results) == 1
        assert "07h00" in results[0].document.page_content
        assert results[0].score > 0.2

    def test_similarity_search_emergency(self, vector_store):
        results = vector_store.similarity_search("Số điện thoại cấp cứu là gì?", top_k=1)
        assert len(results) == 1
        assert "1900 6868" in results[0].document.page_content

    def test_empty_query(self, vector_store):
        results = vector_store.similarity_search("", top_k=2)
        assert len(results) == 0


class TestRAGPipeline:
    def test_answer_with_context(self, vector_store):
        pipeline = HospitalRAGPipeline(vector_store=vector_store, llm_provider="offline")
        res = pipeline.answer_question("Bệnh viện mở cửa lúc mấy giờ?")
        assert "answer" in res
        assert "07h00" in res["answer"]
        assert len(res["sources"]) > 0

    def test_answer_out_of_scope(self, vector_store):
        pipeline = HospitalRAGPipeline(vector_store=vector_store, score_threshold=0.85)
        res = pipeline.answer_question("Ai là tổng thống Mỹ?")
        assert "chưa tìm thấy" in res["answer"].lower() or "quầy tiếp đón" in res["answer"].lower()
