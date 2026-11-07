param(
    [ValidateRange(1,65535)][int]$Port = 7860,
    [switch]$OpenBrowser,
    [ValidateSet('studio','faq')][string]$Page = 'studio',
    [string]$PythonPath = ''
)
$ErrorActionPreference = 'Stop'
$env:PYTHONIOENCODING = 'utf-8'
$voicePython = $PythonPath
$voicePrefix = @()
if (-not $voicePython) {
    foreach ($folder in @('.venv-voice','.venv')) {
        $candidate = Join-Path $PSScriptRoot "$folder\Scripts\python.exe"
        if (Test-Path -LiteralPath $candidate) { $voicePython = $candidate; break }
    }
    if (-not $voicePython) {
        if (Get-Command py -ErrorAction SilentlyContinue) { $voicePython = 'py'; $voicePrefix = @('-3.11') }
        elseif (Get-Command python -ErrorAction SilentlyContinue) { $voicePython = 'python' }
        else { throw 'Python 3.11 with the voice dependencies is required.' }
    }
}
$pagePath = if ($Page -eq 'faq') { '/faq' } else { '/' }
$studioArgs = @('-m','src.platform.faq_launcher','--port',"$Port",'--page',$pagePath)
if ($OpenBrowser) { $studioArgs += '--open-browser' } else { $studioArgs += '--no-browser' }
Push-Location -LiteralPath $PSScriptRoot
try {
    & $voicePython @voicePrefix @studioArgs
    if ($LASTEXITCODE -ne 0) { throw "Voice server exited with code $LASTEXITCODE" }
} finally {
    Pop-Location
}
