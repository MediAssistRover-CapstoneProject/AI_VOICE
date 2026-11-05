param(
    [ValidateRange(1,65535)][int]$Port = 7860,
    [switch]$OpenBrowser
)
$ErrorActionPreference = 'Stop'
$env:PYTHONIOENCODING = 'utf-8'
$voicePython = Join-Path $PSScriptRoot '.venv\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $voicePython)) {
    throw 'Create the project .venv and install the voice dependencies before running this script.'
}
Write-Host "AI Voice/RAG Studio: http://127.0.0.1:$Port"
Write-Host 'One local server. Ctrl+C stops it. No robot stack or second backend is started.'
if ($OpenBrowser) { Start-Process "http://127.0.0.1:$Port" }
Push-Location -LiteralPath $PSScriptRoot
try {
    & $voicePython -m uvicorn src.web.app:app --host 127.0.0.1 --port $Port
    if ($LASTEXITCODE -ne 0) { throw "Voice server exited with code $LASTEXITCODE" }
} finally {
    Pop-Location
}
