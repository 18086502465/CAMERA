<#
    产线缺陷在线AI检测系统 —— 缺陷图片与元数据清理（Windows 版）

    与 Linux 版 cleanup-defect-images.sh 对齐的行为，且**严格"先删库记录、再删磁盘文件"**：
    反过来（先删文件）会让库中记录仍指向已删除的文件 → 前端显示破图，
    且记录永不复收。先删记录再删文件则天然幂等 —— 文件删失败下次会跳过。

    两种清理依据：
      1. 默认：按时间（库记录 capture_time / upload_time 超期 + 文件 mtime 超期）
      2. -PurgeDb：**以库记录为准** —— 查过期记录的 image_path → 删库 → 按路径删文件。
         适合"文件很新但库判定过期"的场景（如刚导入的历史数据）。

    手工执行：
        powershell -NoProfile -ExecutionPolicy Bypass -File .\cleanup-defect-images.ps1 `
            -DbPassword 'xxx' -Dir 'D:\CameraDetection\data\uploads\defect-images' -DryRun

    重要保护：
      - 连不上数据库时**拒绝删除磁盘文件** —— 避免"库连不上 → 删文件 → 库记录还在 → 永久孤儿"
      - 绝不静默吞错：失败以非 0 退出，便于计划任务留痕
#>
[CmdletBinding()]
param(
    [string]$DbHost     = '127.0.0.1',
    [int]$DbPort        = 3306,
    [string]$DbUser     = 'root',
    [string]$DbPassword = '',
    [string]$DbName     = 'wms-new',
    [string]$Dir        = '',
    [int]$KeepDays        = 7,
    [int]$KeepBatchDays   = 30,
    [string]$MySql       = '',
    [switch]$DryRun,
    [switch]$PurgeDb
)

$ErrorActionPreference = 'Stop'
$installDir = Split-Path -Parent $PSScriptRoot

if (-not $Dir) {
    # 默认与 application.yml 的 defect-image.upload.dir 保持一致
    $cfg = Join-Path $installDir 'config\application.yml'
    if (Test-Path $cfg) {
        $m = Select-String -Path $cfg -Pattern '^\s*dir:\s*(.+?)\s*$' -Encoding UTF8 |
             Where-Object { $_.Line -match 'defect-images' } | Select-Object -First 1
        if ($m) {
            $v = ($m.Matches[0].Groups[1].Value).Trim().Trim('"').Trim("'")
            if ($v -and $v -notmatch '^\$\{') { $Dir = $v }
        }
    }
    if (-not $Dir) { $Dir = Join-Path $installDir 'data\uploads\defect-images' }
}

$LogFile = Join-Path $installDir 'logs\cleanup-defect-images.log'
function Write-Log([string]$Msg, [string]$Level = 'INFO') {
    $line = '[{0}] [{1}] {2}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Level, $Msg
    Write-Host $line
    try {
        $logDir = Split-Path -Parent $LogFile
        if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Force -Path $logDir | Out-Null }
        Add-Content -LiteralPath $LogFile -Value $line -Encoding UTF8
    } catch { }
}
function Fail([string]$Msg, [int]$Code = 1) { Write-Log $Msg 'ERROR'; exit $Code }

# --- 定位 mysql 客户端 ----------------------------------------------------------
function Resolve-MySql {
    param([string]$Explicit)
    if ($Explicit -and (Test-Path -LiteralPath $Explicit)) { return $Explicit }
    $cmd = Get-Command 'mysql.exe' -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    foreach ($c in @('C:\mysql8\bin\mysql.exe',
                     'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
                     'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe',
                     'C:\xampp\mysql\bin\mysql.exe')) {
        if (Test-Path -LiteralPath $c) { return $c }
    }
    return $null
}
$mysql = Resolve-MySql -Explicit $MySql
if (-not $mysql) { Fail '未找到 mysql.exe。请安装 MySQL 8 客户端，或用 -MySql 指定路径' }
if (-not $DbPassword) { Fail '未提供数据库口令（用 -DbPassword 传入）' }
if (-not (Test-Path -LiteralPath $Dir)) {
    Write-Log ("图片目录不存在，跳过：{0}" -f $Dir) 'WARN'
    exit 0
}

# 用 MYSQL_PWD 传口令 —— 不出现在进程命令行
$env:MYSQL_PWD = $DbPassword

function Invoke-Sql {
    param([string]$Sql, [switch]$Scalar)
    # 参数拆成独立元素传（'-h', $DbHost）—— 不要用裸写的 "-h127.0.0.1"，
    # PowerShell 5.1 会把 "." 当小数点拆成 -h127 + .0.0.1，
    # mysql 会报 "Unknown MySQL server host '127'"。
    $mysqlArgs = @('-h', $DbHost, '-P', $DbPort, '-u', $DbUser, '--default-character-set=utf8mb4', '--batch', '--raw')
    if ($Scalar) { $mysqlArgs += '--skip-column-names' }
    # 单条 SQL 直接用 --execute 传（不含换行与中文，PowerShell 的参数转义不会出问题）
    $mysqlArgs += @('--execute', $Sql)
    $out = & $mysql @mysqlArgs $DbName 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw ("MySQL 执行失败：{0}" -f (($out | Select-Object -Last 5) -join '; '))
    }
    return $out
}
function Invoke-SqlFile {
    param([string]$SqlFile)
    # 实现要点（三个 Windows 特有的坑）：
    #  1. PowerShell **不支持 `<` 输入重定向** —— 写了会直接报
    #     "运算符是为将来使用而保留的"，脚本根本跑不起来；
    #  2. `Get-Content file | mysql` 会让**中文全部乱码** ——
    #     PS 5.1 的管道按 GBK 重编码，而库里的值是 utf8mb4；
    #  3. `mysql ... > file`（stdout 重定向）会把输出转成 **UTF-16LE**，
    #     文件看着有内容、校验也过，实际不是合法 SQL。
    # 因此统一用 `--execute "source <路径>"`：**让 mysql 自己读文件**，
    # 走的是 C 层文件 IO，不做任何编码转换，中文完好。
    # 注意：source 的路径必须用**正斜杠**（反斜杠会被 mysql 当转义符）。
    $sqlPath = $SqlFile -replace '\\', '/'
    $out = & $mysql '-h' $DbHost '-P' $DbPort '-u' $DbUser '--default-character-set=utf8mb4' `
                        '--batch' '--raw' $DbName '--execute' "source $sqlPath" 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw ("MySQL 执行失败：{0}" -f (($out | Select-Object -Last 5) -join '; '))
    }
    return $out
}

