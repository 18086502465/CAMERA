# Windows 服务器运行与问题排查手册

> **适用**：产线缺陷在线AI检测系统 v1.0.0 · Windows Server 2016+ / Windows 10-11
> **配套**：`docs/部署方案-Windows.md`（安装） · `docs/架构评审报告.md`（架构与实测数据）
> **Linux 服务器请看**：`docs/服务器运行与问题排查手册.md`
>
> **最后更新**：2026-10-28 —— 补齐鉴权修复说明、备份/清理/巡检三件套、计划任务

---

## 0. 速查表

| 想做什么 | 命令 |
|---|---|
| 看服务状态 | `Get-Service CameraDetectionSystem` |
| 启 / 停 / 重启服务 | `Start-Service` / `Stop-Service` / `Restart-Service CameraDetectionSystem` |
| 看应用日志 | `Get-Content '…\runtime\logs\camera-detection-system.log' -Tail 100` |
| 看服务包装器日志 | `Get-Content '…\runtime\logs\service-wrapper.log' -Tail 50` |
| 看崩溃事件 | `Get-WinEvent -FilterHashtable @{LogName='Application'; Level=2} -MaxEvents 20` |
| 看磁盘 | `Get-PSDrive C,D \| Select Name,@{n='已用GB';e={[math]::Round(\$_.Used/1GB,1)}},@{n='可用GB';e={[math]::Round(\$_.Free/1GB,1)}}` |
| 看内存 | `Get-CimInstance Win32_OperatingSystem \| Select @{n='总GB';e={[math]::Round(\$_.TotalVisibleMemorySize/1MB,1)}},@{n='可用GB';e={[math]::Round(\$_.FreePhysicalMemory/1MB,1)}}` |
| 看 JVM 进程 | `Get-CimInstance Win32_Process -Filter "Name='java.exe'" \| Select ProcessId,@{n='内存MB';e={[math]::Round(\$_.WorkingSetSize/1MB,1)}}` |
| 看图片占多少 | `Get-ChildItem '…\data\uploads\defect-images' -Recurse -File \| Measure-Object Length -Sum` |
| 看图片文件数 | `(Get-ChildItem '…\data\uploads\defect-images' -Recurse -File).Count` |
| 看 8091 是否监听 | `Get-NetTCPConnection -LocalPort 8091 -State Listen` |
| 手动备份 | `powershell -File '…\scripts\backup-database.ps1' -DbPassword 'xxx'` |
| 看备份定时任务 | `Get-ScheduledTask -TaskName 'CameraDetection*' \| Format-Table TaskName,State` |
| 手动清理（预演） | `powershell -File '…\scripts\cleanup-defect-images.ps1' -DbPassword 'xxx' -DryRun` |
| 磁盘水位巡检 | `powershell -File '…\scripts\check-disk-usage.ps1' -Drives 'C D'` |
| 一键自检（23 项） | `powershell -File '…\scripts\verify-installation.ps1'` |
| 登录取 token | `curl.exe -X POST -H "Content-Type: application/json" -d '{"username":"admin","password":"xxx"}' http://localhost:8091/api/auth/login` |

---

## 1. 服务器与部署现状

