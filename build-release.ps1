<#
    产线缺陷在线AI检测系统 —— Windows 版发布包构建脚本

    用法（在本仓库根目录执行）：
        powershell -NoProfile -ExecutionPolicy Bypass -File .\build-release.ps1

    可选参数：
        -SkipFrontend   跳过前端构建（复用已有 frontend\dist）
        -SkipBackend    跳过后端打包（复用已有 target\time-bucket-demo.jar）
        -JreSource      指定要打包的 JRE 目录（默认自动探测 Java 8 的 JRE）
        -Version        安装包版本号（默认 1.0.0）
        -NoInstaller    只产出发布目录，不打 Inno Setup 安装包

    阶段划分与 2026-06-02 的构建日志保持一致：
        1. 构建 Vue dist
        2. 拷贝 Vue dist 到 Spring Boot static
        3. 打包 Spring Boot 可执行 JAR
        4. 组装发布目录
        5. 编译 Windows 服务包装器
        6. 拷贝本地 JRE 到发布包
        7. 打 Inno Setup 安装包（存在 ISCC 时）
        8. 输出结果

    注意事项：
        - 脚本必须在仓库根目录执行（脚本自身会定位到 $PSScriptRoot）。
        - 第 5 步需要 .NET Framework 4.x 自带的 C# 编译器；
          Windows 10/11 自带 C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe。
        - 第 7 步需要安装 Inno Setup 6（默认路径 C:\Program Files (x86)\Inno Setup 6\ISCC.exe）；
          未安装时会跳过并给出明确提示，其余阶段照常完成。
