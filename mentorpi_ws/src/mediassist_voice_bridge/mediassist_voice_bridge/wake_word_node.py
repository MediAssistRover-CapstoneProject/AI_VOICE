import hashlib
import json
import os
import threading

import rclpy
from rclpy.node import Node
from std_msgs.msg import String

WAKE_FRAME = b'\xaa\x55\x03\x00\xfb'


def contains_wake_frame(buffer):
    return WAKE_FRAME in buffer


class WakeWordNode(Node):
    def __init__(self):
        super().__init__('mediassist_wake_word')
        self.declare_parameter('mode', 'auto')
        self.declare_parameter('serial_port', '/dev/wonderecho')
        self.declare_parameter('input_device', '')
        self.declare_parameter('sample_rate', 16000)
        self.declare_parameter('wake_phrases', ['hello mar', 'hello ma', 'hê lô mar'])
        self.declare_parameter('vosk_model_path', '/opt/mediassist/vosk-model')
        self.declare_parameter('vosk_model_sha256', '')
        self._enabled = False
        self._stop = threading.Event()
        self._publisher = self.create_publisher(String, '/mediassist_voice/wake_detected', 10)
        self.create_subscription(String, '/mediassist_voice/conversation_state', self._state, 10)

    def _state(self, msg):
        enabled = msg.data == 'WAKE_LISTENING'
        if enabled == self._enabled:
            return
        self._enabled = enabled
        self._stop.set()
        if enabled:
            self._stop = threading.Event()
            threading.Thread(target=self._listen, args=(self._stop,), daemon=True).start()

    def _listen(self, stop):
        mode = self.get_parameter('mode').value
        if mode in ('auto', 'hardware') and self._hardware(stop):
            return
        if mode == 'hardware':
            self.get_logger().error('WonderEcho wake mode requested but /dev/wonderecho is unavailable')
            return
        self._vosk(stop)

    def _hardware(self, stop):
        try:
            import serial
            port = self.get_parameter('serial_port').value
            with serial.Serial(port, 115200, timeout=0.25) as source:
                buffer = b''
                while self._enabled and not stop.is_set():
                    buffer = (buffer + source.read(32))[-128:]
                    if contains_wake_frame(buffer):
                        self._publish('wonder_echo', 'hello mar'); return True
            return True
        except Exception as exc:
            self.get_logger().warning('WonderEcho wake unavailable, using Vosk fallback: %s' % exc)
            return False

    def _vosk(self, stop):
        try:
            import sounddevice as sd
            from vosk import KaldiRecognizer, Model
            model_path = self.get_parameter('vosk_model_path').value
            self._verify_model(model_path)
            phrases = list(self.get_parameter('wake_phrases').value)
            rate = int(self.get_parameter('sample_rate').value)
            recognizer = KaldiRecognizer(Model(model_path), rate, json.dumps(phrases, ensure_ascii=False))
            device = self.get_parameter('input_device').value or None
            with sd.RawInputStream(samplerate=rate, blocksize=4000, device=device, channels=1, dtype='int16') as stream:
                while self._enabled and not stop.is_set():
                    data, _overflow = stream.read(4000)
                    if recognizer.AcceptWaveform(bytes(data)):
                        text = json.loads(recognizer.Result()).get('text', '').strip().lower()
                        if any(phrase.lower() in text for phrase in phrases):
                            self._publish('vosk', text); return
        except Exception as exc:
            self.get_logger().error('Vosk wake detector failed: %s' % exc)

    def _verify_model(self, model_path):
        if not os.path.isdir(model_path):
            raise RuntimeError('Vosk model directory is missing: ' + model_path)
        expected = self.get_parameter('vosk_model_sha256').value.strip().lower()
        if not expected:
            return
        marker = os.path.join(model_path, 'MODEL_SHA256')
        if not os.path.isfile(marker):
            raise RuntimeError('MODEL_SHA256 marker is missing')
        with open(marker, encoding='utf-8') as source:
            actual = source.read().strip().lower()
        if actual != expected or len(actual) != 64:
            raise RuntimeError('Vosk model checksum does not match the pinned version')

    def _publish(self, detector, text):
        msg = String(); msg.data = json.dumps({'detector': detector, 'text': text}, ensure_ascii=False)
        self._publisher.publish(msg)


def main(args=None):
    rclpy.init(args=args); node = WakeWordNode()
    try: rclpy.spin(node)
    finally: node.destroy_node(); rclpy.shutdown()
