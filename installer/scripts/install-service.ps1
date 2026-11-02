param(
  [string]$WorkDir
)

$ErrorActionPreference = 'Stop'

$serviceName = 'CameraDetectionSystem'
$displayName = 'Camera Detection System'
$installDir = Split-Path -Parent $PSScriptRoot
$serviceExe = Join-Path $installDir 'service\CameraDetectionService.exe'

if (-not (Test-Path -LiteralPath $serviceExe)) {
  throw "Service executable not found: $serviceExe"
}

$resolvedWorkDir = if ([string]::IsNullOrWhiteSpace($WorkDir)) {
  $installDir
} else {
  [System.IO.Path]::GetFullPath($WorkDir)
}

New-Item -ItemType Directory -Force -Path (Join-Path $installDir 'config') | Out-Null
Set-Content -LiteralPath (Join-Path $installDir 'config\workdir.txt') -Value $resolvedWorkDir -Encoding UTF8

New-Item -ItemType Directory -Force -Path `
  (Join-Path $resolvedWorkDir 'logs'), `
  (Join-Path $resolvedWorkDir 'data\uploads\defect-images'), `
  (Join-Path $resolvedWorkDir 'data\uploads\images') | Out-Null

$existing = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
if ($existing) {
  if ($existing.Status -ne 'Stopped') {
    Stop-Service -Name $serviceName -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
  }
  sc.exe delete $serviceName | Out-Null
  Start-Sleep -Seconds 2
}

New-Service `
  -Name $serviceName `
  -BinaryPathName "`"$serviceExe`"" `
  -DisplayName $displayName `
  -Description 'Camera detection system: Spring Boot, Vue static pages, camera status and image upload APIs.' `
  -StartupType Automatic | Out-Null

sc.exe failure $serviceName reset= 86400 actions= restart/60000/restart/60000/restart/60000 | Out-Null

Start-Service -Name $serviceName
Write-Host "Service installed and started: $displayName ($serviceName)"
Write-Host "Work directory: $resolvedWorkDir"
