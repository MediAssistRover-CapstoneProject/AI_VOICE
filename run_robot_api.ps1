param([ValidateRange(1,65535)][int]$Port=7861)
$ErrorActionPreference='Stop'
$env:PYTHONIOENCODING='utf-8'
$voicePython=Join-Path $PSScriptRoot '.venv\Scripts\python.exe'
$tls=Join-Path $env:LOCALAPPDATA 'MediAssistRover\voice-ai'
foreach($file in @($voicePython,(Join-Path $tls 'ca.pem'),(Join-Path $tls 'server.pem'),(Join-Path $tls 'server.key'))){
    if(-not(Test-Path -LiteralPath $file)){throw "Required voice runtime file is missing: $file"}
}
$voiceListener=Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if($voiceListener){
    & $voicePython -c "import json,ssl,sys,urllib.request; ctx=ssl.create_default_context(cafile=sys.argv[1]); r=json.load(urllib.request.urlopen('https://127.0.0.1:'+sys.argv[2]+'/health',context=ctx,timeout=3)); assert r.get('state')=='READY'; print('Robot RAG is already READY on port '+sys.argv[2])" (Join-Path $tls 'ca.pem') $Port
    if($LASTEXITCODE -ne 0){throw "Port $Port is occupied; the expected RAG service is not ready."}
    return
}
Write-Host "Robot RAG HTTPS API on port $Port. Ctrl+C stops it. No backend or MQTT client is started."
Push-Location -LiteralPath $PSScriptRoot
try {
    & $voicePython -m uvicorn src.web.robot_api:app --host 0.0.0.0 --port $Port --ssl-certfile (Join-Path $tls 'server.pem') --ssl-keyfile (Join-Path $tls 'server.key')
    if($LASTEXITCODE -ne 0){throw "Robot RAG API exited with code $LASTEXITCODE"}
} finally { Pop-Location }
