param(
    [ValidateRange(1,65535)][int]$Port = 7860,
    [switch]$NoBrowser,
    [string]$PythonPath = ''
)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'run_web.ps1') -Port $Port -Page faq -OpenBrowser:(-not $NoBrowser) -PythonPath $PythonPath