| 项 | 值 |
|---|---|
| 服务名 | `CameraDetectionSystem`（Windows 服务） |
| 程序目录 | `C:\Program Files\CameraDetectionSystem\` |
| 工作路径 | 安装向导里选定，存于 `config\workdir.txt` |
| **图片目录** | `<工作路径>\data\uploads\defect-images\` |
| **备份目录** | 默认 `<应用目录同级>\backup\db\`，可用 `-BackupDir` 改 |
| 日志 | `<工作路径>\logs\` + Windows 事件日志 |
| 端口 | 8091（TCP） |
| 数据库 | MySQL 8.0，库名 `wms-new` |
| 内置运行时 | Java 8 JRE（`jre\`，无需另装 JDK） |
| 服务包装器 | `service\CameraDetectionService.exe`（.NET Framework 4.x） |

### 1.1 账号

| 账号 | 口令 | 说明 |
|---|---|---|
| `admin` | `123456` | **安装包默认，必须改** |
| MySQL `root` | 现场设定 | 写在 `config\application.yml` 的 `spring.datasource.password` |

> ⚠️ `config\application.yml` 与 `config\backup.conf` 里存着**明文口令**。
> 正式投产前请：
> ① 限制这两个文件的 ACL（仅 Administrators 可读）
> ② 考虑改用 Windows 凭据管理器或 DPAPI 加密存储。

---

## 2. 日志体系

| 日志 | 位置 | 看什么 |
|---|---|---|
| 应用日志 | `<工作路径>\logs\camera-detection-system.log` | 业务异常、SQL 报错 |
| 服务包装器日志 | `<工作路径>\logs\service-wrapper.log` | **JVM 启动失败时的第一现场** |
| Windows 事件日志 | `应用程序` 日志 → 来源 `CameraDetection` 或 `.NET Runtime` | 服务崩溃、JVM 非正常退出 |
| 磁盘告警 | `<工作路径>\logs\disk-alert.log` | 分区超 85% |
| 备份日志 | `<备份目录>\logs\backup.log` | 备份执行记录 |
| 清理日志 | `<工作路径>\logs\cleanup-defect-images.log` | 清理执行记录 |

```powershell
# 最近 100 行应用日志
Get-Content 'C:\ProgramData\CameraDetection\logs\camera-detection-system.log' -Tail 100

# 只看错误
Get-Content '…\camera-detection-system.log' -Tail 2000 | Select-String 'ERROR'

# 服务崩溃事件
Get-WinEvent -FilterHashtable @{LogName='Application'; Level=2; StartTime=(Get-Date).AddHours(-24)} -MaxEvents 20 |
    Format-List TimeCreated, ProviderName, Message
```

> **服务"运行中"但业务全错** —— 最常见的原因是磁盘写满或数据库连不上。
> 这时看 `service-wrapper.log`：JVM 起来后应用连库失败，日志里会有完整的 JDBC 异常。

---

## 3. 数据库

```powershell
# 连库（用环境变量传口令，不进命令行）
$env:MYSQL_PWD = '你的root口令'
& 'C:\mysql8\bin\mysql.exe' -h127.0.0.1 -uroot -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='wms-new';"
Remove-Item Env:\MYSQL_PWD
```

| 表 | 保留 | 说明 |
|---|---|---|
| `defect_image_file` / `_box` | **7 天** | 缺陷图片元数据 + 标注框 |
| `defect_image_batch` | 30 天 | 设备上报批次 |
| `camera_images` | 7 / 30 天 | 兼容表 |
| `fact_production_30min` | 3 个月 | 30 分钟桶产量，归档到 `fact_production_30min_archive` |
| `sys_operation_log` | 6 个月 | 操作日志，约 3 万条/天 |

> **表数是 34 还是 38？** 取决于什么时候查：刚建完库是 **34**（脚本建的），
> **应用启动过一次之后是 38**（应用启动时用 `CREATE TABLE IF NOT EXISTS` 补建 4 张：
> `defect_image_batch` / `defect_image_file` / `defect_image_box` + 4 张聚合表）。
> **建完库必须启动一次应用**，否则设备上报图片会因表不存在而报错。
| `cfg_camera_defect_mapping` | 永久 | 120 条（8 相机 × 15 编码） |

> ⚠️ `sys_data_retention_policy` 表存了 5 条策略，但**代码从不读它** ——
> 真正的清理是应用内 `DataCleanupTask`（每天 02:00）与 `cleanup-defect-images.ps1`（03:30）
> 里**硬编码**的规则。改那张表**没有任何效果**。

---

## 4. 图片存储

```
<工作路径>\data\uploads\defect-images\
└── 20261028\                    ← 日期
    └── CAM-001\                 ← 相机
        └── 103000000_adc28391.png
