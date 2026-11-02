param(
  [string]$HostName = '127.0.0.1',
  [int]$Port = 3306,
  [string]$Database = 'wms-new',
  [string]$User = 'root',
  [string]$Password = 'CCshenda889',
  [string]$SqlFile,
  [switch]$Yes
)

$ErrorActionPreference = 'Stop'

function Find-MysqlExe {
  $cmd = Get-Command 'mysql.exe' -ErrorAction SilentlyContinue
  if ($cmd) { return $cmd.Source }

  $candidates = @(
    'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
    'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe',
    'C:\Program Files (x86)\MySQL\MySQL Server 8.0\bin\mysql.exe'
  )
  foreach ($candidate in $candidates) {
    if (Test-Path -LiteralPath $candidate) {
      return $candidate
    }
  }
  throw '未找到 mysql.exe。请先安装 MySQL 8.0，并确认 mysql.exe 在 PATH 中，或安装到默认目录。'
}

function Quote-CmdArg([string]$Value) {
  return '"' + ($Value -replace '"', '\"') + '"'
}

function Assert-File([string]$Path, [string]$Name) {
  if (-not (Test-Path -LiteralPath $Path)) {
    throw "$Name 不存在：$Path"
  }
}

$installDir = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($SqlFile)) {
  $SqlFile = Join-Path $installDir 'sql\wms-new.sql'
}

Assert-File $SqlFile '数据库 SQL 文件'
$mysql = Find-MysqlExe

Write-Host '=========================================' -ForegroundColor Cyan
Write-Host '产线缺陷在线AI检测系统 - MySQL 初始化导入' -ForegroundColor Cyan
Write-Host '=========================================' -ForegroundColor Cyan
Write-Host "MySQL:     $mysql"
Write-Host "Host:      $HostName"
Write-Host "Port:      $Port"
Write-Host "Database:  $Database"
Write-Host "SQL File:  $SqlFile"
Write-Host ''
Write-Host '注意：当前 SQL 包含 DROP TABLE/CREATE TABLE，会重建系统表并写入初始数据。' -ForegroundColor Yellow

if (-not $Yes) {
  $confirm = Read-Host '确认继续导入？输入 YES 继续'
  if ($confirm -ne 'YES') {
    Write-Host '已取消数据库初始化。'
    exit 2
  }
}

$baseArgs = @(
  "--host=$HostName",
  "--port=$Port",
  "--user=$User",
  "--default-character-set=utf8mb4",
  "--protocol=tcp"
)
if (-not [string]::IsNullOrEmpty($Password)) {
  $baseArgs += "--password=$Password"
}

$escapedDb = $Database.Replace('`', '``')
$createSql = "CREATE DATABASE IF NOT EXISTS ``$escapedDb`` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
Write-Host '1/3 创建数据库...' -ForegroundColor Green
& $mysql @baseArgs -e $createSql
if ($LASTEXITCODE -ne 0) {
  throw "创建数据库失败，ExitCode=$LASTEXITCODE"
}

Write-Host '2/3 导入 SQL，可能需要几十秒...' -ForegroundColor Green
$importArgs = @()
$importArgs += $baseArgs
$importArgs += @('--binary-mode=1', $Database)
$quotedArgs = ($importArgs | ForEach-Object { Quote-CmdArg $_ }) -join ' '
$cmdLine = (Quote-CmdArg $mysql) + ' ' + $quotedArgs + ' < ' + (Quote-CmdArg $SqlFile)
cmd.exe /d /c $cmdLine
if ($LASTEXITCODE -ne 0) {
  throw "导入 SQL 失败，ExitCode=$LASTEXITCODE"
}

Write-Host '3/3 校验数据库表数量...' -ForegroundColor Green
$safeDbName = $Database.Replace("'", "''")
$countSql = "SELECT COUNT(*) AS table_count FROM information_schema.tables WHERE table_schema='$safeDbName';"
$tableCount = & $mysql @baseArgs -N -B -e $countSql
if ($LASTEXITCODE -ne 0) {
  throw "校验数据库失败，ExitCode=$LASTEXITCODE"
}

Write-Host ''
Write-Host "数据库初始化完成：$Database，表数量：$tableCount" -ForegroundColor Green
Write-Host '下一步：启动 Windows 服务后访问 http://localhost:8091'



