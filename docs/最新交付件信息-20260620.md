# 产线缺陷在线AI检测系统 — 最新交付件信息

版本：v1.0.0  
整理时间：2026-06-20  
交付根目录：`D:\45344\0004-软件源码`

---

## 1. 本次交付结论

当前项目已经形成可安装交付形态：**Windows 安装包 + 内置 JRE + Spring Boot 后端 + Vue 前端静态资源 + 外部配置 + MySQL 初始化脚本 + 运维脚本 + 交付文档 + 自动化测试报告**。

最新安装包级发布验证结论：**PASS，可作为现场验收候选包**。

---

## 2. 交付件总览

| 类型 | 路径 | 说明 |
|---|---|---|
| Windows 安装包 | `D:\45344\0004-软件源码\dist-release\installer\CameraDetectionSystem-Setup-1.0.0.exe` | 最终安装程序，约 101.45 MB |
| 发布目录 | `D:\45344\0004-软件源码\dist-release\CameraDetectionSystem` | 安装包生成前的完整文件目录 |
| 后端 JAR | `D:\45344\0004-软件源码\dist-release\CameraDetectionSystem\app\time-bucket-demo.jar` | Spring Boot 可执行 JAR，约 54.21 MB |
| 内置 JRE | `D:\45344\0004-软件源码\dist-release\CameraDetectionSystem\jre` | 避免目标机器缺少 Java 运行环境 |
| Windows 服务包装器 | `D:\45344\0004-软件源码\dist-release\CameraDetectionSystem\service\CameraDetectionService.exe` | 将后端 JAR 作为 Windows 服务运行 |
| 外部配置 | `D:\45344\0004-软件源码\dist-release\CameraDetectionSystem\config\application.yml` | 端口、数据库、上传目录、日志目录等配置 |
| 数据库 SQL | `D:\45344\0004-软件源码\dist-release\CameraDetectionSystem\sql\wms-new.sql` | 初始化数据库数据脚本 |
| 运维脚本 | `D:\45344\0004-软件源码\dist-release\CameraDetectionSystem\scripts` | 安装/启动/停止/卸载服务、自检、数据库初始化 |
| 交付文档 | `D:\45344\0004-软件源码\docs` | 项目说明、接口协议、部署调试、测试报告索引 |
| 测试报告 | `D:\45344\0004-软件源码\test-reports` | 自动化测试、压测、安装包验收报告 |

---

## 3. 发布目录结构

```text
CameraDetectionSystem
├─ app
│  └─ time-bucket-demo.jar
├─ config
│  └─ application.yml
├─ data
│  └─ uploads
├─ docs
├─ jre
│  └─ bin/java.exe
├─ logs
├─ runtime
│  ├─ data/uploads/defect-images
│  └─ logs
├─ scripts
│  ├─ init-database.ps1
│  ├─ install-service.ps1
│  ├─ start-service.ps1
│  ├─ stop-service.ps1
│  ├─ uninstall-service.ps1
│  ├─ service-status.ps1
│  └─ verify-installation.ps1
├─ service
│  └─ CameraDetectionService.exe
└─ sql
   └─ wms-new.sql
```

---

## 4. 功能说明

### 4.1 页面功能

| 页面 | URL | 功能 |
|---|---|---|
| 登录页 | `/login` | 用户登录，进入系统后默认跳转相机状态 |
| 相机状态 | `/camera-status` | 相机运行状态、上传状态、资源信息、检测计数、最近图片入口 |
| 图像记录 | `/stats/images` | 按相机聚合缺陷图片，支持筛选、分页、展开、图片预览和切换 |
| 产线统计 | `/line-analytics` | 动态质量看板、图表配置、模板管理、聚合任务、大屏展示 |
| 系统配置 | `/system/config` | 工厂/产线/工位/相机/用户/操作记录配置维护 |
| 个人中心 | `/profile` | 当前用户信息查看 |

### 4.2 核心业务能力

