<#
    产线缺陷在线AI检测系统 —— 安装自检（Windows 版）

    与 Linux 版 verify.sh 对齐（当前 23 项），覆盖：
      - 文件与运行时完整性（JRE / JAR / 配置 / 建库脚本 / 服务包装器）
      - Windows 服务状态与端口监听
      - **鉴权是否真的生效**（匿名访问业务接口必须被拒）
      - 设备端接口是否可用（冻结契约不能被鉴权修复误伤）
      - 运维三件套（备份 / 清理 / 巡检）是否就位
      - 计划任务是否已注册
      - 备份产物是否可用（sha256 可校验）

    用法：
        powershell -NoProfile -ExecutionPolicy Bypass -File .\verify-installation.ps1
        powershell -NoProfile -ExecutionPolicy Bypass -File .\verify-installation.ps1 `
            -BaseUrl 'http://10.0.0.5:8091' -Password '实际口令'
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8091',
    [string]$User    = 'admin',
    [string]$Password = ''
)

$ErrorActionPreference = 'Continue'
$installDir = Split-Path -Parent $PSScriptRoot
$checks = New-Object System.Collections.Generic.List[object]

function Add-Check([string]$Name, [bool]$Pass, [string]$Detail) {
    $script:checks.Add([pscustomobject]@{
        Name = $Name
        Result = if ($Pass) { '通过' } else { '失败' }
        Detail = $Detail
    })
}

$jre      = Join-Path $installDir 'jre\bin\java.exe'
$jar      = Join-Path $installDir 'app\time-bucket-demo.jar'
$config   = Join-Path $installDir 'config\application.yml'
$sqlDir   = Join-Path $installDir 'sql'
$svcExe   = Join-Path $installDir 'service\CameraDetectionService.exe'
$serviceName = 'CameraDetectionSystem'

# ---------- 一、文件与运行时 ----------
Add-Check '内置 JRE' (Test-Path -LiteralPath $jre) $jre
Add-Check '后端 JAR' (Test-Path -LiteralPath $jar) $jar
Add-Check '外部配置 application.yml' (Test-Path -LiteralPath $config) $config
Add-Check 'Windows 服务包装器' (Test-Path -LiteralPath $svcExe) $svcExe

# 建库脚本：必须是 deploy/ 下的四段。旧的 wms-new.sql 缺 15 张 P0 表，导了必 500。
$sqlFiles = @(Get-ChildItem $sqlDir -Filter '*.sql' -File -ErrorAction SilentlyContinue | Sort-Object Name)
$needSql = @('01_init_schema_nodb.sql', '02_final_schema_p0.sql', '03_system_settings.sql', '04_align_entity_columns.sql')
$sqlNames = @($sqlFiles | ForEach-Object { $_.Name })
$missingSql = @($needSql | Where-Object { $sqlNames -notcontains $_ })
Add-Check '建库脚本四段齐全' ($missingSql.Count -eq 0) `
    $(if ($missingSql.Count -eq 0) { ($sqlNames -join ', ') } else { "缺少：$($missingSql -join ', ')" })

# ---------- 二、服务与端口 ----------
$service = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
Add-Check 'Windows 服务存在' ($null -ne $service) $serviceName
Add-Check 'Windows 服务运行' ($service -and $service.Status -eq 'Running') `
    $(if ($service) { $service.Status } else { 'NotFound' })

$listener = Get-NetTCPConnection -LocalPort 8091 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
Add-Check 'TCP 8091 监听' ($null -ne $listener) `
    $(if ($listener) { "PID=$($listener.OwningProcess)" } else { '未监听' })

try {
    $resp = Invoke-WebRequest -Uri $BaseUrl -UseBasicParsing -TimeoutSec 8
    Add-Check '页面访问' ($resp.StatusCode -eq 200) "$BaseUrl / HTTP $($resp.StatusCode)"
} catch {
    Add-Check '页面访问' $false "$BaseUrl / $($_.Exception.Message)"
}

