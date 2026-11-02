# 服务器部署方案 —— Windows Server / Windows 10-11（单机部署）

> **适用版本**：产线缺陷在线AI检测系统 v1.0.0（Spring Boot 2.7 + Vue 3 + MySQL 8）
> **配套**：
> - 精简版：`docs/部署方案-精简版.md`（照着做就能上线）
> - 问题排查：`docs/Windows服务器运行与问题排查手册.md`
> - **Linux 服务器请看：`docs/部署方案-Ubuntu22.04.md`**
>
> **最后更新**：2026-10-28 —— 补齐鉴权修复、备份/清理/巡检三件套、计划任务

---

## 0. 一页速览

```
构建机（装 JDK8 + Maven + Node + Inno Setup）
─────────────────────────────────────────────────────────
① npm run build                前端产物
② 拷贝 dist → static          两份拷贝必须同步
③ mvn package                 app\time-bucket-demo.jar
④ 组装发布目录                dist-release\CameraDetectionSystem\
⑤ 编 C# 服务包装器            service\CameraDetectionService.exe
⑥ 拷贝本地 JRE                jre\  （目标机无需装 JDK）
⑦ 打 Inno Setup 包            CameraDetectionSystem-Setup-1.0.0.exe
─────────────────────────────────────────────────────────
                    ↓ 拷贝到目标机
目标机（装 MySQL 8 即可，JRE 已内置）
─────────────────────────────────────────────────────────
装 MySQL → 建库(4 段) → 装包 → 改 2 处配置 → 启服务 → 验收
```

> **一键构建**：`build-release.ps1` 已把上面 7 步串成一条命令，见 §2。
> 目标机**只需装 MySQL 8**，Java 运行时随安装包内置。

### 目录规划（安装后）

| 位置 | 内容 | 说明 |
|---|---|---|
| `C:\Program Files\CameraDetectionSystem\` | 程序本体 | 由安装包写入，默认路径 |
| `…\app\time-bucket-demo.jar` | 后端 JAR | **内含前端静态资源**，无需单独部署前端 |
| `…\config\application.yml` | 外部配置 | **安装后必改 2 处**（见 §4.3） |
| `…\config\workdir.txt` | 工作路径 | 安装向导里选的那个盘 |
| `…\scripts\` | 9 个运维脚本 | 启停 / 自检 / 建库 / 备份 / 清理 / 巡检 |
| `…\sql\` | 建库脚本 4 段 | 必须按 01→02→03→04 顺序执行 |
| `…\service\CameraDetectionService.exe` | 服务包装器 | .NET Framework 4.x |
| `…\jre\` | 内置 Java 8 | 目标机无需装 JDK |
| `<工作路径>\data\uploads\defect-images\` | 相机上传图片 | **容量大头，见 §5.2** |
| `<工作路径>\logs\` | 应用日志 | |

---

## 1. 环境要求与改造点

### 1.1 目标机要求

| 项 | 要求 | 说明 |
|---|---|---|
| 操作系统 | Windows Server 2016+ / Windows 10-11 | x64 |
| 内存 | ≥ 4 GB（建议 8 GB） | 应用实测常驻 0.6–0.7 GB |
| **磁盘** | 系统盘 ≥ 10 GB + **数据盘 ≥ 100 GB** | 图片按 350 KB/张、144000 张/天 = **日增 48 GB** |
| MySQL | 8.0（客户端 + 服务端） | 客户端要能跑 `mysql.exe` / `mysqldump.exe` |
| .NET Framework | 4.x（系统自带） | 服务包装器需要 |
| 端口 | 8091（TCP 入站） | 应用端口 |

> ⚠️ **磁盘是最容易踩的坑**：如果图片和数据库都放在系统盘，按目标负载
> **约 1 天就写满**，且写满后应用**仍能正常启动**、只是图片上传与数据库写入陆续失败。
> **必须把工作路径放到独立数据盘**，并配好巡检（见 §4.6）。

### 1.2 为什么用 Java 8（已实测）

JAR 的 class 文件是 **major=52（Java 8 字节码）**。实测在 OpenJDK 1.8.0_504 上启动正常，
应用启动耗时 7.378 秒。安装包内置 JRE（从 `C:\Program Files\Java\jre1.8.0_333` 拷贝），
**目标机不需要装 JDK**。

### 1.3 改造点：改这里，其余自动跟随

| 会变的值 | 位置 | 改完要做什么 |
|---|---|---|
| 数据库地址/库名/账号/口令 | `…\config\application.yml` 的 `spring.datasource` | 重启服务 |
| JWT 密钥 | `…\config\application.yml` 的 `jwt.secret` | 重启服务 |
| 图片存储目录 | `…\config\application.yml` 的 `defect-image.upload.dir` | 重启服务 |
| 清理任务时间 | `…\config\application.yml` 的 `app.cleanup.cron` | 重启服务 |
| 数据库备份口令 | `…\config\backup.conf` | 重新注册计划任务 |
| 运行数据 / 图片 / 日志根目录 | 安装向导的「工作路径」 | 重装或改 `workdir.txt` |

### 1.4 必改的 2 项（安全）

```yaml
# …\config\application.yml
spring:
  datasource:
    password: <你的 MySQL 口令>      # ← 必改 ①