- 相机状态实时展示。
- 缺陷图片实时上传、入库、文件保存、页面展示。
- 检测统计数据上报、缺陷映射、30 分钟聚合、日聚合加速。
- 动态报表图表配置与看板模板管理。
- 工厂、产线、工位、相机层级配置导入导出。
- 操作日志审计。
- Windows 安装包部署和服务化运行。

---

## 5. 对外接口说明

### 5.1 接口一：相机状态心跳

```http
POST /api/v1/camera/status/heartbeat
Content-Type: application/json
Authorization: Bearer <device-token>
X-Device-Id: <cameraCode/deviceId>
```

请求示例：

```json
{
  "cameraName": "杭州1线-上料相机A",
  "productName": "产品A",
  "timestamp": "2026-06-20 10:00:00",
  "cameraStatus": 1,
  "recognizeCount": 1000,
  "correctCount": 980,
  "abnormalCount": 20,
  "cpuUsage": 35.5,
  "memoryUsage": 62.1,
  "temperature": 45.2,
  "errorMessage": ""
}
```

字段注意事项：

| 字段 | 是否必填 | 说明 |
|---|---|---|
| `cameraName` | 是 | 展示名称 |
| `productName` | 否 | 当前检测产品 |
| `timestamp` | 否 | 上报时间，建议 `yyyy-MM-dd HH:mm:ss` |
| `cameraStatus` | 否 | 0 停止、1 运行、2 故障、3 待机 |
| `recognizeCount` | 否 | 累计识别总数 |
| `correctCount` | 否 | 累计识别正确数 |
| `abnormalCount` | 否 | 累计识别异常数 |
| `cpuUsage` | 否 | 0~100 |
| `memoryUsage` | 否 | 0~100 |
| `temperature` | 否 | 摄氏度 |
| `errorMessage` | 否 | 故障状态建议填写 |

### 5.2 接口二：缺陷图片批量上传

```http
POST /api/v1/defect-images/upload
Content-Type: multipart/form-data
Authorization: Bearer <device-token>
X-Device-Id: <cameraCode/deviceId>
X-Image-Count: <image-count>
```

form-data：

| 字段 | 类型 | 说明 |
|---|---|---|
| `files` / `file` | File[] | 图片数组，单批最大 100 张 |
| `metadata` | String | JSON 字符串 |

metadata 示例：

```json
{
  "cameraId": "CAM-001",
  "cameraName": "杭州1线-上料相机A",
  "uploadTime": "2026-06-20 10:00:00.000",
  "images": [
    {
      "fileName": "img-001.png",
      "captureTime": "2026-06-20 09:59:59.120",
      "defects": [
        {
          "type": "划伤",
          "severity": "HIGH",
          "x": 10,
          "y": 20,
          "width": 100,
          "height": 60,
          "area": 6000
        }
      ]
    }
  ]
}
```

接入注意事项：

1. `metadata.images[].fileName` 必须与上传文件原始文件名完全一致。
2. 图片数量超过 100 会直接拒绝，建议设备端传 `X-Image-Count` 便于快速失败。
3. 支持格式：jpg、jpeg、png、bmp、gif、webp、svg。
4. 严重度建议固定为 `HIGH`、`MEDIUM`、`LOW`。
5. 上传成功后页面通过 `/api/v1/defect-images/camera-summaries` 与 `/history` 展示。

### 5.3 接口三：检测统计批量上报

```http
POST /api/v1/camera/inspection/batch-upload
Content-Type: application/json
Authorization: Bearer <device-token>
X-Device-Id: <cameraCode/deviceId>
X-Batch-Id: <batchId>
```

请求示例：