# ---------- 三、鉴权（关键）----------
# 2026-10-28 修复了一个严重缺陷：JwtRequestFilter 从不写 SecurityContextHolder，
# 叠加宽松白名单，导致「看起来在鉴权、实际完全无鉴权」。
# 这两项检查把鉴权是否真的生效验出来，而不是只看日志里有没有"验证成功"。
$anonProbe = "$BaseUrl/api/stats/dashboard-overview"
try {
    $r = Invoke-WebRequest -Uri $anonProbe -UseBasicParsing -TimeoutSec 8
    Add-Check '鉴权生效（匿名应被拒）' $false "匿名访问 $anonProbe 返回 HTTP $($r.StatusCode)，预期 401/403"
} catch {
    $status = $null
    if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
    Add-Check '鉴权生效（匿名应被拒）' ($status -eq 401 -or $status -eq 403) "匿名访问 HTTP $status"
}

# ---------- 三之二、匿名不得访问管理端点（2026-10-31 新增）----------
# 背景：SecurityConfig 曾把整个 "/api/v1/**" 放进白名单，
# 而该前缀下不只有设备端点，还有系统设置、产品配置、缺陷类型、
# 映射、看板等管理端点 → 实测匿名 PUT 系统设置、匿名 POST 产品配置
# 都能改生产库。这里逐条确认它们已被挡下。
Write-Host ''
Write-Host '  匿名访问管理端点（期望全部 401/403）：' -ForegroundColor Cyan
$anonAdminAllOk = $true
foreach ($p in @(
    '/api/v1/system/settings',
    '/api/v1/config/products',
    '/api/v1/config/defect-types',
    '/api/v1/config/camera-defect-mappings',
    '/api/v1/analytics/metadata',
    '/api/user',
    '/api/logs/operation',
    '/api/config-import/template'
)) {
    $code = 0
    try {
        $r2 = Invoke-WebRequest -Uri "$BaseUrl$p" -UseBasicParsing -TimeoutSec 8
        $code = [int]$r2.StatusCode
    } catch {
        if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
    }
    if ($code -eq 401 -or $code -eq 403) {
        Write-Host ("    [OK] {0,-42} HTTP {1}" -f $p, $code) -ForegroundColor Green
    } else {
        Write-Host ("    [FAIL] {0,-42} HTTP {1}（期望 401/403）" -f $p, $code) -ForegroundColor Red
        $anonAdminAllOk = $false
    }
}
Add-Check '匿名不得访问管理端点' $anonAdminAllOk '8 个管理端点'

