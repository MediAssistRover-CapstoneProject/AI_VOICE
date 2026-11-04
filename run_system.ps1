param(
    [ValidateSet('local','integrated')][string]$Profile = 'local',
    [string]$Source = '0',
    [switch]$Check,
    [switch]$NoVoice,
    [switch]$NoVision,
    [switch]$BackendCompose,
    [switch]$LiveEvents,
    [string]$PythonPath = '',
    [string]$VoicePython = '',
    [string]$VisionPython = ''
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$env:PYTHONIOENCODING = 'utf-8'
$pythonPrefix = @()
if (-not $PythonPath) {
    $localPython = Join-Path $PSScriptRoot '.venv-voice\Scripts\python.exe'
    if (Test-Path -LiteralPath $localPython) { $PythonPath = $localPython }
    elseif (Get-Command py -ErrorAction SilentlyContinue) { $PythonPath = 'py'; $pythonPrefix = @('-3.11') }
    else { $PythonPath = 'python' }
}
$action = if ($Check) { 'check' } else { 'run' }
$launchArgs = @('-m','src.platform',$action,'--profile',$Profile,'--source',$Source)
if ($NoVoice) { $launchArgs += '--no-voice' }
if ($NoVision) { $launchArgs += '--no-vision' }
if ($BackendCompose) { $launchArgs += '--backend-compose' }
if ($LiveEvents) { $launchArgs += '--live-events' }
if ($VoicePython) { $launchArgs += @('--voice-python',$VoicePython) }
if ($VisionPython) { $launchArgs += @('--vision-python',$VisionPython) }
& $PythonPath @pythonPrefix @launchArgs
exit $LASTEXITCODE
