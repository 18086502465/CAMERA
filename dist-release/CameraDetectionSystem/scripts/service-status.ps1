$ErrorActionPreference = 'Stop'

$serviceName = 'CameraDetectionSystem'
$service = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
$installDir = Split-Path -Parent $PSScriptRoot
$workDirFile = Join-Path $installDir 'config\workdir.txt'
$workDir = if (Test-Path -LiteralPath $workDirFile) {
  (Get-Content -LiteralPath $workDirFile -Encoding UTF8 -ErrorAction SilentlyContinue | Select-Object -First 1)
} else {
  $installDir
}

Write-Host '=========================================' -ForegroundColor Cyan
Write-Host '产线缺陷在线AI检测系统 - 服务状态' -ForegroundColor Cyan
Write-Host '=========================================' -ForegroundColor Cyan

if (-not $service) {
  Write-Host "服务不存在：$serviceName" -ForegroundColor Yellow
  exit 1
}

$statusColor = switch ($service.Status) {
  'Running' { 'Green' }
  'Stopped' { 'Yellow' }
  default { 'Cyan' }
}

Write-Host "服务名称：$serviceName"
Write-Host "服务状态：$($service.Status)" -ForegroundColor $statusColor
Write-Host "安装目录：$installDir"
Write-Host "工作路径：$workDir"
Write-Host "访问地址：http://localhost:8091"

$listener = Get-NetTCPConnection -LocalPort 8091 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($listener) {
  Write-Host "端口监听：8091 / PID=$($listener.OwningProcess)" -ForegroundColor Green
} else {
  Write-Host '端口监听：8091 未监听' -ForegroundColor Yellow
}

$logFile = Join-Path $workDir 'logs\service-wrapper.log'
if (Test-Path -LiteralPath $logFile) {
  Write-Host ''
  Write-Host '最近服务日志：' -ForegroundColor Cyan
  Get-Content -LiteralPath $logFile -Encoding UTF8 -Tail 8
}


