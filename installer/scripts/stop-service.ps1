$ErrorActionPreference = 'Stop'

$serviceName = 'CameraDetectionSystem'
$service = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
if (-not $service) {
  Write-Host "服务不存在：$serviceName" -ForegroundColor Yellow
  exit 0
}

if ($service.Status -eq 'Stopped') {
  Write-Host "服务已停止：$serviceName" -ForegroundColor Yellow
} else {
  Write-Host "正在停止服务：$serviceName" -ForegroundColor Cyan
  Stop-Service -Name $serviceName -Force
  $service.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(30))
  Write-Host '服务已停止。' -ForegroundColor Green
}

Get-Service -Name $serviceName


