"""Supervise independent voice/vision environments from one project root.

Only `run` starts services. Backend containers start only with --backend-compose.
The launcher's own imports never initialize microphones, cameras, AI or auth.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import threading
import time
from urllib.parse import urlsplit, urlunsplit


ROOT = Path(__file__).resolve().parents[2]


def load_environment(root: Path, inherited: dict[str, str] | None = None) -> dict[str, str]:
    """Read simple KEY=value .env entries; inherited environment takes precedence.

    No shell evaluation/interpolation; credentials are passed only in env.
    """
    env = dict(os.environ if inherited is None else inherited)
    path = root / ".env"
    if not path.is_file():
        return env
    for raw in path.read_text(encoding="utf-8-sig").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[7:].lstrip()
        key, sep, value = line.partition("=")
        key = key.strip()
        if not sep or not key.isidentifier():
            continue
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        else:
            value = value.split(" #", 1)[0].rstrip()
        env.setdefault(key, value)
    return env


def load_config(root: Path, filename: str | None = None) -> dict:
    path = Path(filename) if filename else root / "config" / "system.json"
    if not path.is_absolute():
        path = root / path
    config = json.loads(path.read_text(encoding="utf-8-sig"))
    for name in ("voice", "vision", "dashboard"):
        section = config[name]
        if section.get("host", "127.0.0.1") not in {"127.0.0.1", "localhost"}:
            raise ValueError(f"{name}.host must be localhost/127.0.0.1 for this launcher")
        port = section.get("port")
        if isinstance(port, bool) or not isinstance(port, int) or not 1 <= port <= 65535:
            raise ValueError(f"Invalid {name}.port")
    ports = [config[name]["port"] for name in ("voice", "vision", "dashboard")]
    if len(set(ports)) != len(ports):
        raise ValueError("Voice, vision and dashboard ports must be different")
    return config


def choose_python(root: Path, service: str, configured: str = "auto") -> str:
    if configured != "auto":
        path = Path(configured)
        if not path.is_absolute():
            local = root / path
            if local.is_file():
                return str(local)
            executable = shutil.which(configured)
            if executable:
                return executable
        if path.is_file():
            return str(path)
        raise ValueError(f"Python interpreter not found for {service}; configure --python-{service}")
    for folder in (f".venv-{service}", ".venv", "venv"):
        for suffix in ("Scripts/python.exe", "bin/python"):
            candidate = root / folder / suffix
            if candidate.is_file():
                return str(candidate)
    return sys.executable


def public_url(value: str) -> str:
    parts = urlsplit(value)
    if parts.scheme not in {"http", "https"} or not parts.hostname:
        return ""
    host = f"[{parts.hostname}]" if ":" in parts.hostname else parts.hostname
    if parts.port:
        host += ":" + str(parts.port)
    return urlunsplit((parts.scheme, host, parts.path.rstrip("/"), "", ""))


@dataclass(frozen=True)
class ServicePlan:
    name: str
    command: tuple[str, ...]
    cwd: Path
    env: dict[str, str] = field(repr=False)
    url: str = ""


def build_plan(root: Path, config: dict, env: dict[str, str], *, profile: str = "local",
               source: str | None = None, no_voice: bool = False, no_vision: bool = False,
               voice_python: str | None = None, vision_python: str | None = None,
               live_events: bool = False, backend_compose: bool = False) -> list[ServicePlan]:
    if profile not in {"local", "integrated"}:
        raise ValueError("Unknown profile")
    if live_events and profile != "integrated":
        raise ValueError("--live-events requires --profile integrated")
    shared = dict(env)
    shared.setdefault("BACKEND_URL", config["backend_url"])
    shared.setdefault("ROBOT_CODE", config["robot_code"])
    shared["PYTHONIOENCODING"] = "utf-8"
    plans = []
    if backend_compose:
        compose = (root / config["backend"]["compose_file"]).resolve()
        plans.append(ServicePlan("backend", ("docker", "compose", "--file", str(compose), "up", "--build"),
                                 compose.parent, shared.copy(), public_url(shared["BACKEND_URL"])))
    for name, disabled, module, override in (
        ("voice", no_voice, "src.web.app:app", voice_python),
        ("vision", no_vision, "src.vision.web.app:app", vision_python),
    ):
        section = config[name]
        if disabled or not section.get("enabled", True):
            continue
        service_env = shared.copy()
        if name == "vision":
            service_env.update({
                "VISION_SOURCE": source if source is not None else env.get("VISION_SOURCE", section.get("source", "0")),
                "VISION_MODE": env.get("VISION_MODE", section.get("mode", "lower_body")),
                "VISION_DELIVERY_ENABLED": "true" if profile == "integrated" else "false",
                "VISION_SIMULATION": "false" if live_events else "true",
            })
            # Preview mode does not claim the laptop webcam before the admin clicks.
            service_env["VISION_AUTOSTART_CAMERA"] = "true" if source is not None or live_events else "false"
            chosen_source = str(service_env["VISION_SOURCE"])
            if live_events and not (chosen_source.isdigit() or chosen_source.startswith(("/dev/video", "http://", "https://", "rtsp://"))):
                raise ValueError("Không gửi cảnh báo thật từ video phát lại; bỏ --live-events khi thử file.")
            if not service_env.get("VISION_MODEL"):
                service_env["VISION_MODEL"] = str(root / section.get("model", "models/vision/yolov8n-pose.onnx"))
        python = choose_python(root, name, override or section.get("python", "auto"))
        host, port = section.get("host", "127.0.0.1"), section["port"]
        command = ((python, '-m', 'src.platform.faq_launcher', '--port', str(port), '--no-browser', '--supervised')
                   if name == 'voice' else (python, '-m', 'uvicorn', module, '--host', host, '--port', str(port)))
        plans.append(ServicePlan(name, command,
                                 root, service_env, f"http://{host}:{port}"))
    return plans


def probe_dependencies(plan: ServicePlan) -> dict:
    if plan.name == "backend":
        return {"available": shutil.which("docker") is not None, "missing": [] if shutil.which("docker") else ["docker"]}
    modules = ("fastapi", "uvicorn", "numpy", "sounddevice", "dotenv", "rich", "httpx") if plan.name == "voice" else (
        "fastapi", "uvicorn", "numpy", "cv2", "ultralytics", "mediapipe", "dotenv", "httpx")
    code = "import importlib.util,json; names=" + repr(modules) + "; print(json.dumps([n for n in names if importlib.util.find_spec(n) is None]))"
    try:
        result = subprocess.run([plan.command[0], "-c", code], cwd=plan.cwd, env=plan.env,
                                capture_output=True, text=True, timeout=20, check=False)
        if result.returncode:
            return {"available": False, "missing": ["interpreter_unusable"]}
        missing = json.loads(result.stdout.strip().splitlines()[-1])
        return {"available": not missing, "missing": missing}
    except (OSError, subprocess.TimeoutExpired, ValueError, IndexError):
        return {"available": False, "missing": ["interpreter_unusable"]}


def model_ready(plan: ServicePlan) -> bool:
    if plan.name != "vision":
        return True
    path = Path(plan.env["VISION_MODEL"]).expanduser()
    if not path.is_absolute():
        path = plan.cwd / path
    return path.is_file()


class Supervisor:
    def __init__(self, plans: list[ServicePlan]):
        self.plans = plans
        self.processes: dict[str, subprocess.Popen] = {}

    def start(self):
        try:
            for plan in self.plans:
                flags = subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0
                self.processes[plan.name] = subprocess.Popen(list(plan.command), cwd=plan.cwd, env=plan.env,
                                                            creationflags=flags)
        except Exception:
            self.stop()
            raise

    def status(self):
        return [{"name": p.name, "url": p.url,
                 "state": "not_started" if p.name not in self.processes else
                          "running" if self.processes[p.name].poll() is None else "exited",
                 "exit_code": self.processes[p.name].poll() if p.name in self.processes else None}
                for p in self.plans]

    def stop(self):
        for plan in reversed(self.plans):
            process = self.processes.get(plan.name)
            if process is None or process.poll() is not None:
                continue
            try:
                if os.name == "nt":
                    process.send_signal(signal.CTRL_BREAK_EVENT)
                else:
                    process.terminate()
                process.wait(timeout=10)
            except (OSError, subprocess.TimeoutExpired):
                process.kill()
                process.wait(timeout=5)
        self.processes.clear()


def dashboard_handler(supervisor: Supervisor, profile: str, backend: str):
    page = Path(__file__).with_name("dashboard.html").read_bytes()
    links = {"kiosk": backend + "/assistant-kiosk.html", "staff": backend + "/vision-staff.html"}
    voice = next((p for p in supervisor.plans if p.name == 'voice'), None)
    if voice:links['faq'] = voice.url.rstrip('/') + '/faq'

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            return

        def do_GET(self):
            if self.path in {"/", "/index.html"}:
                body, content_type = page, "text/html; charset=utf-8"
            elif self.path in {"/api/system", "/healthz"}:
                body = json.dumps({"profile": profile, "services": supervisor.status(), "links": links}).encode()
                content_type = "application/json"
            else:
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header("Content-Type", content_type)
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
    return Handler


def main(argv=None):
    parser = argparse.ArgumentParser(description="MediAssist Voice + Vision + Backend")
    parser.add_argument("command", nargs="?", choices=("check", "run"), default="check")
    parser.add_argument("--profile", choices=("local", "integrated"), default="local")
    parser.add_argument("--source", help="Webcam index, video path, or Pi camera URL")
    parser.add_argument("--config")
    parser.add_argument("--no-voice", action="store_true")
    parser.add_argument("--no-vision", action="store_true")
    parser.add_argument("--voice-python", "--python-voice", dest="python_voice")
    parser.add_argument("--vision-python", "--python-vision", dest="python_vision")
    parser.add_argument("--backend-compose", action="store_true", help="Explicitly start backend containers; data volumes are preserved")
    parser.add_argument("--live-events", action="store_true", help="Integrated profile: send live events instead of simulation events")
    args = parser.parse_args(argv)
    try:
        config = load_config(ROOT, args.config)
        env = load_environment(ROOT)
        plans = build_plan(ROOT, config, env, profile=args.profile, source=args.source,
                           no_voice=args.no_voice, no_vision=args.no_vision,
                           voice_python=args.python_voice, vision_python=args.python_vision,
                           live_events=args.live_events, backend_compose=args.backend_compose)
    except (ValueError, OSError, KeyError) as exc:
        parser.error(str(exc))
    if not plans:
        parser.error("No services enabled")
    checks = [{"name": p.name, "url": p.url, "model_file_present": model_ready(p), **probe_dependencies(p)} for p in plans]
    identity_configured = bool(env.get("DEVICE_SECRET")) and bool(env.get("ROBOT_CODE", config["robot_code"]))
    identity_required = args.profile == "integrated" and any(p.name == "vision" for p in plans)
    identity_ready = not identity_required or identity_configured
    if args.command == "check":
        print(json.dumps({"profile": args.profile, "services": checks,
                          "vision_delivery_enabled": args.profile == "integrated",
                          "simulation": not args.live_events, "device_identity_configured": identity_configured}, indent=2))
        return 0 if identity_ready and all(c["available"] and c["model_file_present"] for c in checks) else 1
    if not all(c["available"] and c["model_file_present"] for c in checks):
        print("Missing service dependencies or vision model file. Run check and configure the required files/environment.", file=sys.stderr)
        return 1
    if not identity_ready:
        print("Integrated vision requires DEVICE_SECRET in the root .env.", file=sys.stderr)
        return 1
    supervisor = Supervisor(plans)
    section = config["dashboard"]
    server = ThreadingHTTPServer((section.get("host", "127.0.0.1"), section["port"]),
                                 dashboard_handler(supervisor, args.profile, public_url(env.get("BACKEND_URL", config["backend_url"]))))
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    previous_break = None
    if os.name == "nt" and threading.current_thread() is threading.main_thread():
        def shutdown_on_break(signum, frame):
            raise KeyboardInterrupt
        previous_break = signal.signal(signal.SIGBREAK, shutdown_on_break)
    try:
        supervisor.start()
        thread.start()
        print(f"MediAssist system: http://127.0.0.1:{section['port']} (profile={args.profile}); Ctrl+C to stop")
        while True:
            if any(s["state"] == "exited" for s in supervisor.status()):
                print("A service stopped; stopping the remaining services.", file=sys.stderr)
                return 1
            time.sleep(0.5)
    except KeyboardInterrupt:
        return 0
    finally:
        if thread.is_alive():
            server.shutdown()
        server.server_close()
        supervisor.stop()
        if previous_break is not None:
            signal.signal(signal.SIGBREAK, previous_break)
