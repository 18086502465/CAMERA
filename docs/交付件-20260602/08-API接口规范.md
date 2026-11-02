# API 接口规范

## 1. 基础约定

| 项 | 说明 |
|---|---|
| 服务地址 | `http://<host>:8091` |
| 前端 Axios baseURL | `/api` |
| 设备上报推荐前缀 | `/api/v1` |
| 数据格式 | JSON，图片上传为 multipart/form-data |
| 时间格式 | `yyyy-MM-dd HH:mm:ss`，部分兼容 ISO `yyyy-MM-ddTHH:mm:ss` |
| 鉴权 | `Authorization: Bearer <token>` |
| 设备标识 | `X-Device-Id: <cameraCode/deviceId>` |

## 2. 统一响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {}
}
```

## 3. 相机心跳接口

### 3.1 上报心跳

```http
POST /api/v1/camera/status/heartbeat
Content-Type: application/json
Authorization: Bearer <device-token>
X-Device-Id: cv6-001
```

请求体：

```json
{
  "cameraName": "cv6产品正面污点检测相机001",
  "productName": "ZK63",
  "timestamp": "2026-06-02 09:30:00",
  "cameraStatus": 1,
  "recognizeCount": 10000,
  "correctCount": 9850,
  "abnormalCount": 150,
  "cpuUsage": 45.5,
  "memoryUsage": 60.2,
  "temperature": 42.6,
  "errorMessage": null,
  "resetFlag": false
}
```

字段说明：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| cameraName | String | 是 | 相机展示名称。 |
| productName | String | 否 | 当前生产产品。 |
| timestamp | String | 否 | 上报时间。 |
| cameraStatus | Integer | 否 | `0=停止`、`1=运行`、`2=故障`、`3=待机`。 |
| recognizeCount | Integer | 否 | 识别总数累计值。 |
| correctCount | Integer | 否 | 识别正确累计值。 |
| abnormalCount | Integer | 否 | 识别异常累计值。 |
| cpuUsage | Double | 否 | CPU 使用率。 |
| memoryUsage | Double | 否 | 内存使用率。 |
| temperature | Double | 否 | 设备温度。 |
| errorMessage | String | 否 | 故障信息。 |
| resetFlag | Boolean | 否 | 设备计数器是否重置。 |

业务规则：

- `cameraStatus=2` 生成故障风险。
- CPU>90%、内存>90%、温度>70℃ 生成 WARNING 风险。
- 设备上报不修改相机所属产线/工位。

## 4. 检测批量上报接口

```http
POST /api/v1/camera/inspection/batch-upload
Content-Type: application/json
Authorization: Bearer <device-token>
X-Device-Id: cv6-001
X-Batch-Id: optional-batch-id
```

请求体：

```json
{
  "deviceId": "cv6-001",
  "batchTime": "2026-06-02 09:30:00",
  "recordCount": 1,
  "dataList": [
    {
      "startTime": "2026-06-02 09:30:00",
      "endTime": "2026-06-02 09:30:01",
      "productName": "ZK63",
      "lineId": 10,
      "workstationId": 34,
      "brandId": 1,
      "shiftId": 1,
      "captureSpeed": "HIGH",
      "result": "NG",
      "cameraName": "cv6产品正面污点检测相机001",
      "totalCount": 1000,
      "okCount": 980,
      "ngCount": 20,
      "incrementalTotal": 10,
      "incrementalOk": 9,
      "incrementalNg": 1,
      "defectArea": "FRONT",
      "defectType": "DIRT",
      "defectSize": "1_3",
      "imagePath": "/api/v1/defect-images/file/20260602/cv6-001/demo.jpg",
      "confidence": 96.5
    }
  ]
}
```

规则：

- `dataList` 不能为空。
- 当前实现限制单次不超过 100 条。
- 记录写入 `product_detection_history`，并更新统计事实表。
- `productName` 可通过品牌表映射 `brandId`，无法匹配时保留原始名称并返回告警明细是后续目标。

## 5. 缺陷图片上传接口

```http
POST /api/v1/defect-images/upload
Content-Type: multipart/form-data
Authorization: Bearer <device-token>
X-Device-Id: cv6-001
```

表单字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| metadata | String(JSON) | 是 | 图片和缺陷 metadata。 |
| files | File[] | 是 | 图片数组。 |
| file | File[] | 兼容 | 兼容 Postman 多个同名 `file` 字段。 |

metadata 示例：

```json
{
  "cameraId": "cv6-001",
  "cameraName": "cv6产品正面污点检测相机001",
  "uploadTime": "2026-06-02 09:30:12.123",
  "images": [
    {
      "fileName": "aaaaa.jpg",
      "captureTime": "2026-06-02 09:30:12",
      "defects": [
        {
          "type": "污点",
          "severity": "HIGH",
          "x": 120.5,
          "y": 300.0,
          "width": 80.2,
          "height": 35.6,
          "area": 2854.12
        }
      ]
    }
  ]
}
```

规则：

- `metadata.cameraId` 或 `metadata.cameraName` 至少填写一个。
- `metadata.images` 不能为空，建议不超过 50 张。
- `metadata.images[].fileName` 必须与 multipart 上传文件原始文件名完全一致。
- 支持严重度：`HIGH`、`MEDIUM`、`LOW`。
- 上传成功后写入：`defect_image_batch`、`defect_image_file`、`defect_image_box`。
- 图片保存到 `${app.home}/data/uploads/defect-images/`。

## 6. 缺陷图片查询接口

### 6.1 最近图片

```http
GET /api/v1/defect-images/recent?limit=200&cameraId=cv6-001&factoryId=4&lineId=10&stationId=34
```

参数：

| 参数 | 类型 | 说明 |
|---|---|---|
| limit | Integer | 返回数量，默认 200，最大 1000。 |
| cameraId | String | 相机 ID/编码。 |
| cameraName | String | 相机名称。 |
| factoryId | Integer | 工厂。 |
| lineId | Integer | 产线。 |
| stationId | Integer | 工位。 |

### 6.2 图片文件

```http
GET /api/v1/defect-images/file/{yyyyMMdd}/{cameraId}/{fileName}
```

返回图片二进制，Content-Type 根据文件类型判断。

## 7. 相机管理接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/cameras?page=1&size=10` | 分页查询相机。 |
| GET | `/api/cameras/stats` | 相机状态统计。 |
| GET | `/api/cameras/recognize-stats` | 识别统计。 |
| POST | `/api/cameras` | 新增相机。 |
| PUT | `/api/cameras/{id}` | 修改相机。 |
| GET | `/api/cameras/{id}/dependencies` | 查询依赖。 |
| DELETE | `/api/cameras/{id}` | 删除相机。 |

