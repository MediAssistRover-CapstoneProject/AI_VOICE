# MediAssist voice bridge for MentorPi M1

This is an overlay ROS 2 Humble workspace. Copy `src/mediassist_voice_bridge` next to the Hiwonder packages or run the supplied Docker Compose stack. The hospital launch file never starts the vendor large-model/motor-control demo nodes.

1. Set `MACHINE_TYPE=MentorPi_Mecanum`, run `sh preflight.sh`, and record the ALSA input/output names.
2. Set `MEDIASSIST_BACKEND_URL`, `ALSA_INPUT_DEVICE`, and `ALSA_OUTPUT_DEVICE` in a Pi-local protected environment file. For authentication, set either `MEDIASSIST_DEVICE_JWT`, or `MEDIASSIST_ROBOT_CODE` plus `MEDIASSIST_DEVICE_SECRET` to enable automatic JWT rotation.
3. For offline fallback, mount a pinned Vosk model at `./models/vosk-model`, put its checksum in `MODEL_SHA256`, and set the same value in `VOSK_MODEL_SHA256`. WonderEcho `/dev/wonderecho` remains the preferred detector.
4. Run `docker compose up --build voice-bridge`.
5. Say `Hello MAR`, or start manually with `ros2 service call /mediassist_voice/start_conversation std_srvs/srv/Trigger {}`. The compatibility service `/mediassist_voice/start_capture` starts the same full conversation.
6. Confirm a pending navigation/escort/SOS with `ros2 service call /mediassist_voice/confirm_action std_srvs/srv/Trigger {}`.

After an introduction, audio capture waits at most 10 seconds for speech. Silence plays the configured goodbye and returns to `WAKE_LISTENING`. The journal stores only paths and idempotency keys for unacknowledged turns; input WAV files are deleted after a successful backend acknowledgement. A refreshed JWT is written to the shared state volume with mode `0600` and is never published on a ROS topic. The bridge never publishes `/cmd_vel` or accepts movement parameters from AI output.
