# 产线缺陷在线AI检测系统 — 对外接口冻结契约 v1.0

版本：v1.0  
日期：2026-06-16  
范围：相机端对外上报接口  
状态：冻结基线，用于后续渐进重构的回归依据

---

## 1. 冻结原则

后续代码结构按“冻结接口 → 补测试 → 抽组件 → 瘦 Controller → 归拢 SQL → 模块化目录”渐进优化。为了避免重构影响现场设备接入，本文件先冻结当前对外接口的 HTTP 路径、关键请求字段、响应结构和错误处理口径。

冻结后允许优化内部实现，但不得随意破坏以下内容：

1. URL 路径不变。
2. Header 名称不变。
3. 当前已约定的 JSON 字段不删除、不改名。
4. 业务校验失败仍返回明确错误码和错误说明。
5. 对外接口必须有自动化契约测试覆盖。

---

## 2. 接口清单

| 序号 | 接口 | 方法 | 用途 | 当前冻结状态 |
|---:|---|---|---|---|
| 1 | `/api/v1/camera/status/heartbeat` | POST | 相机心跳、运行状态和资源指标上报 | 已冻结 |
| 2 | `/api/v1/camera/inspection/batch-upload` | POST | 相机检测统计增量上报 | 已冻结 |
| 3 | `/api/v1/defect-images/upload` | POST multipart/form-data | 缺陷图片和 metadata 批量上传 | 已冻结 |

---

## 3. 通用 Header

| Header | 是否必填 | 说明 |
|---|---|---|
| `Authorization` | 是 | 设备 Token，格式：`Bearer <token>` |
| `X-Device-Id` | 是 | 设备/相机编号，应与系统配置中的相机编号一致 |
| `X-Batch-Id` | 检测上报建议必填 | 批次幂等编号，检测上报接口使用 |

---

## 4. 统一响应结构

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {}
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `code` | Integer | 业务状态码，成功为 `200`，参数/业务校验失败可能为 `422`，旧心跳校验失败当前仍可能为 `500` |
| `message` | String | 面向调用方的处理结果说明 |
| `data` | Object / Array / null | 接口数据体 |

> 注意：心跳接口当前部分参数校验失败仍是 HTTP 200 + `code=500`，本轮先冻结现状；后续如改为 HTTP 400/422，必须先同步协议版本并提供兼容期。

---

## 5. 心跳上报接口

### 5.1 请求

`POST /api/v1/camera/status/heartbeat`

```json
{
  "cameraName": "杭州工位A-正面相机",
  "productName": "杭州测试产品A",
  "timestamp": "2026-06-16 12:00:00",
  "cameraStatus": 1,
  "recognizeCount": 1000,
  "correctCount": 960,
  "abnormalCount": 40,
  "cpuUsage": 45.5,
  "memoryUsage": 60.0,
  "temperature": 38.2,
  "errorMessage": "",
  "resetFlag": false
}
```

### 5.2 字段说明

| 字段 | 类型 | 是否必填 | 说明 |
|---|---|---|---|
| `cameraName` | String | 是 | 相机展示名称 |
| `productName` | String | 否 | 当前检测产品名称 |
| `timestamp` | String | 否 | 上报时间，建议 `yyyy-MM-dd HH:mm:ss` |
| `cameraStatus` | Integer | 否 | `0=停止`，`1=运行`，`2=故障`，`3=待机` |
| `recognizeCount` | Integer | 否 | 识别总数，累计值 |
| `correctCount` | Integer | 否 | 识别正确数，累计值 |
| `abnormalCount` | Integer | 否 | 识别异常数，累计值 |
| `cpuUsage` | Double | 否 | CPU 使用率，范围建议 0～100 |
| `memoryUsage` | Double | 否 | 内存使用率，范围建议 0～100 |
| `temperature` | Double | 否 | 设备温度，单位 ℃ |
| `errorMessage` | String | 否 | 故障信息，`cameraStatus=2` 时建议填写 |
| `resetFlag` | Boolean | 否 | 计数器重置标识 |

### 5.3 响应 data

```json
{
  "serverTime": "2026-06-16 12:00:01",
  "nextInterval": 5000,
  "configVersion": "v1"
}
```

---

## 6. 检测统计批量上报接口

### 6.1 请求

`POST /api/v1/camera/inspection/batch-upload`

