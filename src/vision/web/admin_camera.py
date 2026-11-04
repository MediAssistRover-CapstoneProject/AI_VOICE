"""Session-isolated camera previews. This module never creates a VisionReporter."""
from __future__ import annotations
import os, threading, time, uuid
from pathlib import Path
from urllib.parse import urlsplit
from ..core.fall_session import FallSession
from ..core.pose_adapter import yolo_to_mp_landmarks


class CameraError(Exception):
    def __init__(self, status, detail):
        self.status, self.detail = status, detail


class PoseEngine:
    def __init__(self):
        self.model = None

    def infer(self, frame):
        from ultralytics import YOLO
        from ..config import MODEL_DIR
        if self.model is None:
            path = Path(os.getenv('VISION_MODEL', str(MODEL_DIR / 'yolov8n-pose.onnx')))
            if not path.is_file():
                raise CameraError(503, 'Không tìm thấy model pose đã cài trên máy Vision.')
            self.model = YOLO(str(path), task='pose')
        h, w = frame.shape[:2]
        predictions = self.model(frame, imgsz=640, conf=.35, verbose=False)
        poses = []
        for prediction in predictions:
            if prediction.keypoints is None:
                continue
            k = prediction.keypoints
            for i in range(min(len(k), 6)):
                confidence = k.conf[i].tolist() if k.conf is not None else None
                poses.append(yolo_to_mp_landmarks(k.xy[i].tolist(), confidence, w, h))
        return poses


