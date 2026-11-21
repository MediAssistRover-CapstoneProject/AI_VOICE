# Hosted knowledge after BE preprocessing

AI Voice still uses `OPENAI_VECTOR_STORE_ID`. BE uploads cleaned text chunks;
there is no PDF upload to AI Voice and no use of `AI_VOICE/backend`.

Required configuration:

```dotenv
OPENAI_VECTOR_STORE_ID=vs_your_store
BACKEND_URL=https://your-backend-origin
KNOWLEDGE_SERVICE_TOKEN=the-same-random-32-or-more-character-token-as-BE
```

Keep the token private to the two servers. HTTP origins are permitted only on
localhost for development. Set the token and deploy BE's internal resolver
before enabling the new Voice release. Coordinate rollout with uploads paused.

Search retrieves up to 20 candidates from OpenAI. The publication resolver calls
`POST /api/v1/internal/knowledge/resolve` using `X-Knowledge-Token`, obtains only
published files and complete BE chunk content, and builds context from at most
five whole chunks. No generated title/source prefix is inserted into the text.
Source names and file IDs remain separate response metadata. Legacy BE-managed
documents can use their original search snippets, without cutting them mid-text.

The resolver is called again after generation and before replaying an old
document answer. A withdrawn file cannot be reused from the turn cache. If BE
cannot verify publication, chat fails with `AI_KNOWLEDGE_UNAVAILABLE`; it never
falls back to unverified provider content. The model's existing source-only
prompt is retained; generated answers still require factual evaluation with real
documents. No claim of guaranteed latency improvement is made.

This does not change map catalogs, endpoint matching, navigation confirmation,
job creation, STT or TTS. The JSON response contract is unchanged.

Tests:

```text
python -m pytest tests/test_openai_knowledge.py tests/test_knowledge_resolver.py tests/test_rag.py tests/test_robot_api_latency.py -q
```

These tests mock provider responses, and cover withheld/withdrawn data, complete
conditions beyond the old 2400-character cutoff, resolver outages, replay and
existing navigation/SOS behavior. Validate actual OCR/model accuracy and measure
timings with the deployment's hospital documents before acceptance.
