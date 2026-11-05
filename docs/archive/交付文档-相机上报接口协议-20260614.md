# 产线缺陷在线AI检测系统 — 相机上报接口协议与接入注意事项

版本：v1.0  
日期：2026-06-14  
适用对象：现场相机、AI 检测设备、设备网关、接口联调人员

---

## 1. 对外接口总览

当前对外供相机/设备上报使用的接口只保留以下 3 个：

| 序号 | 接口 | 方法 | 用途 |
---:|---|---|---|
| 1 | `/api/v1/camera/status/heartbeat` | POST | 相机实时状态、资源指标、运行计数心跳 |
| 2 | `/api/v1/camera/inspection/batch-upload` | POST | 相机检测统计增量批次上报 |
| 3 | `/api/v1/defect-images/upload` | POST | 缺陷图片和缺陷框 metadata 批量上传 |

访问地址格式：

```text
http://<服务器IP>:8091
```

示例：

```text
http://192.168.1.100:8091/api/v1/camera/status/heartbeat
```

---

## 2. 通用约定

### 2.1 编码和时间

| 项 | 约定 |
---|---|
| 字符集 | UTF-8 |
| JSON Content-Type | `application/json; charset=utf-8` |
| 图片上传 Content-Type | `multipart/form-data` |
| 时区 | Asia/Shanghai / GMT+8 |
| 普通时间格式 | `yyyy-MM-dd HH:mm:ss` |
| 图片上传时间格式 | `yyyy-MM-dd HH:mm:ss` 或 `yyyy-MM-dd HH:mm:ss.SSS` |

### 2.2 通用 Header

| Header | 必填 | 说明 |
---|---|---|
| `Authorization` | 是 | `Bearer <device-token>` |
| `X-Device-Id` | 是 | 相机设备编号，建议和系统配置中的 `cameraCode/deviceId` 一致 |
| `X-Batch-Id` | 否 | 仅检测统计接口可用；当请求体缺少 `batchId` 时可作为批次号 |

示例：

```http
Authorization: Bearer qa-token
X-Device-Id: CAM-001
```

### 2.3 相机身份约定

系统配置中的相机唯一识别建议：

| 字段 | 用途 |
---|---|
| `cameraCode` | 设备唯一编号，设备上报主键 |
| `deviceId` | 设备 ID，可与 `cameraCode` 一致 |
| `cameraName` | 页面展示名称 |

接入注意：

1. `X-Device-Id` 必须能匹配系统配置中的相机。
2. `/api/v1/camera/inspection/batch-upload` 中的 `cameraCode` 必须能匹配相机配置。
3. `/api/v1/defect-images/upload` 的 `metadata.cameraId` 建议与 `X-Device-Id` 完全一致。
4. 相机所属工厂、产线、工位由系统配置维护，不允许通过上报接口修改。

### 2.4 统一响应结构

