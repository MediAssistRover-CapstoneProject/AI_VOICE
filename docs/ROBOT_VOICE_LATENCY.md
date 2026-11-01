# Robot voice latency changes

Applies to `python -m src.main` (the Python robot engine), not the web kiosk or ROS bridge.

- End-of-speech silence: 700 ms (previously 1500 ms). Increase if slow speakers are cut off.
- Intent classification: local rules, no extra classification LLM request. Unknown questions go through hospital RAG.
- Reviewed FAQ: a vector point must have metadata `approved: true`, `visibility: PUBLIC`, a nonempty `source`, and `questions` containing the exact question. Its text is the reviewed answer. No embedding/model request is needed. No hospital FAQ has been invented or marked approved by this change; hospital staff must review entries before enabling this path.
- Repeated RAG questions: up to 128 entries, TTL 300 seconds. Changes to loaded point text/metadata invalidate answers. Restart/reload the engine after updating the on-disk vector database; there is no automatic knowledge-admin synchronization.
- Audio: bounded 16 MiB in-memory cache, keyed by exact text/language/speed. Resets on process restart.
- Speech is requested and played sentence by sentence; this is not token/audio streaming. The complete answer still has to be generated before the first sentence is spoken.
- Interaction reporting happens after speech, preserving event order. It can still delay listening for the next question.
- No-source questions return `Tôi không trả lời thông tin này được.` without a second broad backend search.

Validation: run `python -m pytest tests/test_voice_latency.py tests/test_voice_engine.py tests/test_rag.py -q`.

Measurement: `[LATENCY] STT + intent + retrieval + answer` plus first `[LATENCY] TTS to playback` plus the 700 ms silence interval approximates end-of-user-speech to first playback. Measure cold and repeated questions on the actual robot/network. Five seconds is a target, not a guaranteed deadline: STT, LLM and TTS provider requests remain dependent on network/service latency. No physical robot was deployed or benchmarked in this workspace.
