from contextlib import redirect_stdout,redirect_stderr
import io
from pathlib import Path
from unittest.mock import patch

from src.platform import backend_local as launcher


def test_local_environment_uses_loopback_and_keeps_credentials_off_commands(tmp_path):
    (tmp_path/'backend').mkdir()
    (tmp_path/'backend/.env').write_text('MYSQL_ROOT_PASSWORD=unit-root-password\nMYSQL_PASSWORD=unit-db-password\nJWT_SECRET=unit-jwt-secret\nDEVICE_JWT_SECRET=unit-device-secret\n',encoding='utf-8')
    env=launcher.java_environment(tmp_path,8080,{})
    assert env['DB_URL'].startswith('jdbc:mysql://127.0.0.1:3306/')
    assert env['DB_PASSWORD']=='unit-db-password'
    assert env['SERVER_ADDRESS']=='127.0.0.1'
    assert env['MQTT_ENABLED']==env['MQTT_DISPATCH_ENABLED']=='false'
    assert env['ASSISTANT_VOICE_ACTIONS_ENABLED']=='false'


def test_check_never_starts_or_stops_services(tmp_path):
    with patch.object(launcher,'ROOT',tmp_path),patch.object(launcher,'tools',return_value=(Path('java.exe'),Path('docker.exe'))),\
         patch.object(launcher,'java_environment',return_value={'DB_PASSWORD':'unit-private-password'}),\
         patch.object(launcher,'needs_build',return_value=False),patch.object(launcher.subprocess,'run') as run,\
         patch.object(launcher.subprocess,'Popen') as start,redirect_stdout(io.StringIO()) as out:
        assert launcher.main(['--check'])==0
    run.assert_not_called();start.assert_not_called()
    assert 'unit-private-password' not in out.getvalue()


def test_busy_be_port_does_not_start_data_or_another_java(tmp_path):
    with patch.object(launcher,'ROOT',tmp_path),patch.object(launcher,'tools',return_value=(Path('java.exe'),Path('docker.exe'))),\
         patch.object(launcher,'java_environment',return_value={}),patch.object(launcher,'needs_build',return_value=False),\
         patch.object(launcher,'occupied',return_value=True),patch.object(launcher.subprocess,'run') as run,\
         patch.object(launcher.subprocess,'Popen') as start,redirect_stderr(io.StringIO()):
        assert launcher.main([])==1
    run.assert_not_called();start.assert_not_called()