jwt:
  secret: <随机长字符串>              # ← 必改 ②
```

生成随机密钥：

```powershell
[System.Web.HttpUtility]::UrlEncode([System.Guid]::NewGuid().ToString() + [System.Guid]::NewGuid().ToString())
```

> 安装包里的 `application.yml` 用 `onlyifdoesntexist` 保护 —— **已存在的配置不会被覆盖**，
> 所以升级安装不会把你改过的值冲掉。

---

## 2. 构建机操作

### 2.1 一键构建（推荐）

```powershell
# 在仓库根目录执行
powershell -NoProfile -ExecutionPolicy Bypass -File .\build-release.ps1
```

脚本按 8 个阶段依次执行（与 2026-06-02 的构建日志一致）：

| 阶段 | 内容 | 说明 |
|---|---|---|
| 1 | 构建 Vue dist | `npm run build` |
| 2 | 拷贝 dist → Spring Boot static | **两份拷贝必须同步** |
| 3 | 打包 Spring Boot 可执行 JAR | `mvn -DskipTests clean package` |
| 4 | 组装发布目录 | app/config/docs/scripts/sql/service/jre/data/logs |
| 5 | 编译 Windows 服务包装器 | 用 .NET Framework 自带的 C# 编译器 |
| 6 | 拷贝本地 JRE | 自动探测 Java 8 的 JRE 目录 |
| 7 | 打 Inno Setup 安装包 | **需要本机装 Inno Setup 6**，否则跳过并提示 |
| 8 | 输出结果 | 发布目录体积、文件数、安装包路径、构建日志 |

常用参数：

```powershell
# 只重组发布目录，不重新编译前端/后端
.\build-release.ps1 -SkipFrontend -SkipBackend

# 指定 JRE 来源
.\build-release.ps1 -JreSource 'C:\Program Files\Java\jre1.8.0_333'

# 只出发布目录，跳过安装包
.\build-release.ps1 -NoInstaller
```

> **构建日志**落在 `dist-release\logs\build-release-<时间戳>.log`（UTF-8）。
> 2026-06-02 那份旧日志是 UTF-16 编码，已保留在 `dist-release\build-release-latest.log` 备查。

### 2.2 手工分步（需要时）

<details>
<summary>展开查看 8 个阶段的等价手工命令</summary>

```powershell
# ① 前端
cd frontend; npm install; npm run build

# ② 前端产物并入后端（两份拷贝，必须同步）
Remove-Item -Recurse -Force ..\fact-product\src\main\resources\static\assets
Copy-Item -Recurse -Force dist\assets ..\fact-product\src\main\resources\static\assets
Copy-Item -Force dist\index.html ..\fact-product\src\main\resources\static\index.html

# ③ 后端
cd ..\fact-product; mvn -B -DskipTests clean package

# ④ 组装发布目录（sql 目录先清空，避免把旧的 wms-new.sql 打进包）
$stage = '..\dist-release\CameraDetectionSystem'
Remove-Item -Recurse -Force $stage -ErrorAction SilentlyContinue
' app config docs scripts sql service jre data\uploads\defect-images logs' -split ' ' |
  ForEach-Object { New-Item -ItemType Directory -Force -Path (Join-Path $stage $_) | Out-Null }