#>
[CmdletBinding()]
param(
    [switch]$SkipFrontend,
    [switch]$SkipBackend,
    [switch]$NoInstaller,
    [string]$JreSource,
    [string]$Version = '1.0.0'
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

# ---------------------------------------------------------------- 路径与常量
$Root        = $PSScriptRoot
$FrontendDir = Join-Path $Root 'frontend'
$BackendDir  = Join-Path $Root 'fact-product'
$InstallerDir= Join-Path $Root 'installer'
$DistDir     = Join-Path $Root 'dist-release'
$StageDir    = Join-Path $DistDir 'CameraDetectionSystem'
$OutDir      = Join-Path $DistDir 'installer'
$LogDir      = Join-Path $DistDir 'logs'
$LogFile     = Join-Path $LogDir ('build-release-{0}.log' -f (Get-Date -Format 'yyyyMMdd-HHmmss'))

$JarName    = 'time-bucket-demo.jar'
$ServiceExe = 'CameraDetectionService.exe'

# 需要随包交付的文档（Windows 侧）
$DocList = @(
    '部署方案-精简版.md',
    '部署方案-Windows.md',
    'Windows服务器运行与问题排查手册.md',
    '服务器运行与问题排查手册.md',
    '架构评审报告.md',
    '完整性与易用性评审报告-20261028.md',
    '服务器运行评审报告-20261031.md',
    '项目说明.md',
    '交付文档-相机上报接口协议-latest.md',
    '部署说明和验收清单.md',
    '用户使用手册-20260611.md'
)

function Write-Stage([string]$Text) {
    $line = '==== {0} ====' -f $Text
    Write-Host $line -ForegroundColor Cyan
    Add-Content -LiteralPath $LogFile -Value $line -Encoding UTF8
}

function Write-Step([string]$Text) {
    Write-Host "  $Text" -ForegroundColor Gray
    Add-Content -LiteralPath $LogFile -Value "  $Text" -Encoding UTF8
}

function Write-Ok([string]$Text) {
    Write-Host "  [OK] $Text" -ForegroundColor Green
    Add-Content -LiteralPath $LogFile -Value "  [OK] $Text" -Encoding UTF8
}

function Write-Warn2([string]$Text) {
    Write-Host "  [!] $Text" -ForegroundColor Yellow
    Add-Content -LiteralPath $LogFile -Value "  [!] $Text" -Encoding UTF8
}

function Fail([string]$Text) {
    Write-Host "  [X] $Text" -ForegroundColor Red
    Add-Content -LiteralPath $LogFile -Value "  [X] $Text" -Encoding UTF8
    throw $Text
}

# 把子进程的输出同时写到控制台与日志
function Invoke-Logged([string]$FilePath, [string[]]$Arguments, [string]$WorkDir = $Root) {
    Write-Step "$FilePath $($Arguments -join ' ')"
    $out = & $FilePath @Arguments 2>&1
    $code = $LASTEXITCODE
    foreach ($l in $out) { Add-Content -LiteralPath $LogFile -Value "      $l" -Encoding UTF8 }
    if ($code -ne 0) {
        foreach ($l in ($out | Select-Object -Last 25)) { Write-Host "      $l" -ForegroundColor DarkGray }
        Fail "命令失败（退出码 $code）：$FilePath $($Arguments -join ' ')"
    }
    return $out
}

New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
Set-Content -LiteralPath $LogFile -Value ("构建开始 {0}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')) -Encoding UTF8

Write-Host ''
Write-Host '############################################################' -ForegroundColor DarkCyan
Write-Host '#  产线缺陷在线AI检测系统 —— Windows 发布包构建' -ForegroundColor DarkCyan
Write-Host ("#  仓库根目录：{0}" -f $Root) -ForegroundColor DarkCyan
Write-Host ("#  版本：{0}    日志：{1}" -f $Version, $LogFile) -ForegroundColor DarkCyan
Write-Host '############################################################' -ForegroundColor DarkCyan

# ================================================================ 1. 构建前端
Write-Stage '1. Build Vue dist'
if ($SkipFrontend) {
    Write-Warn2 '已指定 -SkipFrontend，跳过'
} else {
    if (-not (Test-Path (Join-Path $FrontendDir 'node_modules'))) {
        Push-Location $FrontendDir
        Write-Step '未检测到 node_modules，执行 npm install ...'
        Invoke-Logged 'npm.cmd' @('install', '--no-audit', '--no-fund') $FrontendDir | Out-Null
        Pop-Location
    }
    Push-Location $FrontendDir
    try {
        Invoke-Logged 'npm.cmd' @('run', 'build') $FrontendDir | Out-Null
    } finally {
        Pop-Location
    }
}
$DistIndex = Join-Path $FrontendDir 'dist\index.html'
if (-not (Test-Path $DistIndex)) { Fail "前端产物缺失：$DistIndex" }
$AssetCount = (Get-ChildItem (Join-Path $FrontendDir 'dist\assets') -File -ErrorAction SilentlyContinue).Count
Write-Ok "前端产物就绪（assets $AssetCount 个）"

# ================================================================ 2. 拷贝到 static
Write-Stage '2. Copy Vue dist into Spring Boot static'
$StaticDir = Join-Path $BackendDir 'src\main\resources\static'
New-Item -ItemType Directory -Force -Path $StaticDir | Out-Null
# 先清掉旧的 assets，避免残留过期 hash 的 chunk
$OldAssets = Join-Path $StaticDir 'assets'
if (Test-Path $OldAssets) { Remove-Item -Recurse -Force $OldAssets }
Copy-Item -Recurse -Force (Join-Path $FrontendDir 'dist\assets') $StaticDir
Copy-Item -Force (Join-Path $FrontendDir 'dist\index.html') (Join-Path $StaticDir 'index.html')
$StaticAssets = (Get-ChildItem (Join-Path $StaticDir 'assets') -File -ErrorAction SilentlyContinue).Count
if ($StaticAssets -ne $AssetCount) { Fail "static 资源数与 dist 不一致（$StaticAssets vs $AssetCount）" }
Write-Ok "static 已同步（$StaticAssets 个 assets）"

# ================================================================ 3. 打包 JAR
Write-Stage '3. Build Spring Boot executable jar'
if ($SkipBackend) {
    Write-Warn2 '已指定 -SkipBackend，跳过'
} else {
    Invoke-Logged 'mvn.cmd' @('-B', '-DskipTests', 'clean', 'package') $BackendDir | Out-Null
}
$JarPath = Join-Path $BackendDir "target\$JarName"
if (-not (Test-Path $JarPath)) { Fail "后端 JAR 缺失：$JarPath" }
$JarSize = (Get-Item $JarPath).Length
Write-Ok ("JAR 就绪：{0:N0} 字节" -f $JarSize)

# ================================================================ 4. 组装发布目录
Write-Stage '4. Create release directory layout'
if (Test-Path $StageDir) { Remove-Item -Recurse -Force $StageDir }
foreach ($d in @('app', 'config', 'docs', 'scripts', 'sql', 'service', 'jre', 'data\uploads\defect-images', 'data\uploads\images', 'logs')) {
    New-Item -ItemType Directory -Force -Path (Join-Path $StageDir $d) | Out-Null
}

Copy-Item -Force $JarPath (Join-Path $StageDir "app\$JarName")
Write-Ok 'app/ 已就绪'

# 外部配置：模板优先
$TemplateYml = Join-Path $InstallerDir 'templates\application.yml'
if (Test-Path $TemplateYml) {
    Copy-Item -Force $TemplateYml (Join-Path $StageDir 'config\application.yml')
    Write-Ok 'config/application.yml 已就绪'
} else {
    Fail "配置模板缺失：$TemplateYml"
}

# 安装与运维脚本
Get-ChildItem (Join-Path $InstallerDir 'scripts') -Filter '*.ps1' | ForEach-Object {
    Copy-Item -Force $_.FullName (Join-Path $StageDir 'scripts')
}
Get-ChildItem (Join-Path $InstallerDir 'scripts') -Filter '*.bat' | ForEach-Object {
    Copy-Item -Force $_.FullName (Join-Path $StageDir 'scripts')
}
Write-Ok ("scripts/ 已就绪（{0} 个）" -f (Get-ChildItem (Join-Path $StageDir 'scripts') -File).Count)

# 数据库建库脚本：必须用 deploy/ 下的四段，不能用旧的 wms-new.sql
$DeploySql = Join-Path $BackendDir 'src\main\resources\sql\deploy'
if (-not (Test-Path $DeploySql)) { Fail "建库脚本目录缺失：$DeploySql" }
Get-ChildItem $DeploySql -Filter '*.sql' | ForEach-Object {
    Copy-Item -Force $_.FullName (Join-Path $StageDir 'sql')
}
Write-Ok ("sql/ 已就绪（{0} 段建库脚本）" -f (Get-ChildItem (Join-Path $StageDir 'sql') -Filter '*.sql').Count)

# 文档
$DocSrc = Join-Path $Root 'docs'
$copied = 0
foreach ($d in $DocList) {
    $p = Join-Path $DocSrc $d
    if (Test-Path $p) { Copy-Item -Force $p (Join-Path $StageDir 'docs'); $copied++ }
    else { Write-Warn2 "文档不存在，已跳过：$d" }
}
Write-Ok ("docs/ 已就绪（{0} 份）" -f $copied)

# 原型图 HTML —— iss 的开始菜单快捷方式「产线状态P1原型」直接指向
# docs\dashboard-line-status-prototype.html，漏拷会让装完后的快捷方式点不开。
# 这里按 iss 的引用来拷，而不是无脑拷全部 html（其余 html 是历史设计稿，不必随包）。
$IssFile = Join-Path $InstallerDir 'camera-detection-system.iss'
if (Test-Path $IssFile) {
    $issText = Get-Content $IssFile -Raw -Encoding UTF8
    $htmlRefs = [regex]::Matches($issText, '\{app\}\\docs\\([^"]+\.html)') |
                ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique
    foreach ($h in $htmlRefs) {
        $src = Join-Path $DocSrc $h
        if (Test-Path $src) {
            Copy-Item -Force $src (Join-Path $StageDir 'docs')
            Write-Ok "原型图已就绪：$h"
        } else {
            Write-Warn2 "iss 引用的原型图不存在，快捷方式会失效：$h"
        }
    }
}

# 兜底校验：iss 引用的每个文件都必须真实存在于发布目录
# （少拷一个文件，装完点快捷方式就是「找不到文件」，这种问题必须在构建期暴露）
if (Test-Path $IssFile) {
    $issText2 = Get-Content $IssFile -Raw -Encoding UTF8
    $allRefs = @()
    $allRefs += [regex]::Matches($issText2, '\{app\}\\(docs|scripts)\\([^"]+)') |
                ForEach-Object { ($_.Groups[1].Value + '\' + $_.Groups[2].Value) }
    $bad = 0
    foreach ($r in ($allRefs | Select-Object -Unique)) {
        if (-not (Test-Path (Join-Path $StageDir $r))) {
            Write-Warn2 "iss 引用但包内缺失：$r"
            $bad++
        }
    }
    if ($bad -gt 0) {
        Fail "发布目录缺少 $bad 个 iss 引用的文件，安装后快捷方式会失效"
    }
    Write-Ok 'iss 引用完整性校验通过'
}

# README
$Readme = @"
产线缺陷在线AI检测系统 —— Windows 版发布包
版本：$Version
构建时间：$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')

目录说明：
  app\        后端 JAR（Spring Boot 2.7 可执行包，内含前端静态资源）
  config\     外部配置 application.yml（安装后按现场改数据库口令与 jwt.secret）
  scripts\    安装/启停/自检/备份/清理/巡检脚本（PowerShell）
  sql\        建库脚本四段，必须按 01→02→03→04 顺序执行
  service\    Windows 服务包装器（.NET Framework 4.x）
  jre\        内置 Java 8 运行时（无需另装 JDK）
  docs\       部署方案与问题排查手册
  data\       运行数据与相机上传图片
  logs\       应用日志

部署三步：
  1. 双击安装包，安装向导里选择"工作路径"（建议非系统盘）
  2. 用 scripts\init-database.ps1 初始化数据库（需 MySQL 8）
  3. 打开 http://localhost:8091 ，默认账号 admin / admin123（部署后请改）
"@
Set-Content -LiteralPath (Join-Path $StageDir 'README.txt') -Value $Readme -Encoding UTF8
Write-Ok 'README.txt 已生成'

# ================================================================ 5. 编译服务包装器
Write-Stage '5. Compile Windows service wrapper'
$csSrc = Join-Path $InstallerDir 'service-wrapper\CameraDetectionService.cs'
if (-not (Test-Path $csSrc)) { Fail "服务包装器源码缺失：$csSrc" }
$fwDir = Get-ChildItem 'C:\Windows\Microsoft.NET\Framework64' -Directory -ErrorAction SilentlyContinue |
         Sort-Object Name -Descending | Select-Object -First 1
if (-not $fwDir) { Fail '未找到 .NET Framework 目录，无法编译服务包装器' }
$csCompiler = Join-Path $fwDir.FullName ('c' + 'sc.exe')
if (-not (Test-Path $csCompiler)) { Fail "未找到 C# 编译器：$csCompiler" }
$svcOut = Join-Path $StageDir "service\$ServiceExe"
Invoke-Logged $csCompiler @('/nologo', '/target:exe', '/optimize+', '/platform:x64',
    "/out:$svcOut", $csSrc) $Root | Out-Null
if (-not (Test-Path $svcOut)) { Fail "服务包装器编译失败：$svcOut" }
Write-Ok ("服务包装器已生成：{0:N0} 字节" -f (Get-Item $svcOut).Length)

# ================================================================ 6. 拷贝 JRE
Write-Stage '6. Copy local JRE into release package'
function Resolve-Jre {
    param([string]$Explicit)
    if ($Explicit) { return $Explicit }
    $cands = @()
    if ($env:JAVA_HOME) {
        $cands += (Join-Path $env:JAVA_HOME 'jre')
        # JAVA_HOME 指向 JDK8 时，JRE 通常在同级 jre1.8.0_xxx
        $parent = Split-Path -Parent $env:JAVA_HOME
        $cands += (Get-ChildItem $parent -Directory -Filter 'jre1.8*' -ErrorAction SilentlyContinue |
                   ForEach-Object { $_.FullName })
    }
    foreach ($base in @('C:\Program Files\Java', 'C:\Program Files (x86)\Java')) {
        if (Test-Path $base) {
            $cands += (Get-ChildItem $base -Directory -Filter 'jre1.8*' -ErrorAction SilentlyContinue |
                       ForEach-Object { $_.FullName })
            $cands += (Get-ChildItem $base -Directory -Filter 'jre*' -ErrorAction SilentlyContinue |
                       ForEach-Object { $_.FullName })
        }
    }
    foreach ($c in $cands) {
        if ($c -and (Test-Path (Join-Path $c 'bin\java.exe'))) { return $c }
    }
    return $null
}
$jre = Resolve-Jre -Explicit $JreSource
if (-not $jre) {
    Fail '未找到可打包的 Java 8 JRE。请用 -JreSource 指定目录（含 bin\java.exe）。'
}
Write-Step "JRE 来源：$jre"
Copy-Item -Recurse -Force (Join-Path $jre '*') (Join-Path $StageDir 'jre')
$jreJava = Join-Path $StageDir 'jre\bin\java.exe'
if (-not (Test-Path $jreJava)) { Fail 'JRE 拷贝后缺少 bin\java.exe' }
Write-Ok ("JRE 已拷贝（{0} 个文件）" -f (Get-ChildItem (Join-Path $StageDir 'jre') -Recurse -File).Count)

# ================================================================ 7. 打安装包
Write-Stage '7. Build Inno Setup installer when ISCC exists'
$iscc = $null
# 探测顺序：系统级安装 → per-user 安装（winget 默认装这里）
$isccCands = @(
    'C:\Program Files (x86)\Inno Setup 6\ISCC.exe',
    'C:\Program Files\Inno Setup 6\ISCC.exe',
    'C:\Program Files (x86)\Inno Setup 5\ISCC.exe',
    'C:\Program Files\Inno Setup 5\ISCC.exe',
    (Join-Path $env:LOCALAPPDATA 'Programs\Inno Setup 6\ISCC.exe'),
    (Join-Path $env:LOCALAPPDATA 'Programs\Inno Setup 5\ISCC.exe')
)
foreach ($p in $isccCands) {
    if ($p -and (Test-Path $p)) { $iscc = $p; break }
}
# 兜底：在 LOCALAPPDATA 下按目录名再找一次
if (-not $iscc) {
    $found = Get-ChildItem (Join-Path $env:LOCALAPPDATA 'Programs') -Filter 'ISCC.exe' -Recurse -ErrorAction SilentlyContinue |
             Select-Object -First 1
    if ($found) { $iscc = $found.FullName }
}
$SetupExe = Join-Path $OutDir ("CameraDetectionSystem-Setup-{0}.exe" -f $Version)
if ($NoInstaller) {
    Write-Warn2 '已指定 -NoInstaller，跳过安装包生成'
} elseif (-not $iscc) {
    Write-Warn2 '本机未安装 Inno Setup 6，跳过安装包生成'
    Write-Warn2 "发布目录已就绪：$StageDir"
    Write-Warn2 '安装 Inno Setup 6 后重新执行本脚本，或直接运行：'
    Write-Warn2 "    `"$iscc`" `"$(Join-Path $InstallerDir 'camera-detection-system.iss')`" /DScriptDir=$StageDir /DOutputDir=$OutDir"
} else {
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    $iss = Join-Path $InstallerDir 'camera-detection-system.iss'
    Invoke-Logged $iscc @($iss, "/DScriptDir=$StageDir", "/DOutputDir=$OutDir") $Root | Out-Null
    if (-not (Test-Path $SetupExe)) { Fail "安装包未生成：$SetupExe" }
    Write-Ok ("安装包已生成：{0}（{1:N0} 字节）" -f $SetupExe, (Get-Item $SetupExe).Length)
}

# ================================================================ 8. 输出结果
Write-Stage '8. Release result'
$total = (Get-ChildItem $StageDir -Recurse -File | Measure-Object -Property Length -Sum).Sum
Write-Host ''
Write-Host '  ---------------- 发布结果 ----------------' -ForegroundColor DarkCyan
Write-Host ("  发布目录 : {0}" -f $StageDir)
Write-Host ("  文件总数 : {0}" -f (Get-ChildItem $StageDir -Recurse -File).Count)
Write-Host ("  总体积   : {0:N1} MB" -f ($total / 1MB))
if (Test-Path $SetupExe) {
    Write-Host ("  安装包   : {0}（{1:N1} MB）" -f $SetupExe, ((Get-Item $SetupExe).Length / 1MB)) -ForegroundColor Green
} else {
    Write-Host '  安装包   : 未生成（见阶段 7 的提示）' -ForegroundColor Yellow
}
Write-Host ("  构建日志 : {0}" -f $LogFile)
Write-Host '  --------------------------------------------' -ForegroundColor DarkCyan
Write-Host ''
Write-Host '构建完成。' -ForegroundColor Green
Add-Content -LiteralPath $LogFile -Value ("构建结束 {0}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')) -Encoding UTF8