```

- **配置项**：`config\application.yml` 的 `defect-image.upload.dir`
- **落盘规则**：`saveFile()` 自动建 `yyyyMMdd\<cameraId>\` 子目录
- **命名**：`HHmmssSSS_<uuid8>.<ext>` —— 毫秒时间戳 + 随机后缀，天然不重名

| 字段 | 存什么 | 影响 |
|---|---|---|
| `image_url` | **相对路径** `/api/v1/defect-images/file/20261028/CAM-001/xxx.png` | 前端只用它，**改根目录不影响前端** |
| `defect_image_file.image_path` | **绝对路径** | 仅作兜底反查；**旧记录会失真但不影响回读** |

回读逻辑（`resolveImagePath()`）第一步就是 `root.resolve(decoded)`，
用 URL 里的相对路径直接命中当前根目录 —— **换根目录后旧图片仍能正常显示**。

### 4.1 容量测算

目标场景：**20 相机 × 5 张/分钟 = 144,000 张/天**。

单张图片体积**高度依赖产线画面**，实测区间跨 70 倍：

| 类型 | 体积 |
|---|---|
| 真实工业画面 800×600 JPEG q85 | **19 KB** |
| 随机噪声 800×600 PNG | 1,220 KB |
| 随机噪声 1920×1080 PNG（最坏） | **6,078 KB** |

| 单张均寸 | 日增 | 7 天保留期 | 100 GB 数据盘够用？ |
|---|---|---|---|
| **19 KB**（真实 JPEG） | 2.6 GB | **18 GB** | ✓ 够 5 倍 |
| 350 KB（保守估计） | 48 GB | **336 GB** | ✗ **不够** |
| 1,220 KB（噪声 PNG） | 167 GB | 1,172 GB | ✗ **远远不够** |

> **设备端输出格式是容量规划的第一决定因素** —— 这比加磁盘重要得多。
> **上线首日必须用真实相机图片复核单张均寸。**

数据库侧对比（说明为什么瓶颈在磁盘而非数据库）：
`defect_image_file` 单行（数据+索引）实测 **630 字节** → 100 万行仅 **0.6 GB**。

---

## 5. 常见故障速查

| 症状 | 最可能原因 | 立即动作 |
|---|---|---|
| **服务未运行** | 安装时 DB 连不上 / 配置语法错 | `Get-Content '…\logs\service-wrapper.log' -Tail 50` |
| 服务运行但**页面 404** | 前端静态资源没打进 JAR | 确认 `app\time-bucket-demo.jar` 是新版本 |
| 页面正常但**业务接口全 403** | **没带 token**（鉴权已收紧） | 登录取 token 再带 `Authorization: Bearer` |
| **登录返回"用户名或密码错误"** | 口令未改 / 改过但配置里是旧的 | 见 §7.3 |
| **接口返回 500** | MyBatis 绑定失败 / SQL 报错 | 看应用日志里的 `BindingException` |
| 上传图片**返回 200 但文件不落盘** | `defect-image.upload.dir` 指向的目录不存在或无写权限 | `Test-Path` + 检查 ACL |
| 上传图片**返回 413** | 单批超 100 张 / 超过 10 MB | 看 `X-Image-Count` 头；反代要放开 `client_max_body_size` |
| 设备上报**返回 422 UNMAPPED** | `sourceCode` 未在 `cfg_camera_defect_mapping` 里 | 核对大小写（**大小写敏感**） |
| 设备上报**返回 413** | 单批超 100 张 | 拆批 |
| **磁盘 100% 但找不到大文件** | inode 耗尽（海量小文件） | `Get-ChildItem … -Recurse -File \| Measure-Object` |
| 图片**显示破图** | 记录在但文件被删了 | 见 §7.2「孤儿记录」 |
| 服务**莫名重启** | JVM OOM / 未捕获异常 | 看事件日志 + `service-wrapper.log` |
| **端口 8091 被占** | 上次安装没卸干净 | `Get-NetTCPConnection -LocalPort 8091` |

---

## 6. 磁盘与容量监控

### 6.1 水位告警（已上线）

```powershell
# 每 30 分钟执行一次（计划任务 CameraDetection-DiskUsage）
powershell -File '…\scripts\check-disk-usage.ps1' -Drives 'C D' -Threshold 85
```

行为：
- 分区使用率 > **85%** → 写本地告警文件 + **Windows 事件日志**（源 `CameraDetection`，EventID 3001）
- 退出码 1 → 便于被外部监控采集

```powershell
# 看告警记录
Get-Content '…\logs\disk-alert.log' -Tail 20
Get-WinEvent -FilterHashtable @{LogName='Application'; ProviderName='CameraDetection'} -MaxEvents 10 |
    Format-List TimeCreated, Message
