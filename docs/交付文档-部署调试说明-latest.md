# 产线缺陷在线AI检测系统 — 部署调试说明文档

版本：v1.0  
日期：2026-06-14  
适用对象：部署工程师、运维人员、现场调试人员

---

## 1. 部署目标

在一台全新的 Windows 电脑上完成以下目标：

1. 安装 MySQL 8.0。
2. 导入系统数据库 `wms-new`。
3. 安装产线缺陷在线AI检测系统。
4. 启动 Windows 服务 `CameraDetectionSystem`。
5. 浏览器访问页面。
6. 相机通过 8091 端口上报状态、图片和检测统计数据。

最终运行结构：

```text
Windows 电脑
├─ MySQL 8.0
│  └─ 数据库：wms-new
│
└─ 产线缺陷在线AI检测系统 Windows 服务
   ├─ Spring Boot 后端
   ├─ Vue 前端静态页面
   ├─ 图片上传目录
   ├─ 日志目录
   └─ 外部配置 application.yml
```

---

## 2. 交付物位置

当前项目交付物：

| 类型 | 路径 |
---|---|
| 安装包 | `D:\45344\0004-软件源码\dist-release\installer\CameraDetectionSystem-Setup-1.0.0.exe` |
| 发布目录 | `D:\45344\0004-软件源码\dist-release\CameraDetectionSystem` |
| 后端 Jar | `D:\45344\0004-软件源码\dist-release\CameraDetectionSystem\app\time-bucket-demo.jar` |
| 外部配置 | `D:\45344\0004-软件源码\dist-release\CameraDetectionSystem\config\application.yml` |
| 数据库 SQL | `D:\45344\0004-软件源码\dist-release\CameraDetectionSystem\sql\wms-new.sql` |
| 安装脚本 | `D:\45344\0004-软件源码\dist-release\CameraDetectionSystem\scripts` |
| 内置 JRE | `D:\45344\0004-软件源码\dist-release\CameraDetectionSystem\jre\bin\java.exe` |

---

## 3. 环境要求

### 3.1 操作系统

- Windows 10 / Windows Server 2016+，64 位。
- 安装和服务注册需要管理员权限。

### 3.2 数据库

- MySQL 8.0。
- 默认连接：

```text
Host: 127.0.0.1
Port: 3306
Database: wms-new
User: root
Password: CCshenda889
```

可在安装目录下修改：

```text
config\application.yml
```

### 3.3 端口

| 端口 | 说明 | 建议 |
---:|---|---|
| 8091 | 页面访问 + 相机接口上报 | 按现场网络开放 |
| 3306 | MySQL | 默认只允许本机访问 |

### 3.4 磁盘空间

建议：

- 系统安装目录：至少 2GB。
- 工作路径：根据图片保存周期预留，建议 50GB 以上。
- 缺陷图片默认保存到工作路径下：

```text
<工作路径>\data\uploads\defect-images
```

---

## 4. 安装步骤

### 4.1 安装 MySQL

1. 安装 MySQL 8.0。
2. 设置 root 密码。
3. 确认 `mysql.exe` 可用。

默认查找路径：

```text
C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe
C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe
C:\Program Files (x86)\MySQL\MySQL Server 8.0\bin\mysql.exe
```

验证：

```powershell
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" --version
```

### 4.2 运行安装包

以管理员身份运行：

```text
D:\45344\0004-软件源码\dist-release\installer\CameraDetectionSystem-Setup-1.0.0.exe
```

安装过程中需要选择：

1. 安装目录：程序文件位置。
2. 工作路径：日志、图片和运行数据保存位置。

建议：

- 安装目录可使用默认路径。
- 工作路径建议选择非系统盘，例如：

```text
D:\CameraDetectionSystemRuntime
```

安装器会：

- 复制程序文件。
- 写入 `config\workdir.txt`。
- 注册 Windows 服务 `CameraDetectionSystem`。
- 启动服务。
- 可选打开浏览器访问 `http://localhost:8091`。

### 4.3 初始化数据库

安装完成后，进入安装目录，执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\scripts\init-database.ps1" -Yes
```

如果 MySQL 密码不是默认值：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\scripts\init-database.ps1" -Password "你的MySQL密码" -Yes
```

指定 SQL：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\scripts\init-database.ps1" `
  -SqlFile ".\sql\wms-new.sql" `
  -HostName "127.0.0.1" `
  -Port 3306 `
  -Database "wms-new" `
  -User "root" `
  -Password "CCshenda889" `
  -Yes
