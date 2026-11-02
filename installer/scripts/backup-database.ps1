<#
    产线缺陷在线AI检测系统 —— 数据库全量备份（Windows 版）

    与 Linux 版 backup-database.sh 对齐的行为：
      1. --single-transaction —— 不锁表，业务无感知（InnoDB 一致性快照）
      2. 备份前检查磁盘水位 —— 与数据同盘时，写满就不再写入
      3. 每个备份文件生成 .sha256 —— 备份可校验
      4. 保留 N 天，过期自动删除（先删备份再删校验文件）
      5. 口令走 MYSQL_PWD 环境变量，**不出现在进程命令行**

    手动执行：
        powershell -NoProfile -ExecutionPolicy Bypass -File .\backup-database.ps1 `
            -DbPassword 'xxx' -BackupDir 'D:\CameraDetection\backup\db'

    作为计划任务运行（每天 01:00）：
        powershell -NoProfile -ExecutionPolicy Bypass -File .\register-scheduled-tasks.ps1
#>
[CmdletBinding()]
param(
    [string]$DbHost     = '127.0.0.1',
    [int]$DbPort        = 3306,
    [string]$DbUser     = 'root',
    [string]$DbPassword = '',
    [string]$DbName     = 'wms-new',
    # 备份根目录。默认放在应用目录的同级，避免占用系统盘；
    # 生产环境建议指向独立的大容量磁盘。
    [string]$BackupDir  = '',
    [string]$MySqlDump  = '',
    [int]$KeepDays     = 14,
    # 磁盘水位上限（百分比），超过则跳过本次备份，避免把磁盘写满
    [int]$MaxDiskUse   = 85,
    # 异机备份目录（可选）。指向另一块磁盘或网络盘才算真正的防线。
    [string]$OffsiteDir = ''
)

$ErrorActionPreference = 'Stop'
$installDir = Split-Path -Parent $PSScriptRoot

if (-not $BackupDir) {
    $BackupDir = Join-Path (Split-Path -Parent $installDir) 'backup\db'
}
$FullDir = Join-Path $BackupDir 'full'
$LogDir  = Join-Path $BackupDir 'logs'
$LogFile = Join-Path $LogDir 'backup.log'

function Write-Log([string]$Msg, [string]$Level = 'INFO') {
    $line = '[{0}] [{1}] {2}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Level, $Msg
    Write-Host $line
    try { Add-Content -LiteralPath $LogFile -Value $line -Encoding UTF8 } catch { }
}

function Fail([string]$Msg) { Write-Log $Msg 'ERROR'; exit 1 }

New-Item -ItemType Directory -Force -Path $FullDir, $LogDir | Out-Null

# --- 前置检查 ------------------------------------------------------------------
if (-not $DbPassword) {
    Fail '未提供数据库口令（用 -DbPassword 传入，或设置环境变量 MYSQL_PWD）'
}

# 定位 mysqldump
function Resolve-MySqlDump {
    param([string]$Explicit)
    if ($Explicit -and (Test-Path -LiteralPath $Explicit)) { return $Explicit }
    $cmd = Get-Command 'mysqldump.exe' -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $cands = @(
        'C:\mysql8\bin\mysqldump.exe',
        'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe',
        'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysqldump.exe',
        'C:\xampp\mysql\bin\mysqldump.exe'
    )
    foreach ($c in $cands) { if (Test-Path -LiteralPath $c) { return $c } }
    # 从注册表找已安装的 MySQL
    $keys = @(
        'HKLM:\SOFTWARE\MySQL AB\MySQL Server 8.0',
        'HKLM:\SOFTWARE\MySQL AB\MySQL Server 8.4'
    )
    foreach ($k in $keys) {
        if (Test-Path $k) {
            $loc = (Get-ItemProperty -Path $k -ErrorAction SilentlyContinue).Location
            if ($loc) {
                $p = Join-Path $loc 'bin\mysqldump.exe'
                if (Test-Path -LiteralPath $p) { return $p }
            }
        }
    }
    return $null
}
$dump = Resolve-MySqlDump -Explicit $MySqlDump
if (-not $dump) { Fail '未找到 mysqldump.exe。请安装 MySQL 8 客户端，或用 -MySqlDump 指定路径' }
Write-Log "mysqldump：$dump"

# --- 磁盘水位检查 --------------------------------------------------------------
# 备份与数据同盘时，水位过高再写备份会把磁盘彻底写满，反而丢数据
$driveName = (Split-Path -Qualifier (Split-Path -Qualifier $BackupDir)).TrimEnd(':')
if (-not $driveName) { $driveName = 'C' }
try {
    $drive = Get-PSDrive -Name $driveName -ErrorAction Stop
    $usePct = 0
    if ($drive.Used -gt 0) {
        $usePct = [math]::Round(($drive.Used / ($drive.Used + $drive.Free)) * 100)
    }
    if ($usePct -gt $MaxDiskUse) {
        Fail ("备份分区 {0}: 使用率 {1}% 超过阈值 {2}%，跳过本次备份" -f $driveName, $usePct, $MaxDiskUse)
    }
    Write-Log ("备份分区 {0}: 使用率 {1}%" -f $driveName, $usePct)
} catch {
    Write-Log ("无法读取磁盘 {0} 水位：{1}" -f $driveName, $_.Exception.Message) 'WARN'
}

# --- 全量转储 ------------------------------------------------------------------
$stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$rawFile  = Join-Path $FullDir ("{0}_{1}.sql" -f $DbName, $stamp)
$errFile  = "$rawFile.err"
$gzFile   = "$rawFile.gz"
$outFile  = Join-Path $FullDir ("{0}_{1}.sql.gz" -f $DbName, $stamp)

Write-Log "开始全量备份：$DbName -> $outFile"
$sw = [System.Diagnostics.Stopwatch]::StartNew()

# 用 MYSQL_PWD 环境变量传口令 —— 不出现在进程命令行，避免被 ps/tasklist 看到
$env:MYSQL_PWD = $DbPassword
try {
    # --single-transaction：InnoDB 一致性快照，不阻塞业务写入
    # --quick：逐行读取，避免大表一次性载入内存
    # --set-gtid-purged=OFF：本库 gtid_mode=OFF，不加此项在部分版本会报错
    # 参数一律拆成独立元素传（'-h', $DbHost）—— **不要写成 "-h$DbHost" 以外的形式**。
    # 原因：PowerShell 5.1 解析裸写的原生参数时会把 "127.0.0.1" 里的 "." 当小数点，
    # 拆成 -h127 + .0.0.1，mysql 报 "Unknown MySQL server host '127'"。
    #
    # ⚠️ **必须用 --result-file 让 mysqldump 自己写文件**，绝不能靠 PowerShell 的 `1>` 重定向：
    #   Windows PowerShell 5.1 的重定向会把原生程序的 stdout **转成 UTF-16LE**
    #   （每个字符后跟一个 0x00）。实测产出的 .sql 用 UTF-16LE 编码，
    #   压缩与 sha256 校验**全部通过、退出码 0、一切看起来正常**，
    #   但文件内容根本不是合法 SQL —— **这是一份"假成功"的备份**。
    #   --result-file 走 mysqldump 的 C 层 FILE*，不做任何编码转换，中文完好。
    $dumpArgs = @(
        '-h', $DbHost, '-P', $DbPort, '-u', $DbUser,
        '--single-transaction', '--quick',
        '--routines', '--triggers', '--events',
        '--set-gtid-purged=OFF',
        '--default-character-set=utf8mb4',
        "--result-file=$rawFile",
        $DbName
    )
    # stderr 单独收走（内容是英文警告，编码无所谓）
    & $dump @dumpArgs 2> $errFile
    $dumpExit = $LASTEXITCODE
    if ($dumpExit -ne 0) {
        $err = (Get-Content $errFile -ErrorAction SilentlyContinue) -join '; '
        Remove-Item -Force $rawFile, $errFile -ErrorAction SilentlyContinue
        Fail "mysqldump 执行失败（退出码 $dumpExit）：$err"
    }
} finally {
    Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
}

# mysqldump 返回 0 但文件为空也要当失败（管道/重定向场景下可能发生）
if (-not (Test-Path $rawFile) -or (Get-Item $rawFile).Length -eq 0) {
    Remove-Item -Force $rawFile, $errFile -ErrorAction SilentlyContinue
    Fail '备份文件为空（mysqldump 可能已失败）'
}
Remove-Item -Force $errFile -ErrorAction SilentlyContinue

# --- 编码防御：确认是 UTF-8 而不是 UTF-16LE -------------------------------------
# 这道检查是踩过坑之后加的：PowerShell 5.1 的重定向曾把备份写成 UTF-16LE，
# 而压缩、sha256 校验、退出码**全都正常** —— 只有真正恢复时才会发现文件不可用。
# 现在从源头防住：一旦发现 BOM 是 FF FE（UTF-16LE），直接判定失败。
$rawBytes = [System.IO.File]::ReadAllBytes($rawFile)
if ($rawBytes.Length -ge 2 -and $rawBytes[0] -eq 0xFF -and $rawBytes[1] -eq 0xFE) {
    Remove-Item -Force $rawFile -ErrorAction SilentlyContinue
    Fail '备份文件是 UTF-16LE 编码（PowerShell 重定向所致），不是合法 SQL —— 已删除'
}
# 再确认内容里确实有 SQL 语句，而不是一堆乱码
$rawHead = [System.Text.Encoding]::UTF8.GetString($rawBytes, 0, [Math]::Min(4096, $rawBytes.Length))
if ($rawHead -notmatch 'SET|CREATE TABLE|INSERT|DUMP') {
    Remove-Item -Force $rawFile -ErrorAction SilentlyContinue
    Fail '备份文件内容不像 SQL（既非 UTF-8 的 SQL 文本，也不是合法的 UTF-16LE）—— 已删除'
}

# --- gzip 压缩 -----------------------------------------------------------------
# Windows 没有 zstd，用 .NET 自带的 GZipStream（压缩率与 zstd 接近，足够）
$inStream  = [System.IO.File]::OpenRead($rawFile)
$outStream = [System.IO.File]::Create($gzFile)
try {
    $gz = New-Object System.IO.Compression.GZipStream($outStream, [System.IO.Compression.CompressionLevel]::Optimal)
    try { $inStream.CopyTo($gz) } finally { $gz.Dispose() }
} finally {
    $inStream.Dispose(); $outStream.Dispose()
}
Remove-Item -Force $rawFile -ErrorAction SilentlyContinue

# --- 校验 ----------------------------------------------------------------------
# 读回来能解 = 压缩流完整；同时写 .sha256 供事后校验
$verifyStream = [System.IO.File]::OpenRead($gzFile)
try {
    $gzRead = New-Object System.IO.Compression.GZipStream($verifyStream, [System.IO.Compression.CompressionMode]::Decompress)
    try {
        $buf = New-Object byte[] 65536
        while ($gzRead.Read($buf, 0, $buf.Length) -gt 0) { }
    } finally { $gzRead.Dispose() }
} catch {
    Remove-Item -Force $gzFile -ErrorAction SilentlyContinue
    Fail "压缩文件校验失败，已删除：$($_.Exception.Message)"
} finally {
    $verifyStream.Dispose()
}
(Get-FileHash -LiteralPath $gzFile -Algorithm SHA256).Hash.ToLower() |
    ForEach-Object { "$_  $(Split-Path -Leaf $gzFile)" } |
    Set-Content -LiteralPath "$gzFile.sha256" -Encoding ASCII

$sw.Stop()
$sizeMB = [math]::Round((Get-Item $gzFile).Length / 1MB, 2)
Write-Log ("全量备份完成：{0}（{1} MB，耗时 {2}s）" -f $gzFile, $sizeMB, [math]::Round($sw.Elapsed.TotalSeconds, 1))

# --- 清理过期备份 --------------------------------------------------------------
# 先删备份再删校验文件，避免出现「有校验文件无备份文件」的中间态
$cutoff = (Get-Date).AddDays(-$KeepDays)
$old = Get-ChildItem $FullDir -Filter ("{0}_*.sql.gz" -f $DbName) -File -ErrorAction SilentlyContinue |
       Where-Object { $_.LastWriteTime -lt $cutoff }
if ($old) {
    foreach ($f in $old) {
        Remove-Item -Force $f.FullName -ErrorAction SilentlyContinue
        Remove-Item -Force "$($f.FullName).sha256" -ErrorAction SilentlyContinue
    }
    Write-Log ("已清理 {0} 份超过 {1} 天的旧备份" -f $old.Count, $KeepDays)
}

# --- 异机备份（可选）----------------------------------------------------------
# 同盘备份不抗物理故障，只有异机留存才是真正的最后一道防线
if ($OffsiteDir -and (Test-Path -LiteralPath $OffsiteDir)) {
    try {
        $offFull = Join-Path $OffsiteDir 'full'
        New-Item -ItemType Directory -Force -Path $offFull | Out-Null
        Copy-Item -Force (Join-Path $FullDir "*") $offFull -ErrorAction Stop
        Write-Log "已复制到异机目录：$offFull"
    } catch {
        Write-Log ("异机同步失败（不影响本地备份）：{0}" -f $_.Exception.Message) 'WARN'
    }
}

Write-Log '本次备份结束'
exit 0