```

### 6.2 综合健康巡检

```powershell
function Get-CdsHealth {
    Write-Host '=== 服务 ===' -ForegroundColor Cyan
    Get-Service CameraDetectionSystem | Format-Table Name, Status, StartType

    Write-Host '=== 磁盘（容量 + inode 概念上的文件数）===' -ForegroundColor Cyan
    Get-PSDrive C, D -ErrorAction SilentlyContinue |
        Select-Object Name,
            @{n='已用GB';e={[math]::Round($_.Used/1GB,1)}},
            @{n='可用GB';e={[math]::Round($_.Free/1GB,1)}},
            @{n='使用率%';e={[math]::Round($_.Used/($_.Used+$_.Free)*100)}}

    Write-Host '=== JVM ===' -ForegroundColor Cyan
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Select-Object ProcessId, @{n='内存MB';e={[math]::Round($_.WorkingSetSize/1MB,1)}}

    Write-Host '=== 图片 ===' -ForegroundColor Cyan
    $up = 'C:\ProgramData\CameraDetection\data\uploads\defect-images'
    $f = Get-ChildItem $up -Recurse -File -ErrorAction SilentlyContinue
    Write-Host ("  文件数 {0}，占用 {1:N1} MB" -f $f.Count, (($f | Measure-Object Length -Sum).Sum/1MB))

    Write-Host '=== 备份 ===' -ForegroundColor Cyan
    Get-ChildItem 'D:\CameraDetection\backup\db\full\*.sql.gz' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 3 Name, LastWriteTime
    Get-ScheduledTask -TaskName 'CameraDetection*' | Format-Table TaskName, State

    Write-Host '=== 真实错误数（近 1 小时）===' -ForegroundColor Cyan
    (Get-Content '…\logs\camera-detection-system.log' -Tail 5000 |
        Select-String 'ERROR' | Measure-Object).Count
}
```

### 6.3 容量增长趋势

| 数据 | 日增 | 7 天 | 30 天 |
|---|---|---|---|
| 图片（350 KB/张 × 144000） | 48 GB | 336 GB | — |
| `defect_image_file`（630 字节/行） | 87 MB | 0.6 GB | 2.6 GB |
| `sys_operation_log`（3 万条/天） | 视访问量 | < 50 MB | < 200 MB |

> **图片是唯一需要关注的增长线**。数据库侧在 7 天保留期下会自我回收，总量稳定。

---

## 7. 备份与恢复

### 7.1 自动备份（已上线）

| 项 | 值 |
|---|---|
| 触发 | **Windows 计划任务** `CameraDetection-Backup`，每天 **01:00** |
| 方式 | `mysqldump --single-transaction --quick --routines --triggers --events` + **gzip** |
| 位置 | `<备份目录>\full\wms-new_<时间戳>.sql.gz` |
| 保留 | 14 份（`KeepDays`），过期自动删除 |
| 校验 | 每个文件配 `.sha256` |
| 增量 | 依赖 MySQL binlog（**已开启，ROW 格式，保留 30 天**） |
| 异机 | `-OffsiteDir` 指向的目录（⚠️ **当前仍是同一块物理盘**） |

```powershell
# 看定时任务
Get-ScheduledTask -TaskName 'CameraDetection-Backup' | Format-List TaskName, State

# 立即试跑
Start-ScheduledTask -TaskName 'CameraDetection-Backup'
Get-ScheduledTaskInfo -TaskName 'CameraDetection-Backup' | Format-List LastRunTime, LastTaskResult

# 看产物 + 校验
$full = 'D:\CameraDetection\backup\db\full'
Get-ChildItem $full | Select-Object Name, Length, LastWriteTime
Get-Content "$full\wms-new_<最新>.sql.gz.sha256"    # 与 Get-FileHash 对比

# 备份日志
Get-Content 'D:\CameraDetection\backup\db\logs\backup.log' -Tail 20
```

> **口令来源**：`config\backup.conf` 的 `MYSQL_PASSWORD`。
> 计划任务无法传参，所以口令必须落在文件里（**含明文，限制 ACL**）。

### 7.2 恢复演练（**务必每月做一次**）

> 未验证的备份等于没有备份。以下流程**不碰生产库**。

```powershell
$env:MYSQL_PWD = '你的root口令'
$mysql = 'C:\mysql8\bin\mysql.exe'

