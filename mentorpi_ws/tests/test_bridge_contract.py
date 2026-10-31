from pathlib import Path
import ast
import unittest

ROOT = Path(__file__).parents[1] / 'src' / 'mediassist_voice_bridge'


class BridgeContractTest(unittest.TestCase):
    def test_all_python_sources_parse(self):
        for path in ROOT.rglob('*.py'):
            ast.parse(path.read_text(encoding='utf-8'), filename=str(path))

    def test_bridge_never_publishes_motor_topics(self):
        source = '\n'.join(p.read_text(encoding='utf-8') for p in ROOT.rglob('*.py'))
        self.assertNotIn('create_publisher(Twist', source)
        self.assertNotIn("'/controller/cmd_vel'", source)
        self.assertNotIn("'/cmd_vel'", source)
        self.assertNotIn("'token': token", source)

    def test_hospital_launch_excludes_vendor_ai_control_nodes(self):
        launch = (ROOT / 'launch' / 'mediassist_voice.launch.py').read_text(encoding='utf-8')
        for executable in ('agent_process', 'llm_control_move', 'function_call', 'vllm_navigation'):
            self.assertNotIn(f"executable='{executable}'", launch)

    def test_audio_contract_is_bounded_and_pcm16(self):
        capture = (ROOT / 'mediassist_voice_bridge' / 'audio_capture_node.py').read_text(encoding='utf-8')
        self.assertIn("'sample_rate', 16000", capture)
        self.assertIn("'max_seconds', 20", capture)
        self.assertIn("'max_bytes', 5 * 1024 * 1024", capture)
        self.assertIn("dtype='int16'", capture)


if __name__ == '__main__':
    unittest.main()
