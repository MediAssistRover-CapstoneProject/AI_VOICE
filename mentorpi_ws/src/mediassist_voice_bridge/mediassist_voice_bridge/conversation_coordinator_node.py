import json

import rclpy
from rclpy.node import Node
from std_msgs.msg import String
from std_srvs.srv import Trigger


class ConversationCoordinatorNode(Node):
    def __init__(self):
        super().__init__('mediassist_conversation_coordinator')
        self.declare_parameter('language', 'vi-VN')
        self.declare_parameter('intro_text_vi', 'Xin chào, tôi là MAR, trợ lý thông tin của Bệnh viện MediAssist. Tôi có thể giúp bạn tìm khoa phòng, tra cứu quy trình và trả lời câu hỏi chung. Bạn cần tôi hỗ trợ gì?')
        self.declare_parameter('goodbye_text_vi', 'Phiên hỗ trợ đã kết thúc. Khi cần, hãy nói Hello MAR.')
        self.declare_parameter('intro_text_en', "Hello, I am MAR, MediAssist Hospital's information assistant. How may I help you?")
        self.declare_parameter('goodbye_text_en', 'This support session has ended. Say Hello MAR whenever you need me.')
        self._state = 'BOOTSTRAPPING'
        self._state_pub = self.create_publisher(String, '/mediassist_voice/conversation_state', 10)
        self._prompt = self.create_publisher(String, '/mediassist_voice/system_prompt_request', 10)
        self._capture = self.create_client(Trigger, '/mediassist_voice/capture_once')
        self.create_subscription(String, '/mediassist_voice/wake_detected', self._wake, 10)
        self.create_subscription(String, '/mediassist_voice/playback_finished', self._playback_finished, 10)
        self.create_subscription(String, '/mediassist_voice/state', self._bridge_state, 10)
        self.create_service(Trigger, '/mediassist_voice/start_conversation', self._manual)
        self.create_service(Trigger, '/mediassist_voice/start_capture', self._manual)
        self.create_timer(1.0, self._boot)

    def _boot(self):
        if self._state == 'BOOTSTRAPPING': self._set_state('WAKE_LISTENING')

    def _wake(self, _msg):
        if self._state == 'WAKE_LISTENING': self._begin()

    def _manual(self, _request, response):
        if self._state != 'WAKE_LISTENING':
            response.success, response.message = False, 'a conversation is already active'
        else:
            self._begin(); response.success, response.message = True, 'conversation started'
        return response

    def _begin(self):
        self._set_state('INTRO_PLAYING'); self._request_prompt('INTRO', self._intro())

    def _start_capture(self):
        if not self._capture.wait_for_service(timeout_sec=2.0):
            self.get_logger().error('audio capture service is unavailable'); self._end(); return
        self._set_state('FOLLOW_UP_LISTENING')
        future = self._capture.call_async(Trigger.Request())
        future.add_done_callback(lambda result: self._capture_result(result))

    def _capture_result(self, future):
        try:
            if not future.result().success: self._end()
        except Exception: self._end()

    def _bridge_state(self, msg):
        if msg.data == 'NO_SPEECH' and self._state == 'FOLLOW_UP_LISTENING': self._end()
        elif msg.data in ('UPLOADING', 'WAITING'): self._set_state('PROCESSING')
        elif msg.data == 'PLAYING' and self._state not in ('INTRO_PLAYING', 'GOODBYE_PLAYING'): self._set_state('ANSWER_PLAYING')

    def _playback_finished(self, msg):
        try: data=json.loads(msg.data); kind=data.get('kind', 'ANSWER'); status=data.get('status')
        except Exception: return
        if status != 'PLAYED': self._end(); return
        if kind == 'INTRO': self._start_capture()
        elif kind == 'ANSWER': self._start_capture()
        elif kind == 'GOODBYE': self._set_state('WAKE_LISTENING')

    def _end(self):
        if self._state in ('ENDING', 'GOODBYE_PLAYING', 'WAKE_LISTENING'): return
        self._set_state('GOODBYE_PLAYING'); self._request_prompt('GOODBYE', self._goodbye())

    def _request_prompt(self, kind, text):
        msg=String(); msg.data=json.dumps({'kind': kind, 'text': text, 'language': self.get_parameter('language').value}, ensure_ascii=False); self._prompt.publish(msg)

    def _intro(self): return self.get_parameter('intro_text_en' if self._english() else 'intro_text_vi').value
    def _goodbye(self): return self.get_parameter('goodbye_text_en' if self._english() else 'goodbye_text_vi').value
    def _english(self): return self.get_parameter('language').value.lower().startswith('en')
    def _set_state(self, value): self._state=value; msg=String(); msg.data=value; self._state_pub.publish(msg)


def main(args=None):
    rclpy.init(args=args); node=ConversationCoordinatorNode()
    try: rclpy.spin(node)
    finally: node.destroy_node(); rclpy.shutdown()