```

注意：

- 初始化 SQL 包含建表和初始数据。
- 若现场已有业务数据，导入前必须先备份。

### 4.4 启动服务

安装器默认会启动服务，如需手动启动：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\scripts\start-service.ps1"
```

查看状态：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\scripts\service-status.ps1"
```

停止服务：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\scripts\stop-service.ps1"
```

卸载服务：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\scripts\uninstall-service.ps1"
```

---

## 5. 访问验证

### 5.1 页面访问

浏览器打开：

```text
http://localhost:8091
```

或：

```text
http://localhost:8091/camera-status
```

页面应显示：

- 顶部标题：`产线缺陷在线AI检测系统`
- 主 Tab：相机状态、图像记录、产线统计
- 默认进入：相机状态

### 5.2 接口访问

相机列表接口：

```powershell
Invoke-WebRequest -UseBasicParsing -Uri "http://localhost:8091/api/cameras?page=1&size=1"
```

预期：

```text
HTTP 200
```

### 5.3 服务状态

```powershell
Get-Service -Name CameraDetectionSystem
```

预期：

```text
Status : Running
```

### 5.4 端口监听

```powershell
Get-NetTCPConnection -LocalPort 8091 -State Listen
```

预期：

```text
本机存在 8091 Listen
```

---

## 6. 配置说明

配置文件：

```text
<安装目录>\config\application.yml
```

### 6.1 服务端口

```yaml
server:
  port: 8091
```

修改端口后需重启服务。

### 6.2 数据库

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/wms-new?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false
    username: root
    password: CCshenda889
```

### 6.3 上传大小

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 10MB
      max-request-size: 1024MB
```

### 6.4 工作路径和上传目录

服务启动时会传入：

```text
-Dapp.home=<工作路径>
```

配置：

```yaml
app:
  home: ${APP_HOME:./}

defect-image:
  max-batch-images: 100
  upload:
    dir: ${app.home}/data/uploads/defect-images/

logging:
  file:
    name: ${app.home}/logs/camera-detection-system.log
```

### 6.5 JWT

```yaml
jwt:
  secret: mySecretKey123456789012345678901234567890
  expiration: 86400000
```

生产环境建议修改默认 `secret`。

---

## 7. 日志说明

### 7.1 Spring Boot 日志

```text
<工作路径>\logs\camera-detection-system.log
```

### 7.2 Windows 服务包装器日志

```text
<工作路径>\logs\service-wrapper.log
<工作路径>\logs\service-out.log
<工作路径>\logs\service-err.log
```

### 7.3 安装日志

Inno Setup 安装日志由安装器生成，出现安装失败时优先查看。

---

## 8. 调试常用命令

### 8.1 查看服务

```powershell
Get-Service -Name CameraDetectionSystem | Format-List Name,Status,StartType
```

### 8.2 重启服务

```powershell
Restart-Service -Name CameraDetectionSystem -Force
```

### 8.3 查看 8091 被谁占用

```powershell
Get-NetTCPConnection -LocalPort 8091 -State Listen | Select-Object LocalAddress,LocalPort,OwningProcess
```

### 8.4 查看最近日志

```powershell
Get-Content "<工作路径>\logs\camera-detection-system.log" -Tail 100
Get-Content "<工作路径>\logs\service-wrapper.log" -Tail 50
```

### 8.5 控制台方式启动

用于排查服务启动失败：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\scripts\run-console.ps1"
```

---

## 9. 接口调试示例

### 9.1 心跳接口

```powershell
$body = @{
  cameraName = "杭州1线-上料相机A"
  productName = "产品A"
  timestamp = "2026-06-14 09:30:00"
  cameraStatus = 1
  recognizeCount = 100
  correctCount = 95
  abnormalCount = 5
  cpuUsage = 35.5
  memoryUsage = 46.1
  temperature = 42.3
} | ConvertTo-Json

Invoke-WebRequest `
  -UseBasicParsing `
  -Method POST `
  -Uri "http://localhost:8091/api/v1/camera/status/heartbeat" `
  -Headers @{
    Authorization = "Bearer qa-token"
    "X-Device-Id" = "CAM-001"
  } `
  -ContentType "application/json" `
  -Body $body
```

### 9.2 检测统计接口