# 1. 建临时库
& $mysql -h127.0.0.1 -uroot -e "DROP DATABASE IF EXISTS cds_restore_test; CREATE DATABASE cds_restore_test DEFAULT CHARSET utf8mb4;"

# 2. 回灌（必须用 cmd /c 字节流重定向，PS 管道会乱码）
$gz = (Get-ChildItem 'D:\CameraDetection\backup\db\full\*.sql.gz' | Sort-Object LastWriteTime -Descending)[0].FullName
cmd /c "type `"$gz`" | gzip -d | `"$mysql`" -h127.0.0.1 -uroot cds_restore_test"

# 3. 校验四件事
& $mysql -h127.0.0.1 -uroot -N -e "
  SELECT CONCAT('  表数=',COUNT(*),' (应为 38~39)') FROM information_schema.tables WHERE table_schema='cds_restore_test';
  SELECT CONCAT('  索引数=',COUNT(DISTINCT index_name),' (应为 7)') FROM information_schema.statistics
    WHERE table_schema='cds_restore_test' AND table_name='defect_image_file';
  SELECT CONCAT('  中文=',product_name) FROM cds_restore_test.dim_product LIMIT 1;
  SELECT CONCAT('  映射=',COUNT(*),' 条 (应为 120)') FROM cds_restore_test.cfg_camera_defect_mapping;"

# 4. 清理
& $mysql -h127.0.0.1 -uroot -e "DROP DATABASE IF EXISTS cds_restore_test;"
Remove-Item Env:\MYSQL_PWD
```

**2026-10-26 在 Linux 服务器上的实际演练结果**：39 表、索引 7 个、
`产品A` / `杭州工厂` / `产线缺陷在线AI检测系统` 中文完好、`cfg_camera_defect_mapping` 120 条 —— **全部通过**。

### 7.3 生产恢复

```powershell
Stop-Service CameraDetectionSystem
$env:MYSQL_PWD = '你的root口令'
& $mysql -h127.0.0.1 -uroot -e "DROP DATABASE IF EXISTS ``wms-new``; CREATE DATABASE ``wms-new`` DEFAULT CHARSET utf8mb4;"
$gz = 'D:\CameraDetection\backup\db\full\wms-new_<时间戳>.sql.gz'
cmd /c "type `"$gz`" | gzip -d | `"$mysql`" -h127.0.0.1 -uroot ``wms-new``"
Remove-Item Env:\MYSQL_PWD
Start-Service CameraDetectionSystem
```

> ⚠️ 恢复**会覆盖现有数据**。生产环境先按 §7.2 在测试库演练。

### 7.4 管理员口令重置

Windows 版暂无独立的重置脚本。推荐顺序：

1. **首选**：应用内「修改密码」功能
2. **其次**：用 BCrypt 工具生成哈希后直接更新（BCrypt，cost 10，前缀 `$2a$`）：

```sql
UPDATE sys_user SET password_hash = '<BCrypt 哈希>' WHERE username = 'admin';
```

> **注意**：哈希必须是 **BCrypt**，不能是 MD5/SHA —— 登录走 `BCryptPasswordEncoder.matches()`，
> 格式不对会永远提示"用户名或密码错误"。

3. **兜底**：重跑 `03_system_settings.sql` 段把 admin 恢复成默认口令，再走第 1 步。
   ⚠️ **该脚本会重置系统设置**，生产环境慎用。

### 7.5 孤儿记录（记录在、文件没了）

前端显示破图且记录永不复收 —— 通常是历史遗留（清理顺序错乱）留下的。

```powershell
# 找出库里记录了、但文件不存在的
$env:MYSQL_PWD = '你的口令'
$paths = & 'C:\mysql8\bin\mysql.exe' -h127.0.0.1 -uroot --batch --skip-column-names `
         -e "SELECT image_path FROM ``wms-new``.defect_image_file;" 'wms-new'
$missing = $paths | Where-Object { $_ -and -not (Test-Path -LiteralPath $_) }
Write-Host "孤儿记录：$($missing.Count) 条"
$missing | Select-Object -First 20
```

处理：直接删掉这些库记录（前端就不再显示破图了）：

```sql
DELETE FROM defect_image_box WHERE image_id IN (SELECT image_id FROM defect_image_file WHERE ...);
DELETE FROM defect_image_file WHERE image_path IN (...);
```

