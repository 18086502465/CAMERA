$ErrorActionPreference = 'Stop'

$serviceName = 'CameraDetectionSystem'
$service = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
if (-not $service) {
  throw "服务不存在：$serviceName，请先运行 install-service.ps1 或重新安装程序。"
}

if ($service.Status -eq 'Running') {
  Write-Host "服务已在运行：$serviceName" -ForegroundColor Green
} else {
  Write-Host "正在启动服务：$serviceName" -ForegroundColor Cyan
  Start-Service -Name $serviceName
  $service.WaitForStatus('Running', [TimeSpan]::FromSeconds(30))
  Write-Host '服务启动成功。' -ForegroundColor Green
}

Get-Service -Name $serviceName
Write-Host '访问地址：http://localhost:8091'