大部分接口返回：

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {}
}
```

| 字段 | 类型 | 说明 |
---|---|---|
| `code` | Integer | `200` 成功；`422` 表示业务校验不通过；`500` 表示失败 |
| `message` | String | 人可读提示 |
| `data` | Object | 业务数据 |

说明：

- 检测统计接口和图片上传接口的严重参数错误使用 HTTP 422。
- 心跳接口当前实现对校验失败返回 HTTP 200 + `code=500`。
- 设备侧应同时判断 HTTP 状态码和响应体 `code`。

---

## 3. 接口一：相机状态心跳上报

### 3.1 基本信息

```http
POST /api/v1/camera/status/heartbeat
Content-Type: application/json
Authorization: Bearer <device-token>
X-Device-Id: <cameraCode>
```

用途：

- 更新相机当前运行状态。
- 更新当前产品、识别计数、CPU、内存、温度、故障信息。
- 触发故障、资源阈值告警。
- 页面 `相机状态` 实时展示数据来源。

### 3.2 请求字段

| 字段 | 类型 | 必填 | 说明 |
---|---|---:|---|
| `cameraName` | String | 是 | 相机展示名称 |
| `productName` | String | 否 | 当前检测产品名称 |
| `timestamp` | String | 否 | 设备上报时间，格式 `yyyy-MM-dd HH:mm:ss`；为空时服务端使用当前时间 |
| `cameraStatus` | Integer | 否 | `0=停止`，`1=运行`，`2=故障`，`3=待机` |
| `recognizeCount` | Integer | 否 | 识别总数，累计值 |
| `correctCount` | Integer | 否 | 识别正确数，累计值 |
| `abnormalCount` | Integer | 否 | 识别异常数，累计值 |
| `cpuUsage` | Double | 否 | CPU 使用率，范围建议 `0~100` |
| `memoryUsage` | Double | 否 | 内存使用率，范围建议 `0~100` |
| `temperature` | Double | 否 | 设备温度，单位 ℃ |
| `errorMessage` | String | 否 | 故障信息，`cameraStatus=2` 时建议填写 |
| `resetFlag` | Boolean | 否 | 设备计数器重置时传 `true` |

### 3.3 状态枚举

| cameraStatus | 存储状态 | 中文 |
---:|---|---|
| 0 | `STOPPED` | 停止 |
| 1 | `RUNNING` | 运行 |
| 2 | `FAULT` | 故障 |
| 3 | `STANDBY` | 待机 |

页面离线状态：

- 心跳 ≤ 30 秒：在线
- 30 ~ 120 秒：延迟
- > 120 秒：离线

### 3.4 告警规则

| 条件 | 告警类型 | 等级 |
---|---|---|
| `cameraStatus=2` | `CAMERA_FAULT` | `CRITICAL` |
| `cpuUsage > 90` | `CPU_HIGH` | `WARNING` |
| `memoryUsage > 90` | `MEMORY_HIGH` | `WARNING` |
| `temperature > 70` | `TEMPERATURE_HIGH` | `WARNING` |
| `recognizeCount` 与 `correctCount + abnormalCount` 差值大于 1 | `COUNT_MISMATCH` | `WARNING` |

### 3.5 请求示例

```json
{
  "cameraName": "杭州1线-上料相机A",
  "productName": "产品A",
  "timestamp": "2026-06-14 09:30:00",
  "cameraStatus": 1,
  "recognizeCount": 1000,
  "correctCount": 980,
  "abnormalCount": 20,
  "cpuUsage": 35.5,
  "memoryUsage": 46.1,
  "temperature": 42.3,
  "errorMessage": "",
  "resetFlag": false
}
```

### 3.6 成功响应

```json
{
  "code": 200,
  "message": "状态已更新",
  "data": {
    "serverTime": "2026-06-14 09:30:01",
    "nextInterval": 10000,
    "configVersion": "v1.0.0"
  }
}
```

### 3.7 常见失败

| 场景 | 当前响应特征 | 处理建议 |
---|---|---|
| 未传 Authorization | HTTP 200 + `code=500`，提示缺少认证令牌 | 增加 `Authorization: Bearer <token>` |
| 未传 X-Device-Id | HTTP 200 + `code=500`，提示缺少设备ID | 增加设备编号 Header |
| `cameraName` 为空 | HTTP 200 + `code=500` | 填写相机展示名称 |
| `cameraStatus` 不在 0~3 | HTTP 200 + `code=500` | 使用标准枚举 |
| 设备未配置 | HTTP 200 + `code=500` | 先在系统配置中维护相机 |

---

## 4. 接口二：相机检测统计批量上报

### 4.1 基本信息

```http
POST /api/v1/camera/inspection/batch-upload
Content-Type: application/json
Authorization: Bearer <device-token>
X-Device-Id: <cameraCode>
X-Batch-Id: <batchId，可选>
```

用途：

- 接收相机按时间段上报的检测统计增量。
- 将 `sourceCode` 映射成系统标准缺陷类型。
- 写入检测事实表和聚合表。
- 支撑 `产线统计` 页面展示 KPI、趋势、排行、动态报表。

### 4.2 关键规则

| 规则 | 当前实现 |
---|---|
| 协议版本 | `schemaVersion` 必须为 `2.0` |
| 计数模式 | 只支持 `counterMode=INCREMENTAL` |
| 幂等键 | 同一相机下 `batchId` 重复时按幂等处理，返回 `duplicate=true` |
| 产品匹配 | `productName` 必须等于系统配置中的产品名称 |
| 相机匹配 | `cameraCode` 或 `X-Device-Id` 必须匹配相机配置 |
| Token | 若相机配置了有效 Token，则必须匹配；支持明文 Token 或 SHA-256 值 |
| 缺陷映射 | 每个 `sourceCode` 必须配置一对一映射 |
| 汇总校验 | `totalCount = okCount + ngCount` |
| 缺陷校验 | `ngCount = sum(defects[].count)` |

### 4.3 请求字段

| 字段 | 类型 | 必填 | 说明 |
---|---|---:|---|
| `schemaVersion` | String | 是 | 固定 `2.0` |
| `batchId` | String | 是* | 批次号；可由请求头 `X-Batch-Id` 兜底 |
| `requestId` | String | 否 | 请求流水号 |
| `cameraCode` | String | 是 | 相机编号 |
| `productName` | String | 是 | 当前检测产品名称，必须匹配系统配置 |
| `reportTime` | String | 是 | 上报时间 |
| `timeRange.startTime` | String | 是 | 本批统计开始时间 |
| `timeRange.endTime` | String | 是 | 本批统计结束时间 |
| `counterMode` | String | 是 | 固定 `INCREMENTAL` |
| `summary.totalCount` | Long | 是 | 增量检测总数 |
| `summary.okCount` | Long | 是 | 增量 OK 数 |
| `summary.ngCount` | Long | 是 | 增量 NG 数 |
| `defects[].sourceCode` | String | 是 | 设备侧缺陷编码 |
| `defects[].count` | Long | 是 | 该缺陷编码增量数量 |

### 4.4 请求示例

```json
{
  "schemaVersion": "2.0",
  "batchId": "CAM-001-20260614093000",
  "requestId": "REQ-20260614093000-001",
  "cameraCode": "CAM-001",
  "productName": "产品A",
  "reportTime": "2026-06-14 09:30:00",
  "counterMode": "INCREMENTAL",
  "timeRange": {
    "startTime": "2026-06-14 09:20:00",
    "endTime": "2026-06-14 09:30:00"
  },
  "summary": {
    "totalCount": 100,
    "okCount": 95,
    "ngCount": 5
  },
  "defects": [
    {
      "sourceCode": "p0",
      "count": 3
    },
    {
      "sourceCode": "p1",
      "count": 2
    }
  ]
}
```

### 4.5 成功响应

```json
{
  "code": 200,
  "message": "检测批次上报成功",
  "data": {
    "accepted": true,
    "duplicate": false,
    "batchId": "CAM-001-20260614093000",
    "inspectionBatchId": 123,
    "cameraId": 1,
    "cameraCode": "CAM-001",
    "productId": 10,
    "productName": "产品A",
    "totalCount": 100,
    "okCount": 95,
    "ngCount": 5,
    "mappedDefectCount": 5,
    "unmappedDefectCount": 0,
    "timeBucket30m": "2026-06-14 09:30:00",
    "mappedDefects": [
      {
        "sourceCode": "p0",
        "mappingId": 1,
        "defectTypeId": 100,
        "defectCode": "P0",
        "defectName": "P0胶滴",
        "severity": "HIGH",
        "count": 3
      }
    ],
    "issues": []
  }
}
```

### 4.6 幂等响应

同一相机重复上报同一 `batchId`：

```json
{
  "code": 200,
  "message": "批次已接收过，本次按幂等请求处理",
  "data": {
    "accepted": true,
    "duplicate": true,
    "batchId": "CAM-001-20260614093000",
    "inspectionBatchId": 123
  }
}
```

### 4.7 失败响应

HTTP 422 示例：

```json
{
  "code": 422,
  "message": "上报编码未配置映射：p9",
  "data": {
    "accepted": false,
    "batchId": "CAM-001-20260614093000",
    "issues": [
      {
        "sourceCode": "p9",
        "issueType": "UNMAPPED",
        "message": "上报编码未配置映射：p9",
        "suggestion": "请先维护相机缺陷映射，确保每个 sourceCode 均能一对一匹配。"
      }
    ]
  }
}
```

常见错误：

| 错误类型 | 触发条件 | 处理建议 |
---|---|---|
| `TOKEN_REQUIRED` | 未传 Bearer Token | 增加 Authorization Header |
| `DEVICE_ID_REQUIRED` | 未传 `X-Device-Id` | 增加设备编号 Header |
| `INVALID_SCHEMA_VERSION` | `schemaVersion` 不是 `2.0` | 升级设备协议 |
| `UNSUPPORTED_COUNTER_MODE` | 非 `INCREMENTAL` | 固定使用增量计数 |
| `CAMERA_NOT_FOUND` | 相机未配置 | 在系统配置中维护相机 |
| `PRODUCT_NOT_FOUND` | 产品名称无法匹配 | 在产品管理中维护产品，或修正 productName |
| `TOKEN_INVALID` | Token 不匹配或过期 | 检查相机 Token 配置 |
| `COUNT_MISMATCH` | 汇总计数不一致 | 校验 total/ok/ng/defects |
| `UNMAPPED` | 缺陷编码未配置映射 | 在相机缺陷映射中配置 |
| `INVALID_DEFECT_COUNT` | 缺陷数量为负数 | 修正设备端计数 |

### 4.8 上报后落库

| 表 | 写入内容 |
---|---|
| `fact_inspection_batch` | 批次级统计 |
| `fact_inspection_defect_count` | 每个映射缺陷的数量 |
| `agg_quality_30m` | 30 分钟质量聚合 |
| `agg_defect_type_30m` | 30 分钟缺陷聚合 |
| `camera_status` | 同步更新相机当前产品、OK、NG、总数 |
| `fact_inspection_mapping_exception` | 未映射、产品缺失等异常 |

---

## 5. 接口三：缺陷图片批量上传

### 5.1 基本信息

```http
POST /api/v1/defect-images/upload
Content-Type: multipart/form-data
Authorization: Bearer <device-token>
X-Device-Id: <cameraCode>
```

用途：

- 上传缺陷图片文件。
- 上传每张图片的拍摄时间、缺陷类型、严重度、坐标和面积。
- 支撑图像记录页和相机状态页的最近缺陷图展示。

### 5.2 Form-data 字段

| 字段 | 类型 | 必填 | 说明 |
---|---|---:|---|
| `files` | File[] | 是* | 图片数组，推荐字段名 |
| `file` | File[] | 是* | 兼容字段名；`files` 和 `file` 至少传一种 |
| `metadata` | String | 是 | JSON 字符串，描述图片和缺陷框 |

当前限制：

| 项 | 限制 |
---|---|
| 单批图片数量 | 最大 100 张 |
| 单文件大小 | 配置默认 10MB |
| 请求大小 | 配置默认 1024MB |
| 支持扩展名 | `jpg/jpeg/png/bmp/gif/webp/svg` |
| 强校验内容 | `jpg/jpeg/png/bmp/gif` 会通过 ImageIO 解析内容 |
| 占位图限制 | 宽或高 ≤ 2px 判定为异常 |

### 5.3 metadata 字段

```json
{
  "cameraId": "CAM-001",
  "cameraName": "杭州1线-上料相机A",
  "uploadTime": "2026-06-14 09:30:00",
  "images": [
    {
      "fileName": "CAM-001-20260614093000-001.png",
      "captureTime": "2026-06-14 09:29:58",
      "defects": [
        {
          "type": "胶滴",
          "severity": "HIGH",
          "x": 10,
          "y": 12,
          "width": 30,
          "height": 24,
          "area": 720
        }
      ]
    }
  ]
}
```

字段说明：

| 字段 | 类型 | 必填 | 说明 |
---|---|---:|---|
| `cameraId` | String | 是 | 相机编号，建议与 `X-Device-Id` 一致 |
| `cameraName` | String | 否 | 相机展示名称；为空时使用 `cameraId` |
| `uploadTime` | String | 否 | 批次上传时间；为空时服务端使用当前时间 |
| `images` | Array | 是 | 图片 metadata 数组，不能为空 |
| `images[].fileName` | String | 是 | 必须与实际上传文件原始文件名完全一致 |
| `images[].captureTime` | String | 否 | 图片拍摄时间；为空时使用上传时间 |
| `images[].defects` | Array | 否 | 缺陷框数组；可为空 |
| `defects[].type` | String | 是 | 图片上的缺陷类型文本 |
| `defects[].severity` | String | 是 | `HIGH/MEDIUM/LOW` |
| `defects[].x` | Double | 否 | 缺陷框左上角 X |
| `defects[].y` | Double | 否 | 缺陷框左上角 Y |
| `defects[].width` | Double | 否 | 缺陷框宽度 |
| `defects[].height` | Double | 否 | 缺陷框高度 |
| `defects[].area` | Double | 否 | 面积 |

### 5.4 文件名匹配规则

服务端按以下规则匹配文件：

```text
metadata.images[].fileName == multipart 文件原始文件名
```

要求：

1. 大小写建议保持完全一致。
2. 必须包含扩展名。
3. 不要只传相对路径或绝对路径。
4. 一个 metadata 图片项应能匹配一个上传文件。

### 5.5 成功响应

```json
{
  "code": 200,
  "message": "缺陷图片上传成功",
  "data": {
    "success": true,
    "batchNo": "CAM-001-20260614093000000-abcd1234",
    "batchId": 10,
    "cameraId": "CAM-001",
    "cameraName": "杭州1线-上料相机A",
    "imageCount": 2,
    "successCount": 2,
    "failCount": 0,
    "defectCount": 3,
    "highCount": 1,
    "mediumCount": 1,
    "lowCount": 1,
    "results": [
      {
        "fileName": "CAM-001-20260614093000-001.png",
        "success": true,
        "imageId": 101,
        "imageUrl": "/api/v1/defect-images/file/20260614/CAM-001/093000000_abcd1234.png",
        "errorCode": null,
        "message": "上传成功",
        "failureReason": null,
        "handlingAdvice": null
      }
    ]
  }
}
```

### 5.6 部分失败响应

图片级处理失败时，接口仍可能返回 HTTP 200 + `code=200`，但 `data.success=false`：

```json
{
  "code": 200,
  "message": "缺陷图片部分上传失败",
  "data": {
    "success": false,
    "successCount": 1,
    "failCount": 1,
    "results": [
      {
        "fileName": "bad.png",
        "success": false,
        "errorCode": "IMAGE_CONTENT_INVALID",
        "message": "图片内容解析失败，请确认文件不是损坏图片且扩展名与内容一致: bad.png",
        "failureReason": "图片内容解析失败，可能是空文件、损坏文件或扩展名与内容不一致",
        "handlingAdvice": "请从设备端重新导出原始图片后上传；如仍失败，请检查传输过程是否截断或被压缩工具破坏。"
      }
    ]
  }
}
```

设备端接入时必须判断：

```text
HTTP 状态码 + code + data.success + results[].success
```

### 5.7 整体失败响应

HTTP 422 示例：

```json
{
  "code": 422,
  "message": "单批次最多支持 100 张图片，当前上传 101 张；请拆分为多个批次后重试",
  "data": null
}
```

### 5.8 图片级错误码

| errorCode | 说明 | 处理建议 |
---|---|---|
| `FILE_NAME_MISMATCH` | metadata 文件名与实际上传文件不一致 | 检查 `metadata.images[].fileName` 与 multipart 原始文件名 |
| `IMAGE_CONTENT_INVALID` | 图片内容解析失败、损坏、空文件、扩展名与内容不一致 | 重新导出原图，检查传输过程 |
| `UNSUPPORTED_IMAGE_FORMAT` | 图片格式不支持或扩展名缺失 | 使用支持的图片格式 |
| `IMAGE_SIZE_INVALID` | 图片尺寸过小，疑似占位图 | 重新采集有效图片 |
| `UPLOAD_FAILED` | 其他处理失败 | 检查 Header、metadata、文件完整性、上传目录权限 |

### 5.9 cURL 示例

```bash
curl -X POST "http://localhost:8091/api/v1/defect-images/upload" \
  -H "Authorization: Bearer qa-token" \
  -H "X-Device-Id: CAM-001" \
  -F "files=@CAM-001-20260614093000-001.png" \
  -F "files=@CAM-001-20260614093000-002.png" \
  -F 'metadata={
    "cameraId":"CAM-001",
    "cameraName":"杭州1线-上料相机A",
    "uploadTime":"2026-06-14 09:30:00",
    "images":[
      {
        "fileName":"CAM-001-20260614093000-001.png",
        "captureTime":"2026-06-14 09:29:58",
        "defects":[{"type":"胶滴","severity":"HIGH","x":10,"y":12,"width":30,"height":24,"area":720}]
      },
      {
        "fileName":"CAM-001-20260614093000-002.png",
        "captureTime":"2026-06-14 09:29:59",
        "defects":[{"type":"异物","severity":"MEDIUM","x":20,"y":22,"width":16,"height":12,"area":192}]
      }
    ]
  }'
