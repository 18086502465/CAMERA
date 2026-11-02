$ErrorActionPreference = 'Stop'

$serviceName = 'CameraDetectionSystem'
$existing = Get-Service -Name $serviceName -ErrorAction SilentlyContinue

if ($existing) {
  if ($existing.Status -ne 'Stopped') {
    Write-Host "正在停止服务：$serviceName"
    Stop-Service -Name $serviceName -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
  }
  Write-Host "正在卸载服务：$serviceName"
  sc.exe delete $serviceName | Out-Null
  Write-Host "Service uninstalled: $serviceName"
} else {
  Write-Host "Service does not exist: $serviceName"
}