Copy-Item target\time-bucket-demo.jar $stage\app\
Copy-Item ..\installer\templates\application.yml $stage\config\
Copy-Item ..\installer\scripts\*.ps1, ..\installer\scripts\*.bat $stage\scripts\
Copy-Item src\main\resources\sql\deploy\*.sql $stage\sql\
Copy-Item ..\docs\*.md $stage\docs\

# ⑤ 编 C# 服务包装器
$fw = (Get-ChildItem 'C:\Windows\Microsoft.NET\Framework64' -Directory | Sort-Object Name -Descending)[0]
& (Join-Path $fw.FullName 'csc.exe') /nologo /target:exe /optimize+ /platform:x64 `
    "/out:$stage\service\CameraDetectionService.exe" ..\installer\service-wrapper\CameraDetectionService.cs

# ⑥ 内置 JRE
Copy-Item -Recurse -Force 'C:\Program Files\Java\jre1.8.0_333\*' $stage\jre\

# ⑦ 打安装包（需 Inno Setup 6）
& 'C:\Program Files (x86)\Inno Setup 6\ISCC.exe' ..\installer\camera-detection-system.iss `
    /DScriptDir="$PWD\$stage" /DOutputDir="$PWD\..\dist-release\installer"
```

</details>

### 2.3 契约测试（必跑）

```powershell
cd fact-product
mvn -B test
```

**8 个用例必须全绿**。这些用例覆盖设备端冻结契约（heartbeat / batch-upload / defect-images-upload），
改 URL、Header、字段名、错误码都会红。

> **`batch-upload` 的 `schemaVersion` 实测只接受 `"2.0"`** —— DTO 注释和契约测试里写的
> `1.0` 是错的，别照抄。

---

## 3. 目标机操作

### 3.1 装 MySQL 8.0

装 MySQL 8.0（服务端 + 客户端），记住 root 口令。

> **注意**：Ubuntu 的 root 默认是 `auth_socket`（只认 OS 身份），但 **Windows 版没有这个问题**，
> root 走密码认证，JDBC 直接连即可。

### 3.2 建库（4 段脚本，按序）

**必须用安装包 `sql\` 下的四段脚本**，按顺序执行：

| 顺序 | 文件 | 作用 |
|---|---|---|
| 1 | `01_init_schema_nodb.sql` | 基础表（**不要**用旧版 `wms-new.sql`，它缺 15 张 P0 表） |
| 2 | `02_final_schema_p0.sql` | P0 表（**会 DROP 并重建 21 张表**） |
| 3 | `03_system_settings.sql` | 系统设置 |
| 4 | `04_align_entity_columns.sql` | 实体字段对齐（32 条 ALTER） |

```powershell
cd 'C:\Program Files\CameraDetectionSystem\sql'
$mysql = 'C:\mysql8\bin\mysql.exe'
$env:MYSQL_PWD = '你的root口令'      # 用环境变量传口令，别写进命令行