```

---

## 6. 接入前配置清单

设备正式接入前，必须完成以下配置：

| 配置项 | 页面位置 | 要求 |
---|---|---|
| 工厂 | 系统配置 | 至少一个工厂 |
| 产线 | 系统配置 | 相机关联产线 |
| 工位 | 系统配置 | 相机关联工位 |
| 相机 | 系统配置 | `cameraCode/deviceId` 与设备上报一致 |
| 产品 | 系统配置 > 产品管理 | `productName` 与设备上报完全一致 |
| 缺陷类型 | 系统配置 > 缺陷类型 | 每种标准缺陷建一条 |
| 相机缺陷映射 | 系统配置 > 相机缺陷映射 | 每个设备 `sourceCode` 必须 1:1 映射 |
| Token | 数据库/配置 | 设备上报需携带 Bearer Token |

---

## 7. 接入测试建议

### 7.1 推荐顺序

1. 先调用心跳接口，确认相机状态页能看到相机在线。
2. 再调用缺陷图片上传接口，确认图像记录页能看到图片。
3. 最后调用检测统计接口，确认产线统计页能看到 KPI 和图表数据。

### 7.2 最小联调用例

| 编号 | 用例 | 预期 |
---|---|---|
| T01 | 正常心跳 `cameraStatus=1` | 相机状态变为运行 |
| T02 | 故障心跳 `cameraStatus=2` | 相机状态变为故障，并产生告警 |
| T03 | 两张图片 + 正确 metadata | 图像记录展示两张图片 |
| T04 | 文件名不匹配 | 返回 `FILE_NAME_MISMATCH` |
| T05 | 正常检测批次 | 产线统计 KPI 增加 |
| T06 | 未映射 `sourceCode` | HTTP 422，并生成映射异常 |
| T07 | 重复 `batchId` | 返回 `duplicate=true` |
| T08 | 图片超过 100 张 | HTTP 422 |

---

## 8. Postman 调试注意事项

1. 心跳和检测统计接口选择 `Body -> raw -> JSON`。
2. 图片接口选择 `Body -> form-data`。
3. 图片字段名建议使用 `files`，同名字段可添加多行。
4. `metadata` 字段类型选择 `Text`，值必须是合法 JSON 字符串。
5. Header 中必须添加：
   - `Authorization: Bearer <token>`
   - `X-Device-Id: <cameraCode>`
6. 图片上传后页面不展示时，优先检查：
   - `metadata.cameraId` 是否和相机编号一致。
   - `metadata.images[].fileName` 是否和文件名一致。
   - 图片是否真实可解析。
   - 图像记录页是否被工厂/产线/工位筛选过滤。

---

## 9. 设备端重试策略建议

| 场景 | 是否重试 | 建议 |
---|---|---|
| 网络超时 | 是 | 间隔 3~10 秒重试 |
| HTTP 500 | 是 | 限次重试，并记录日志 |
| HTTP 422 | 否 | 数据或配置错误，修正后重新上报 |
| `duplicate=true` | 否 | 服务端已接收过 |
| 图片部分失败 | 视情况 | 只补传失败图片或重新拆批 |

检测统计接口的 `batchId` 必须稳定，避免网络重试造成重复计数。服务端会按相机 + `batchId` 做幂等处理。
