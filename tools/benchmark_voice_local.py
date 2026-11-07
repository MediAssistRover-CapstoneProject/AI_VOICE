"""Offline CPU/ASGI benchmarks; never call a speech or language provider."""
import argparse
import json
import math
import os
from pathlib import Path
import platform
import statistics
import sys
import time
from datetime import datetime, timezone
from uuid import uuid4
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from src.rag import HospitalRAGPipeline, HospitalVectorStore
from src.rag.document_loader import Document
from src.rag.embeddings import LocalOfflineEmbeddings


def measure(fn, count):
    samples = []
    for _ in range(count):
        start = time.perf_counter()
        fn()
        samples.append((time.perf_counter() - start) * 1000)
    ordered = sorted(samples)
    return dict(samples=count, p50_ms=round(statistics.median(samples), 3),
                p95_ms=round(ordered[math.ceil(.95 * count)-1], 3))


def run(count):
    store = HospitalVectorStore(LocalOfflineEmbeddings(dimensions=384))
    store.add_documents([Document('Giờ khám từ 7 giờ đến 17 giờ.',
        {'source': 'offline-benchmark-fixture', 'visibility': 'PUBLIC', 'approved': True,
         'questions': ['Giờ khám?']})])
    rag = HospitalRAGPipeline(store, llm_provider='offline', score_threshold=-1)
    calls = dict(search=0, generation=0)
    search = store.similarity_search

    def counted_search(*args, **kwargs):
        calls['search'] += 1
        return search(*args, **kwargs)

    def no_provider(*_args, **_kwargs):
        calls['generation'] += 1
        return 'Giờ khám từ 7 giờ đến 17 giờ.'

    with patch.object(store, 'similarity_search', counted_search), patch.object(rag, '_generate_answer', no_provider):
        def faq_with_history():
            # Different histories expose the regression without an answer-cache hit.
            history = [{'role': 'user', 'content': 'Xin chào ' + str(uuid4())}]
            rag.answer_question('Giờ khám?', history=history)
        faq = measure(faq_with_history, count)
    faq['search_calls'] = calls['search']
    faq['generation_calls'] = calls['generation']
    results = {'approved_exact_faq_with_history': faq}
    actual = HospitalVectorStore(LocalOfflineEmbeddings(), ROOT/'data/hospital_vector_store.json')
    results['tracked_index_vector_search'] = dict(points=len(actual.points),
        **measure(lambda: actual.similarity_search('Giờ khám bệnh?', top_k=3), count))

    from fastapi.testclient import TestClient
    # A dummy process-local token for in-process requests only.
    with patch.dict(os.environ, {'AI_ROBOT_API_TOKEN': 'offline-benchmark-only-token'}):
        from src.web.robot_api import app
        with TestClient(app) as client:
            def greeting():
                response = client.post('/api/robot/chat', headers={
                    'Authorization': 'Bearer offline-benchmark-only-token'},
                    json=dict(session_id=str(uuid4()), turn_id=str(uuid4()), text='Xin chào'))
                response.raise_for_status()
            for _ in range(5):
                greeting()
            results['in_process_greeting_http'] = measure(greeting, count)
    return dict(recorded_at=datetime.now(timezone.utc).isoformat(), python=platform.python_version(),
        scope='LOCAL_OFFLINE_CPU_AND_IN_PROCESS_HTTP; provider generation stubbed; no Pi/mic/network/TTS',
        results=results)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--iterations', type=int, default=100)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not 10 <= args.iterations <= 10000:
        parser.error('--iterations must be 10..10000')
    result = run(args.iterations)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False, indent=2))