& $mysql -h127.0.0.1 -uroot -e "DROP DATABASE IF EXISTS ``wms-new``; CREATE DATABASE ``wms-new`` DEFAULT CHARSET utf8mb4;"
Get-ChildItem *.sql | Sort-Object Name | ForEach-Object {
    Write-Host "导入 $($_.Name) ..."
    cmd /c "type `"$($_.FullName)`" | `"$mysql`" -h127.0.0.1 -uroot ``wms-new``"
}
Remove-Item Env:\MYSQL_PWD
```

> ⚠️ **中文乱码的坑**：PowerShell 5.1 的管道按 GBK 重编码，直接
> `Get-Content | & $mysql` 会让中文变乱码、备份都不可用。
> **必须用 `cmd /c "type file | mysql ..."` 的字节流重定向**（见 §6.1 同样的坑）。

验证：

```powershell
& $mysql -h127.0.0.1 -uroot -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='wms-new';"
# 期望 34（脚本建的部分）→ 见下面的说明
```

> ⚠️ **表数是 34 还是 38？** —— 两个数都对，取决于什么时候查：
>
> | 时机 | 表数 | 构成 |
> |---|---|---|
> | 刚跑完 4 段建库脚本 | **34** | 建库脚本创建的 |
> | **应用启动过一次之后** | **38** | 34 + 4 张由应用启动时自动创建 |
>
> 启动时自动创建的是（`DefectImageServiceImpl.ensureSchema()`）：
> `defect_image_batch` / `defect_image_file` / `defect_image_box`
> 以及 `LineAnalyticsController` 建的 `analytics_aggregation_schedule` /
> `agg_quality_day` / `agg_defect_type_day` / `analytics_aggregation_job`。
>
> 全部用 `CREATE TABLE IF NOT EXISTS` + `ALTER ... ADD COLUMN` 补列，
> **重复执行无害**。所以**建完库必须启动一次应用**，这 4 张表才会存在。
> 忘了启动 → 设备上报图片会因表不存在而报错。

### 3.3 装包

双击 `CameraDetectionSystem-Setup-1.0.0.exe`：

1. 选安装目录（默认 `C:\Program Files\CameraDetectionSystem`）
2. **选「工作路径」** —— 运行数据、图片、日志的保存位置，**建议放独立数据盘**
3. 安装完成会自动注册并启动 Windows 服务

### 3.4 改配置（2 处）

编辑 `C:\Program Files\CameraDetectionSystem\config\application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/wms-new?...
    username: root
    password: <你的 MySQL 口令>     # ← 必改 ①

jwt:
  secret: <随机长字符串>              # ← 必改 ②
```

> 改完**必须重启服务**才生效：
> ```powershell
> Restart-Service CameraDetectionSystem
> ```

### 3.5 改管理员口令

部署后**必须改**。默认是 `admin` / `123456`（公开发布的默认口令）。

```powershell
cd 'C:\Program Files\CameraDetectionSystem'
# 用 MySQL 直接改 BCrypt 哈希比较麻烦，推荐用应用自带的重置脚本
```

Windows 版暂无独立的重置脚本，**最快的办法**是直接改库（BCrypt，$2a$10$）：

```powershell
# 生成 BCrypt 哈希（需要 BCrypt.Net 库，或用 Java 侧的 BCryptPasswordEncoder）
# 一次性方案：用应用登录后的接口，或用 docker run --rm jpillora/crackstation
```

> **推荐做法**：正式投产前用应用内的「修改密码」功能改；实在没有，
> 可临时用 §6.2 的 `init-database.ps1` 重跑第 3 段脚本重置为默认口令再改。
> **不要把明文口令留在任何脚本或配置文件里。**

### 3.6 启动与放行端口

```powershell
# 服务状态
Get-Service CameraDetectionSystem
Get-NetTCPConnection -LocalPort 8091 -State Listen

# 防火墙放行
New-NetFirewallRule -DisplayName 'Camera Detection System 8091' `
  -Direction Inbound -Protocol TCP -LocalPort 8091 -Action Allow
```

### 3.7 （可选）IIS / Nginx 反向代理

若要走域名访问，建议用 IIS ARR 或 Nginx 反代到 `http://127.0.0.1:8091`。

> ⚠️ **反代必须放开请求体大小限制**：图片单张 ≤ 10 MB、整批 ≤ 1024 MB，
> IIS 默认 `maxAllowedContentLength` 为 30 MB，Nginx 默认 `client_max_body_size` 为 1 MB ——
> **都会把图片上传拦成 413**，表现成"设备上报失败"而不是应用报错。

```nginx
client_max_body_size 1024m;
```

```powershell
# IIS
Set-WebConfigurationProperty -PSPath 'MACHINE\WEBROOT\APPHOST\Default Web Site' `
  -Filter 'system.webServer/security/requestFiltering' -Name 'maxAllowedContentLength' -Value 1073741824
```

---

## 4. 验收

### 4.1 一键自检（23 项）

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File 'C:\Program Files\CameraDetectionSystem\scripts\verify-installation.ps1'
```

自检覆盖：

