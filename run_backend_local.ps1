param(
    [ValidateRange(1,65535)][int]$Port = 8080,
    [switch]$Build,
    [switch]$Check,
    [switch]$KeepDataServices
)
$ErrorActionPreference = 'Stop'
$env:PYTHONIOENCODING = 'utf-8'
$bePython = Join-Path $PSScriptRoot '.venv-voice\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $bePython)) { $bePython = Join-Path $PSScriptRoot '.venv\Scripts\python.exe' }
if (-not (Test-Path -LiteralPath $bePython)) { throw 'Không tìm thấy môi trường Python của dự án.' }
$beArguments = @('-m','src.platform.backend_local','--port',"$Port")
if ($Build) { $beArguments += '--build' }
if ($Check) { $beArguments += '--check' }
if ($KeepDataServices) { $beArguments += '--keep-data' }
Push-Location -LiteralPath $PSScriptRoot
try {
    & $bePython @beArguments
    if ($LASTEXITCODE -ne 0) { throw "BE local thoát với mã $LASTEXITCODE." }
} finally {
    Pop-Location
}
