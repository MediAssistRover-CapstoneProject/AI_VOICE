# Khởi chạy Giao diện Trực quan MediAssistRover AI Voice & RAG Studio (PowerShell)
param(
    [int]$Port = 7860
)

$OutputEncoding = [Console]::OutputEncoding = [Console]::InputEncoding = [System.Text.Encoding]::UTF8
$env:PYTHONIOENCODING = "utf-8"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " 🌐 Khởi chạy MediAssistRover AI Voice & RAG Web Studio" -ForegroundColor Cyan
Write-Host "    Địa chỉ truy cập: http://localhost:$Port" -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Cyan

$pyLauncher = "py"
if (-not (Get-Command $pyLauncher -ErrorAction SilentlyContinue)) {
    $pyLauncher = "python"
}

# Tự động mở trình duyệt web sau 1.5 giây
Start-Job -ScriptBlock {
    param($p)
    Start-Sleep -Seconds 2
    Start-Process "http://localhost:$p"
} -ArgumentList $Port | Out-Null

& $pyLauncher -3.11 -m uvicorn src.web.app:app --host 0.0.0.0 --port $Port --reload