| 组 | 项数 | 内容 |
|---|---|---|
| 文件与运行时 | 5 | JRE / JAR / 配置 / 服务包装器 / **建库脚本四段齐全** |
| 服务与端口 | 3 | 服务存在 / 运行中 / 8091 监听 / 页面可访问 |
| **鉴权** | 4 | **匿名应被拒（401/403）** / 登录 / 带令牌可访问 / 设备端仍可用 |
| 运维三件套 | 4 | 备份 / 清理 / 巡检 / 计划任务注册脚本 |
| 计划任务 | 3 | 备份 / 清理 / 巡检是否已注册 |
| 备份产物 | 1 | 备份文件存在且 **sha256 可校验** |

> **鉴权那两项是 2026-10-28 新增的** —— 此前系统存在一个严重缺陷：
> `JwtRequestFilter` 解析 token、查用户、`validateToken()` 全都做了，
> **唯独没调 `setAuthentication()`**，只打一行"JWT Token 验证成功"的日志。
> 叠加原本约 60 条 `permitAll()` 白名单（**含全部业务查询路径**），等于**完全无鉴权**。
> 自检里加这两项，是把"鉴权是否真的生效"**验出来**，而不是看日志。

### 4.2 功能基线（逐条核对）

```powershell
$B = 'http://localhost:8091'

# ① 登录取 token
$token = (Invoke-RestMethod -Uri "$B/api/auth/login" -Method Post -ContentType 'application/json' `
    -Body '{"username":"admin","password":"你的口令"}').data.token
$h = @{ Authorization = "Bearer $token" }

# ② 看板
Invoke-RestMethod "$B/api/stats/dashboard-overview" -Headers $h | ConvertTo-Json -Depth 3

# ③ 相机列表
Invoke-RestMethod "$B/api/cameras?page=1&size=20" -Headers $h | ConvertTo-Json -Depth 3

# ④ 4 个曾因前端路径写错而 404 的模块（本轮已修）
foreach ($p in @('/api/company','/api/stats/brands','/api/v1/config/defect-types',
                 '/api/holiday/range?startDate=2026-01-01&endDate=2026-12-31')) {
    $code = (Invoke-WebRequest "$B$p" -Headers $h -UseBasicParsing).StatusCode
    Write-Host "$p -> $code"
}
```

### 4.3 设备侧 4 项确认

```powershell
# ① 心跳
curl.exe -X POST -H "X-Device-Id: CAM-001" -H "Authorization: Bearer dev-token" `
  -H "Content-Type: application/json" `
  -d '{"cameraCode":"CAM-001","cameraName":"杭州1线-上料相机A"}' `
  http://localhost:8091/api/camera/heartbeat

# ② 批量上报（schemaVersion 必须是 "2.0"）
curl.exe -X POST -H "X-Device-Id: CAM-001" -H "Authorization: Bearer dev-token" `
  -H "Content-Type: application/json" -H "X-Batch-Id: TEST-001" `
  -d '{"schemaVersion":"2.0","batchId":"TEST-001","cameraCode":"CAM-001","productName":"产品A","reportTime":"2026-10-28 10:00:00","counterMode":"INCREMENTAL","timeRange":{"startTime":"2026-10-28 09:30:00","endTime":"2026-10-28 10:00:00"},"summary":{"totalCount":100,"okCount":98,"ngCount":2},"defects":[{"sourceCode":"p0","count":2}]}' `
  http://localhost:8091/api/v1/camera/inspection/batch-upload

# ③ 图片上传（关注是否落盘到工作路径）
# ④ 最近图片列表
curl.exe -H "X-Device-Id: CAM-001" -H "Authorization: Bearer dev-token" `
  "http://localhost:8091/api/v1/defect-images/recent?limit=5"
```

> **设备端用 `dev-token` 体系，独立于 JWT**。鉴权修复只收紧了管理端，
> **冻结契约未受影响**（自检里有一项专门验证这点）。

---

## 5. 运维三件套（2026-10-28 新增）

> 改造前服务器上**完全没有备份**，磁盘也没有任何告警。
> 这三个脚本 + 计划任务把这两块补齐，与 Linux 版行为一致。

### 5.1 数据库全量备份

