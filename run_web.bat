@echo off
setlocal
cd /d "%~dp0"
set "MAR_VOICE_PY=%~dp0.venv-voice\Scripts\python.exe"
if not exist "%MAR_VOICE_PY%" set "MAR_VOICE_PY=%~dp0.venv\Scripts\python.exe"
if exist "%MAR_VOICE_PY%" (
  "%MAR_VOICE_PY%" -m src.platform.faq_launcher --open-browser %*
) else (
  py -3.11 -m src.platform.faq_launcher --open-browser %*
)
if errorlevel 1 pause
