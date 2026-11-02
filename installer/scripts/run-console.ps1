$ErrorActionPreference = 'Stop'

$installDir = Split-Path -Parent $PSScriptRoot
$serviceExe = Join-Path $installDir 'service\CameraDetectionService.exe'

if (-not (Test-Path -LiteralPath $serviceExe)) {
  throw "服务程序不存在：$serviceExe"
}

& $serviceExe
