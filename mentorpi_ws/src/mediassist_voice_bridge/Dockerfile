FROM ros:humble-ros-base-jammy
RUN apt-get update && apt-get install -y --no-install-recommends python3-pip python3-sounddevice alsa-utils libportaudio2 && rm -rf /var/lib/apt/lists/*
WORKDIR /ws
COPY . /ws/src/mediassist_voice_bridge
RUN pip3 install --no-cache-dir requests sounddevice webrtcvad-wheels && . /opt/ros/humble/setup.sh && colcon build --symlink-install
COPY docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod 0755 /docker-entrypoint.sh
ENTRYPOINT ["/docker-entrypoint.sh"]
