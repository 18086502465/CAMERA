<#
    产线缺陷在线AI检测系统 —— 注册 Windows 计划任务（对应 Linux 的 systemd timer + cron）

    注册三个计划任务：
      1. 数据库全量备份   每天 01:00
      2. 缺陷图片清理     每天 03:30
      3. 磁盘水位巡检     每 30 分钟

    用法：
        # 1) 先把数据库口令写进 config\backup.conf（只含一行 MYSQL_PASSWORD=xxx）
        # 2) 以管理员身份执行：
        powershell -NoProfile -ExecutionPolicy Bypass -File .\register-scheduled-tasks.ps1
        # 3) 查看已注册的任务：
        powershell -NoProfile -Command "Get-ScheduledTask -TaskName 'CameraDetection*' | Format-Table TaskName,State"

    卸载：
        powershell -NoProfile -ExecutionPolicy Bypass -File .\register-scheduled-tasks.ps1 -Uninstall
#>
[CmdletBinding()]
param(
    [string]$InstallDir = '',
    [switch]$Uninstall
)

$ErrorActionPreference = 'Stop'
$scriptDir = $PSScriptRoot
if (-not $InstallDir) { $InstallDir = Split-Path -Parent $scriptDir }

$Tasks = @(
    @{ Name = 'CameraDetection-Backup';
       Desc = '产线缺陷在线AI检测系统 —— 数据库全量备份';
       Time = '01:00'; Daily = $true; Interval = $null },
    @{ Name = 'CameraDetection-CleanupImages';
       Desc = '产线缺陷在线AI检测系统 —— 缺陷图片与元数据清理';
       Time = '03:30'; Daily = $true; Interval = $null },
    @{ Name = 'CameraDetection-DiskUsage';
       Desc = '产线缺陷在线AI检测系统 —— 磁盘水位巡检';
       Time = $null;    Daily = $false; Interval = 'PT30M' }
)

$psExe = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'

if ($Uninstall) {
    foreach ($t in $Tasks) {
        if (Get-ScheduledTask -TaskName $t.Name -ErrorAction SilentlyContinue) {
            Unregister-ScheduledTask -TaskName $t.Name -Confirm:$false
            Write-Host ("  已删除计划任务：{0}" -f $t.Name) -ForegroundColor Yellow
        }
    }
    Write-Host '计划任务已全部卸载。' -ForegroundColor Green
    exit 0
}

# --- 备份任务的口令来源 ---------------------------------------------------------
# 计划任务无法传参，口令必须落在文件里。
$confPath = Join-Path $InstallDir 'config\backup.conf'
if (-not (Test-Path $confPath)) {
    Write-Host ("未找到 {0}" -f $confPath) -ForegroundColor Yellow
    Write-Host '请先创建该文件，内容一行：' -ForegroundColor Yellow
    Write-Host '    MYSQL_PASSWORD=你的数据库口令' -ForegroundColor White
    Write-Host '（该文件含明文口令，请限制访问权限）' -ForegroundColor Yellow
    exit 1
}

# --- 逐个注册 ------------------------------------------------------------------
foreach ($t in $Tasks) {
    $script = switch ($t.Name) {
        'CameraDetection-Backup'     { Join-Path $scriptDir 'backup-database.ps1' }
        'CameraDetection-CleanupImages' { Join-Path $scriptDir 'cleanup-defect-images.ps1' }
        'CameraDetection-DiskUsage'  { Join-Path $scriptDir 'check-disk-usage.ps1' }
    }
    if (-not (Test-Path $script)) {
        Write-Host ("  [X] 脚本不存在：{0}" -f $script) -ForegroundColor Red
        continue
    }

    # 备份与清理需要口令 —— 从 conf 读出来放进参数
    $dbPass = ''
    $confLine = Get-Content $confPath -Encoding UTF8 | Where-Object { $_ -match '^\s*MYSQL_PASSWORD\s*=' } | Select-Object -First 1
    if ($confLine) { $dbPass = ($confLine -split '=', 2)[1].Trim() }

    $argList = @('-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', "`"$script`"")
    if ($dbPass -and $t.Name -ne 'CameraDetection-DiskUsage') {
        $argList += @('-DbPassword', "`"$dbPass`"")
    }

    $action = New-ScheduledTaskAction -Execute $psExe -Argument ($argList -join ' ') -WorkingDirectory $InstallDir

    if ($t.Daily) {
        $parts = $t.Time.Split(':')
        $trigger = New-ScheduledTaskTrigger -Daily -At ([datetime]::ParseExact($t.Time, 'HH:mm', $null))
    } else {
        $trigger = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(2) `
                    -RepetitionInterval (New-TimeSpan -Minutes 30)
    }

    $principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
    $settings = New-ScheduledTaskSettingsSet -StartWhenAvailable `
                -DontStopIfGoingOnBatteries `
                -DontStopOnIdleEnd `
                -ExecutionTimeLimit (New-TimeSpan -Hours 1) `
                -MultipleInstances IgnoreNew

    if (Get-ScheduledTask -TaskName $t.Name -ErrorAction SilentlyContinue) {
        Unregister-ScheduledTask -TaskName $t.Name -Confirm:$false
    }
    Register-ScheduledTask -TaskName $t.Name -Description $t.Desc -Action $action `
                            -Trigger $trigger -Principal $principal -Settings $settings | Out-Null
    Write-Host ("  [OK] 已注册：{0}" -f $t.Name) -ForegroundColor Green
    if ($t.Daily) {
        Write-Host ("       触发：每天 {0}" -f $t.Time)
    } else {
        Write-Host '       触发：每 30 分钟'
    }
}

Write-Host ''
Write-Host '计划任务注册完成。' -ForegroundColor Green
Write-Host '查看：Get-ScheduledTask -TaskName "CameraDetection*" | Format-Table TaskName,State' -ForegroundColor Gray
Write-Host '立即试跑：Start-ScheduledTask -TaskName "CameraDetection-Backup"' -ForegroundColor Gray
exit 0