## 8. 配置管理接口

| 资源 | 路径 | 方法 |
|---|---|---|
| 工厂 | `/api/factories`、`/api/factories/{id}`、`/api/factories/{id}/dependencies` | GET/POST/PUT/DELETE |
| 产线 | `/api/lines`、`/api/lines/{id}`、`/api/lines/{id}/dependencies` | GET/POST/PUT/DELETE |
| 工位 | `/api/workstations`、`/api/workstations/{id}`、`/api/workstations/{id}/dependencies` | GET/POST/PUT/DELETE |
| 用户 | `/api/user`、`/api/user/{id}`、`/api/user/{id}/toggle-status` | GET/POST/PUT/DELETE |
| 级联 | `/api/cascade/all` | GET |
| 配置导入模板 | `/api/config-import/template` | GET |
| 配置导入 | `/api/config-import/import` | POST multipart |
| 操作记录 | `/api/logs/operation` | GET/DELETE |

## 9. 统计接口

| 接口 | 说明 |
|---|---|
| `GET /api/stats/dashboard-overview` | 产线总览 KPI。 |
| `GET /api/stats/defect-overview` | 缺陷总览。 |
| `GET /api/stats/defect-trend` | 缺陷趋势。 |
| `GET /api/stats/defect-heatmap` | 缺陷热力图。 |
| `GET /api/stats/yield-trend` | 良率趋势。 |
| `GET /api/stats/station-ranking` | 工位排行。 |
| `GET /api/stats/brand-compare` | 品牌对比。 |
| `GET /api/stats/lines` | 统计筛选产线。 |
| `GET /api/stats/brands` | 统计筛选品牌。 |
| `GET /api/stats/workstations` | 统计筛选工位。 |

## 10. 错误提示规范

| 场景 | 建议错误码 | 文案 |
|---|---|---|
| 缺 Token | AUTH_MISSING | 缺少认证令牌，请在 Header 中传 Authorization。 |
| 缺设备 ID | DEVICE_MISSING | 缺少设备 ID，请在 Header 中传 X-Device-Id。 |
| 文件名不匹配 | FILE_NAME_MISMATCH | metadata 中的文件名未匹配到上传文件。 |
| 图片格式不支持 | UNSUPPORTED_IMAGE_FORMAT | 请上传 jpg/jpeg/png/bmp/webp 等支持格式。 |
| 图片内容损坏 | IMAGE_CONTENT_INVALID | 图片内容解析失败，请重新导出原图后上传。 |
| 存在依赖不能删除 | DEPENDENCY_EXISTS | 当前数据存在下级或业务数据，请先迁移/解绑。 |
