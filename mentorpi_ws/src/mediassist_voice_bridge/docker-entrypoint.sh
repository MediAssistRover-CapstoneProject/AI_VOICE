#!/bin/sh
set -eu
. /opt/ros/humble/setup.sh
. /ws/install/setup.sh
exec ros2 launch mediassist_voice_bridge mediassist_voice.launch.py backend_url:="${MEDIASSIST_BACKEND_URL}" input_device:="${ALSA_INPUT_DEVICE:-}" output_device:="${ALSA_OUTPUT_DEVICE:-}" ca_certificate:="${MEDIASSIST_CA_CERT:-}" language:="${MEDIASSIST_LANGUAGE:-vi-VN}"
