"""Optional local providers. Models must already exist; no implicit cloud fallback."""
import io
import os
import wave
from pathlib import Path
import httpx


class LocalWhisper:
    def __init__(self):
        from faster_whisper import WhisperModel
        self.model = WhisperModel(os.environ['LOCAL_STT_MODEL'],
            device=os.getenv('LOCAL_STT_DEVICE', 'cpu'),
            compute_type=os.getenv('LOCAL_STT_COMPUTE', 'int8'), local_files_only=True)

    def transcribe(self, wav_bytes, language='vi'):
        segments, _ = self.model.transcribe(io.BytesIO(wav_bytes), language=language[:2],
                                           beam_size=1, vad_filter=True)
        return ' '.join(s.text.strip() for s in segments).strip() or None


class SemanticEmbeddings:
    def __init__(self):
        from sentence_transformers import SentenceTransformer
        self.model_path = str(Path(os.environ['LOCAL_EMBEDDING_MODEL']).resolve())
        self.model = SentenceTransformer(self.model_path, device='cpu', local_files_only=True)
        self.identity = 'sentence-transformers:' + self.model_path

    def embed_documents(self, texts):
        return self.model.encode(list(texts), normalize_embeddings=True).tolist()

    def embed_query(self, text):
        return self.embed_documents([text])[0]


class Ollama:
    def __init__(self):
        self.model = os.getenv('LOCAL_LLM_MODEL', 'qwen3:4b')
        self.client = httpx.Client(base_url=os.getenv('OLLAMA_URL', 'http://127.0.0.1:11434'),
                                   timeout=30, trust_env=False)

    def answer(self, question, system):
        response = self.client.post('/api/generate', json={'model': self.model,
            'prompt': question, 'system': system, 'stream': False, 'think': False,
            'keep_alive': '30m', 'options': {'num_ctx': 4096, 'num_predict': 180, 'temperature': 0.1}})
        response.raise_for_status()
        text = response.json().get('response', '').strip()
        if not text:
            raise RuntimeError('Local LLM returned empty answer')
        return text

    def close(self):
        self.client.close()


class PiperSpeech:
    def __init__(self):
        from piper import PiperVoice
        self.voice = PiperVoice.load(os.environ['LOCAL_TTS_MODEL'])

    def synthesize(self, text):
        output = io.BytesIO()
        with wave.open(output, 'wb') as wav:
            self.voice.synthesize_wav(text, wav)
        return output.getvalue()
