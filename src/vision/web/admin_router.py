"""ADMIN-authenticated previews, independent of device-authenticated event delivery."""
import hashlib, os, threading, time
import httpx
from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import Response
from pydantic import BaseModel, ConfigDict, Field
from .admin_camera import camera_service, CameraError

router=APIRouter(prefix='/api/admin/camera')
_cache={}
_cache_lock=threading.Lock()

def admin(request: Request):
    bearer=request.headers.get('authorization','')
    if not bearer.startswith('Bearer '):raise HTTPException(401,'Cần đăng nhập ADMIN.')
    key=hashlib.sha256(bearer.encode()).hexdigest()
    with _cache_lock:
        cached=_cache.get(key)
        if cached and cached[0]>time.monotonic():return cached[1]
    try:
        response=httpx.get(os.getenv('BACKEND_URL','http://localhost:8080').rstrip('/')+'/api/v1/auth/me',headers={'Authorization':bearer},timeout=3,follow_redirects=False)
    except httpx.HTTPError:raise HTTPException(503,'Không kết nối được backend để kiểm tra quyền ADMIN.')
    if response.status_code!=200:raise HTTPException(401,'Phiên đăng nhập đã hết hạn.')
    user=response.json()
    if user.get('role')!='ADMIN':raise HTTPException(403,'Chỉ ADMIN được sử dụng camera kiểm thử.')
    owner=str(user.get('accountId',''))
    if not owner:raise HTTPException(403,'Thiếu danh tính ADMIN.')
    with _cache_lock:
        if len(_cache)>32:_cache.clear()
        _cache[key]=(time.monotonic()+2,owner)
    return owner

class Start(BaseModel):
    model_config=ConfigDict(extra='forbid')
    source:str
    cropTopPercent:int=Field(default=0,ge=0,le=70)

class Stop(BaseModel):
    model_config=ConfigDict(extra='forbid')
    sessionId:str|None=None

def invoke(fn,*args):
    try:return fn(*args)
    except CameraError as e:raise HTTPException(e.status,e.detail)

@router.get('/config')
def config(owner=Depends(admin)):return camera_service.config()

@router.post('/start')
def start(body:Start,owner=Depends(admin)):return invoke(camera_service.start,owner,body.source,body.cropTopPercent)

@router.post('/stop')
def stop(body:Stop,owner=Depends(admin)):return invoke(camera_service.stop,owner,body.sessionId)

@router.get('/state')
def state(owner=Depends(admin)):return invoke(camera_service.state,owner)

@router.get('/snapshot')
def snapshot(sessionId:str,owner=Depends(admin)):
    return Response(invoke(camera_service.snapshot,owner,sessionId),media_type='image/jpeg',headers={'Cache-Control':'no-store'})

@router.post('/frame')
async def frame(request:Request,owner=Depends(admin)):
    if not request.headers.get('content-type','').startswith('image/jpeg'):raise HTTPException(415,'Cần image/jpeg.')
    data=bytearray()
    async for part in request.stream():
        data.extend(part)
        if len(data)>camera_service.MAX_BYTES:raise HTTPException(413,'Khung JPEG vượt quá 2 MB.')
    try:
        sid=request.headers['x-session-id'];seq=int(request.headers['x-frame-seq']);stamp=float(request.headers['x-sample-time'])
    except (KeyError,ValueError):raise HTTPException(422,'Thiếu thông tin thứ tự/thời điểm khung hình.')
    from starlette.concurrency import run_in_threadpool
    return await run_in_threadpool(invoke,camera_service.frame,owner,sid,seq,stamp,bytes(data))
