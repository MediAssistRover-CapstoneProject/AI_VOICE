"""Foreground laptop BE launcher. Credentials stay in env; data volumes persist."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import sys

from .runtime import load_environment
from .faq_launcher import stop_child

ROOT = Path(__file__).resolve().parents[2]
PROJECT = 'mar-laptop-voice'


def tool(name, candidates):
    found=shutil.which(name)
    if found:return Path(found)
    for candidate in candidates:
        if Path(candidate).is_file():return Path(candidate)
    raise ValueError('Không tìm thấy '+name+'. Kiểm tra cài đặt công cụ trước khi chạy.')


def tools():
    program_files=Path(os.getenv('ProgramFiles','C:/Program Files'))
    java_candidates=list((program_files/'Eclipse Adoptium').glob('jdk-21*/bin/java.exe'))
    java_candidates+=list((Path.home()/'.jdks').glob('*21*/bin/java.exe'))
    java=next((p for p in java_candidates if p.is_file()),None)
    if java is None:java=tool('java',[])
    docker=tool('docker',[program_files/'Docker/Docker/resources/bin/docker.exe'])
    return java,docker


def java_environment(root, port, inherited=None):
    env=load_environment(root/'backend',load_environment(root,inherited))
    required=['MYSQL_ROOT_PASSWORD','MYSQL_PASSWORD','JWT_SECRET','DEVICE_JWT_SECRET']
    missing=[key for key in required if not env.get(key)]
    if missing:raise ValueError('Thiếu cấu hình trong backend/.env: '+', '.join(missing))
    env.update(DB_URL='jdbc:mysql://127.0.0.1:3306/'+env.get('MYSQL_DATABASE','backend_robot_v1'),
        DB_USERNAME=env.get('MYSQL_USER','mediassist'),DB_PASSWORD=env['MYSQL_PASSWORD'],
        SERVER_ADDRESS='127.0.0.1',SERVER_PORT=str(port),PUBLIC_BASE_URL=f'http://127.0.0.1:{port}',
        QDRANT_URL='http://127.0.0.1:6333',MQTT_ENABLED='false',MQTT_DISPATCH_ENABLED='false',
        MAPPING_ENABLED='false',AI_SENSING_ENABLED='false',OPERATIONS_SCHEDULER_ENABLED='false',
        ASSISTANT_VOICE_ACTIONS_ENABLED='false',ASSISTANT_PI_BRIDGE_ENABLED='false',
        ASSISTANT_ENABLED='true',TTS_ENABLED='true',
        CORS_ALLOWED_ORIGINS='http://127.0.0.1:7860,http://localhost:7860',PYTHONIOENCODING='utf-8')
    return env


def needs_build(backend, jar):
    if not jar.is_file():return True
    stamp=jar.stat().st_mtime_ns
    return any(p.is_file() and p.stat().st_mtime_ns>stamp
               for p in [backend/'pom.xml',*(backend/'src').rglob('*')])


def occupied(port):
    try:
        with socket.create_connection(('127.0.0.1',port),timeout=.4):return True
    except OSError:return False


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port',type=int,default=8080)
    parser.add_argument('--build',action='store_true',help='Rebuild even if the JAR is current')
    parser.add_argument('--check',action='store_true',help='Check configuration only; start nothing')
    parser.add_argument('--keep-data',action='store_true',help='Leave this test DB/Qdrant running after BE exits')
    args=parser.parse_args(argv)
    if not 1<=args.port<=65535:parser.error('Port phải nằm trong khoảng 1..65535')
    backend=ROOT/'backend';jar=backend/'target/backend-0.0.1-SNAPSHOT.jar'
    try:
        java,docker=tools()
        env=java_environment(ROOT,args.port)
        build=args.build or needs_build(backend,jar)
    except (OSError,ValueError) as error:
        print(str(error),file=sys.stderr);return 1
    if args.check:
        print(json.dumps(dict(project_root=str(ROOT),java=str(java),docker=str(docker),
            jar=str(jar),build_required=build,database_project=PROJECT,url=f'http://127.0.0.1:{args.port}'),ensure_ascii=False,indent=2))
        return 0
    if occupied(args.port):
        print(f'Cổng {args.port} đang được dùng. Dừng BE cũ trước khi chạy.',file=sys.stderr);return 1
    if build:
        program_files=Path(os.getenv('ProgramFiles','C:/Program Files'))
        try:
            maven=tool('mvn',list((program_files/'JetBrains').glob('*/plugins/maven/lib/maven3/bin/mvn.cmd')))
            build_env=dict(env,JAVA_HOME=str(java.parent.parent))
            build_env['PATH']=str(java.parent)+os.pathsep+build_env.get('PATH','')
            subprocess.run([str(maven),'-B','--no-transfer-progress','-DskipTests','package'],cwd=backend,env=build_env,check=True)
        except (OSError,ValueError,subprocess.CalledProcessError) as error:
            print('Không build được BE: '+str(error),file=sys.stderr);return 1
    compose=[str(docker),'compose','--project-name',PROJECT,'--file',str(backend/'compose.yaml')]
    data_env=dict(env,MYSQL_PORT='127.0.0.1:3306')
    child=None;data_attempted=False;previous_break=None
    try:
        if os.name=='nt':
            def interrupted(_signum,_frame):raise KeyboardInterrupt
            previous_break=signal.signal(signal.SIGBREAK,interrupted)
        data_attempted=True
        subprocess.run(compose+['up','-d','--wait','--wait-timeout','90','mysql','qdrant'],cwd=backend,env=data_env,check=True)
        print(f'BE local: http://127.0.0.1:{args.port}/swagger-ui/index.html',flush=True)
        print('Ctrl+C để dừng BE. Log Java hiển thị ngay trong terminal này.',flush=True)
        child=subprocess.Popen([str(java),'-Xms128m','-Xmx768m','-jar',str(jar)],cwd=backend,env=env,
            creationflags=subprocess.CREATE_NEW_PROCESS_GROUP if os.name=='nt' else 0)
        return child.wait()
    except KeyboardInterrupt:return 0
    except (OSError,subprocess.CalledProcessError) as error:
        print('Không khởi động được BE: '+str(error),file=sys.stderr);return 1
    finally:
        if child is not None and child.poll() is None and os.name=='nt':
            try:child.send_signal(signal.CTRL_BREAK_EVENT);child.wait(timeout=8)
            except (OSError,subprocess.TimeoutExpired):pass
        stop_child(child)
        if data_attempted and (not args.keep_data or child is None):
            try:subprocess.run(compose+['stop','mysql','qdrant'],cwd=backend,env=data_env,check=False,timeout=40)
            except (OSError,subprocess.TimeoutExpired):print('Chưa dừng được dịch vụ dữ liệu; kiểm tra Docker Desktop.',file=sys.stderr)
        if previous_break is not None:signal.signal(signal.SIGBREAK,previous_break)


if __name__=='__main__':raise SystemExit(main())