> **预防**：清理脚本严格「先删库记录、再删磁盘文件」，且**连不上数据库时拒绝删文件**。
> 改脚本时务必保持这个顺序。

### 7.6 遗留风险

| # | 风险 | 说明 |
|---|---|---|
| 1 | **备份与数据同盘** | `-OffsiteDir` 当前指向同一块物理盘 → 只抗逻辑误删，**不抗磁盘故障** |
| 2 | 无时间点恢复自动化 | binlog 具备条件（ROW 格式保留 30 天），但恢复需手工 `mysqlbinlog` 拼接，流程未脚本化 |
| 3 | 备份耗时随库增长 | 当前 < 1 秒；按 2.6 GB 库线性外推约 60 秒。计划任务 `ExecutionTimeLimit` 设了 1 小时，有充足余量 |

---

## 8. 鉴权现状（2026-10-28 已修复）

> ⚠️ **重大安全缺陷，已修复**。此前 `JwtRequestFilter` 解析 token、查用户、
> `validateToken()` 全都做了，**唯独没有调用 `setAuthentication()`**，
> 只打一行"JWT Token 验证成功"的日志 —— 看起来在鉴权，实际 SecurityContext 始终为空。
> 叠加原本约 60 条 `permitAll()` 白名单（**含全部业务查询路径**），**等于完全无鉴权**。

### 8.1 当前行为（实测）

| 场景 | 响应 |
|---|---|
| 无 token 访问 `/api/cameras`、`/api/stats/dashboard-overview`、`/api/company` | **403** |
| 带有效 JWT 访问上述接口 | **200** |
| 设备端 `dev-token` 访问 `/api/v1/**` | **200**（冻结契约未受影响） |
| SPA 深链 `/system/config`（浏览器刷新，无 token） | **200**（放行页面路由） |
| 前端 SPA 静态资源 | **200** |

### 8.2 手工验证

```powershell
$B = 'http://localhost:8091'

# 匿名 —— 应 403
curl.exe -s -o NUL -w "%{http_code}`n" "$B/api/stats/dashboard-overview"

