# MediAssistRover AI Voice - Quick Start Script (PowerShell)
param(
    [switch]$TextMode,
    [string]$Backend = "http://localhost:8080"
)

$OutputEncoding = [Console]::OutputEncoding = [Console]::InputEncoding = [System.Text.Encoding]::UTF8
$env:PYTHONIOENCODING = "utf-8"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " 🤖 MediAssistRover AI Voice Assistant Launcher" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

$envFile = Join-Path $PSScriptRoot ".env"
if (-not (Test-Path $envFile)) {
    Write-Host "[INFO] Copying .env.example to .env..." -ForegroundColor Yellow
    Copy-Item (Join-Path $PSScriptRoot ".env.example") $envFile
}

$pyLauncher = "py"
if (-not (Get-Command $pyLauncher -ErrorAction SilentlyContinue)) {
    $pyLauncher = "python"
}

if ($TextMode) {
    Write-Host "[RUN] Starting in TEXT-ONLY mode (no mic needed)..." -ForegroundColor Green
    & $pyLauncher -3.11 -m src.main --text --backend $Backend
} else {
    Write-Host "[RUN] Starting in VOICE mode (microphone & speaker)..." -ForegroundColor Green
    & $pyLauncher -3.11 -m src.main --backend $Backend
}
