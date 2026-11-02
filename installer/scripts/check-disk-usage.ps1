<#
    产线缺陷在线AI检测系统 —— 磁盘水位巡检（Windows 版）

    为什么需要：
      磁盘写满时应用**能正常启动**，但图片上传与数据库写入会陆续失败，
      表现为「服务活着但业务全错」—— 这是最难排查的一类故障。
      实测：图片按 350KB/张、144000 张/天 计算，日增 48 GB，
            而 45 GB 的数据盘约 1 天写满，且**没有任何告警**。

    动作：
      - 检查指定盘符的使用率
      - 超过阈值时写事件日志（Windows Event Log）+ 本地告警文件
      - 退出码 1 表示有分区超阈值（便于被计划任务或监控采集）

    手工执行：
        powershell -NoProfile -ExecutionPolicy Bypass -File .\check-disk-usage.ps1

    作为计划任务运行（每 30 分钟）：
        powershell -NoProfile -ExecutionPolicy Bypass -File .\register-scheduled-tasks.ps1
#>
[CmdletBinding()]
param(
    # 要检查的盘符，空格分隔（默认 C 与 D）
    [string]$Drives = 'C D',
    # 水位阈值百分比
    [int]$Threshold = 85,
    # 告警文件路径（默认安装目录下的 logs\disk-alert.log）
    [string]$AlertFile = ''
)

$ErrorActionPreference = 'Continue'
$installDir = Split-Path -Parent $PSScriptRoot
if (-not $AlertFile) { $AlertFile = Join-Path $installDir 'logs\disk-alert.log' }

function Write-Alert([string]$Msg) {
    $line = '[{0}] {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Msg
    Write-Host $line -ForegroundColor Yellow
    try {
        $logDir = Split-Path -Parent $AlertFile
        if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Force -Path $logDir | Out-Null }
        Add-Content -LiteralPath $AlertFile -Value $line -Encoding UTF8
    } catch { }
    # 写 Windows 事件日志（便于被监控采集）
    try {
        if (-not ([System.Diagnostics.EventLog]::SourceExists('CameraDetection'))) {
            New-EventLog -LogName 'Application' -Source 'CameraDetection' | Out-Null
        }
        Write-EventLog -LogName 'Application' -Source 'CameraDetection' -EventId 3001 `
                       -EntryType Warning -Message $Msg
    } catch { }
}

$rc = 0
foreach ($d in ($Drives -split '\s+' | Where-Object { $_ })) {
    $name = $d.Trim().TrimEnd('\').TrimEnd(':')
    if (-not $name) { continue }
    try {
        $drive = Get-PSDrive -Name $name -ErrorAction Stop
    } catch {
        continue   # 盘符不存在（未挂载）—— 跳过而不是报错
    }
    $total = $drive.Used + $drive.Free
    if ($total -le 0) { continue }
    $usePct = [math]::Round(($drive.Used / $total) * 100)
    $freeGB = [math]::Round($drive.Free / 1GB, 1)
    if ($usePct -gt $Threshold) {
        Write-Alert ("磁盘告警: {0}: 使用率 {1}% 超过阈值 {2}%（剩余 {3} GB / 共 {4} GB）" -f `
                     $name, $usePct, $Threshold, $freeGB, [math]::Round($total / 1GB, 1))
        $rc = 1
    } else {
        Write-Host ("  {0}: 使用率 {1}%  剩余 {2} GB" -f $name, $usePct, $freeGB)
    }
}
exit $rc
