# Local voice setup — implementation status

Implemented in this revision:
- BackendClient accepts `ca_cert_path` or `BACKEND_CA_CERT`; system CA trust remains enabled.
- Escort resolves room endpoints, asks for explicit confirmation, expires after 60 seconds and sends an idempotency key. Ambiguous/missing endpoints fail closed. Receipt means accepted, not robot moving.
- SOS uses the actual `/api/v1/pi/incidents/sos` envelope contract. No promise of staff arrival.
- Both actions require `voice_actions_enabled: true` in config; leave disabled until integration acceptance.
- Optional local adapters: faster-whisper, SentenceTransformer, Ollama, Piper. No implicit model downloads or cloud fallback from these adapters.
- Vector indexes identify the embedding provider; semantic models reject legacy indexes. Build a NEW index when changing models.
- Independent model warmup health process: `/healthz`, `/readyz`. This process is a readiness probe, not yet the LAN inference service; its models are not shared with VoiceEngine.
- Read-only migration comparison: `python -m src.check_migrations PATH_TO_OFFICIAL_MIGRATIONS`. No SQL/history renaming is performed.

Install regular dependencies from requirements.txt; inference laptop additionally needs requirements-local.txt. Set:

```
STT_PROVIDER=local
LOCAL_STT_MODEL=/absolute/path/to/faster-whisper-model
LOCAL_STT_DEVICE=cpu
LOCAL_STT_COMPUTE=int8
EMBEDDING_PROVIDER=semantic
LOCAL_EMBEDDING_MODEL=/absolute/path/to/sentence-transformer-model
VECTOR_DB_PATH=/absolute/path/to/new-semantic-index.json
LLM_PROVIDER=ollama
OLLAMA_URL=http://127.0.0.1:11434
LOCAL_LLM_MODEL=qwen3:4b
TTS_PROVIDER=piper
LOCAL_TTS_MODEL=/absolute/path/to/vi-voice.onnx
BACKEND_CA_CERT=/absolute/path/to/hospital-ca.pem
```

Keep voice model files and adjacent Piper JSON config installed locally. Review model/voice licensing before deployment. Use the index-builder help to specify semantic provider and a new output file; never overwrite the current index before verification. Documents currently disagree on hospital facts; select a reviewed source directory first.

Not completed or validated on hardware:
- Consolidating Flyway V38 with the official backend (official migration directory/history needed).
- USB microphone/speaker selection on Pi.
- Shared LAN inference service, automatic barge-in, and one priority audio queue shared with Nav2/system announcements.
- Real model loading/inference benchmark and five-second acceptance test.
- Deployment to robot or live action requests.

Provider references:
https://github.com/SYSTRAN/faster-whisper
https://github.com/ollama/ollama/blob/main/docs/api.md
https://www.sbert.net/docs/package_reference/sentence_transformer/model.html
https://github.com/OHF-Voice/piper1-gpl/blob/main/src/piper/voice.py
