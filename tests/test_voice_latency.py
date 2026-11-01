from src.tts import SpeechClient
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


def test_reviewed_exact_faq_skips_search_and_model():
    s = store()
    s.points[0].metadata.update(approved=True, visibility='PUBLIC', questions=['Giờ khám?'])
    rag = HospitalRAGPipeline(s)
    with patch.object(s, 'similarity_search') as search, patch.object(rag, '_generate_answer') as generate:
        assert rag.answer_question('Giờ khám?')['answer'] == s.points[0].text
        search.assert_not_called()
        generate.assert_not_called()


def test_audio_is_reused_from_cache():
    backend = Mock()
    backend.synthesize_speech.return_value = b'audio'
    tts = SpeechClient(backend)
    with patch('src.tts.play_wav') as play:
        tts.speak('Xin chào.')
        tts.speak('Xin chào.')
        assert backend.synthesize_speech.call_count == 1
        assert play.call_count == 2
