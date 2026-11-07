"""Unified launch planning tests; no cameras, models, backend or broker started."""
from contextlib import redirect_stdout
import copy
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import MagicMock, patch

from src.platform import runtime


CONFIG = {
    "backend_url": "http://localhost:8080", "robot_code": "MAR-TEST",
    "dashboard": {"host": "127.0.0.1", "port": 7800},
    "voice": {"enabled": True, "python": "auto", "host": "127.0.0.1", "port": 7860},
    "vision": {"enabled": True, "python": "auto", "host": "127.0.0.1", "port": 8001, "mode": "lower_body", "source": "0"},
    "backend": {"compose_file": "backend/compose.yaml"},
}


class SystemLauncherTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.config = copy.deepcopy(CONFIG)

    def tearDown(self):
        self.temp.cleanup()

    def plans(self, **kwargs):
        return runtime.build_plan(self.root, self.config, kwargs.pop("env", {}), **kwargs)

    def test_local_overrides_accidental_delivery_settings(self):
        plans = self.plans(env={"VISION_DELIVERY_ENABLED": "true", "VISION_SIMULATION": "false"})
        vision = next(p for p in plans if p.name == "vision")
        self.assertEqual(vision.env["VISION_DELIVERY_ENABLED"], "false")
        self.assertEqual(vision.env["VISION_SIMULATION"], "true")

    def test_integrated_defaults_simulation(self):
        vision = self.plans(profile="integrated")[1]
        self.assertEqual(vision.env["VISION_DELIVERY_ENABLED"], "true")
        self.assertEqual(vision.env["VISION_SIMULATION"], "true")

    def test_live_requires_integrated(self):
        with self.assertRaisesRegex(ValueError, "integrated"):
            self.plans(live_events=True)
        self.assertEqual(self.plans(profile="integrated", live_events=True)[1].env["VISION_SIMULATION"], "false")

    def test_preview_does_not_claim_webcam_and_replay_cannot_send_live(self):
        self.assertEqual(self.plans()[1].env["VISION_AUTOSTART_CAMERA"], "false")
        self.assertEqual(self.plans(source="0")[1].env["VISION_AUTOSTART_CAMERA"], "true")
        with self.assertRaises(ValueError):
            self.plans(profile="integrated", live_events=True, source="recording.mp4")

    def test_shared_device_identity_and_source(self):
        env = {"BACKEND_URL": "http://hospital.test", "ROBOT_CODE": "R-UNIT", "DEVICE_SECRET": "unit-test-secret"}
        plans = self.plans(env=env, source="http://pi.test:8081/video")
        for plan in plans:
            self.assertEqual(plan.env["ROBOT_CODE"], "R-UNIT")
            self.assertEqual(plan.env["BACKEND_URL"], "http://hospital.test")
        self.assertEqual(plans[1].env["VISION_SOURCE"], "http://pi.test:8081/video")
        self.assertNotIn("unit-test-secret", repr(plans))
        self.assertNotIn("unit-test-secret", " ".join(plans[1].command))

    def test_backend_requires_explicit_flag(self):
        self.assertEqual([p.name for p in self.plans()], ["voice", "vision"])
        self.assertEqual(self.plans(backend_compose=True)[0].name, "backend")

    def test_voice_includes_faq_with_same_folder_guard(self):
        voice = self.plans(no_vision=True)[0]
        self.assertIn('src.platform.faq_launcher',voice.command)
        self.assertIn('--supervised',voice.command)
        self.assertNotIn('0.0.0.0',voice.command)

    def test_can_disable_voice_without_importing_it(self):
        previously_loaded = sys.modules.get("src.web.app")
        self.assertEqual([p.name for p in self.plans(no_voice=True)], ["vision"])
        self.assertIs(sys.modules.get("src.web.app"), previously_loaded)

    def test_separate_interpreters(self):
        voice, vision = self.root/"voice-python.exe", self.root/"vision-python.exe"
        voice.touch(); vision.touch()
        plans = self.plans(voice_python=str(voice), vision_python=str(vision))
        self.assertEqual(plans[0].command[0], str(voice))
        self.assertEqual(plans[1].command[0], str(vision))
        self.assertIn("src.vision.web.app:app", plans[1].command)

    def test_env_file_preserves_inherited_values(self):
        (self.root/".env").write_text('ROBOT_CODE=file-value\nDEVICE_SECRET="unit-test-secret"\nexport BACKEND_URL=http://localhost:8080 # local\n', encoding="utf-8")
        result = runtime.load_environment(self.root, {"ROBOT_CODE": "inherited"})
        self.assertEqual(result["ROBOT_CODE"], "inherited")
        self.assertEqual(result["DEVICE_SECRET"], "unit-test-secret")
        self.assertEqual(result["BACKEND_URL"], "http://localhost:8080")

    def test_config_rejects_conflicting_ports(self):
        self.config["vision"]["port"] = 7860
        (self.root/"config").mkdir()
        (self.root/"config/system.json").write_text(json.dumps(self.config))
        with self.assertRaisesRegex(ValueError, "different"):
            runtime.load_config(self.root)

    def test_config_rejects_public_binding(self):
        self.config["dashboard"]["host"] = "0.0.0.0"
        (self.root/"config").mkdir()
        (self.root/"config/system.json").write_text(json.dumps(self.config))
        with self.assertRaisesRegex(ValueError, "localhost"):
            runtime.load_config(self.root)

    def test_urls_do_not_expose_credentials_or_query(self):
        self.assertEqual(runtime.public_url("https://user:secret@example.test:8080/path?token=secret#x"), "https://example.test:8080/path")
        self.assertEqual(runtime.public_url("javascript:alert(1)"), "")

    def test_supervisor_uses_argument_array_not_shell(self):
        plans = self.plans(source="video & unexpected-command.mp4")
        with patch.object(runtime.subprocess, "Popen") as popen:
            supervisor = runtime.Supervisor(plans)
            supervisor.start()
            self.assertEqual(popen.call_count, 2)
            for call in popen.call_args_list:
                self.assertIsInstance(call.args[0], list)
                self.assertNotIn("shell", call.kwargs)
                self.assertEqual(call.kwargs["cwd"], self.root)

    def test_supervisor_stops_started_children_if_later_start_fails(self):
        first = MagicMock()
        first.poll.return_value = None
        with patch.object(runtime.subprocess, "Popen", side_effect=[first, OSError("cannot start")]):
            supervisor = runtime.Supervisor(self.plans())
            with self.assertRaises(OSError):
                supervisor.start()
            first.wait.assert_called_once()
            self.assertEqual(supervisor.processes, {})

    def test_stuck_child_is_killed_after_grace_period(self):
        process = MagicMock()
        process.poll.return_value = None
        process.wait.side_effect = [subprocess.TimeoutExpired("unit", 10), 0]
        supervisor = runtime.Supervisor(self.plans(no_voice=True))
        supervisor.processes["vision"] = process
        supervisor.stop()
        process.kill.assert_called_once()

    def test_check_never_starts_services_or_http_server(self):
        (self.root/"config").mkdir()
        (self.root/"config/system.json").write_text(json.dumps(self.config))
        (self.root/"models/vision").mkdir(parents=True)
        (self.root/"models/vision/yolov8n-pose.onnx").touch()
        with patch.object(runtime, "ROOT", self.root), patch.object(runtime, "probe_dependencies", return_value={"available": True, "missing": []}), patch.object(runtime, "Supervisor") as supervisor, patch.object(runtime, "ThreadingHTTPServer") as server:
            with redirect_stdout(io.StringIO()) as output:
                code = runtime.main(["check"])
            self.assertEqual(code, 0)
            self.assertFalse(json.loads(output.getvalue())["vision_delivery_enabled"])
            supervisor.assert_not_called()
            server.assert_not_called()

    def test_model_presence_checked_without_loading_model(self):
        vision = self.plans(no_voice=True)[0]
        self.assertFalse(runtime.model_ready(vision))
        path = self.root/"models/vision/yolov8n-pose.onnx"
        path.parent.mkdir(parents=True)
        path.touch()
        self.assertTrue(runtime.model_ready(vision))


if __name__ == "__main__":
    unittest.main()