# 匿名不得执行写操作
$anonWriteCode = 0
try {
    $r3 = Invoke-WebRequest -Uri "$BaseUrl/api/v1/system/settings" -Method Put `
            -ContentType 'application/json' `
            -Body '{"settings":[{"settingKey":"alert.cpuWarning","settingValue":"1"}]}' `
            -UseBasicParsing -TimeoutSec 8
    $anonWriteCode = [int]$r3.StatusCode
} catch {
    if ($_.Exception.Response) { $anonWriteCode = [int]$_.Exception.Response.StatusCode }
}
Add-Check '匿名不得写系统设置' ($anonWriteCode -eq 401 -or $anonWriteCode -eq 403) `
    "匿名 PUT HTTP $anonWriteCode（期望 401/403）"

# 登录取 token
$token = ''
if (-not $Password) {
    $confPath = Join-Path $installDir 'config\backup.conf'
    if (Test-Path $confPath) {
        $line = Get-Content $confPath -Encoding UTF8 | Where-Object { $_ -match '^\s*MYSQL_PASSWORD\s*=' } | Select-Object -First 1
    }
}
try {
    $body = @{ username = $User; password = $Password } | ConvertTo-Json
    $lr = Invoke-RestMethod -Uri "$BaseUrl/api/auth/login" -Method Post -ContentType 'application/json' `
                           -Body $body -TimeoutSec 10
    $token = $lr.data.token
    Add-Check '登录接口' ([bool]$token) "code:200（账号 $User）"
} catch {
    Add-Check '登录接口' $false ("登录失败：{0}" -f $_.Exception.Message)
}

if ($token) {
    try {
        $h = @{ Authorization = "Bearer $token" }
        $r = Invoke-WebRequest -Uri $anonProbe -Headers $h -UseBasicParsing -TimeoutSec 8
        Add-Check '带令牌访问业务接口' ($r.StatusCode -eq 200) "HTTP $($r.StatusCode)"
    } catch {
        $status = $null
        if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
        Add-Check '带令牌访问业务接口' ($status -eq 200) "HTTP $status"
    }
}

# 设备端：冻结契约必须仍可用（dev-token 体系独立于 JWT）
try {
    $dh = @{ 'X-Device-Id' = 'CAM-001'; Authorization = 'Bearer dev-token' }
    $r = Invoke-WebRequest -Uri "$BaseUrl/api/v1/defect-images/recent?limit=1" -Headers $dh -UseBasicParsing -TimeoutSec 8
    Add-Check '设备端接口（冻结契约）' ($r.StatusCode -eq 200) "HTTP $($r.StatusCode)"
} catch {
    $status = $null
    if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
    Add-Check '设备端接口（冻结契约）' ($status -eq 200) "HTTP $status"
}

# ---------- 四、运维三件套 ----------
foreach ($s in @('backup-database.ps1', 'cleanup-defect-images.ps1', 'check-disk-usage.ps1', 'register-scheduled-tasks.ps1')) {
    $p = Join-Path $installDir "scripts\$s"
    Add-Check "运维脚本 $s" (Test-Path -LiteralPath $p) $s
}

# ---------- 五、计划任务 ----------
foreach ($t in @('CameraDetection-Backup', 'CameraDetection-CleanupImages', 'CameraDetection-DiskUsage')) {
    $tt = Get-ScheduledTask -TaskName $t -ErrorAction SilentlyContinue
    Add-Check "计划任务 $t" ($null -ne $tt) $(if ($tt) { "状态：$($tt.State)" } else { '未注册（运行 register-scheduled-tasks.ps1）' })
}

# ---------- 六、备份产物 ----------
$backupFull = Join-Path (Split-Path -Parent $installDir) 'backup\db\full'
$baks = @(Get-ChildItem $backupFull -Filter '*.sql.gz' -File -ErrorAction SilentlyContinue)
if ($baks.Count -gt 0) {
    $latest = $baks | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    $shaFile = "$($latest.FullName).sha256"
    $shaOk = $false
    if (Test-Path $shaFile) {
        $expect = ((Get-Content $shaFile -Encoding ASCII) -split '\s+')[0]
        $actual = (Get-FileHash -LiteralPath $latest.FullName -Algorithm SHA256).Hash
        $shaOk = ($expect -eq $actual)
    }
    Add-Check '备份产物与 sha256' $shaOk `
        "$($latest.Name)（$([math]::Round($latest.Length/1KB,1)) KB，校验$($(if($shaOk){'通过'}else{'不通过'}))）"
} else {
    Add-Check '备份产物与 sha256' $false "未找到备份（$backupFull），若已注册计划任务请等首次执行"
}

# ---------- 输出 ----------
Write-Host ''
Write-Host '=========================================' -ForegroundColor Cyan
Write-Host '产线缺陷在线AI检测系统 - 安装自检报告（Windows）' -ForegroundColor Cyan
Write-Host '=========================================' -ForegroundColor Cyan
$checks | Format-Table -AutoSize

$failed = @($checks | Where-Object { $_.Result -ne '通过' })
Write-Host ("共 {0} 项检查，通过 {1} 项，失败 {2} 项" -f $checks.Count, ($checks.Count - $failed.Count), $failed.Count)
if ($failed.Count -gt 0) {
    Write-Host ''
    foreach ($f in $failed) { Write-Host ("  ! {0} —— {1}" -f $f.Name, $f.Detail) -ForegroundColor Yellow }
    Write-Host ''
    Write-Host '请按《Windows 服务器运行与问题排查手册》处理。' -ForegroundColor Yellow
    exit 1
}

Write-Host ''
Write-Host '全部自检通过。' -ForegroundColor Green
exit 0