# 带 token —— 应 200
$token = (Invoke-RestMethod -Uri "$B/api/auth/login" -Method Post -ContentType 'application/json' `
    -Body '{"username":"admin","password":"你的口令"}').data.token
curl.exe -s -o NUL -w "%{http_code}`n" -H "Authorization: Bearer $token" "$B/api/stats/dashboard-overview"

# 设备端 —— 应 200
curl.exe -s -o NUL -w "%{http_code}`n" -H "X-Device-Id: CAM-001" -H "Authorization: Bearer dev-token" `
    "$B/api/v1/defect-images/recent?limit=1"
```

自检里已内置这两项（`鉴权生效（匿名应被拒）` / `带令牌访问业务接口`）。

### 8.3 前端 401 会怎样

`frontend\src\utils\request.js` 的响应拦截器：401 → 清 token → 跳登录页。
所以**页面刷新后需要重新登录**，这是正常的。

> **改白名单时的两个坑**：
> 1. **不要把 `/api/**` 业务接口放进白名单** —— 那是需要登录才能看的数据。
> 2. **但 SPA 页面路由必须放行**（`/dashboard`、`/system/config` 等）——
>    浏览器直接访问/刷新时还没登录、没有 token，不放行会 403，用户连登录页都进不去。
>    原则：**放行页面路由（返回 HTML），不放行数据接口（`/api/**`）**。

---

## 9. 升级与回滚

见 `docs/部署方案-Windows.md` §6（完整流程）。要点：

```powershell
# 升级前：备份（数据库 + JAR + 配置）
Start-ScheduledTask -TaskName 'CameraDetection-Backup'
Copy-Item '…\app\time-bucket-demo.jar' "D:\backup\jars\time-bucket-demo.jar.$stamp.bak"
Copy-Item '…\config\application.yml' "D:\backup\application.yml.$stamp.bak"

# 升级：停服务 → 换 JAR 与 scripts → 起服务 → 自检
Stop-Service CameraDetectionSystem
Copy-Item 'D:\new\time-bucket-demo.jar' '…\app\' -Force
Start-Service CameraDetectionSystem
powershell -File '…\scripts\verify-installation.ps1'
```

> ⚠️ **不要整体覆盖 `config\application.yml`** —— 里面有现场改过的 `jwt.secret` 与数据库口令。
> ⚠️ **库结构有变更时**先在测试库演练增量 SQL，**绝不直接跑全量 `02_final_schema_p0.sql`**
>（它会 DROP 并重建 21 张表）。

---

## 10. 上线前仍需处理的事项

| # | 事项 | 说明 |
|---|---|---|
| 1 | **改管理员口令** | 默认 `admin`/`123456` 是公开的默认口令 |
| 2 | **限制配置文件 ACL** | `config\application.yml`、`config\backup.conf` 存着明文口令 |
| 3 | **异机备份** | `-OffsiteDir` 当前指向同一块物理盘 |
| 4 | **设备端输出 JPEG** | 若相机输出 PNG，7 天需 1172 GB，会撑爆数据盘 |
| 5 | **把工作路径放到独立数据盘** | 否则按目标负载约 1 天写满系统盘 |

---

## 附录 A：常用命令速查

```powershell
# 全部脚本的帮助
powershell -File '…\scripts\backup-database.ps1' -?          # Get-Help
powershell -File '…\scripts\cleanup-defect-images.ps1' -?

# 查看服务的启动参数（服务包装器读 workdir.txt）
Get-Content '…\config\workdir.txt'

# 重启并观察启动日志
Restart-Service CameraDetectionSystem
Get-Content '…\logs\service-wrapper.log' -Tail 30 -Wait

# 端口占用排查
Get-NetTCPConnection -LocalPort 8091 -ErrorAction SilentlyContinue |
    Select LocalAddress, LocalPort, State, OwningProcess
Get-Process -Id (Get-NetTCPConnection -LocalPort 8091).OwningProcess

# 防火墙规则
Get-NetFirewallRule -DisplayName '*Camera*' | Format-Table DisplayName, Enabled, Direction

# 解压备份看内容（不解压到生产库）
$gz = (Get-ChildItem 'D:\backup\db\full\*.sql.gz' | Sort-Object LastWriteTime -Descending)[0].FullName
cmd /c "gzip -dc `"$gz`" | more"
```

## 附录 B：目录速查

```
C:\Program Files\CameraDetectionSystem\     程序本体
├─ app\time-bucket-demo.jar                  后端 JAR（含前端）
├─ config\
│  ├─ application.yml                        外部配置（必改 2 处）
│  ├─ workdir.txt                            工作路径
│  └─ backup.conf                            备份口令（计划任务用）
├─ scripts\                                  9 个运维脚本
├─ sql\                                      建库脚本 4 段
├─ service\CameraDetectionService.exe        服务包装器
├─ jre\                                      内置 Java 8
├─ data\uploads\defect-images\              图片（工作路径内）
└─ logs\                                     日志（工作路径内）

C:\ProgramData\CameraDetection\              默认工作路径
├─ data\uploads\defect-images\
└─ logs\

D:\CameraDetection\backup\db\                备份（可用 -BackupDir 改）
├─ full\wms-new_<时间戳>.sql.gz
├─ full\wms-new_<时间戳>.sql.gz.sha256
└─ logs\backup.log
```

## 附录 B2：Windows PowerShell 的五个坑（实测）

写 Windows 运维脚本时，这五个坑**每一个都曾让脚本"看起来正常"或直接失败**。
本项目的 `backup-database.ps1` / `cleanup-defect-images.ps1` 里都已写进注释。

| # | 坑 | 现象 | 正确写法 |
|---|---|---|---|
| 1 | **stdout 重定向变 UTF-16LE** | `mysqldump ... > file.sql` 产出 UTF-16LE —— 压缩/校验/退出码**全正常**，但内容不是合法 SQL，**备份不可用** | `--result-file=<路径>` 让 mysqldump 自己写 |
| 2 | **不支持 `<` 输入重定向** | `mysql ... < file.sql` 报「运算符是为将来使用而保留的」 | `--execute "source <正斜杠路径>"` |
| 3 | **管道按 GBK 重编码** | `Get-Content f.sql \| mysql` → 中文全乱码 | 同上 |
| 4 | **裸写 `-h127.0.0.1` 被拆开** | `Unknown MySQL server host '127'` | 参数拆开：`'-h', $DbHost` |
| 5 | **`Start-Process` 被环境变量噎死** | 环境里同时有 `HTTPS_PROXY` 与 `https_proxy` → .NET 抛「已添加项。字典中的关键字…」 | 改用 `&` + 原生重定向 |

### B2.1 第 1 条是典型的「假成功」

```
退出码 0 ✓   日志"全量备份完成" ✓   gzip 成功 ✓   sha256 通过 ✓   大小正常 ✓
—— 但文件是 UTF-16LE，恢复时根本执行不了
```

**不实际解压看内容，这个 bug 可以静默运行很久。**
所以 `backup-database.ps1` 里加了编码防御：发现 BOM 是 `FF FE` 直接判失败并删除。

```powershell
if ($rawBytes[0] -eq 0xFF -and $rawBytes[1] -eq 0xFE) {
    Remove-Item -Force $rawFile
    Fail '备份文件是 UTF-16LE 编码（PowerShell 重定向所致），不是合法 SQL —— 已删除'
}
```

---

## 附录 C：磁盘写满故障树

> 触发条件：图片按 350 KB/张、144000 张/天 约 **48 GB/天**；
> 若图片仍在系统盘，**约 1 天写满**。

### C.1 症状对照

| 症状 | 最可能原因 | 立即动作 |
|---|---|---|
| 页面 200 但**上传图片失败** | 磁盘写满 | `Get-PSDrive C,D` |
| 上传返回 **200 但文件不落盘** | 目标目录不存在或无写权限 | `Test-Path` + ACL |
| 上传返回 **500 / 数据库错误** | 系统盘写满（MySQL datadir 在系统盘） | `Get-PSDrive C` |
| 磁盘 100% 但找不到大文件 | **海量小文件**（inode 耗尽） | 数一下文件数 |
| 应用日志暴涨 | 日志无轮转 | `Get-ChildItem …\logs` |

### C.2 排查步骤

```powershell
# 1. 容量一起看
Get-PSDrive C, D -ErrorAction SilentlyContinue |
    Select-Object Name, @{n='已用GB';e={[math]::Round($_.Used/1GB,1)}},
                     @{n='可用GB';e={[math]::Round($_.Free/1GB,1)}}

# 2. 谁在占
Get-ChildItem 'C:\ProgramData\CameraDetection' -Recurse -File -ErrorAction SilentlyContinue |
    Measure-Object Length -Sum
Get-ChildItem 'C:\Program Files\MySQL\MySQL Server 8.0\Data' -Recurse -File -ErrorAction SilentlyContinue |
    Measure-Object Length -Sum

# 3. 图片文件数
(Get-ChildItem 'C:\ProgramData\CameraDetection\data\uploads\defect-images' -Recurse -File).Count

# 4. 看告警记录
Get-Content '…\logs\disk-alert.log' -Tail 20
```

### C.3 应急处置

```powershell
# 1. 立即释放：手动跑一次清理（缩短保留期）
powershell -File '…\scripts\cleanup-defect-images.ps1' -DbPassword 'xxx' -KeepDays 2

# 2. 紧急清日志
Get-ChildItem '…\logs\*.log' | Where-Object { $_.Length -gt 100MB } | Remove-Item -Force

# 3. 若系统盘满：先停应用，再清 MySQL 临时文件
Stop-Service CameraDetectionSystem
Remove-Item 'C:\Program Files\MySQL\MySQL Server 8.0\Data\*.bin' -Force -ErrorAction SilentlyContinue
# 注意：会丢失增量恢复点
Start-Service CameraDetectionSystem
```

### C.4 长期对策

| 对策 | 状态 |
|---|---|
| 工作路径放独立数据盘 | **待现场确认**（安装向导里选） |
| 保留期收敛到 7 天 | ✅ 已上线（`cleanup-defect-images.ps1`） |
| 磁盘水位告警 | ✅ 已上线（每 30 分钟，超 85% 告警） |
| **设备端转码压缩** | **待做** —— 随机噪声 PNG 6 MB → JPEG q85 约 200 KB，可省 97% |
| **异机备份** | **待做** —— 当前备份与数据同盘，不抗磁盘故障 |
