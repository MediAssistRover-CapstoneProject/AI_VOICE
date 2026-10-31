import json
import os
import tempfile
import threading
import time
import wave

import rclpy
from rclpy.node import Node
from std_msgs.msg import Bool, String
from std_srvs.srv import Trigger

try:
    import sounddevice as sd
    import webrtcvad
except ImportError:  # reported at runtime with a useful ROS error
    sd = None
    webrtcvad = None


class AudioCaptureNode(Node):
    def __init__(self):
        super().__init__('mediassist_audio_capture')
        self.declare_parameter('input_device', '')
        self.declare_parameter('sample_rate', 16000)
        self.declare_parameter('max_seconds', 20)
        self.declare_parameter('max_bytes', 5 * 1024 * 1024)
        self.declare_parameter('silence_seconds', 1.2)
        self.declare_parameter('vad_aggressiveness', 2)
        self.declare_parameter('capture_directory', '/var/lib/mediassist-voice/captures')
        self._recording = False
        self._playing = False
        self._stop = threading.Event()
        self._captured = self.create_publisher(String, '/mediassist_voice/captured_audio', 10)
        self._state = self.create_publisher(String, '/mediassist_voice/state', 10)
        self._error = self.create_publisher(String, '/mediassist_voice/error', 10)
        self.create_subscription(Bool, '/mediassist_voice/playback_active', self._playback, 10)
        self.create_service(Trigger, '/mediassist_voice/start_capture', self._start)
        self.create_service(Trigger, '/mediassist_voice/cancel_capture', self._cancel)

    def _playback(self, msg):
        self._playing = bool(msg.data)

    def _start(self, _request, response):
        if sd is None or webrtcvad is None:
            response.success, response.message = False, 'sounddevice/webrtcvad is not installed'
        elif self._playing:
            response.success, response.message = False, 'playback is active; microphone remains muted'
        elif self._recording:
            response.success, response.message = False, 'capture is already active'
        else:
            self._recording = True
            self._stop.clear()
            threading.Thread(target=self._capture, daemon=True).start()
            response.success, response.message = True, 'capture started'
        return response

    def _cancel(self, _request, response):
        self._stop.set()
        response.success, response.message = True, 'capture cancellation requested'
        return response

    def _capture(self):
        self._publish_state('RECORDING')
        rate = int(self.get_parameter('sample_rate').value)
        max_seconds = int(self.get_parameter('max_seconds').value)
        max_bytes = int(self.get_parameter('max_bytes').value)
        silence_seconds = float(self.get_parameter('silence_seconds').value)
        frame_ms, frame_samples = 30, rate * 30 // 1000
        device = self.get_parameter('input_device').value or None
        vad = webrtcvad.Vad(int(self.get_parameter('vad_aggressiveness').value))
        frames, heard_speech, silence_frames = [], False, 0
        try:
            with sd.RawInputStream(samplerate=rate, blocksize=frame_samples, device=device,
                                   channels=1, dtype='int16') as stream:
                deadline = time.monotonic() + max_seconds
                while time.monotonic() < deadline and not self._stop.is_set():
                    raw, overflow = stream.read(frame_samples)
                    if overflow:
                        self.get_logger().warning('audio input overflow')
                    data = bytes(raw)
                    speech = vad.is_speech(data, rate)
                    frames.append(data)
                    heard_speech = heard_speech or speech
                    silence_frames = 0 if speech else silence_frames + 1
                    if sum(map(len, frames)) + 44 > max_bytes:
                        raise RuntimeError('captured audio exceeds max_bytes')
                    if heard_speech and silence_frames * frame_ms / 1000.0 >= silence_seconds:
                        break
            if self._stop.is_set():
                self._publish_state('CANCELLED')
            elif not heard_speech:
                self._publish_error('NO_SPEECH', 'No speech detected')
            else:
                directory = self.get_parameter('capture_directory').value
                os.makedirs(directory, mode=0o750, exist_ok=True)
                fd, path = tempfile.mkstemp(prefix='turn-', suffix='.wav', dir=directory)
                os.close(fd)
                with wave.open(path, 'wb') as out:
                    out.setnchannels(1); out.setsampwidth(2); out.setframerate(rate)
                    out.writeframes(b''.join(frames))
                msg = String(); msg.data = json.dumps({'path': path, 'sample_rate': rate})
                self._captured.publish(msg)
                self._publish_state('UPLOADING')
        except Exception as exc:
            self._publish_error('CAPTURE_FAILED', str(exc))
        finally:
            self._recording = False

    def _publish_state(self, value):
        msg = String(); msg.data = value; self._state.publish(msg)

    def _publish_error(self, code, message):
        msg = String(); msg.data = json.dumps({'code': code, 'message': message[:500]})
        self._error.publish(msg)
        self._publish_state('FAILED')


def main(args=None):
    rclpy.init(args=args); node = AudioCaptureNode()
    try: rclpy.spin(node)
    finally: node.destroy_node(); rclpy.shutdown()
