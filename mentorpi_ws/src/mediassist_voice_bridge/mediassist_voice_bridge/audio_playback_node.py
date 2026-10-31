import hashlib
import json
import os
import subprocess
import tempfile
import threading

import rclpy
from rclpy.node import Node
from std_msgs.msg import Bool, String

import requests


class AudioPlaybackNode(Node):
    def __init__(self):
        super().__init__('mediassist_audio_playback')
        self.declare_parameter('output_device', '')
        self.declare_parameter('device_token_env', 'MEDIASSIST_DEVICE_JWT')
        self.declare_parameter('device_token_file', '/var/lib/mediassist-voice/device.jwt')
        self.declare_parameter('ca_certificate', '')
        self.declare_parameter('request_timeout_seconds', 30)
        self._active = self.create_publisher(Bool, '/mediassist_voice/playback_active', 10)
        self._finished = self.create_publisher(String, '/mediassist_voice/playback_finished', 10)
        self._legacy = self.create_publisher(Bool, 'tts_node/play_finish', 10)
        self.create_subscription(String, '/mediassist_voice/playback_command', self._command, 10)

    def _command(self, msg):
        threading.Thread(target=self._play, args=(msg.data,), daemon=True).start()

    def _play(self, raw):
        data = json.loads(raw); turn_id = data.get('turn_id', '')
        active = Bool(); active.data = True; self._active.publish(active)
        path = None
        try:
            verify = self.get_parameter('ca_certificate').value or True
            token_file = self.get_parameter('device_token_file').value
            token = ''
            if token_file and os.path.isfile(token_file):
                with open(token_file, encoding='utf-8') as source: token = source.read().strip()
            if not token:
                token = os.environ.get(self.get_parameter('device_token_env').value, '')
            if not token:
                raise RuntimeError('DEVICE JWT environment variable is missing')
            response = requests.get(data['url'], headers={'Authorization': 'Bearer ' + token},
                                    timeout=float(self.get_parameter('request_timeout_seconds').value), verify=verify)
            response.raise_for_status()
            if not response.content.startswith(b'RIFF') or response.content[8:12] != b'WAVE':
                raise RuntimeError('backend returned invalid WAV')
            actual = hashlib.sha256(response.content).hexdigest()
            if actual != data['sha256']:
                raise RuntimeError('WAV SHA-256 mismatch')
            fd, path = tempfile.mkstemp(prefix='mediassist-play-', suffix='.wav'); os.close(fd)
            with open(path, 'wb') as output: output.write(response.content)
            command = ['aplay', '-q']
            device = self.get_parameter('output_device').value
            if device: command += ['-D', device]
            command.append(path)
            subprocess.run(command, check=True, timeout=90)
            result = String(); result.data = json.dumps({'turn_id': turn_id, 'status': 'PLAYED'})
            self._finished.publish(result)
            legacy = Bool(); legacy.data = True; self._legacy.publish(legacy)
        except Exception as exc:
            result = String(); result.data = json.dumps({'turn_id': turn_id, 'status': 'FAILED', 'error': str(exc)[:500]})
            self._finished.publish(result)
        finally:
            if path:
                try: os.unlink(path)
                except OSError: pass
            active.data = False; self._active.publish(active)


def main(args=None):
    rclpy.init(args=args); node = AudioPlaybackNode()
    try: rclpy.spin(node)
    finally: node.destroy_node(); rclpy.shutdown()