```powershell
$body = @{
  schemaVersion = "2.0"
  batchId = "CAM-001-20260614093000"
  requestId = "REQ-20260614093000"
  cameraCode = "CAM-001"
  productName = "产品A"
  reportTime = "2026-06-14 09:30:00"
  counterMode = "INCREMENTAL"
  timeRange = @{
    startTime = "2026-06-14 09:20:00"
    endTime = "2026-06-14 09:30:00"
  }
  summary = @{
    totalCount = 100
    okCount = 95
    ngCount = 5
  }
  defects = @(
    @{ sourceCode = "p0"; count = 3 },
    @{ sourceCode = "p1"; count = 2 }
  )
} | ConvertTo-Json -Depth 8

Invoke-WebRequest `
  -UseBasicParsing `
  -Method POST `
  -Uri "http://localhost:8091/api/v1/camera/inspection/batch-upload" `
  -Headers @{
    Authorization = "Bearer qa-token"
    "X-Device-Id" = "CAM-001"
    "X-Batch-Id" = "CAM-001-20260614093000"
  } `
  -ContentType "application/json" `
  -Body $body
```

### 9.3 图片接口

PowerShell 原生命令构造 multipart 较繁琐，建议使用 Postman 或 curl。

curl 示例：

```bash
curl -X POST "http://localhost:8091/api/v1/defect-images/upload" ^
  -H "Authorization: Bearer qa-token" ^
  -H "X-Device-Id: CAM-001" ^
  -F "files=@D:\test\img1.png" ^
  -F "metadata={\"cameraId\":\"CAM-001\",\"cameraName\":\"杭州1线-上料相机A\",\"uploadTime\":\"2026-06-14 09:30:00\",\"images\":[{\"fileName\":\"img1.png\",\"captureTime\":\"2026-06-14 09:29:59\",\"defects\":[{\"type\":\"胶滴\",\"severity\":\"HIGH\",\"x\":10,\"y\":12,\"width\":30,\"height\":24,\"area\":720}]}]}"
```

---

## 10. 常见问题与处理

### 10.1 页面打不开

排查顺序：

1. 服务是否存在：

```powershell
Get-Service -Name CameraDetectionSystem
```

2. 服务是否 Running：

```powershell
Start-Service -Name CameraDetectionSystem
```

3. 8091 是否监听：

```powershell
Get-NetTCPConnection -LocalPort 8091 -State Listen
```

4. 查看日志：

```powershell
Get-Content "<工作路径>\logs\service-err.log" -Tail 100
Get-Content "<工作路径>\logs\camera-detection-system.log" -Tail 100
```

### 10.2 服务启动报 `java.exe not found`

原因：

- 安装目录缺少 `jre\bin\java.exe`。
- 或安装包未包含 JRE。

处理：

1. 确认：

```text
<安装目录>\jre\bin\java.exe
```

2. 若不存在，重新使用包含 JRE 的安装包安装。
3. 或安装 JDK/JRE 8+ 并设置 `JAVA_HOME`。

### 10.3 数据库连接失败

常见日志：

```text
Communications link failure
Access denied for user
Unknown database 'wms-new'
```

处理：

1. 确认 MySQL 已启动。
2. 确认端口 3306。
3. 确认 `config\application.yml` 中用户名密码。
4. 执行初始化脚本导入数据库。

### 10.4 图片上传成功但页面不展示

排查：

1. 图像记录页是否被工厂/产线/工位筛选过滤。
2. `metadata.cameraId` 是否和 `X-Device-Id`、系统配置相机编号一致。
3. `metadata.images[].fileName` 是否和上传文件名完全一致。
4. 图片是否真实可解析。
5. 数据库是否有记录：

```sql
SELECT * FROM defect_image_file ORDER BY image_id DESC LIMIT 10;
```

### 10.5 图片解析失败

处理：

| 错误 | 原因 | 处理 |
---|---|---|
| `FILE_NAME_MISMATCH` | 文件名不一致 | 修正 metadata 文件名 |
| `IMAGE_CONTENT_INVALID` | 图片损坏、空文件、扩展名与内容不一致 | 重新导出原始图片 |
| `UNSUPPORTED_IMAGE_FORMAT` | 扩展名不支持 | 改用支持格式 |
| `IMAGE_SIZE_INVALID` | 占位小图 | 重新采集 |

### 10.6 检测统计接口返回 422

常见原因：

1. `counterMode` 不是 `INCREMENTAL`。
2. `productName` 与产品配置不一致。
3. 相机未配置。
4. Token 不正确。
5. `sourceCode` 未配置映射。
6. `totalCount != okCount + ngCount`。
7. `ngCount != sum(defects[].count)`。

建议：

