import io
import numpy as np
import unittest, os
from unittest.mock import patch
from PIL import Image
from fastapi import FastAPI
from fastapi.testclient import TestClient
from src.vision.web.admin_camera import AdminCamera, CameraError
from src.vision.web import admin_router

class Clock:
    value=10.
    def __call__(self):return self.value

class Engine:
    def __init__(self):self.shapes=[]
    def infer(self,frame):self.shapes.append(frame.shape);return []

def jpeg():
    buffer=io.BytesIO();Image.fromarray(np.zeros((480,640,3),dtype=np.uint8)).save(buffer,format='JPEG');return buffer.getvalue()

def setup():
    clock=Clock();engine=Engine();service=AdminCamera(engine,clock);return service,clock,engine

class AdminCameraTests(unittest.TestCase):
    def test_preview_crops_before_engine_and_never_delivers_events(self):
        s,t,e=setup();sid=s.start('admin','browser',50)['sessionId'];result=s.frame('admin',sid,1,0,jpeg())
        assert e.shapes==[(240,640,3)]
        assert result['fallState']=='NO_PERSON'
        assert result['simulation'] is True and result['deliveryEnabled'] is False
        assert s.snapshot('admin',sid).startswith(b'\xff\xd8')

    def test_missing_frames_hide_stale_observation_and_snapshot(self):
        s,t,e=setup();sid=s.start('admin','browser')['sessionId'];s.frame('admin',sid,1,0,jpeg());t.value+=3
        assert s.state('admin')['status']=='STALE'
        assert s.state('admin')['fallState']=='NO_DATA'
        with self.assertRaises(CameraError):s.snapshot('admin',sid)

    def test_session_ownership_old_frames_and_stop_are_enforced(self):
        s,t,e=setup();sid=s.start('one','browser')['sessionId']
        with self.assertRaises(CameraError):s.start('two','browser')
        with self.assertRaises(CameraError):s.stop('two',sid)
        s.frame('one',sid,1,0,jpeg());t.value+=1
        with self.assertRaises(CameraError):s.frame('one',sid,1,.5,jpeg())
        s.stop('one',sid)
        with self.assertRaises(CameraError):s.frame('one',sid,2,1,jpeg())

    @patch.dict(os.environ, {}, clear=False)
    def test_robot_requires_explicit_server_allowlist(self):
        s,t,e=setup();os.environ['VISION_ROBOT_STREAM_URL']='http://192.168.1.15:8090/video';os.environ.pop('VISION_CAMERA_ALLOWED_HOSTS',None)
        assert s.config()['robotConfigured'] is False
        os.environ['VISION_CAMERA_ALLOWED_HOSTS']='192.168.1.15';assert s.config()['robotConfigured'] is True
        os.environ['VISION_ROBOT_STREAM_URL']='http://user:secret@192.168.1.15:8090/video';assert s.config()['robotConfigured'] is False

    def test_frame_validation_and_boundaries(self):
        s,t,e=setup();sid=s.start('admin','browser')['sessionId']
        for data in [b'bad',b'\xff\xd8'+b'x'*(s.MAX_BYTES+1)]:
            with self.assertRaises(CameraError):s.frame('admin',sid,1,0,data)
        with self.assertRaises(CameraError):s.frame('admin',sid,1,float('nan'),jpeg())
        assert not e.shapes

    def test_http_requires_admin_and_rejects_live_flag(self):
        app=FastAPI();app.include_router(admin_router.router);client=TestClient(app)
        assert client.get('/api/admin/camera/config').status_code==401
        app.dependency_overrides[admin_router.admin]=lambda:'admin'
        response=client.post('/api/admin/camera/start',json={'source':'browser','simulation':False})
        assert response.status_code==422
        self.addCleanup(patch.stopall);patch.object(admin_router,'camera_service',AdminCamera(Engine(),Clock())).start()
        sid=client.post('/api/admin/camera/start',json={'source':'browser'}).json()['sessionId']
        response=client.post('/api/admin/camera/frame',content=jpeg(),headers={'Content-Type':'image/jpeg','X-Session-Id':sid,'X-Frame-Seq':'1','X-Sample-Time':'0'})
        assert response.status_code==200
        assert response.json()['deliveryEnabled'] is False