```powershell
# 手工跑一次
powershell -NoProfile -ExecutionPolicy Bypass `
  -File 'C:\Program Files\CameraDetectionSystem\scripts\backup-database.ps1' `
  -DbPassword '你的口令' -BackupDir 'D:\CameraDetection\backup\db'

# 参数说明
#   -BackupDir  备份根目录（默认：应用目录同级的 backup\db）
#   -KeepDays   保留天数（默认 14）
#   -MaxDiskUse 磁盘水位上限（默认 85%，超过则跳过本次备份）
#   -OffsiteDir 异机备份目录（可选）
```

特点：

- `--single-transaction` —— 不锁表，业务无感知（InnoDB 一致性快照）
- 备份前检查磁盘水位 —— 与数据同盘时，写满就不再写入
- gzip 压缩 + `.sha256` 校验文件
- 保留 N 天，过期自动删除（先删备份再删校验文件）
- **口令走 `MYSQL_PWD` 环境变量，不出现在进程命令行**

### 5.2 缺陷图片清理

```powershell
# 预演（不实际删除）
powershell -NoProfile -ExecutionPolicy Bypass `
  -File 'C:\Program Files\CameraDetectionSystem\scripts\cleanup-defect-images.ps1' `
  -DbPassword '你的口令' -DryRun

# 实际执行
powershell -NoProfile -ExecutionPolicy Bypass `
  -File 'C:\Program Files\CameraDetectionSystem\scripts\cleanup-defect-images.ps1' `
  -DbPassword '你的口令'

# 以库记录为准清理（文件很新但库判定过期时用这个）
… -PurgeDb
```

> ⚠️ **清理顺序必须是「先删库记录、再删磁盘文件」**：
> 反过来会产生孤儿记录（记录在、文件没了）→ 前端显示破图且记录永不复收。
> 脚本在**连不上数据库时会拒绝删文件** —— 这个保护是故意的。

**保留期**：

| 数据 | 保留 | 由谁清理 |
|---|---|---|
| `defect_image_file` / `_box` | **7 天** | `cleanup-defect-images.ps1`（计划任务 03:30） |
| `defect_image_batch` | 30 天 | 同上 |
| `camera_images`（兼容表） | 7 / 30 天 | 同上 + 应用内 `DataCleanupTask`（02:00） |
| `fact_production_30min` | 3 个月 | 应用内 `DataCleanupTask`（归档） |
| `sys_operation_log` | 6 个月 | 应用内 `DataCleanupTask` |

### 5.3 磁盘水位巡检

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File 'C:\Program Files\CameraDetectionSystem\scripts\check-disk-usage.ps1' `
  -Drives 'C D' -Threshold 85
```

- 超过阈值写**本地告警文件**（`logs\disk-alert.log`）+ **Windows 事件日志**（`CameraDetection` 源，EventID 3001）
- 退出码 1 表示有分区超阈值（便于被监控采集）

### 5.4 注册计划任务

```powershell
# 1) 先写口令文件（只含一行）
'C:\Program Files\CameraDetectionSystem\config\backup.conf' |
    ForEach-Object { "MYSQL_PASSWORD=你的口令" | Set-Content -LiteralPath $_ -Encoding UTF8 }

# 2) 以管理员身份注册（需管理员权限）
powershell -NoProfile -ExecutionPolicy Bypass `
  -File 'C:\Program Files\CameraDetectionSystem\scripts\register-scheduled-tasks.ps1'

# 3) 查看
Get-ScheduledTask -TaskName 'CameraDetection*' | Format-Table TaskName,State

# 4) 立即试跑
Start-ScheduledTask -TaskName 'CameraDetection-Backup'