```json
{
  "schemaVersion": "1.0",
  "batchId": "BATCH-HZ-001",
  "requestId": "REQ-HZ-001",
  "cameraCode": "CAM-HZ-001",
  "productName": "杭州测试产品A",
  "reportTime": "2026-06-16 12:00:00",
  "counterMode": "INCREMENTAL",
  "timeRange": {
    "startTime": "2026-06-16 11:30:00",
    "endTime": "2026-06-16 12:00:00"
  },
  "summary": {
    "totalCount": 100,
    "okCount": 95,
    "ngCount": 5
  },
  "defects": [
    {
      "sourceCode": "P0_FOREIGN_BODY",
      "count": 5
    }
  ]
}
```

### 6.2 关键约束

| 约束 | 说明 |
|---|---|
| 计数模式 | P0 阶段只支持 `INCREMENTAL` 增量计数 |
| 缺陷编码 | `defects[].sourceCode` 必须在系统配置中按“产品 + 相机 + 来源编码”维护一对一映射 |
| 未映射处理 | 未映射编码坚持返回 `422`，不得静默入库为未知缺陷 |
| 批次幂等 | 建议使用 `X-Batch-Id` / `batchId` 做幂等处理 |
| 查询效率 | 落库时应保存标准缺陷类型 ID、编码、严重度、时间桶、产线、工位、相机、产品等冗余查询字段 |

### 6.3 成功响应 data

```json
{
  "accepted": true,
  "duplicate": false,
  "batchId": "BATCH-HZ-001",
  "inspectionBatchId": 10001,
  "cameraId": 1,
  "cameraCode": "CAM-HZ-001",
  "productId": 10,
  "productName": "杭州测试产品A",
  "totalCount": 100,
  "okCount": 95,
  "ngCount": 5,
  "mappedDefectCount": 5,
  "unmappedDefectCount": 0,
  "timeBucket30m": "2026-06-16 12:00:00",
  "mappedDefects": [],
  "issues": []
}
```

### 6.4 失败响应示例

```json
{
  "code": 422,
  "message": "缺陷编码未配置一对一映射",
  "data": {
    "accepted": false,
    "batchId": "BATCH-HZ-002",
    "issues": [
      {
        "sourceCode": "P0_FOREIGN_BODY",
        "issueType": "UNMAPPED_DEFECT_CODE",
        "message": "缺陷编码未配置一对一映射",
        "suggestion": "请在系统配置-映射配置中维护产品、相机、缺陷编码映射"
      }
    ]
  }
}
```

---

## 7. 缺陷图片上传接口

### 7.1 请求

`POST /api/v1/defect-images/upload`

Content-Type：`multipart/form-data`

| Form 字段 | 类型 | 是否必填 | 说明 |
|---|---|---|---|
| `files` | File[] | 是 | 图片文件数组，当前最大 100 张 |
| `file` | File[] | 否 | 兼容字段，和 `files` 合并处理 |
| `metadata` | String | 是 | JSON 字符串，描述批次、图片和缺陷框 |

### 7.2 metadata 示例

```json
{
  "cameraId": "CAM-HZ-001",
  "cameraName": "杭州工位A-正面相机",
  "uploadTime": "2026-06-16 12:00:00",
  "images": [
    {
      "fileName": "hz-001.jpg",
      "captureTime": "2026-06-16 11:59:58",
      "defects": [
        {
          "type": "异物",
          "severity": "HIGH",
          "x": 10,
          "y": 20,
          "width": 30,
          "height": 40,
          "area": 1200
        }
      ]
    }
  ]
}
```

### 7.3 关键约束

| 约束 | 说明 |
|---|---|
| 最大文件数 | 单批次最大 100 张；超过 100 张直接返回 `422` |
| 文件名匹配 | `metadata.images[].fileName` 必须与上传文件名一致 |
| 图片格式 | 建议 JPEG / PNG / BMP / WebP 等真实图片文件 |
| 严重度 | `HIGH` / `MEDIUM` / `LOW` |
| 缺陷框 | 一张图片可包含多个缺陷框 |
| 存储目录 | 使用外部可配置上传目录，不应写入源码目录 |

---

## 8. 当前自动化契约测试

已补充 Controller 层契约测试，不依赖真实数据库：

| 测试文件 | 覆盖接口 |
|---|---|
| `fact-product/src/test/java/com/wms/controller/CameraStatusHeartbeatControllerTest.java` | 心跳上报成功、缺少 Token、缺少设备编号、非法状态 |
| `fact-product/src/test/java/com/wms/controller/BatchUploadControllerTest.java` | 检测上报成功、422 映射校验失败 |
| `fact-product/src/test/java/com/wms/controller/DefectImageControllerTest.java` | 多图上传成功、超过 100 张直接拒绝 |

后续每次瘦 Controller、迁 SQL、改 DTO 或改返回结构前，必须先运行这些契约测试。
