@echo off
REM MediAssistRover AI Voice - Quick Start Script (Windows Batch)

echo ==========================================================
echo  MediAssistRover AI Voice Assistant Launcher
echo ==========================================================

if not exist "%~dp0.env" (
    echo [INFO] Copying .env.example to .env...
    copy "%~dp0.env.example" "%~dp0.env"
)

echo.
echo Choose mode:
echo 1. Voice Mode (Microphone + Speaker)
echo 2. Text Mode (Keyboard Input - Testing)
echo 3. Run Unit Tests (25 test cases)
echo.
set /p choice="Enter choice [1, 2, or 3]: "

if "%choice%"=="1" (
    echo Starting Voice Mode...
    py -3.11 -m src.main
) else if "%choice%"=="2" (
    echo Starting Text Mode...
    py -3.11 -m src.main --text
) else if "%choice%"=="3" (
    echo Running Pytest...
    py -3.11 -m pytest tests -v
) else (
    echo Invalid choice.
)

pause