# 卸载
… -Uninstall
```

| 任务名 | 触发 | 说明 |
|---|---|---|
| `CameraDetection-Backup` | 每天 **01:00** | 数据库全量备份 |
| `CameraDetection-CleanupImages` | 每天 **03:30** | 图片与元数据清理（错开应用内 02:00 的清理） |
| `CameraDetection-DiskUsage` | **每 30 分钟** | 磁盘水位巡检 |

> 备份时间选 01:00 的理由：**早于**应用内 02:00 的清理任务 —— 备份的是**清理前的完整状态**。

---

## 6. 升级 / 回滚

### 6.1 升级前必做

```powershell
# ① 数据库备份（用自动备份机制）
$stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
powershell -NoProfile -ExecutionPolicy Bypass `
  -File 'C:\Program Files\CameraDetectionSystem\scripts\backup-database.ps1' -DbPassword '你的口令'
# 记下备份文件名，回滚时要用
Get-ChildItem 'D:\CameraDetection\backup\db\full\*.sql.gz' | Sort-Object LastWriteTime -Descending | Select-Object -First 1

# ② 备份 JAR（用于回滚）
New-Item -ItemType Directory -Force -Path 'D:\CameraDetection\backup\jars' | Out-Null
Copy-Item 'C:\Program Files\CameraDetectionSystem\app\time-bucket-demo.jar' `
          "D:\CameraDetection\backup\jars\time-bucket-demo.jar.$stamp.bak"

# ③ 备份现场配置（含 jwt.secret 与改过的口令）
Copy-Item 'C:\Program Files\CameraDetectionSystem\config\application.yml' `
          "D:\CameraDetection\backup\application.yml.$stamp.bak"
```

> ⚠️ **mysqldump 导出到文件必须用 `--result-file`**：
> PowerShell 5.1 的 `>` 重定向生成 UTF-16LE、经管道则按 GBK 重编码 —— **两者都会让中文乱码**，
> 备份等于不可用。`--result-file` 让 mysqldump 自己写文件，最可靠。

### 6.2 升级

```powershell
# 停止服务
Stop-Service CameraDetectionSystem
# 替换 JAR 与 scripts（配置不要覆盖！）
Copy-Item 'D:\new\time-bucket-demo.jar' 'C:\Program Files\CameraDetectionSystem\app\'
Copy-Item 'D:\new\*.ps1' 'C:\Program Files\CameraDetectionSystem\scripts\'
# 启动
Start-Service CameraDetectionSystem
# 自检
powershell -NoProfile -ExecutionPolicy Bypass `
  -File 'C:\Program Files\CameraDetectionSystem\scripts\verify-installation.ps1'
```

> ⚠️ **不要整体覆盖 `config\application.yml`** —— 里面有现场改过的 `jwt.secret` 与数据库口令。
> 安装包本身用 `onlyifdoesntexist` 保护，但手工拷贝时没有这个保护。

> ⚠️ **库结构有变更时**：先在测试库演练增量 SQL（如 `04_align_entity_columns.sql`），
> **绝不直接跑全量 `02_final_schema_p0.sql`** —— 它会 DROP 并重建 21 张表。

### 6.3 回滚

```powershell
Stop-Service CameraDetectionSystem
Copy-Item 'D:\CameraDetection\backup\jars\time-bucket-demo.jar.<时间戳>.bak' `
          'C:\Program Files\CameraDetectionSystem\app\time-bucket-demo.jar' -Force
# 如需回滚数据（会覆盖现有数据！）
$env:MYSQL_PWD = '你的口令'
cmd /c "type D:\CameraDetection\backup\db\full\wms-new_<时间戳>.sql.gz | mysql -h127.0.0.1 -uroot wms-new"
Remove-Item Env:\MYSQL_PWD
Start-Service CameraDetectionSystem
```

---

## 7. 2026-10-28 修复说明（升级后行为变化）

| # | 缺陷 | 修复后行为 | 对使用者的影响 |
|---|---|---|---|
| 1 | **后端鉴权完全失效**（`JwtRequestFilter` 不写 `SecurityContextHolder` + 60 条 `permitAll`） | 匿名访问业务接口 → **403** | **前端必须带 token**，若之前靠匿名调接口的脚本会失效 |
| 2 | 前端 13 个 URL 后端无对应（品牌/公司/缺陷类型/节假日 4 个模块全 404） | 路径已修正 | 这 4 个模块恢复可用 |
| 3 | 3 个 Mapper 的 XML 劫持 BaseMapper → 3 个接口 **500** | 已修 | `/api/company`、`/api/holiday/range`、操作日志查询恢复 |
| 4 | `CameraImage` / `ProductDetectionHistory` 的 `@TableId` 推导错列 → 定时清理任务整体回滚 | 已修 | 02:00 的清理任务三步全部生效 |
| 5 | 清理任务 cron 硬编码，无法临时调整 | 改为 `app.cleanup.cron` 配置项 | 排查时可临时改高频验证 |
| 6 | Windows 版**没有备份/清理/巡检**，也**没有排查手册** | 已补齐 | 见 §5 |

