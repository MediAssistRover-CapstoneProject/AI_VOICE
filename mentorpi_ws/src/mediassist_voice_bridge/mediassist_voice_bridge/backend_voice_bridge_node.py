import base64
import json
import os
import sqlite3
import threading
import time
import uuid

import rclpy
from rclpy.node import Node
from std_msgs.msg import String
from std_srvs.srv import Trigger

import requests


class BackendVoiceBridgeNode(Node):
    def __init__(self):
        super().__init__('mediassist_backend_voice_bridge')
        self.declare_parameter('backend_url', 'https://localhost')
        self.declare_parameter('device_token_env', 'MEDIASSIST_DEVICE_JWT')
        self.declare_parameter('robot_code_env', 'MEDIASSIST_ROBOT_CODE')
        self.declare_parameter('device_secret_env', 'MEDIASSIST_DEVICE_SECRET')
        self.declare_parameter('device_token_file', '/var/lib/mediassist-voice/device.jwt')
        self.declare_parameter('language', 'vi-VN')
        self.declare_parameter('ca_certificate', '')
        self.declare_parameter('request_timeout_seconds', 90)
        self.declare_parameter('journal_path', '/var/lib/mediassist-voice/journal.sqlite3')
        self._session_id = None
        self._device_token = None
        self._last_confirmation = None
        self._busy = threading.Lock()
        self._state = self.create_publisher(String, '/mediassist_voice/state', 10)
        self._transcript = self.create_publisher(String, '/mediassist_voice/transcript', 10)
        self._answer = self.create_publisher(String, '/mediassist_voice/answer', 10)
        self._error = self.create_publisher(String, '/mediassist_voice/error', 10)
        self._receipt = self.create_publisher(String, '/mediassist_voice/action_receipt', 10)
        self._playback = self.create_publisher(String, '/mediassist_voice/playback_command', 10)
        self.create_subscription(String, '/mediassist_voice/captured_audio', self._captured, 10)
        self.create_subscription(String, '/mediassist_voice/playback_finished', self._playback_finished, 10)
        self.create_service(Trigger, '/mediassist_voice/confirm_action', self._confirm)
        self._db = self._open_journal()
        self.create_timer(15.0, self._retry_pending)

    def _open_journal(self):
        path = self.get_parameter('journal_path').value
        os.makedirs(os.path.dirname(path), mode=0o750, exist_ok=True)
        db = sqlite3.connect(path, check_same_thread=False)
        db.execute('CREATE TABLE IF NOT EXISTS pending_turn(idempotency_key TEXT PRIMARY KEY, audio_path TEXT NOT NULL, created_at REAL NOT NULL, attempts INTEGER NOT NULL DEFAULT 0)')
        db.commit(); return db

    def _captured(self, msg):
        try:
            path = json.loads(msg.data)['path']; key = str(uuid.uuid4())
            self._db.execute('INSERT OR IGNORE INTO pending_turn(idempotency_key,audio_path,created_at) VALUES(?,?,?)', (key, path, time.time())); self._db.commit()
            threading.Thread(target=self._upload, args=(key, path), daemon=True).start()
        except Exception as exc: self._publish_error('JOURNAL_FAILED', str(exc))

    def _upload(self, key, path):
        if not self._busy.acquire(blocking=False): return
        try:
            token = self._token(); self._ensure_session(token); self._publish_state('UPLOADING')
            with open(path, 'rb') as audio:
                response = requests.post(self._url(f'/api/v1/pi/assistant/sessions/{self._session_id}/turns'),
                    headers={'Authorization': 'Bearer ' + token, 'Idempotency-Key': key},
                    files={'audio': ('mentorpi.wav', audio, 'audio/wav')}, timeout=self._timeout(), verify=self._verify())
            if response.status_code == 401:
                self._session_id = None
            response.raise_for_status(); turn = response.json()
            self._db.execute('DELETE FROM pending_turn WHERE idempotency_key=?', (key,)); self._db.commit()
            try: os.unlink(path)
            except OSError: pass
            self._publish_state('WAITING')
            self._publish(self._transcript, turn.get('transcript', ''))
            self._publish(self._answer, turn.get('answer', ''))
            if turn.get('confirmationNonce'):
                self._last_confirmation = (turn['turnId'], turn['confirmationNonce'])
                self._publish(self._receipt, json.dumps({'turn_id': turn['turnId'], 'status': 'AWAITING_CONFIRMATION', 'action': turn.get('actionPreview')}, ensure_ascii=False))
            if turn.get('audioUrl') and turn.get('audioSha256'):
                # The DEVICE JWT remains process-local and is never exposed on a ROS topic.
                command = {'turn_id': turn['turnId'], 'url': self._url(turn['audioUrl']), 'sha256': turn['audioSha256']}
                self._publish(self._playback, json.dumps(command)); self._publish_state('PLAYING')
            else: self._publish_state('IDLE')
        except Exception as exc:
            if isinstance(exc, requests.HTTPError) and exc.response is not None and exc.response.status_code == 401:
                self._session_id = None
                try: self._refresh_token()
                except Exception: pass
            self._db.execute('UPDATE pending_turn SET attempts=attempts+1 WHERE idempotency_key=?', (key,)); self._db.commit()
            self._publish_error('BACKEND_UPLOAD_FAILED', str(exc))
        finally: self._busy.release()

    def _ensure_session(self, token):
        if self._session_id: return
        response = requests.post(self._url('/api/v1/pi/assistant/sessions'), params={'language': self.get_parameter('language').value},
                                 headers={'Authorization': 'Bearer ' + token}, timeout=self._timeout(), verify=self._verify())
        response.raise_for_status(); self._session_id = response.json()['sessionId']

    def _confirm(self, _request, response):
        if not self._last_confirmation:
            response.success, response.message = False, 'there is no pending action'
            return response
        turn_id, nonce = self._last_confirmation
        try:
            result = requests.post(self._url(f'/api/v1/pi/assistant/turns/{turn_id}/confirm'),
                headers={'Authorization': 'Bearer ' + self._token(), 'Content-Type': 'application/json'},
                json={'nonce': nonce}, timeout=self._timeout(), verify=self._verify())
            result.raise_for_status(); payload = result.json(); self._last_confirmation = None
            self._publish(self._receipt, json.dumps({'turn_id': turn_id, 'status': 'CONFIRMED', 'service_request_id': payload.get('serviceRequestId'), 'job_id': payload.get('jobId')}))
            response.success, response.message = True, 'action confirmed'
        except Exception as exc: response.success, response.message = False, str(exc)[:500]
        return response

    def _retry_pending(self):
        if self._busy.locked(): return
        row = self._db.execute('SELECT idempotency_key,audio_path FROM pending_turn WHERE attempts<10 ORDER BY created_at LIMIT 1').fetchone()
        if row and os.path.exists(row[1]): threading.Thread(target=self._upload, args=row, daemon=True).start()

    def _playback_finished(self, msg):
        try:
            status = json.loads(msg.data).get('status')
            self._publish_state('IDLE' if status == 'PLAYED' else 'FAILED')
        except Exception: self._publish_state('FAILED')

    def _token(self):
        token = self._device_token or os.environ.get(self.get_parameter('device_token_env').value, '')
        if not token or self._expires_soon(token):
            token = self._refresh_token()
        self._write_token(token)
        return token
    def _refresh_token(self):
        code = os.environ.get(self.get_parameter('robot_code_env').value, '')
        secret = os.environ.get(self.get_parameter('device_secret_env').value, '')
        if not code or not secret:
            raise RuntimeError('DEVICE JWT is missing or expiring and robot credentials are not configured')
        response = requests.post(self._url('/api/v1/pi/auth/token'), json={'robotCode': code, 'deviceSecret': secret},
                                 timeout=self._timeout(), verify=self._verify())
        response.raise_for_status(); self._device_token = response.json()['accessToken']
        self._write_token(self._device_token); return self._device_token
    def _expires_soon(self, token):
        try:
            payload = token.split('.')[1]; payload += '=' * (-len(payload) % 4)
            return float(json.loads(base64.urlsafe_b64decode(payload.encode()).decode())['exp']) <= time.time() + 60
        except Exception:
            return False
    def _write_token(self, token):
        path = self.get_parameter('device_token_file').value
        os.makedirs(os.path.dirname(path), mode=0o750, exist_ok=True)
        temporary = path + '.tmp'
        with open(temporary, 'w', encoding='utf-8') as output: output.write(token)
        os.chmod(temporary, 0o600); os.replace(temporary, path)
    def _url(self, path): return self.get_parameter('backend_url').value.rstrip('/') + path
    def _verify(self): return self.get_parameter('ca_certificate').value or True
    def _timeout(self): return float(self.get_parameter('request_timeout_seconds').value)
    def _publish(self, publisher, value): msg = String(); msg.data = value; publisher.publish(msg)
    def _publish_state(self, state): self._publish(self._state, state)
    def _publish_error(self, code, error): self._publish(self._error, json.dumps({'code': code, 'message': error[:500]})); self._publish_state('FAILED')


def main(args=None):
    rclpy.init(args=args); node = BackendVoiceBridgeNode()
    try: rclpy.spin(node)
    finally: node.destroy_node(); rclpy.shutdown()