- 先在系统配置页面确认产品、相机、缺陷类型、相机缺陷映射。
- 查看映射异常页。
- 查询异常表：

```sql
SELECT * FROM fact_inspection_mapping_exception ORDER BY exception_id DESC LIMIT 20;
```

### 10.7 产线统计没有数据

排查：

1. 检测统计接口是否返回成功。
2. 筛选条件时间范围是否覆盖上报时间。
3. 工厂/产线/工位筛选是否正确。
4. 聚合表是否有数据：

```sql
SELECT * FROM agg_quality_30m ORDER BY agg_id DESC LIMIT 10;
SELECT * FROM agg_defect_type_30m ORDER BY agg_id DESC LIMIT 10;
```

5. 大时间范围可手动执行日聚合。

### 10.8 前端开发启动 PowerShell 脚本被禁用

如果执行 `npm run dev` 报：

```text
npm.ps1 因执行策略被禁止
```

解决：

```powershell
Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
```

或直接使用：

```powershell
npm.cmd run dev
```

---

## 11. 备份与恢复

### 11.1 数据库备份

项目脚本：

```text
D:\45344\0004-软件源码\scripts\database-backup.ps1
```

示例：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "D:\45344\0004-软件源码\scripts\database-backup.ps1"
```

### 11.2 数据库恢复

项目脚本：

```text
D:\45344\0004-软件源码\scripts\database-restore.ps1
```

恢复前建议：

1. 停止服务。
2. 备份当前数据库。
3. 恢复 SQL。
4. 启动服务。

### 11.3 图片和日志备份

备份工作路径：

```text
<工作路径>\data\uploads
<工作路径>\logs
```

---

## 12. 升级发布流程

### 12.1 构建安装包

在开发机执行：

```powershell
$env:JAVA_HOME="C:\Program Files\Java\jdk1.8.0_281"
$env:Path="$env:JAVA_HOME\bin;D:\45344\0000-jar\apache-maven-3.8.6\bin;C:\Users\Administrator\AppData\Local\Programs\nodejs\current;$env:Path"

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "D:\45344\0004-软件源码\build-release.ps1"
```

输出：

```text
D:\45344\0004-软件源码\dist-release\installer\CameraDetectionSystem-Setup-1.0.0.exe
```

### 12.2 覆盖升级

建议流程：

1. 备份数据库。
2. 备份工作路径。
3. 停止服务。
4. 安装新版本或替换 Jar。
5. 检查 `config\application.yml` 是否保留现场配置。
6. 启动服务。
7. 执行验收清单。

### 12.3 手动替换 Jar

```powershell
Stop-Service -Name CameraDetectionSystem -Force
Copy-Item ".\app\time-bucket-demo.jar" "<安装目录>\app\time-bucket-demo.jar" -Force
Start-Service -Name CameraDetectionSystem
```

---

## 13. 上线验收清单

| 编号 | 检查项 | 预期 |
---|---|---|
| A01 | MySQL 8.0 已安装 | 可连接 |
| A02 | 数据库 `wms-new` 已导入 | 表数量正常 |
| A03 | Windows 服务存在 | `CameraDetectionSystem` |
| A04 | Windows 服务运行 | `Running` |
| A05 | 端口 8091 监听 | Listen |
| A06 | 浏览器访问首页 | 打开相机状态页 |
| A07 | 管理员登录 | 成功 |
| A08 | 系统配置可打开 | 成功 |
| A09 | 心跳接口上报 | 相机状态更新 |
| A10 | 图片接口上报 | 图像记录展示 |
| A11 | 检测统计上报 | 产线统计展示 |
| A12 | 服务重启 | 页面和接口恢复 |
| A13 | 日志目录 | 正常写入日志 |
| A14 | 图片目录 | 正常写入图片 |
| A15 | 3306 访问策略 | 仅本机或受控网络 |

---

## 14. 安全建议

1. MySQL 3306 默认不对外开放。
2. 修改默认 MySQL 密码。
3. 修改 `jwt.secret`。
4. 相机上报 Token 按设备单独配置。
5. 8091 仅对浏览器客户端和相机网络开放。
6. 定期备份数据库和上传图片目录。
7. 定期清理过期图片和操作日志。

---

## 15. 当前验证结果引用

最新自动化专项回归报告：

```text
D:\45344\0004-软件源码\test-reports\专项回归与安装包端到端验收报告-latest.md
```

本轮结果：

- 总用例：48
- 通过：48
- 失败：0
- P0 未通过：0
- 通过率：100%

可作为现场交付前的基线验收记录。
