param([string]$PythonPath = '')
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$prefix = @()
if (-not $PythonPath) {
    if (Get-Command py -ErrorAction SilentlyContinue) { $PythonPath = 'py'; $prefix = @('-3.11') }
    else { $PythonPath = 'python' }
}
foreach ($service in @('voice','vision')) {
    $folder = Join-Path $PSScriptRoot ".venv-$service"
    if (-not (Test-Path -LiteralPath (Join-Path $folder 'Scripts\python.exe'))) {
        & $PythonPath @prefix -m venv $folder
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
    $requirements = if ($service -eq 'voice') { 'requirements.txt' } else { 'requirements-vision.txt' }
    & (Join-Path $folder 'Scripts\python.exe') -m pip install -r $requirements
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
Write-Host 'Environments installed. Run .\run_system.ps1 -Check'