$cutoffImg   = (Get-Date).AddDays(-$KeepDays).ToString('yyyy-MM-dd HH:mm:ss')
$cutoffBatch = (Get-Date).AddDays(-$KeepBatchDays).ToString('yyyy-MM-dd HH:mm:ss')

try {
    # --- 第 1 步：统计 -----------------------------------------------------------
    Write-Log '统计过期记录 ...'
    $imgRows   = [int](Invoke-Sql -Scalar -Sql "SELECT COUNT(*) FROM defect_image_file WHERE capture_time < '$cutoffImg';").Trim()
    $boxRows   = [int](Invoke-Sql -Scalar -Sql "SELECT COUNT(*) FROM defect_image_box b JOIN defect_image_file f ON f.image_id=b.image_id WHERE f.capture_time < '$cutoffImg';").Trim()
    $batchRows = [int](Invoke-Sql -Scalar -Sql "SELECT COUNT(*) FROM defect_image_batch WHERE upload_time < '$cutoffBatch';").Trim()

    $expiredFiles = @(Get-ChildItem $Dir -Recurse -File -ErrorAction SilentlyContinue |
                      Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-$KeepDays) })
    $expiredSize = [math]::Round((($expiredFiles | Measure-Object -Property Length -Sum).Sum) / 1MB, 1)

    Write-Log ("将清理：图片记录 {0} 条 / 缺陷框 {1} 条 / 批次 {2} 条" -f $imgRows, $boxRows, $batchRows)
    Write-Log ("将清理磁盘：{0} 个文件，约 {1} MB" -f $expiredFiles.Count, $expiredSize)

    if ($DryRun) {
        Write-Log '（-DryRun 模式，不实际删除）'
        $expiredFiles | Select-Object -First 20 | ForEach-Object {
            Write-Log ("  {0}  {1:N0} 字节  {2}" -f $_.LastWriteTime.ToString('yyyy-MM-dd'), $_.Length, $_.FullName)
        }
        exit 0
    }

    # --- 第 2 步：先删库记录 -----------------------------------------------------
    # 顺序至关重要。详见文件头说明。
    #
    # -PurgeDb 模式下必须**先把过期记录的 image_path 查出来**，
    # 因为记录删掉之后就再也查不到路径了，文件将永远留在磁盘上变成孤儿。
    $purgePaths = @()
    if ($PurgeDb -and $imgRows -gt 0) {
        $purgePaths = @(Invoke-Sql -Scalar -Sql `
            "SELECT image_path FROM defect_image_file WHERE capture_time < '$cutoffImg';" |
            ForEach-Object { $_.Trim() } | Where-Object { $_ })
        Write-Log ("按库记录清理：已取出 {0} 条过期记录的存储路径" -f $purgePaths.Count)
    }

    if ($imgRows -gt 0 -or $batchRows -gt 0) {
        $sqlFile = [System.IO.Path]::GetTempFileName()
        try {
            # 注意：这里用「独立 SQL 文件」而不是内联字符串 ——
            # MySQL 客户端从文件读取时无需处理多层引号转义，可靠得多。
            $sql = @"
DELETE b FROM defect_image_box b
JOIN defect_image_file f ON f.image_id = b.image_id
WHERE f.capture_time < '$cutoffImg';

DELETE FROM defect_image_file WHERE capture_time < '$cutoffImg';

DELETE FROM defect_image_batch
WHERE upload_time < '$cutoffBatch'
  AND NOT EXISTS (SELECT 1 FROM defect_image_file f WHERE f.batch_id = defect_image_batch.batch_id);

DELETE FROM camera_images WHERE capture_time < '$cutoffImg';
"@
            Set-Content -LiteralPath $sqlFile -Value $sql -Encoding UTF8
            Invoke-SqlFile -SqlFile $sqlFile | Out-Null
            Write-Log 'OK 库记录已清理'
        } catch {
            Fail ("库记录清理失败（详见上方 MySQL 报错）：{0}" -f $_.Exception.Message) 2
        } finally {
            Remove-Item -Force $sqlFile -ErrorAction SilentlyContinue
        }
    }

    # --- 第 3 步：再删磁盘文件 ---------------------------------------------------
    $deleted = 0
    if ($PurgeDb) {
        # 以库记录为准：按刚才取出的**过期**记录的 image_path 精确删除。
        # 注意只删过期的 —— 不能删全部记录，否则未过期的图片会被误删。
        Write-Log '按库记录删除磁盘文件 ...'
        foreach ($p in $purgePaths) {
            try {
                # image_path 可能是绝对路径，也可能是相对 DIR 的相对路径
                $target = if ([System.IO.Path]::IsPathRooted($p)) { $p } else { Join-Path $Dir $p }
                if (Test-Path -LiteralPath $target) {
                    Remove-Item -Force -LiteralPath $target -ErrorAction Stop
                    $deleted++
                }
            } catch { }
        }
        Write-Log ("OK 按库记录删除了 {0} 个文件" -f $deleted)
    } else {
        foreach ($f in $expiredFiles) {
            try { Remove-Item -Force -LiteralPath $f.FullName -ErrorAction Stop; $deleted++ } catch { }
        }
        Write-Log ("OK 删除了 {0} 个文件" -f $deleted)
    }
    # 清掉空目录
    Get-ChildItem $Dir -Recurse -Directory -ErrorAction SilentlyContinue |
        Sort-Object { $_.FullName.Length } -Descending |
        ForEach-Object { try { if (-not (Get-ChildItem $_.FullName -Force)) { Remove-Item $_.FullName -Force -Recurse } } catch { } }

    # --- 第 4 步：回收表空间（仅在删除量大时）----------------------------------
    if ($deleted -gt 1000) {
        Write-Log '回收表空间（OPTIMIZE TABLE）...'
        try {
            Invoke-Sql -Sql "OPTIMIZE TABLE defect_image_file, defect_image_box;" | Out-Null
            Write-Log 'OK 表空间已回收'
        } catch {
            Write-Log ("表空间回收失败（不影响清理结果）：{0}" -f $_.Exception.Message) 'WARN'
        }
    }
} catch {
    Fail $_.Exception.Message 2
} finally {
    Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
}

$remain = @(Get-ChildItem $Dir -Recurse -File -ErrorAction SilentlyContinue).Count
Write-Log ("清理完成，当前占用：{0} MB，剩余文件 {1} 个" -f
           [math]::Round(((Get-ChildItem $Dir -Recurse -File -ErrorAction SilentlyContinue |
                          Measure-Object -Property Length -Sum).Sum) / 1MB, 2), $remain)
exit 0
