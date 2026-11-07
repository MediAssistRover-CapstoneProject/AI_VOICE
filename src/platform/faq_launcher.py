"""Start/reuse the local Studio from this project, never another checkout.

No Spring backend, camera, microphone or robot is started by this command.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import webbrowser

ROOT = Path(__file__).resolve().parents[2]


def runtime_info(root=ROOT):
    root=Path(root).resolve()
    return dict(service='MAR_STUDIO',project_root=str(root),faq_file=str(root/'data/faqs.json'),
                documents_dir=str(root/'data/documents'))


def same_project(value, root=ROOT):
    if not isinstance(value,dict) or value.get('service')!='MAR_STUDIO':return False
    try:return Path(value['project_root']).resolve()==Path(root).resolve()
    except (KeyError,TypeError,OSError,ValueError):return False


def read_runtime(port,timeout=.7):
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(f'http://127.0.0.1:{port}/api/runtime',timeout=timeout) as response:
            raw=response.read(16385)
            if response.status!=200 or len(raw)>16384:return None
            value=json.loads(raw)
            return value if isinstance(value,dict) else None
    except (OSError,ValueError,urllib.error.URLError):return None


def port_in_use(port):
    try:
        with socket.create_connection(('127.0.0.1',port),timeout=.4):return True
    except OSError:return False


def wait_ready(child,port,root=ROOT,timeout=12):
    deadline=time.monotonic()+timeout
    while time.monotonic()<deadline:
        if child.poll() is not None:return False
        if same_project(read_runtime(port),root):return True
        time.sleep(.1)
    return False


def stop_child(child):
    if child is None or child.poll() is not None:return
    if os.name=='nt':
        # Windows venv launchers can own another Python process. Stop only
        # this newly started PID's tree, so Ctrl+C does not orphan the port.
        subprocess.run(['taskkill','/PID',str(child.pid),'/T','/F'],capture_output=True,
                       timeout=8,creationflags=subprocess.CREATE_NO_WINDOW,check=False)
        try:child.wait(timeout=5)
        except subprocess.TimeoutExpired:child.kill();child.wait(timeout=5)
    else:
        child.terminate()
        try:child.wait(timeout=5)
        except subprocess.TimeoutExpired:child.kill();child.wait(timeout=5)


def monitor_existing(port,root):
    try:
        while same_project(read_runtime(port),root):time.sleep(.5)
        return 1
    except KeyboardInterrupt:return 0


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port',type=int,default=7860)
    parser.add_argument('--page',choices=('/', '/faq'),default='/')
    browser=parser.add_mutually_exclusive_group()
    browser.add_argument('--open-browser',action='store_true')
    browser.add_argument('--no-browser',action='store_true')
    parser.add_argument('--check',action='store_true',help='Inspect paths and port only; never start a process')
    parser.add_argument('--supervised',action='store_true',help='Keep a reuse monitor alive for the system launcher')
    args=parser.parse_args(argv)
    if not 1<=args.port<=65535:parser.error('Port must be 1..65535')
    existing=read_runtime(args.port)
    reused=same_project(existing,ROOT)
    occupied=bool(existing) or port_in_use(args.port)
    url=f'http://127.0.0.1:{args.port}{args.page}'
    if args.check:
        print(json.dumps(dict(**runtime_info(ROOT),url=url,reusable=reused,port_available=not occupied),ensure_ascii=False))
        return 0 if reused or not occupied else 1
    if occupied and not reused:
        other=existing.get('project_root','a different or older service') if existing else 'a different or older service'
        print(f'Port {args.port} is serving {other}. Close that service before starting {ROOT}.',file=sys.stderr)
        return 2
    print(f'FAQ file: {ROOT / "data/faqs.json"}',flush=True)
    if reused:
        print(f'Studio is already running from this folder: {url}',flush=True)
        if args.open_browser:webbrowser.open(url)
        if args.supervised:return monitor_existing(args.port,ROOT)
        return 0
    child=None;previous_break=None
    try:
        if os.name=='nt':
            def interrupted(_signum,_frame):raise KeyboardInterrupt
            previous_break=signal.signal(signal.SIGBREAK,interrupted)
        env=dict(os.environ,PYTHONIOENCODING='utf-8')
        child=subprocess.Popen([sys.executable,'-m','uvicorn','src.web.app:app','--host','127.0.0.1','--port',str(args.port)],
            cwd=ROOT,env=env,creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
        if not wait_ready(child,args.port,ROOT):
            print('Studio did not become ready. Check the error above or the occupied port.',file=sys.stderr)
            return 1
        print(f'Studio ready: {url} — Ctrl+C to stop.',flush=True)
        if args.open_browser:webbrowser.open(url)
        return child.wait()
    except KeyboardInterrupt:return 0
    finally:
        stop_child(child)
        if previous_break is not None:signal.signal(signal.SIGBREAK,previous_break)


if __name__=='__main__':raise SystemExit(main())