```json
{
  "schemaVersion": "2.0",
  "batchId": "CAM-001-20260620100000-001",
  "requestId": "REQ-20260620100000-001",
  "cameraCode": "CAM-001",
  "productName": "产品A",
  "reportTime": "2026-06-20 10:00:00",
  "timeRange": {
    "startTime": "2026-06-20 09:59:00",
    "endTime": "2026-06-20 10:00:00"
  },
  "counterMode": "INCREMENTAL",
  "summary": {
    "totalCount": 100,
    "okCount": 95,
    "ngCount": 5
  },
  "defects": [
    { "sourceCode": "P0_SCRATCH", "count": 3 },
    { "sourceCode": "P1_DIRTY", "count": 2 }
  ]
}
```

接入注意事项：

1. `schemaVersion` 固定为 `2.0`。
2. P0 只支持 `counterMode=INCREMENTAL`。
3. `batchId` 对同一相机必须唯一，重复上报按幂等处理。
4. `totalCount` 必须等于 `okCount + ngCount`。
5. `ngCount` 必须等于 `defects[].count` 之和。
6. `productName` 必须提前配置并完全匹配。
7. `sourceCode` 必须提前配置映射，未映射返回 422。
8. 上报成功后会同步写入 30 分钟聚合表，产线统计页面可立即查询。

---

## 6. 调试说明

### 6.1 本地服务检查

```powershell
Get-Service -Name CameraDetectionSystem
Get-NetTCPConnection -LocalPort 8091 -State Listen
Invoke-WebRequest -UseBasicParsing http://localhost:8091/camera-status
```

### 6.2 查看 Java 进程

```powershell
Get-CimInstance Win32_Process |
  Where-Object { $_.Name -like 'java*' -and $_.CommandLine -like '*time-bucket-demo.jar*' } |
  Select-Object ProcessId,CommandLine
```

### 6.3 查看日志

默认日志：

```text
D:\45344\0000-jar\CameraDetectionSystem\runtime\logs\camera-detection-system.log
```

常用命令：

```powershell
Get-Content -LiteralPath "D:\45344\0000-jar\CameraDetectionSystem\runtime\logs\camera-detection-system.log" -Tail 200
```

### 6.4 验证接口

```powershell
Invoke-WebRequest -UseBasicParsing "http://localhost:8091/api/cascade/all"
Invoke-WebRequest -UseBasicParsing "http://localhost:8091/api/v1/defect-images/camera-summaries?latestLimit=1"
```

### 6.5 图片一致性检查

```powershell
cmd /c node D:\45344\0004-软件源码\scripts\defect-image-consistency-check.mjs
```

重点看：

- `missingFileCount`：DB 有记录但文件缺失，必须为 0。
- `orphanFileCount`：文件存在但 DB 无记录，通常是历史失败残留，可隔离清理。

---

## 7. 部署说明

### 7.1 目标机器要求

| 项 | 要求 |
|---|---|
| 操作系统 | Windows |
| 运行时 | 安装包自带 JRE，无需额外安装 Java |
| 数据库 | MySQL 8.0 |
| 端口 | TCP 8091 对页面和相机接口开放；TCP 3306 默认仅本机 MySQL 使用 |
| 磁盘 | 根据图片量预留上传目录空间 |

### 7.2 安装步骤

1. 安装 MySQL 8.0。
2. 创建数据库 `wms-new`，或使用初始化脚本自动创建。
3. 运行安装包：`D:\45344\0004-软件源码\dist-release\installer\CameraDetectionSystem-Setup-1.0.0.exe`。
4. 选择安装目录和工作路径。
5. 检查 `config/application.yml` 中数据库连接、上传目录、日志目录。
6. 执行数据库初始化：

```powershell
powershell -ExecutionPolicy Bypass -File "<安装目录>\scripts\init-database.ps1"
```

7. 安装并启动服务：

```powershell
powershell -ExecutionPolicy Bypass -File "<安装目录>\scripts\install-service.ps1"
powershell -ExecutionPolicy Bypass -File "<安装目录>\scripts\start-service.ps1"
```

8. 浏览器访问：

```text
http://localhost:8091/camera-status
```

### 7.3 服务操作