> **第 1 项最需要注意**：如果现场有脚本直接调 `/api/cameras` 之类的接口，
> 升级后会拿到 403 —— 需要改成先登录取 token 再带 `Authorization: Bearer <token>`。

---

## 7.5 Windows PowerShell 的五个坑（全部实测踩过）

写 Windows 运维脚本时，这五个坑**每一个都曾让脚本"看起来正常"或直接失败**。
本项目的 `backup-database.ps1` / `cleanup-defect-images.ps1` 里都已写进注释。

| # | 坑 | 现象 | 正确写法 |
|---|---|---|---|
| 1 | **stdout 重定向会变 UTF-16LE** | `mysqldump ... > file.sql` 产出 UTF-16LE 文件 —— 压缩、sha256 校验、退出码**全都正常**，但内容不是合法 SQL，**备份不可用** | 用 `--result-file=<路径>` 让 mysqldump 自己写文件（C 层 FILE*，无编码转换） |
| 2 | **不支持 `<` 输入重定向** | `mysql ... < file.sql` 报「运算符是为将来使用而保留的」，脚本根本跑不起来 | 用 `--execute "source <正斜杠路径>"` 让 mysql 自己读文件 |
| 3 | **管道会按 GBK 重编码** | `Get-Content f.sql \| mysql` → 中文全变乱码（`产品A` → `浜у搧A`） | 同上，用 `source` |
| 4 | **裸写的 `-h127.0.0.1` 会被拆开** | PowerShell 5.1 把 `.` 当小数点 → 拆成 `-h127` + `.0.0.1` → `Unknown MySQL server host '127'` | 参数拆成独立元素：`'-h', $DbHost` |
| 5 | **`Start-Process` 会被环境变量噎死** | 环境里同时有 `HTTPS_PROXY` 与 `https_proxy`（大小写不同）→ .NET 抛「已添加项。字典中的关键字…」 | 改用 `&` 直接调用 + 原生重定向；或 `--result-file` 让程序自己写 |

### 7.5.1 第 1 条最值得单独说

它是典型的**「假成功」**：

```
退出码 0        ✓
日志打印"全量备份完成"  ✓
gzip 压缩成功    ✓
sha256 校验通过  ✓
文件大小正常     ✓
—— 但文件是 UTF-16LE，恢复时根本执行不了
```

**如果不实际解压看内容，这个 bug 可以静默运行很久。**
所以 `backup-database.ps1` 里加了一道**编码防御**：

```powershell
# 一旦发现 BOM 是 FF FE（UTF-16LE），直接判定失败并删除
if ($rawBytes[0] -eq 0xFF -and $rawBytes[1] -eq 0xFE) {
    Remove-Item -Force $rawFile
    Fail '备份文件是 UTF-16LE 编码（PowerShell 重定向所致），不是合法 SQL —— 已删除'
}
```

---

## 8. 尚未做的事（需业务侧决策）

| # | 事项 | 说明 |
|---|---|---|
| 1 | **异机备份** | `-OffsiteDir` 当前指向同一块物理盘，只抗逻辑误删、不抗磁盘故障 |
| 2 | **图片转码压缩** | 服务器无 PIL/ImageMagick；建议在**设备端**做（随机噪声 PNG 6 MB → JPEG q85 约 200 KB，省 97%） |
| 3 | **`sys_operation_log` 保留期 6 → 3 个月** | 每次 API 调用写 1 条，约 3 万条/天 |
| 4 | **设备端输出 JPEG** | 若相机输出 PNG，7 天保留期需 1172 GB，会撑爆数据盘 |
| 5 | **多实例分布式锁** | `DataCleanupTask` 无锁，多实例会重复清理（当前性能有 51 倍裕量，暂时不需要） |
