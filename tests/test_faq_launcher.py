"""Launch ownership and path checks only; never open a browser or a real process."""
from contextlib import redirect_stdout, redirect_stderr
import io
from pathlib import Path
from unittest.mock import Mock, patch

from src.platform import faq_launcher as launcher


def test_identity_contains_real_file_location(tmp_path):
    info=launcher.runtime_info(tmp_path)
    assert Path(info['faq_file'])==tmp_path/'data/faqs.json'
    assert launcher.same_project(info,tmp_path)
    assert not launcher.same_project(info,tmp_path/'other')
    assert not launcher.same_project({'service':'different','project_root':str(tmp_path)},tmp_path)


def test_check_has_no_side_effects(tmp_path):
    with patch.object(launcher,'ROOT',tmp_path),patch.object(launcher,'read_runtime',return_value=None),\
         patch.object(launcher,'port_in_use',return_value=False),patch.object(launcher.subprocess,'Popen') as start,\
         patch.object(launcher.webbrowser,'open') as browser,redirect_stdout(io.StringIO()):
        assert launcher.main(['--check','--page','/faq'])==0
    start.assert_not_called();browser.assert_not_called()


def test_occupied_other_checkout_is_not_reused_or_stopped(tmp_path):
    other=launcher.runtime_info(tmp_path/'other')
    with patch.object(launcher,'ROOT',tmp_path),patch.object(launcher,'read_runtime',return_value=other),\
         patch.object(launcher.subprocess,'Popen') as start,patch.object(launcher,'stop_child') as stop,\
         patch.object(launcher.webbrowser,'open') as browser,redirect_stderr(io.StringIO()):
        assert launcher.main(['--page','/faq','--open-browser'])==2
    start.assert_not_called();stop.assert_not_called();browser.assert_not_called()


def test_unknown_occupied_port_is_not_claimed(tmp_path):
    with patch.object(launcher,'ROOT',tmp_path),patch.object(launcher,'read_runtime',return_value=None),\
         patch.object(launcher,'port_in_use',return_value=True),patch.object(launcher.subprocess,'Popen') as start,\
         redirect_stderr(io.StringIO()):
        assert launcher.main(['--no-browser'])==2
    start.assert_not_called()


def test_same_checkout_reuses_without_starting_a_second_server(tmp_path):
    with patch.object(launcher,'ROOT',tmp_path),patch.object(launcher,'read_runtime',return_value=launcher.runtime_info(tmp_path)),\
         patch.object(launcher.subprocess,'Popen') as start,patch.object(launcher.webbrowser,'open') as browser,\
         redirect_stdout(io.StringIO()):
        assert launcher.main(['--page','/faq','--open-browser'])==0
    start.assert_not_called();browser.assert_called_once_with('http://127.0.0.1:7860/faq')


def test_supervisor_reuses_only_same_project_without_second_server(tmp_path):
    with patch.object(launcher,'ROOT',tmp_path),patch.object(launcher,'read_runtime',return_value=launcher.runtime_info(tmp_path)),\
         patch.object(launcher.subprocess,'Popen') as start,patch.object(launcher,'monitor_existing',return_value=0) as monitor,\
         redirect_stdout(io.StringIO()):
        assert launcher.main(['--supervised','--no-browser'])==0
    start.assert_not_called();monitor.assert_called_once_with(7860,tmp_path)


def test_start_uses_selected_python_and_exact_cwd(tmp_path):
    child=Mock();child.wait.return_value=0;child.poll.return_value=0
    with patch.object(launcher,'ROOT',tmp_path),patch.object(launcher,'read_runtime',return_value=None),\
         patch.object(launcher,'port_in_use',return_value=False),patch.object(launcher,'wait_ready',return_value=True),\
         patch.object(launcher.subprocess,'Popen',return_value=child) as start,\
         patch.object(launcher.webbrowser,'open') as browser,redirect_stdout(io.StringIO()):
        assert launcher.main(['--page','/faq','--no-browser'])==0
    call=start.call_args
    assert call.args[0][0]==launcher.sys.executable
    assert call.kwargs['cwd']==tmp_path
    assert 'shell' not in call.kwargs
    assert call.args[0][-4:]==['--host','127.0.0.1','--port','7860']
    browser.assert_not_called()


def test_failed_start_is_cleaned_up(tmp_path):
    child=Mock();child.poll.return_value=None
    with patch.object(launcher,'ROOT',tmp_path),patch.object(launcher,'read_runtime',return_value=None),\
         patch.object(launcher,'port_in_use',return_value=False),patch.object(launcher,'wait_ready',return_value=False),\
         patch.object(launcher.subprocess,'Popen',return_value=child),patch.object(launcher,'stop_child') as stop,\
         redirect_stdout(io.StringIO()),redirect_stderr(io.StringIO()):
        assert launcher.main(['--no-browser'])==1
    stop.assert_called_once_with(child)
