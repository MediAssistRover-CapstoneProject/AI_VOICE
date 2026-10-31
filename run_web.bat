@echo off
REM Khoi chay Giao dien Web MediAssistRover AI Voice & RAG Studio

echo ==========================================================
echo  Khoi chay MediAssistRover AI Voice ^& RAG Web Studio
echo  Dia chi: http://localhost:7860
echo ==========================================================

start "" "http://localhost:7860"
py -3.11 -m uvicorn src.web.app:app --host 0.0.0.0 --port 7860 --reload

pause
