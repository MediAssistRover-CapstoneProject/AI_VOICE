"""Run local model warmup and expose health without loading robot control code.
Start: uvicorn src.local_health:app --host 127.0.0.1 --port 7861
"""
import io
import wave
from contextlib import asynccontextmanager
from fastapi import FastAPI
from fastapi.responses import JSONResponse
from starlette.concurrency import run_in_threadpool
from src.local_providers import LocalWhisper, SemanticEmbeddings, Ollama, PiperSpeech


def warmup():
    models = {}
    try:
        models['stt'] = LocalWhisper()
        silent = io.BytesIO()
        with wave.open(silent, 'wb') as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000)
            w.writeframes(b'\x00\x00' * 16000)
        models['stt'].transcribe(silent.getvalue())
        models['embedding'] = SemanticEmbeddings()
        models['embedding'].embed_query('giờ khám bệnh')
        models['llm'] = Ollama()
        models['llm'].answer('Xin chào', 'Chỉ trả lời: Xin chào.')
        models['tts'] = PiperSpeech()
        models['tts'].synthesize('Xin chào.')
        return models
    except Exception:
        if 'llm' in models:
            models['llm'].close()
        raise


@asynccontextmanager
async def lifespan(app):
    app.state.models = {}
    app.state.ready = False
    try:
        app.state.models = await run_in_threadpool(warmup)
        app.state.ready = True
    except Exception:
        # No secrets or absolute model paths in public health responses.
        app.state.ready = False
    yield
    if 'llm' in app.state.models:
        app.state.models['llm'].close()


app = FastAPI(lifespan=lifespan)


@app.get('/healthz')
def health():
    return {'alive': True}


@app.get('/readyz')
def ready():
    ok = getattr(app.state, 'ready', False)
    return JSONResponse({'ready': ok}, status_code=200 if ok else 503)