class AdminCamera:
    MAX_BYTES = 2 * 1024 * 1024
    MAX_DIM = 960
    MAX_FPS = 8
    STALE_SEC = 2.5
    LEASE_SEC = 20

    def __init__(self, engine=None, clock=time.monotonic):
        self.engine, self.clock = engine or PoseEngine(), clock
        self.lock, self.inference_lock = threading.RLock(), threading.Lock()
        self.session = None
        self.jpeg = None
        self.thread = None

    def robot_url(self):
        raw = os.getenv('VISION_ROBOT_STREAM_URL', '').strip()
        p = urlsplit(raw)
        allowed = {s.strip().lower() for s in os.getenv('VISION_CAMERA_ALLOWED_HOSTS', '').split(',') if s.strip()}
        if (p.scheme not in {'http', 'https', 'rtsp'} or not p.hostname or p.hostname.lower() not in allowed
                or p.username or p.password or p.query or p.fragment):
            raise CameraError(422, 'Cấu hình VISION_ROBOT_STREAM_URL và VISION_CAMERA_ALLOWED_HOSTS trên máy Vision trước khi dùng camera Pi.')
        return raw

    def config(self):
        try:
            self.robot_url()
            configured = True
        except CameraError:
            configured = False
        return {'mode':'lower_body', 'simulation':True, 'deliveryEnabled':False,
                'browserFrameMaxBytes':self.MAX_BYTES, 'maxFrameDimension':self.MAX_DIM,
                'maxFps':self.MAX_FPS, 'robotConfigured':configured,
                'trainedForLowCamera':False, 'confidenceCalibrated':False}

    def _owned(self, owner, sid=None, touch=True):
        s = self.session
        if s is None or (sid and sid != s['sessionId']):
            raise CameraError(409, 'Phiên camera đã kết thúc hoặc thay đổi. Hãy kết nối lại.')
        if s['owner'] != owner:
            raise CameraError(409, 'Camera đang được một quản trị viên khác sử dụng.')
        if self.clock() - s['touch'] > self.LEASE_SEC:
            self._stop()
            raise CameraError(409, 'Phiên camera hết hạn. Hãy kết nối lại.')
        if touch:s['touch'] = self.clock()
        return s

    def start(self, owner, source, crop=0):
        if source not in {'browser', 'robot'} or isinstance(crop, bool) or not isinstance(crop, int) or not 0 <= crop <= 70:
            raise CameraError(422, 'Nguồn camera hoặc tỷ lệ cắt không hợp lệ.')
        stream = self.robot_url() if source == 'robot' else None
        with self.lock:
            if self.session and self.clock()-self.session['touch'] <= self.LEASE_SEC and self.session['owner'] != owner:
                raise CameraError(409, 'Camera đang được một quản trị viên khác sử dụng.')
            self._stop()
            sid = str(uuid.uuid4())
            self.session = {'sessionId':sid, 'source':source, 'owner':owner, 'cropTopPercent':crop,
                            'tracker':FallSession(mode='lower_body'), 'stop':threading.Event(),
                            'status':'WAITING_FRAME', 'fallState':'NO_DATA', 'people':[], 'metrics':{}, 'events':[],
                            'frameSeq':-1, 'sampleTimeSec':None, 'lastFrameAt':None, 'touch':self.clock(),
                            'processingMs':None, 'frames':0, 'started':self.clock(), 'error':None}
            if source == 'robot':
                self.thread = threading.Thread(target=self._robot_worker, args=(sid, stream), daemon=True)
                self.thread.start()
            return self.state(owner)

    def _stop(self):
        if self.session:
            self.session['stop'].set()
            self.session['tracker'].close()
        self.session = None
        self.jpeg = None

    def stop(self, owner, sid=None):
        with self.lock:
            if self.session:
                self._owned(owner, sid)
                self._stop()
        return {'status':'STOPPED', 'simulation':True, 'deliveryEnabled':False}

    def close(self):
        with self.lock:
            self._stop()
        if self.thread and self.thread is not threading.current_thread():
            self.thread.join(timeout=4)

    def state(self, owner, touch=True):
        with self.lock:
            if not self.session:
                return {'status':'STOPPED','fallState':'NO_DATA','simulation':True,'deliveryEnabled':False}
            s = self._owned(owner,touch=touch)
            age = None if s['lastFrameAt'] is None else self.clock()-s['lastFrameAt']
            stale = age is not None and age > self.STALE_SEC
            return {k:s[k] for k in ('sessionId','source','cropTopPercent','frameSeq','sampleTimeSec','processingMs','error')} | {
                'status':'STALE' if stale else s['status'], 'fallState':'NO_DATA' if stale else s['fallState'],
                'people':[] if stale else s['people'], 'metrics':{} if stale else s['metrics'],
                'events':[] if stale else s['events'], 'lastFrameAgeSec':None if age is None else round(age,2),
                'simulation':True,'deliveryEnabled':False,
                'inferenceFps':round(s['frames']/max(.001,self.clock()-s['started']),2),
                'trainedForLowCamera':False,'confidenceCalibrated':False}

    def snapshot(self, owner, sid):
        with self.lock:
            s = self._owned(owner, sid)
            if self.jpeg is None or s['lastFrameAt'] is None or self.clock()-s['lastFrameAt']>self.STALE_SEC:
                raise CameraError(404, 'Chưa có khung hình mới để hiển thị.')
            return self.jpeg

    def frame(self, owner, sid, seq, sample_time, data):
        import cv2, numpy as np, math
        if len(data)>self.MAX_BYTES:
            raise CameraError(413, 'Khung JPEG vượt quá 2 MB.')
        if len(data)<4 or not data.startswith(b'\xff\xd8'):
            raise CameraError(422, 'Cần khung hình JPEG hợp lệ.')
        if not math.isfinite(sample_time) or sample_time<0 or seq<0:
            raise CameraError(422, 'Thứ tự/thời điểm khung hình không hợp lệ.')
        # Reject huge JPEG dimensions before decoding/allocation.
        from PIL import Image
        import io
        try:
            with Image.open(io.BytesIO(data)) as image:
                if max(image.size)>1920 or min(image.size)<32:
                    raise CameraError(422, 'Khung đầu vào phải từ 32 đến 1920 pixel mỗi chiều.')
        except CameraError:
            raise
        except Exception:
            raise CameraError(422, 'Không đọc được JPEG.')
        with self.lock:
            s=self._owned(owner,sid,touch=False)
            if s['source']!='browser':raise CameraError(409,'Phiên này đang dùng camera robot.')
        frame = cv2.imdecode(np.frombuffer(data,dtype=np.uint8),cv2.IMREAD_COLOR)
        if frame is None:raise CameraError(422,'Không giải mã được JPEG.')
        return self._process(owner,sid,seq,sample_time,frame)

    def _process(self, owner, sid, seq, sample_time, frame):
        import cv2
        if not self.inference_lock.acquire(blocking=False):
            raise CameraError(429,'Đang xử lý khung trước. Hãy bỏ qua khung này.')
        try:
            with self.lock:
                s=self._owned(owner,sid,touch=False)
                if seq<=s['frameSeq'] or (s['sampleTimeSec'] is not None and sample_time<=s['sampleTimeSec']):
                    raise CameraError(409,'Khung hình cũ hoặc đảo thứ tự.')
                if s['lastFrameAt'] is not None and self.clock()-s['lastFrameAt']<1/self.MAX_FPS:
                    raise CameraError(429,'Tốc độ khung hình vượt giới hạn.')
                crop=s['cropTopPercent']
            h,w=frame.shape[:2];frame=frame[int(h*crop/100):,:]
            h,w=frame.shape[:2]
            if max(h,w)>self.MAX_DIM:
                scale=self.MAX_DIM/max(h,w);frame=cv2.resize(frame,(int(w*scale),int(h*scale)))
            started=self.clock();poses=self.engine.infer(frame);h,w=frame.shape[:2]
            with self.lock:
                s=self._owned(owner,sid,touch=False)
                observed=s['tracker'].update(poses,w,h,sample_time)
                for pose in poses:
                    for a,b in ((23,24),(23,25),(25,27),(24,26),(26,28)):
                        p,q=pose[a],pose[b]
                        if min(p.visibility,q.visibility)>=.5:
                            cv2.line(frame,(int(p.x*w),int(p.y*h)),(int(q.x*w),int(q.y*h)),(40,210,190),2)
                    for i in (23,24,25,26,27,28):
                        p=pose[i]
                        if p.visibility>=.5:cv2.circle(frame,(int(p.x*w),int(p.y*h)),4,(50,190,250),-1)
                cv2.putText(frame,observed['fall_state'],(10,25),cv2.FONT_HERSHEY_SIMPLEX,.6,(40,150,240),2)
                ok,jpg=cv2.imencode('.jpg',frame,[cv2.IMWRITE_JPEG_QUALITY,75])
                if ok:self.jpeg=jpg.tobytes()
                s.update(status='RUNNING',fallState=observed['fall_state'],people=observed['results'],
                         metrics={'width':w,'height':h,'detectedPeople':len(poses)},events=observed['events'],
                         frameSeq=seq,sampleTimeSec=sample_time,lastFrameAt=self.clock(),frames=s['frames']+1,
                         processingMs=round((self.clock()-started)*1000,1),error=None)
                return self.state(owner,touch=False)
        except CameraError:
            raise
        except Exception:
            with self.lock:
                if self.session and self.session['sessionId']==sid:
                    self.session.update(status='ERROR',fallState='NO_DATA',error='Không xử lý được model hoặc khung camera.')
                    self.jpeg=None
            raise CameraError(503,'Không xử lý được model hoặc khung camera. Kiểm tra log Vision.')
        finally:
            self.inference_lock.release()

    def _robot_worker(self, sid, stream):
        import cv2
        cap=None
        try:
            cap=cv2.VideoCapture(stream,cv2.CAP_FFMPEG,[cv2.CAP_PROP_OPEN_TIMEOUT_MSEC,3000,cv2.CAP_PROP_READ_TIMEOUT_MSEC,2000])
            if not cap.isOpened():raise RuntimeError('Camera Pi chưa kết nối được.')
            seq=0;started=self.clock()
            while True:
                with self.lock:
                    s=self.session
                    if not s or s['sessionId']!=sid or s['stop'].is_set():break
                    if self.clock()-s['touch']>self.LEASE_SEC:self._stop();break
                    owner=s['owner']
                ok,frame=cap.read()
                if not ok:raise RuntimeError('Mất kết nối camera Pi.')
                seq+=1
                try:self._process(owner,sid,seq,self.clock()-started,frame)
                except CameraError as e:
                    if e.status!=429:raise
                time.sleep(1/self.MAX_FPS)
        except Exception:
            with self.lock:
                if self.session and self.session['sessionId']==sid:
                    self.session.update(status='ERROR',fallState='NO_DATA',error='Không đọc được camera Pi. Kiểm tra địa chỉ, mạng và streamer.')
                    self.jpeg=None
        finally:
            if cap is not None:cap.release()


camera_service = AdminCamera()
