#!/bin/sh
set -eu
. /opt/ros/humble/setup.sh
. /ws/install/setup.sh
exec ros2 launch mediassist_voice_bridge mediassist_voice.launch.py backend_url:="${MEDIASSIST_BACKEND_URL}" input_device:="${ALSA_INPUT_DEVICE:-}" output_device:="${ALSA_OUTPUT_DEVICE:-}" ca_certificate:="${MEDIASSIST_CA_CERT:-}" language:="${MEDIASSIST_LANGUAGE:-vi-VN}" wake_mode:="${MEDIASSIST_WAKE_MODE:-auto}" serial_port:="${WONDERECHO_SERIAL_PORT:-/dev/wonderecho}" vosk_model_path:="${VOSK_MODEL_PATH:-/opt/mediassist/vosk-model}" vosk_model_sha256:="${VOSK_MODEL_SHA256:-}"