```powershell
# 查看状态
powershell -ExecutionPolicy Bypass -File "<安装目录>\scripts\service-status.ps1"

# 启动
powershell -ExecutionPolicy Bypass -File "<安装目录>\scripts\start-service.ps1"

# 停止
powershell -ExecutionPolicy Bypass -File "<安装目录>\scripts\stop-service.ps1"

# 卸载
powershell -ExecutionPolicy Bypass -File "<安装目录>\scripts\uninstall-service.ps1"
```

### 7.4 安装自检

```powershell
powershell -ExecutionPolicy Bypass -File "<安装目录>\scripts\verify-installation.ps1"
```

自检项包括：内置 JRE、后端 JAR、外部配置、数据库 SQL、Windows 服务、TCP 8091、页面访问、图片超量快速拒绝。

---

## 8. 验收报告与测试脚本

### 8.1 最新验收报告

| 报告 | 路径 |
|---|---|
| 安装包级发布验证汇总 | `D:\45344\0004-软件源码\test-reports\安装包级发布验证汇总-latest.md` |
| 完整项目测试报告 | `D:\45344\0004-软件源码\test-reports\相机检测平台-测试报告-latest.md` |
| 新功能测试报告 | `D:\45344\0004-软件源码\test-reports\新功能测试报告-latest.md` |
| 重点跳转验收 | `D:\45344\0004-软件源码\test-reports\重点验收-相机图像记录跳转-latest.md` |

### 8.2 自动化脚本

```powershell
powershell -ExecutionPolicy Bypass -File D:\45344\0004-软件源码\scripts\install-package-acceptance.ps1
powershell -ExecutionPolicy Bypass -File D:\45344\0004-软件源码\scripts\go-live-acceptance.ps1
cmd /c node D:\45344\0004-软件源码\scripts\qa-full-project-auto-test.mjs
cmd /c node D:\45344\0004-软件源码\scripts\qa-new-features-auto-test.mjs
cmd /c node D:\45344\0004-软件源码\scripts\qa-focus-camera-image-navigation.mjs
cmd /c node D:\45344\0004-软件源码\scripts\perf-100-camera-heartbeat.mjs
cmd /c node D:\45344\0004-软件源码\scripts\perf-100-defect-image-upload.mjs
```

---

## 9. 现场交付检查清单

| 序号 | 检查项 | 验收标准 |
|---:|---|---|
| 1 | 安装包可运行 | 安装向导完成，无缺失 JRE 错误 |
| 2 | 工作路径可选 | 图片、日志落到指定工作路径 |
| 3 | MySQL 可连接 | 应用启动无数据库连接异常 |
| 4 | 初始化脚本可执行 | 空库或升级库执行成功 |
| 5 | Windows 服务可控 | 启动、停止、卸载均正常 |
| 6 | 页面可访问 | `http://localhost:8091/camera-status` HTTP 200 |
| 7 | 心跳接口可用 | 相机状态页实时更新 |
| 8 | 图片上传可用 | 图像记录页展示上传图片 |
| 9 | 检测统计可用 | 产线统计图表出现检测、OK、NG、缺陷数据 |
| 10 | 配置导入可用 | 工厂/产线/工位/相机导入失败有明确原因 |
| 11 | 日志可查看 | 运行日志、操作日志可定位问题 |
| 12 | 安全边界 | 8091 按需开放，3306 默认仅本机开放 |

---

## 10. 已知风险与处理建议

| 风险 | 等级 | 处理建议 |
|---|---|---|
| 尚需真实全新 Windows 电脑完整安装验证 | P1 | 下一轮使用干净环境执行安装包全流程 |
| 历史孤儿图片文件 7 个 | P2 | 不影响展示，可后续执行隔离清理 |
| 产品/缺陷/映射配置 UI 需进一步统一 | P1 | 后续并入系统配置中心，减少现场配置割裂 |
| 配置模板中文注释编码可读性 | P2 | 重写模板注释为 UTF-8，避免查看时乱码 |
