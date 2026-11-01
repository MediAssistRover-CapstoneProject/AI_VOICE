from unittest.mock import Mock, patch
from src.rag.rag_pipeline import HospitalRAGPipeline
from src.rag.vector_store import HospitalVectorStore
from src.rag.embeddings import LocalOfflineEmbeddings
from src.rag.document_loader import Document


def store():
    s = HospitalVectorStore(LocalOfflineEmbeddings(dimensions=384))
    s.add_documents([Document('Giờ khám từ 7 giờ đến 17 giờ.', {'source': 'approved-test'})])
    return s


def test_answer_cache_invalidates_when_source_changes():
    s = store()
    rag = HospitalRAGPipeline(s, llm_provider='offline', score_threshold=-1)
    with patch.object(rag, '_generate_answer', return_value='Giờ khám từ 7 giờ.') as generate:
        rag.answer_question('Giờ khám?')
        rag.answer_question('Giờ khám?')
        assert generate.call_count == 1
        s.points[0].text = 'Giờ khám từ 8 giờ.'
        rag.answer_question('Giờ khám?')
        assert generate.call_count == 2
