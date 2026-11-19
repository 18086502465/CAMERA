# 产线缺陷在线 AI 检测系统 —— 需求 PRD（总纲）

> **这份文档是什么**：把整个项目的**全部需求**收敛到一处 —— 已实现的、在建的、明确不做的、遗留待办的，
> 各自说清「要什么」「规则是什么」「怎么验收」「代码在哪」「怎么验」。
>
> **它不是什么**：不是某一功能的详细设计（那些在各自的专项文档里，见 §0.2）。
> 本文档是**总纲 + 索引 + 追溯矩阵**，让人不进代码也能知道"这系统到底要干什么、做到哪一步了"。
>
> | 项 | 内容 |
> |---|---|
> | 文档版本 | v1.0（2026-11-18） |
> | 覆盖范围 | 全项目（设备接入 → 主数据 → 统计 → 看板 → 日报 → 告警 → 数据治理 → 部署运维 → 授权） |
> | 现场基线 | 杭州临平二期，10.2.77.72；2 工厂 / 6 产线 / 31 工位 / 32 相机（REAL 18、TEST 14）/ 缺陷图 41,176 张（2026-11-19 实测；相机数随现场接入变化，数字以实测为准） |
> | 事实来源 | 现行 `docs/` 文档 + **代码实测**（Controller/端点/表结构/告警/任务/配置）+ `docs/archive/` 历史方案（已甄别真伪） |

---

## 0. 怎么用这份文档

### 0.1 状态标记怎么读

"项目要什么"和"项目现在做到了什么"是两件事，所以本文用**章节级状态标记**区分：

| 标记 | 含义 | 出现在 |
|---|---|---|
| ✅ **已实现** | 代码里有、已部署现场、有回归验证 | 功能需求小节标题 |
| 🟠 **在建** | 需求已拍板，代码已开发但**未提交**（见 §7.4） | 功能需求小节标题 |
| 🔴 **明确不做** | 讨论过并明确排除 —— **这部分同样重要**，它是范围边界的证据 | §1.3 范围边界 |
| ⚪ **遗留待办** | 已识别、未排期 | §7.1 / §7.2 |

**行内的具体限制**（如"仅当名称唯一时兜底""仅图片表无数据才兜底"）直接写在需求行的「关键规则」列里，
不另设标记 —— 规则本身就是可验收的判定条件。

需求编号规则：`DEV` 设备接入 / `CFG` 主数据配置 / `STAT` 统计看板 / `RPT` 日报 /
`IMG` 图像记录 / `ALM` 告警 / `GOV` 数据治理 / `MON` 服务器监控 / `LIC` 授权 /
`IAM` 账号权限 / `TODO` 遗留项。**编号只增不改**（历史条目不回收），便于跨文档引用。

### 0.2 与其他文档的关系

| 文档 | 定位 | 什么时候看 |
|---|---|---|
| **本文** | 需求总纲 + 追溯矩阵 | 想了解"这系统要什么、做到哪" |
| [`架构说明.md`](架构说明.md) | 分层规则、依赖铁律、关键机制 | 改代码前 |
| [`模块设计.md`](模块设计.md) | 每域类清单 | 找某个类干什么 |
| [`接口文档.md`](接口文档.md) | 148 个端点（自动生成） | 查端点路径/权限 |
| [`踩坑速查.md`](踩坑速查.md) | 真实事故与根因（20 节） | **改任何东西之前** |
| [`配置项清单.md`](配置项清单.md) | 配置分层与全部可调项 | 改配置 |
| [`部署与运维手册.md`](部署与运维手册.md) | 安装/升级/备份/排障 | 上线与运维 |
| [`使用说明.md`](使用说明.md) | 面向操作员的页面导览 | 培训交付 |
| [`相机设备接口协议.md`](相机设备接口协议.md) | 设备侧字段/校验/错误码/缺口 | 设备固件对接 |
| `docs/archive/` | 历史评审与方案（**非规范**） | 想追溯某次决策的推理链 |

> ⚠️ `docs/archive/` 下的文档**都不是规范**，结论多已被后续轮次推翻。要现行口径一律回上表。

---

## 一、项目背景与目标

### 1.1 背景

产线上的工业相机做表面缺陷检测。现场设备能算出"这张图有几个缺陷框"，但**数据出不来**：
没有统一入口、没有可视化的结论、没有异常闭环、没有数据治理。系统要解决的是
**"让相机数据变成管理者能用的结论"**这件事。

具体三个缺口（这是本期需求的出发点）：

1. **数据不会自己送达** —— 要人登录点开才能看到；
2. **只看不闭环** —— 异常发现后没有责任人、没有跟进；
3. **看板数字"看似有、实际无"** —— severity 全是 HIGH、TEST 相机的数据混进真实统计、良品率根本算不出来。

### 1.2 业务目标与成功指标

| 目标 | 指标 | 现状（2026-11-18） |
|---|---|---|
| 结论可直达 | 打开日报 Tab 3 秒内看到"昨天"整页 | ✅ 快照查询，P95 < 500ms |
| 数字对得上 | 日报与现场手工盘点偏差 < 5% | ✅ 2026-11-16 实测 1688 张 / 38 框对数通过 |
| 数字不骗人 | 无数据不显示 0；不支持的筛选显式上报 | ✅ `ignoredFilters` + `yieldRateAvailable` |
| 数据不丢 | 缺陷图在线可查 31 天、归档可查 730 天 | ✅ 两层生命周期 |
| 不误删 | 真实相机数据永不被定时清理删除 | ✅ `camera_type` 隔离 + 三道防线 |
| 现场能用 | 5 条产线 / 36 台相机稳定运行 | ✅ 现场部署运行 |

### 1.3 范围边界（🔴 明确不做）

这些是**讨论过并明确排除**的，写在这里是为了防止将来被"顺手加回来"：

| 不做的事 | 为什么 | 出处 |
|---|---|---|
| 良品率 / 产量 / OEE | 图片通道**只上报缺陷图**（每张必带缺陷框），没有良品样本也没有良品计数 → 算出来是假的 | 日报 PRD §三 |
| 产品维筛选（统计页） | `defect_image_file/_box/_batch` **三张表都没有产品列**，物理上无法按产品过滤 | 日报 PRD §19.1 |
| 异常闭环 / 责任人 / 目标基线 | 本期只解决"按天掌握结论"，闭环是下一期 | 日报 PRD §一 |
| 日报推送（企微 / 邮件） | 要先验证推送内容再接通道 | 日报 PRD D4 |
| 异地容灾 | 跨盘副本仍在同一台机器上，不抗整机故障 | README 已知限制 |
| Windows 交付链 | 只维护一套实现 | README |
| 实时数仓 / 流式计算 | 现场日增仅约 1.5k–2.4k 张，离需要还很远 | — |

---

## 二、用户角色与典型场景

| 角色 | 关心什么 | 典型场景 | 主要页面 |
|---|---|---|---|
| **设备（相机）** | 把心跳、计数、缺陷图交上来 | 每 ~5 秒心跳一次；检出缺陷就上传图 | —（设备侧 API） |
| **产线主管** | 我这条线今天怎么样？最差的是谁？ | 早会前打开日报，看昨天缺陷率与最差相机，安排改善 | 产线统计日报 / 产线统计 |
| **厂长** | 全厂趋势、谁最差、什么时段最糟 | 周会看趋势与热力图；圈定改善方向 | 产线统计（工厂质量看板） |
| **系统管理员** | 配置对不对？数据在不在？设备活没活？ | 配工厂/产线/工位/相机；查告警；处理备份失败 | 系统配置 / 异常告警 / 服务器监控 |
| **运维** | 服务活没活？盘满没满？备份成了没？ | 巡检脚本 + 告警页 | 服务器监控 / 异常告警 |
| **交付/实施** | 装得上、升得动、恢复得了 | 装机、升级、回滚、恢复 | —（运维手册） |

---

## 三、功能需求

### 3.1 设备接入 ✅ 已实现

系统有 **4 条设备侧端点**。现场**只用 A（心跳）+ B（图片上传）**，
C（批量上报）是冻结契约、D（单图上传）是调试端点 —— **B 与 C 禁止并用**（会让统计口径分叉）。

| 端点 | 状态 | 用途 |
|---|---|---|
| A `POST /api/v1/camera/status/heartbeat` | ✅ 在用 | 心跳：状态 + 累计计数 + 资源占用 |
| B `POST /api/v1/defect-images/upload` | ✅ **主通道** | 缺陷图片批量上传 |
| C `POST /api/v1/camera/inspection/batch-upload` | 🔴 冻结 | 批量检测上报（现场恒 0 行） |
| D `POST /api/v1/camera/image/upload` | 🟡 调试 | 人工补图，**不进缺陷统计** |

#### 3.1.1 心跳通道（A）

| 编号 | 需求 | 关键规则 | 验收 |
|---|---|---|---|
| DEV-01 | 接收心跳并覆盖设备当前状态 | `cameraStatus` 只接受 `0停止/1运行/2故障/3待机`，**越界直接拒绝**；`productName` 仅作快照落库**不校验存在**；`timestamp` 解析失败**静默回落服务器时间**（不报错） | `code=200`「状态已更新」；越界值返回失败且状态不变 |
| DEV-02 | **设备身份 = `X-Device-Id`** | 该头必填，值必须是 `camera_code`（如 `DA4733319`）。`cameraName` **仅作展示**（现场跨产线重名），服务端按名称兜底匹配**仅在名称唯一时**生效，命中多行则拒绝 | 同名两台设备心跳分别落到各自记录；重名且不传编号 → 「相机名称重复，无法确定目标设备」 |
| DEV-03 | 累计计数语义（**覆盖，非增量**） | 三个计数都是"自设备启动以来累计值"，服务端用新值**覆盖**旧值。新值 < 旧值只打一条「计数器重置」日志，**不拒绝** | 连报 100/98/2 与 120/118/2 → 落库 120/118/2；报小值不报错 |
| DEV-04 | 计数器重置信号 `resetFlag` | 设备归零（重启/换线）时携带 `resetFlag=true`，服务端清该相机计数缓存 | 下轮不再判重置，不产生 `COUNT_MISMATCH` 误报 |
| DEV-05 | 上报节奏由服务端下发 | 响应带 `nextInterval`（**固定 5000ms**，不接受协商）与 `configVersion`（固定 `v1.0.0`，**是协议版本位，不是真实配置下发**） | 响应含 `nextInterval=5000` |
| DEV-06 | 计数自洽校验 | `recognizeCount ≈ correctCount + abnormalCount`（误差 ≤1）。**只告警不拒绝**，`COUNT_MISMATCH`（WARNING） | 100/97/2 触发一条告警且心跳仍 200 |
| DEV-07 | 资源与温度越限告警 | 阈值可配（`alert.cpuWarning` 默认 90、`alert.memoryWarning` 90、`alert.temperatureWarning` **70**） | 上报 `cpuUsage=95` → `CPU_HIGH`；`temperature=75` → `TEMPERATURE_HIGH` |
| DEV-08 | 故障告警 | `cameraStatus=2` → CRITICAL `CAMERA_FAULT`，`errorMessage` 进正文。**同相机同类型 30 分钟只落 1 条**，条件消失后**稳定 2 分钟**才判恢复 | 连发 3 次故障心跳 → 告警表**只有 1 条** |
| DEV-09 | 离线判定（服务端派生） | 超阈值未收到心跳 → 状态置 `cameraStatus=9`（**服务端派生，设备上报 9 会被判越界拒绝**） | 停止上报超时后变离线，恢复后回到上报值 |
| DEV-10 | **心跳强制先建档** | `X-Device-Id` 在配置中查不到 → 拒绝「设备未在系统配置中维护: XXX」 | 未建档设备发心跳 → `code=500`；建档后立即成功 |
| DEV-11 | 心跳失败响应统一形态 | **所有失败分支一律 HTTP 200 + `body.code=500`** + `errorCode=null` + `message` 说明原因 | 9 种失败文案均可区分，HTTP 状态码恒 200 |
| DEV-12 | 授权闸门 | 授权失效 → **HTTP 200 + `code=503`** + `errorCode=LICENSE_EXPIRED`（全站受限为 `LICENSE_BLOCKED`）。设备端收到 503 **必须停止无条件重试**，转本地缓存 | 授权到期后上报收到 503；设备端转本地缓存而非持续打服务端 |

#### 3.1.2 缺陷图片上传通道（B，主通道）

| 编号 | 需求 | 关键规则 | 验收 |
|---|---|---|---|
| DEV-13 | 批量上传缺陷图与缺陷框 | 逐张落库并统计；写 `defect_image_batch/_file/_box` 三表 + `camera_images` 镜像，更新 `latest_image_path`/`last_upload_at` | 三表有行、镜像表有行、状态表更新 |
| DEV-14 | multipart 三段式契约 | 文件字段名 `files`（推荐）或 `file`（兼容，**服务端合并二者，不要都传**）；`metadata` 是 **Text 型 JSON 字符串**（不是文件），容忍开头 BOM | `metadata` 传成 File 或非法 JSON → 422 `UPLOAD_METADATA_INVALID` |
| DEV-15 | `metadata.cameraId` 是落库权威 | 决定挂哪台相机、落哪个目录；**服务端不校验它与 header 是否一致** → 设备端必须自己保证 | 不一致时会出现"上传成功但图挂错相机" |
| DEV-16 | 文件名一一对应 | 上传文件集合与 `metadata.images[].fileName` 集合**必须完全相等**；多/少/重复 → **整批拒绝**。比对口径：去路径 + `trim` + **转小写**（`A.JPG` 与 `a.jpg` 算同名 → 判重复） | 不一致 → 422 `UPLOAD_FILE_NAME_MISMATCH` |
| DEV-17 | 扩展名白名单（**禁 SVG**） | 只接受 `jpg/jpeg/png/bmp/gif/webp`。SVG 可携带脚本（存储型 XSS），**明确禁止** | 传 `.svg`/`.txt` 被拒 |
| DEV-18 | 内容可解码 + 占位图拦截 | 必须能被解码；空文件/损坏/扩展名与内容不符 → `IMAGE_CONTENT_INVALID`；**宽或高 ≤ 2px** → `IMAGE_SIZE_INVALID` | 0 字节、1×1 像素 PNG 被拒 |
| DEV-19 | 批量与体积上限 | 单批 ≤ `defect-image.max-batch-images`（默认 **100**）；单文件 ≤ **10MB**；单请求 ≤ 1024MB。**超容器级限制直接 413/500，拿不到 errorCode** | 101 张 → 422 `UPLOAD_BATCH_SIZE_EXCEEDED`；11MB → 容器级 413 |
| DEV-20 | `severity` 归一化 | `HIGH/严重/高`→HIGH；`MEDIUM/MAJOR/中/重大`→MEDIUM；**其它或空 → LOW** | 传「严重」计数正确；传 `""` 按 LOW 计入 |
| DEV-21 | 缺陷类型自由文本、坐标原样落库 | `defects[].type` 不做字典校验；`x/y/width/height/area` 原样落库，缺省 0。`defects` 缺省/null = 无缺陷（良品） | 传 `type="GLUE_SMALL"` 原样入库；不传坐标落 0 |
| DEV-22 | 部分成功语义 + 逐文件结果 | `data.success` **整批全成功才 true**；HTTP 仍 200/`code=200`，message 变「缺陷图片部分上传失败」；`results[]` 顺序与 `metadata.images` 一致 | 2 张中 1 张坏图 → `success=false`、`successCount=1`、`failCount=1` |
| DEV-23 | 逐文件失败码 | `FILE_NAME_MISMATCH`/`IMAGE_CONTENT_INVALID`/`UNSUPPORTED_IMAGE_FORMAT`/`IMAGE_SIZE_INVALID`/`UPLOAD_FAILED` | 每类都能在 `results[].errorCode` 读到，**不靠匹配中文** |
| DEV-24 | 整批拒绝错误码 | `UPLOAD_BATCH_SIZE_EXCEEDED`/`UPLOAD_FILE_NAME_MISMATCH`/`UPLOAD_METADATA_INVALID`/`AUTH_DEVICE_UNAUTHORIZED`/`PARAM_INVALID`；HTTP **422**，`data.results` 仍非空且附中文处置建议 | 缺 `X-Device-Id` → 422 `AUTH_DEVICE_UNAUTHORIZED` |
| DEV-25 | 落盘路径与文件名**由服务端生成** | `{upload.dir}/yyyyMMdd/{cameraId}/HHmmssSSS_{uuid8}.{ext}`；`cameraId` 非 `[A-Za-z0-9._-]` 字符替换为 `_`；日期目录由 `uploadTime` 决定 | 同批同名不覆盖；URL 形如 `/api/v1/defect-images/file/20261113/DA4733319/103000123_9f3c1a7b.jpg` |
| DEV-26 | 图片读取免鉴权 | `/defect-images/file/**` 在白名单内，**知道路径即可读** | 不带 token 也能取到图（见 §7 缺口 3） |
| DEV-27 | 图片**不强制**建档（与心跳相反） | 未建档的图能上传成功，但**按产线/工位筛选时查不出来**（缺 `line_id/workstation_id`），服务端静默跳过 | 上传 200 但图像记录页无记录 → 上线前必须先建档 |

**重试纪律**（设备端必守）：`success=false` 时**只重传失败的那几张**，整批重传会产生重复图；
422 不重试；500 指数退避 5s/15s/60s，**超 3 次停手上报本地告警**；503 不重试。

#### 3.1.3 批量上报通道（C，冻结契约）

| 编号 | 需求 | 关键规则 |
|---|---|---|
| DEV-28 | 批次聚合上报 | **现场未启用**（`fact_inspection_*` 恒 0 行）。启用前必须先配缺陷映射；除非甲方明确要求按缺陷类型出聚合报表，**否则不要接** |
| DEV-29 | `schemaVersion` 强约束 | 必须是字符串 `"2.0"`，否则 422 `INVALID_SCHEMA_VERSION` |
| DEV-30 | 批次一致性强制校验（**整批不入库**） | ①`totalCount == okCount + ngCount` ②`ngCount == Σ defects[].count` ③相机/产品/映射必须已配。违反 ①② → 422 `COUNT_MISMATCH` |
| DEV-31 | 幂等键 `(cameraId, batchId)` | `batchId` 取 body 或 header `X-Batch-Id`，都无 → 422 `BATCH_ID_REQUIRED`；重复提交返回 `accepted=true, duplicate=true` |
| DEV-32 | 缺陷映射要求 | `sourceCode` 须在 `cfg_camera_defect_mapping` 有 `is_enabled=1` 且在生效期内；**产品精确匹配优先**，其次 `product_id IS NULL` 那条；同 code 多条取 `effective_start` 最新。未映射 → 422 `UNMAPPED` + 写 `fact_inspection_mapping_exception` |
| DEV-33 | 产品名精确匹配 | 必须与 `dim_product` **完全一致（含大小写）**，否则 422 `PRODUCT_NOT_FOUND` |
| DEV-34 | 30 分钟时间桶 | 由 `reportTime` 决定落桶，响应回显 `timeBucket30m` |
| DEV-35 | 批量上报错误码集（15 个） | `TOKEN_REQUIRED`/`INVALID_BODY`/`INVALID_SCHEMA_VERSION`/`BATCH_ID_REQUIRED`/`CAMERA_CODE_REQUIRED`/`PRODUCT_NAME_REQUIRED`/`UNSUPPORTED_COUNTER_MODE`/`SUMMARY_REQUIRED`/`TIME_RANGE_REQUIRED`/`CAMERA_NOT_FOUND`/`PRODUCT_NOT_FOUND`/`TOKEN_INVALID`/`TOKEN_NOT_CONFIGURED`/`COUNT_MISMATCH` 等，HTTP 均 422 |

> ⚠️ **C 通道的历史契约已整体作废**（`dataList[]` + 21 个固定缺陷位 + `recordType/exceptionType/incremental*`），
> 与现行 `schemaVersion 2.0` + `summary` + `defects[].sourceCode` **无字段级对应关系**。
> 新装库默认种子映射是 `source_code = p{sort_order}`、默认产品只有 `产品A/产品B` ——
> 设备若用自有编码会**全部 UNMAPPED / PRODUCT_NOT_FOUND**。

#### 3.1.4 设备令牌与鉴权

| 项 | 规则 |
|---|---|
| 存储 | `cfg_camera_token`（按相机，`token_hash` + `token_name` + `expire_time` + `is_enabled`） |
| 生效条件 | `is_enabled=1 AND (expire_time IS NULL OR expire_time > NOW())`；`expire_time` 为 NULL = 永不过期 |
| 值写法 | **明文**或 **sha256 十六进制**；生产用 sha256 |
| 维护方式 | **无 UI、无 REST 接口**，只能运维直接写库；删相机时连带清理 |
| 新装库种子 | 建库脚本给每台相机灌 `token_hash='dev-token'`（**明文 = 全网公开**），**投产前必须全部替换** |
| 校验口径 | `CameraTokenVerifier.verify(token, deviceId)` —— **令牌 + 设备号绑定**。「配了令牌但传错」**两种模式下都拒绝** |
| 严格模式 | `camera.token.strict` 默认 **`false`**（未配令牌的相机**放行 + WARN**）；置 `true` 则一律拒绝 |

> ⚠️ 默认放行意味着「没配令牌」**不会报错** —— 容易误以为鉴权已生效。
> 全部相机配齐令牌后应把 `strict` 置 `true`，否则**任何知道相机编号的人都能伪造心跳与计数**。

**已知安全缺口（🔴 现行文档 §8.3 三条，勿当成已解决）**：

| # | 缺口 | 影响 |
|---|---|---|
| 1 | **`POST /api/v1/defect-images/upload` 未与令牌比对**，只校验 `Authorization` 以 `Bearer ` 开头 | **主通道无实质鉴权** —— 任何能访问 8091 的人，带任意非空 Bearer 串 + 合法 `X-Device-Id` 即可写图片与统计，污染产线数据与良率口径 |
| 2 | 心跳/上传都会**按 `cameraName` 兜底匹配**（仅名称唯一时） | 名称重名时行为与「按编号」不同，易误判 → 设备端应**始终**传正确的 `X-Device-Id` 与 `metadata.cameraId` |
| 3 | 图片读取 `/defect-images/file/**` **免鉴权** | 路径可枚举（日期 + 相机 + 8 位随机后缀），对外暴露需加网关鉴权或改签名 URL |

> 补充：`X-Image-Count` 预检头在 `fact-product/src/main/resources/application.yml` 注释里提及但**代码未实现**，不可依赖。

### 3.2 主数据与配置 ✅ 已实现

层级：**工厂 → 产线 → 工位 → 相机**。相机↔工位绑定的权威表是 `dim_camera`，
`camera_status` 只是运行状态快照。

| 编号 | 需求 | 关键规则 | 验收 |
|---|---|---|---|
| CFG-01 | 四级主数据 CRUD | 删除带**依赖保护**：有下属数据时列出明细并拒绝 | 删有相机的工位 → 拒绝并列出相机 |
| CFG-02 | 工位删除自动停用绑定相机 | 不会留悬空绑定 | 删工位 → 其相机 `is_active=0` |
| CFG-03 | 相机换工位自动联动 | 同步状态快照与缺陷映射，无需手工改配置 | 换绑后映射的 `line_id/workstation_id` 同步 |
| CFG-04 | 相机身份唯一 | `camera_code` 唯一索引 `uk_camera_code`；`camera_name` **允许重名**（现场现实） | 同编号再新增 → 报「设备编号已存在」 |
| CFG-05 | **删后可重加（原位复活）** | 删除是逻辑删除（`is_active=0`，行还在），物理唯一键会挡死重加 → 重加时**原位复活**：同 id、`is_active=1`、保留 createTime、**类型取原值**（REAL 绝不能被降级成 TEST，否则数据被清理删掉）；配额检查不跳过；活跃行重号仍拒绝 | 删除→重加成功 200；REAL 相机复活后仍是 REAL |
| CFG-06 | **相机类型 `camera_type`** | `REAL`=真实产线相机（**数据不参与定时清理**）/ `TEST`=测试相机（参与）。两表都有，`dim_camera` 权威 | 新建默认 TEST；可改 |
| CFG-07 | 字典维护 | 缺陷类型 / 产品 / 品牌 / 班次 / 节假日可维护 | — |
| CFG-08 | Excel 批量导入 | 提供模板下载；导入返回逐行结果并可导出核对 | 导入失败行有明细 |
| CFG-09 | 配置导入的相机兜底类型 | 导入未带类型时按 TEST 处理（与存量现状一致） | — |
| CFG-10 | 看板管理 | 看板与组件增删改、复制、导出 Excel；内置看板不可改 | — |
| CFG-11 | 系统设置 | 全局参数；**只有 3 个告警阈值被业务读取**，其余为展示性配置 | — |
| CFG-12 | 依赖校验的级联树下发 | 下发到设备端，保证设备知道自己的工厂/产线/工位 | — |

### 3.3 统计与看板（`/line-analytics`）✅ 已实现

| 编号 | 需求 | 关键规则 | 验收 |
|---|---|---|---|
| STAT-01 | **内置「工厂质量看板（默认）」** | 首屏默认渲染代码常量 `builtin-quality-board`（7 个 widget，结论优先：工厂总览 → 缺陷趋势按产线 → 产线排行+责任相机 → 帕累托+时段热力 → 缺陷日历）；只读（禁用拖动/编辑/删除、隐藏组件库与配置面板）；「记住上次看板」开关**默认关** | 进页即出图，不需先配模板 |
| STAT-02 | 看板形态 16 种 | `plantOverview`/`quality`/`trend`/`defectTrend`/`defectRanking`/`defectPareto`/`severityDistribution`/`stationRanking`/`cameraRanking`/`stackDefectBySeverity`/`heatmap`/`shiftCompare`/`pie`/`calendar`/`defectPivot`/`mappingExceptions` | — |
| STAT-03 | **数据源优先级** | **图片事实表优先，聚合表仅兜底**（响应带 `dataSource`）。反过来会被 `agg_*` 孤儿行骗过 → 「筛选后比不筛选还多」 | 不筛选 = 各产线之和（数字一致） |
| STAT-04 | 趋势粒度自适应 | 跨度 < 3 天按 `HOUR`，≥ 3 天按 `DAY`（点数从上千降到几十） | 30 天窗口点数 ≤ 40 |
| STAT-05 | **产线/工位多选** | 前端总同时带 `lineId=lineIds[0]` + `lineIds` → 后端取**并集**再 `IN`；页面内相机筛选按**全部**所选产线收敛，并在筛选变化时清掉悬空相机 | 多选 N 条产线 = 各条单独查询之和 |
| STAT-06 | 缺陷日历固定窗口 | 固定 `[end-29d, end]`，**不跟时间范围收窄** | — |
| STAT-07 | 数据源标签诚实 | 按 `dataSource` 显示「实时 · 图片事实表」/「30分钟聚合」 | 标签与 `dataSourceMessage` 不矛盾 |
| STAT-08 | 筛选诚实度上报 | 不支持的筛选（`productId`）回传 `ignoredFilters` + `ignoredFilterMessage`，前端挂「不生效」角标 | 选了产品 → 角标可见 + 消息说明原因 |
| STAT-09 | 良率不可算时不显示数 | 图片通道无良品样本 → `yieldRate=null`、`yieldRateAvailable=false`，显示「未接入」，趋势图**去掉良率线** | 绝不为 0% 或 100% |
| STAT-10 | 维度拆分 | `DimSplit` 支持 time/line/workstation/camera/defectType/shift/severity；**新增维度必须同时登进 `splitDimOf`/`DIM_NAME`/`wizardDimOptions`** | 勾了维度图表出对应序列 |
| STAT-11 | 班次定义 | 白班 08:00–20:00，晚班 20:00–08:00（跨天） | 时间落在正确班次 |
| STAT-12 | 空结果提示 | 显示时间范围、当前产线、建议「改看近 7 天」，不留空白 | — |
| STAT-13 | 竞态守卫 | 快速切产线时旧响应不得覆盖新响应（`dataSeq`） | 连续切换后数字是最后选择的结果 |

### 3.4 产线统计日报（`/daily-report`）🟠 在建

| 编号 | 需求 | 关键规则 | 验收 |
|---|---|---|---|
| RPT-01 | **三段式形态** | ①产线胶囊 Tab → ②当月月历 → ③当日维度（4 KPI + 四宫格 + 口径页脚） | 选产线 → 点日期 → 看当天维度 |
| RPT-02 | 快照表 | `fact_daily_line_report`，唯一键 `uk_report_date_line(report_date, line_id)`，`line_id=0` 为厂级汇总行 | 每天 00:05 物化昨天 |
| RPT-03 | 当天实时算 | 当天不入库，查询时实时计算，回传 `dataSource=REALTIME` | — |
| RPT-04 | **只统计 REAL 相机** | `dim_camera.camera_type='REAL' AND is_deleted=0`，TEST 与未登记相机不进日报 | TEST 相机数据不进日报 |
| RPT-05 | **缺陷率双口径** | 主口径=心跳计数**日增差分**；辅口径=图片数。**图片口径缺陷率恒 1000‰**（每张必带缺陷框，分母=分子）无区分度，只作辅助 | 首日/计数器重置 → NULL「率待积累」 |
| RPT-06 | **补算不拍计数快照** | `generateFor(date, stampCounters=false)` —— 白天补算时当前计数已含之后上传，差分必错 | 补算后计数列为 NULL |
| RPT-07 | 口径版本化 | 表上有 `snapshot_version`，改口径只影响此版本之后的日期 | 历史快照数字不变 |
| RPT-08 | 月历四态 + 分位色阶 | 有数据 / 无数据 / 无快照 / 未来（虚线）；色阶按**当月**分位 P50/P75/P90，**不写死阈值** | 跨月波动不导致全红或全浅 |
| RPT-09 | 月历格主值 | 用**缺陷框数**不用缺陷率（历史日率不可回溯），第二行诚实显示「率待积累」 | — |
| RPT-10 | 昼夜区分 | 「0 上传」只算白天 08:00–20:00，夜间合并成「夜间未排产」 | 不天天报「13 小时无上传」 |
| RPT-11 | 当日异常口径 | 页面明确写「只能当线索，不当结论」 | — |
| RPT-12 | 最差/最好日 | `worstDate/bestDate` **排除今天**（今天还在涨） | — |
| RPT-13 | 相机健康 | 每行含在线相机 x/y（当日有上报算在线）与当日温度告警数 | 2 台 REAL 1 台未上报 → 1/2 |
| RPT-14 | 样本下限 | 当日图片数 ≥100 才参与「最差相机」排名，低于则「样本不足，未排名」 | 样本 50 张不排名 |
| RPT-15 | 明细归档降级 | 超 31 天归档后 `detailsAvailable=false` → 页面显示「明细已归档（仅保留汇总）」 | — |
| RPT-16 | 手工补算 | 仅 ADMIN，幂等覆盖（DELETE+INSERT 同事务），唯一键兜底 | 重复调不产生重复行 |
| RPT-17 | 失败告警 | `DAILY_REPORT_FAILED`（HIGH，运维类前排），成功后自动恢复 | 修因重跑后 `is_resolved=1` |
| RPT-18 | Excel 导出 | 区间内快照行 + 今天实时行一起导出，列含日期/产线/指标/数据源 | 7 天区间 → 8 行 |

### 3.5 图像记录与缺陷检索 ✅ 已实现

| 编号 | 需求 | 关键规则 | 验收 |
|---|---|---|---|
| IMG-01 | 一行一个相机的图像记录 | 含图片总数、严重度统计、最近缩略图 | — |
| IMG-02 | 分页加载历史 | 走 `defect-images/history`（`product_detection_history` 是冻结通道，现场 0 行） | 大数据量不卡 |
| IMG-03 | 跨归档月表查询 | 范围全在 31 天内只查主表，否则主表 + 相关月表 `UNION ALL` | 跨月查询命中 |
| IMG-04 | 缺陷类型精确筛选 | `defectType` **精确匹配**（类型名互相包含，改回 LIKE 会放大 13 倍）；计数必须 `JOIN defect_image_file` | 筛选「接头」不误含「接头大块脏污」 |
| IMG-05 | 筛选下拉只列实际数据 | `filter-options` 返回现场**实际出现**的类型（与 `dim_defect_type` 15 个标准类型**完全不相交**） | 下拉里有「接头/小污点/胶滴」 |
| IMG-06 | 表格补列 | 相机编号、当前检测产品、分辨率、延迟 | — |
| IMG-07 | 缺陷框位置面积不可用 | `width/height/area` 全 0、位置不可靠 → **不要做位置热力图** | 页面不提供位置维度 |

### 3.6 告警 ✅ 已实现

| 编号 | 需求 | 关键规则 |
|---|---|---|
| ALM-01 | **业务类告警 5 种**（心跳派生） | `CAMERA_FAULT`(CRITICAL)、`COUNT_MISMATCH`(WARNING)、`CPU_HIGH`、`MEMORY_HIGH`、`TEMPERATURE_HIGH` |
| ALM-02 | **运维类告警 6 种** | `BACKUP_FAILED`、`CLEANUP_FAILED`、`ARCHIVE_FAILED`、`DISK_USAGE_HIGH`、`DISK_FULL`、`DISK_CAPACITY_LOW` |
| ALM-03 | 两段式查询 | 运维类**独立窗口、永远排最前**；业务类按 `alert_level` 排序（CRITICAL>HIGH>MEDIUM>WARNING>LOW>INFO），同级按时间倒序 | 新 HIGH 排在旧 WARNING 前 |
| ALM-04 | 去重节流 | 同 `(line_id, workstation_id, alert_type)` 30 分钟只落 1 条；**重启失忆**（进程内状态） |
| ALM-05 | 续报与恢复 | 告警持续时发「续报」并带持续时长；条件消失后**稳定 2 分钟**才判恢复 |
| ALM-06 | **脚本告警必须「同行更新 + 成功后自动恢复」** | 唯一例外：REAL 数据减少**不自动恢复**（要人工确认） |
| ALM-07 | 新增告警类型同步 | 脚本类告警类型必须同步 `FactAnomalyAlertController.OPS_ALERT_TYPES` |

### 3.7 数据治理（备份 / 归档 / 清理）✅ 已实现

| 编号 | 需求 | 关键规则 |
|---|---|---|
| GOV-01 | **磁盘图片文件只有一个删除者** | `cleanup-defect-images.sh`（730 天日期目录）。Java 侧清 `camera_images` **只删行不删文件** —— 两表 `image_path` 完全重叠，Java 删文件会造成「记录留 730 天、文件 30 天消失」的批量裂图 |
| GOV-02 | 两层生命周期 | 主表 31 天 → 按月 `INSERT…SELECT` 移到 `*_hist_YYYYMM`（5000 行/批、独立事务）→ 月表 730 天整表 DROP；磁盘 730 天 |
| GOV-03 | **测试相机清理** | 只清 `camera_type='TEST'`；**三道防线**：①双表 OR 保护集 ②保护集为空即中止(退出码 3) ③单轮待删 > `MAX_SINGLE_DELETE` 即中止(4)；清理后自检 REAL 是否减少 |
| GOV-04 | 清理顺序 | **先删库 → 再删磁盘**（反过来会留「库有行、盘无文件」的裂图） |
| GOV-05 | 每日全量备份 | `mysqldump --single-transaction --quick --routines --triggers --events` → zstd + SHA-256，14 份 |
| GOV-06 | binlog 增量备份 | 只取本次 dump 起点及之后的日志；**历史 binlog 唯一可回溯，不可删** |
| GOV-07 | 跨盘副本 | `/var/backups/camera-detection`（系统盘，唯一抗单盘故障那份）；图片排除 TEST，上限 20GB |
| GOV-08 | 磁盘水位监控 | 每 30 分钟巡检，`DISK_USAGE_HIGH`/`DISK_FULL` 告警 |
| GOV-09 | `sys_data_retention_policy` **不生效** | 代码从不读它；真实保留期硬编码在 `DataCleanupTask`(02:00) 与 `cleanup-defect-images.sh`(03:30)。改那张表不生效 |

### 3.8 服务器监控 ✅ 已实现

| 编号 | 需求 | 关键规则 |
|---|---|---|
| MON-01 | 本机指标实时展示 | CPU / 内存 / 分区容量 / 磁盘读写与 IOPS（OSHI 6.9.3），采集间隔 5s，历史 120 点（10 分钟窗口） |
| MON-02 | 指标只在本机 | 历史曲线存**进程内存**，重启后清零 |
| MON-03 | 端点权限 | `GET /api/v1/system/server/metrics` → **ADMIN/USER** |

### 3.9 授权（License）✅ 已实现

| 编号 | 需求 | 关键规则 |
|---|---|---|
| LIC-01 | 授权文件位置 | `${app.home}/data/license/license.lic`，**必须应用用户 `cdsys` 可写且在 systemd `ReadWritePaths` 内** —— 放 `/etc` 会让「上传新授权」永远 Permission denied，而旧授权还在、系统照常跑 |
| LIC-02 | 三种过期策略 | `warn`（默认，只告警不阻断）/ `readonly`（过宽限期拒绝设备写入）/ `block`（再拒绝业务查询） |
| LIC-03 | 状态机 | `DISABLED` / `NOT_INSTALLED` / `VALID` / `EXPIRING` / `EXPIRED`；`grace-days=7`、`warn-days=30` |
| LIC-04 | 机器绑定 | 指纹变化（换主板/重装/虚拟机克隆）→ 需重新签发；排障可临时 `enforce-machine=false` |
| LIC-05 | **签名安全边界** | 对称密钥在 jar 里，反编译可提取 → **只挡"改到期日/换机器拷/过期继续用"，挡不住逆向**。不可对客户承诺"无法破解" |
| LIC-06 | 签发两端一致 | Java 与 `installer/linux/scripts/gen_license.py` 的密钥与 base64 变体必须逐字节一致；`LicenseSignerTest.GOLDEN` 固化了真实签发文件 —— **不要为了变绿去改 GOLDEN** |
| LIC-07 | 改签名的测试改**中间位** | base64 末位只有 4 位有效信息，改末位可能解码出同一份字节 → 假绿 |

### 3.10 账号、权限与操作日志 ✅ 已实现

| 编号 | 需求 | 关键规则 |
|---|---|---|
| IAM-01 | JWT 登录 | 凭据错误返回 **400 而非 401**（否则前端拦截器当会话过期，改写登录回跳上下文）；登录后回到原页面 |
| IAM-02 | 401 / 403 语义 | 未认证 → 401 `AUTHENTICATION_REQUIRED`；角色不足 → 403 `ACCESS_DENIED`。前端 `utils/auth-redirect.js` 集中判定 |
| IAM-03 | 白名单精确 | 精确到方法+路径，**禁 `/**` 兜底**（历史上匿名就能改生产库）。新增端点同改 `SecurityConfig` / `@PreAuthorize` / `installer/linux/scripts/verify.sh` 三处 |
| IAM-04 | 新前端路由进 SPA 白名单 | 漏了 → 刷新拿 401 JSON 而不是 index.html |
| IAM-05 | 操作日志 | 记录写操作，保留 6 个月 |
| IAM-06 | 默认账号 | `admin`（全新库 `123456` / 现场已改 `admin123`），投产前必须改 |


---

## 四、数据口径与指标定义（真源）

### 4.1 相机身份与类型（一切统计的地基）

| 项 | 规则 |
|---|---|
| **唯一身份** | `camera_code`（唯一索引 `uk_camera_code`），**不是** `camera_name` |
| **名称可重名** | 现场跨产线重名是现实（ZK54 与 ZK23 都有 `CV1_Cam1`、`CV2_Cam1`）→ **全层 code-first**，名称兜底必须**双侧都唯一**，多行则拒写 + WARN |
| `camera_type` | `REAL`（真实产线相机，**数据不参与定时清理**）/ `TEST`（测试相机，参与清理），默认 `TEST` |
| 权威源 | 相机↔工位 = `dim_camera`；`camera_status` 只是运行状态快照 |
| `defect_image_file.camera_id` | 实际存的是 **camera_code** → JOIN 要写 `ON c.camera_code = f.camera_id` |

### 4.2 关键指标公式

| 指标 | 公式 | 数据源 | 诚实度 |
|---|---|---|---|
| 良率 | `okCount / totalCount` | 聚合表有；**图片通道无良品样本** | 图片通道 → `yieldRate=null`、`yieldRateAvailable=false`，显示「未接入」/「—」，**绝不显示 0% 或 100%**，趋势图去良率线 |
| NG 率 | `ngCount / totalCount` | 同上 | 图片通道 → null |
| 缺陷率(‰) | `缺陷框数 ÷ 检测图片数 × 1000` | 日报**辅口径** | 图片通道每张必带框 → **恒 1000‰**，无区分度，只作辅助 |
| 缺陷率(‰) | `NG计数日增 ÷ 检测总数日增 × 1000` | 日报**主口径** | 首日无前日快照 / 计数器重置 → NULL「率待积累」 |
| 高危占比 | `highCount / defectCount` | `defect_image_box.severity` | **现场 severity 全为 HIGH**（设备端上报即如此，服务端分级逻辑正确）→ 当前该指标无区分度 |
| 在线相机 | 当日有上报的 REAL 相机 / 当日在册 REAL 相机 | `camera_status` + 快照 | — |
| 班次 | 白班 08:00–20:00，晚班 20:00–08:00（跨天） | `ShiftCalculator` | — |
| 累计检测计数 | 设备上报的 `total_count/ok_count/ng_count`（**覆盖语义**，非增量） | `camera_status` | 心跳计数是**设备自启动累计值**，与图片张数不同源，**不可直接相加** |

### 4.3 数据源优先级与诚实降级

| 场景 | 规则 |
|---|---|
| 统计接口 | **图片事实表优先**（`defect_image_*`，现场真正在用的通道），**聚合表仅兜底**；响应带 `dataSource` |
| 不可反 | 反过来会被 `agg_*` 孤儿行（产线已删除的残留）骗过 → 「筛选后比不筛选还多」 |
| 孤儿行清理 | `purgeOrphanAggregationRows()` 在日聚合重建后自动执行 |
| 不支持的筛选 | **必须显式上报**（`ignoredFilters` + `ignoredFilterMessage`），前端挂「不生效」角标 —— **静默吞掉筛选是最伤信任的失败模式** |
| 无数据 | 显示「— / 无数据 / 待积累」，**不显示 0**；分母为 0 时比率必须 NULL |

### 4.4 数据保留期

| 数据 | 保留期 | 负责者 | 机制 |
|---|---|---|---|
| `defect_image_file/_box/_batch`（主表） | **31 天** | `DefectImageArchiveTask`（02:30） | 超期按月 `INSERT…SELECT` 移到 `*_hist_YYYYMM` |
| 月表 `*_hist_YYYYMM` | **730 天** | 同上 | 超期整表 DROP（白名单校验 + 730 天闸门） |
| 磁盘图片文件 | **730 天** | `cleanup-defect-images.sh`（03:30） | 按 `{yyyyMMdd}` 目录整目录删；**唯一删除者** |
| `camera_images`（兼容表） | 30 天 | `DataCleanupTask` 02:00 | DELETE **只删行不删文件** |
| `sys_operation_log` | 6 个月 | `DataCleanupTask` 02:00 | DELETE |
| `fact_production_30min` | 3 个月 | `DataCleanupTask` | 归档到 `_archive` 后删 |
| `fact_daily_line_report` | 不参与 31 天归档 | 随库备份策略 | 单行 <1KB，10 年 <400 万行可接受 |

---

## 五、非功能需求

### 5.1 性能

| 项 | 指标 | 来源 |
|---|---|---|
| 日报列表查询 | **P95 < 500ms**（快照表唯一键查询，无大表扫描） | 日报 PRD |
| 日报首屏 | 打开 Tab **3 秒内**看到「昨天」整页 | 日报 PRD |
| 日报生成任务 | 单日耗时 **< 30s**（现场日增约 1.5k 张） | 日报 PRD |
| 趋势点数 | 跨度 ≥3 天按天分桶（30 天从 720 点降到 31 点） | 19.2 |
| 归档批大小 | 5000 行/批（**小事务避免锁表**） | `app.archive.images.batch-size` |
| 前端体验 | 图片通道无良品样本 → 良率列显示「—」；查询跨归档月份会慢（UNION ALL）属正常 | 使用说明 |

### 5.2 容量

| 档 | 单台日增 | 730 天总量 | 200 台总量 | 磁盘需求 |
|---|---|---|---|---|
| 低（实测 CV2_Cam1 近 7 天） | 7 MB | 1.4 GB | 1.0 TB | 75 GB |
| **设计点（推荐）** | 24 MB | 4.8 GB | **3.5 TB** | 260 GB |
| 高（11-04 生产高峰双机实测） | 67 MB | 13.4 GB | 9.8 TB | 720 GB |

- 单张图片均 **60 KB**（设计点取值）；DB 体积 = 张数 × 5.1 KB（索引已含）；
  binlog（ROW）约 0.1–0.5 GB/天。
- **当前现场**（2026-11-19 实测）：2 工厂 / 6 产线 / 31 工位 / 32 相机（REAL 18、TEST 14），
  缺陷图 41,176 张 / 缺陷框 41,322 个。
  ⚠️ 相机与产线数**随现场接入变化**（TEST 相机每小时清理，产线会调整），引用时以实测为准。
- 硬件建议（200 台设计点）：8 vCPU / 16GB → **64GB ECC**（Java 堆 8GB + MySQL buffer pool 24GB + 页缓存 16GB + OS 16GB），
  DB 必须放 SSD（报表要跨主表与月表 `UNION ALL`，HDD 随机读成瓶颈）。

### 5.3 可用性与可靠性

| 项 | 需求 |
|---|---|
| 服务自愈 | 部署失败**自动回滚**到上一个 JAR（已实测生效：application.yml 重复键导致启动失败 → 自动回滚） |
| 部署后旧页面 | Vite chunk 名带哈希 → 旧标签页懒加载 404 → vue-router 导航**静默中断**。三层修复：前端版本守卫轮询自动刷新一次 + 构建保留 3 版 chunk + 恢复脚本 |
| 定时任务幂等 | 日报/归档/清理重跑安全（DELETE+INSERT 同事务 + 唯一键兜底） |
| 定时任务异常 | Spring `@Scheduled` **会吞掉方法内抛出的异常** → 必须在方法内自己捕获并落告警，否则「任务静默停摆」 |
| 磁盘文件安全 | **先删库后删磁盘**；Java 侧不碰磁盘文件 |
| 备份可恢复 | 全量 + binlog 增量 + 跨盘副本；恢复流程见运维手册 §5.2 |

### 5.4 安全

| 项 | 现状与要求 |
|---|---|
| 8091 暴露 | **不得对外网暴露**（无 nginx/WAF）；只能内网访问或经反向代理 |
| 设备令牌 | 按相机 + SHA-256 存储；**默认 `strict=false` 会放行未配令牌的相机** → 配齐后应置 `true` |
| 设备上传 | **主通道目前只校验 `Authorization` 以 `Bearer ` 开头，不比对令牌**（缺口 1） |
| 图片读取 | `/defect-images/file/**` **免鉴权**（缺口 3）→ 对外暴露需加网关鉴权或改签名 URL |
| 凭据管理 | 唯一密钥源 `/etc/default/camera-detection`（640 root:root），systemd `EnvironmentFile` 注入；**不入仓库** |
| 密码占位符 | `${MYSQL_PASSWORD}` / `${JWT_SECRET}` **绝不能带空默认值** —— 带空默认值 = 服务 active 但业务全错 |
| 授权密钥 | `LICENSE_SECRET` 用环境变量注入；换密钥后已签发授权全部失效 |

### 5.5 兼容性与交付形态

| 项 | 要求 |
|---|---|
| 运行环境 | Ubuntu 24.04，Java 8，MySQL 8.0；Spring Boot 2.7.18 / Vue 3.5 + Vite 5 |
| 交付形态 | 单体 JAR（前端产物打进 `classpath:/static`），**无独立前端进程**；systemd 托管，用户 `cdsys` |
| 统一布局 | 唯一根 `/main/app`（`/main` 是数据盘挂载点）；三个旧路径是 **symlink 不能拆** |
| 端口 | 8091（后端）；前端 dev 3000（仅开发） |
| 浏览器 | Chrome / Edge |
| 交付链 | **只维护 Linux**；Windows 安装链 2026-11-13 已移除 |
| 数据库升级 | 增量补丁幂等可重跑；**`sql/deploy/02_final_schema_p0.sql` 会 DROP 重建 21 张表，生产升级绝不跑全量** |
| 新建库 | `sql/deploy/` 01→05（**05 必须进全新库流程**）；`07` 目前靠启动期 `SchemaInitializer` 幂等补建 |
| CI | 4 个 job 必须全绿：后端构建+测试（JDK 8）/ 前端 build / 静态检查 / 产物一致性 |


---

## 六、需求追溯矩阵

本节是本文档的核心价值：**每一条需求都能落到"代码在哪、怎么验"**，
不用翻代码就能判断"这个需求落地了没有、验收怎么跑"。

### 6.1 需求域 → 实现位置 → 验证手段

| 需求域 | 主要实现位置 | 后端验证 | 前端验证 |
|---|---|---|---|
| **DEV 心跳** | `device/api/CameraStatusHeartbeatController`（1 端点）、`device/app/CameraStatusServiceImpl`（心跳处理 + 告警判定） | `CameraStatusHeartbeatControllerTest`、`CameraHeartbeatCacheKeyTest`、`CameraAlertThrottleTest` | — |
| **DEV 图片上传** | `defect/api/DefectImageController`（7 端点）、`defect/app/DefectImageServiceImpl`（上传解析/建表守卫/**幂等去重**）、`defect/infra/repository/DefectImageRepository` | `DefectImageControllerTest`、`DefectImageUploadValidationTest`、`DefectImageRepositoryCameraSummariesTest`、`DefectImageUploadTokenTest`（令牌校验）、`DefectImageUploadIdempotentTest`（幂等去重） | `docs/scripts/verify_camera_status_browser.cjs` |
| **DEV 批量上报** | `inspection/api/BatchUploadController`、`inspection/app/InspectionUploadService` | `BatchUploadControllerTest`、`InspectionUploadServiceTest` | — |
| **CFG 主数据** | `masterdata/api/*`（30 端点）、`system/api/ConfigImportController`、`system/api/FinalConfigController` | `P3Batch2ContractTest`、`P3Batch3ContractTest`、`CameraTypeContractTest`（相机类型双表一致） | `docs/scripts/verify_camera_type_browser.cjs` |
| **STAT 统计/看板** | `analytics/api/LineAnalyticsController`（23 端点）、`analytics/api/CameraDashboardController`（9 端点，含 `/options` 与 `/{id}`）、`analytics/infra/repository/LineAnalyticsQueryRepository`、`shared/common/PageParams`（分页上限） | `P3Batch4ContractTest`、`P3Batch56ContractTest`、`LineSummaryLineIdsTest`、`LineSummaryMultiLineContractTest`、`DddBaselineContractTest`、`PageParamsLimitContractTest`（分页上限保护） | `docs/scripts/verify_line_analytics_browser.cjs`、`docs/scripts/verify_line_multi_browser.cjs`、`docs/scripts/verify_board_ad_browser.cjs`、`docs/scripts/review_line_analytics.cjs` |
| **RPT 日报** 🟠 | `analytics/api/DailyReportController`（6 端点）、`analytics/infra/repository/DailyReportRepository`（含 `v_daily_line_report_factory` 视图）、`analytics/infra/task/DailyReportGenerationTask`、`sql/deploy/07_daily_report.sql` | `DailyReportControllerTest` | `docs/scripts/verify_daily_report_browser.cjs` |
| **IMG 图像记录** | `defect/api/DefectImageController`（history / camera-summaries / filter-options） | `DefectImageRepositoryCameraSummariesTest` | `docs/scripts/verify_camera_kpi_browser.cjs` |
| **ALM 告警** | `device/app/event/CameraAlertEvent` → `analytics/api/FactAnomalyAlertController`（2 端点）、`analytics/infra/repository/AnomalyAlertRepository`、`analytics/app/listener/UploadFailedAlertListener`（上传失败告警 + 失败频次） | `CameraAlertEventListenerTest`、`AnomalyAlertDedupTest`、`ApiErrorCodeTest`、`UploadFailedAlertTest` | — |
| **GOV 数据治理** | `defect/infra/task/DefectImageArchiveTask`、`system/infra/task/DataCleanupTask`、`installer/linux/scripts/cleanup-*.sh`、`backup-database.sh` | `DefectImageArchiveTest`、`DataCleanupTaskImageTest` | — |
| **MON 服务器监控** | `device/app/ServerMetricsServiceImpl`、`device/api/ServerMetricsController`（1 端点） | `ServerMetricsServiceImplTest`、`ServerMetricsControllerTest` | — |
| **LIC 授权** | `shared/license/*`、`system/api/LicenseController`（4 端点）、`installer/linux/scripts/gen_license.py` | `LicenseServiceTest`、`LicenseSignerTest`（GOLDEN） | `docs/scripts/verify_license_browser.cjs` |
| **IAM 账号权限** | `iam/api/AuthController`、`iam/api/SysUserController`、`iam/api/SysOperationLogController`、`iam/infra/SecurityConfig` | `ClientIpResolverTest`、`LogThrottleTest` | `docs/scripts/verify_login_flow_browser.cjs` |
| **架构守护** | — | `ArchitectureTest`（ArchUnit 三条铁律 + 13 处冻结清单） | — |
| **缓存/工具** | — | `TtlCacheTest` | — |

### 6.2 端点分布（实现面，2026-11-18 实测）

| 域 | 端点数 | 主要 Controller |
|---|---|---|
| analytics | **66** | `LineAnalyticsController`(24)、`StatsController`(14)、`DimTimeBucket30minController`(8)、`CameraDashboardController`(8)、`DailyReportController`(7)、`FactAnomalyAlertController`(3)、`FactProduction30minController`(2) |
| masterdata | **38** | `DimWorkstationController`(7)、`DimProductionLineController`(7)、`DimFactoryController`(7)、`SysHolidayConfigController`(7)、`DimCompanyController`(6)、`CascadeController`(2)、`DimProductBrandController`(1)、`DimShiftController`(1) |
| system | **28** | `FinalConfigController`(15)、`ConfigImportController`(5)、`LicenseController`(5)、`SystemSettingsController`(3) |
| iam | **20** | `SysUserController`(9)、`SysOperationLogController`(7)、`AuthController`(4) |
| defect | **8** | `DefectImageController`(8) |
| device | **8** | `CameraImageController`(4)、`CameraStatusHeartbeatController`(2)、`ServerMetricsController`(2) |
| inspection | **6** | `ProductDetectionHistoryController`(3)、`BatchUploadController`(2)、`FactProductionInfoController`(1) |
| shared | **1** | `SpaFallbackController`（SPA 路由兜底） |
| **合计** | **175** | 接口文档口径 **146**（生成器只统计 mapping 注解） |

### 6.3 需求 → 数据表

| 表 | 承载的需求 | 生命周期 |
|---|---|---|
| `camera_status` | 心跳状态与累计计数（DEV-01~09、STAT-05、MON-） | 逻辑删除 + 归档 |
| `dim_camera` | 相机档案与工位绑定（CFG-01~06） | 逻辑删除 `is_deleted` |
| `defect_image_file/_box/_batch` | 缺陷图片与框（DEV-13~27、RPT 辅口径、STAT 主口径） | 31 天 → 月表 → 730 天 |
| `camera_images` | 兼容镜像表 | 30 天（只删行） |
| `fact_daily_line_report` | 日报快照（RPT-02~07） | 不归档，随库备份 |
| `fact_anomaly_alert` | 全部告警（ALM-01~07） | — |
| `cfg_camera_token` | 设备令牌 | 随相机删除清理 |
| `cfg_camera_defect_mapping` | 缺陷四维映射（DEV-32） | 逻辑停用 |
| `cfg_analytics_dashboard` | 看板模板（STAT-01） | 逻辑删除 |
| `agg_quality_30m` / `agg_defect_type_30m` | 冻结通道聚合表 | 源表恒空，孤儿行自动清 |
| `dim_production_line/_workstation/_factory` | 主数据（CFG-01） | 逻辑删除 |
| `sys_system_setting` | 系统设置（CFG-11） | — |
| `sys_operation_log` | 操作日志（IAM-05） | 6 个月 |
| `fact_inspection_*` | 冻结通道（DEV-28~35） | 现场恒 0 行 |

---


---

## 七、已知遗留与规划中

### 7.1 ⚪ 功能层遗留（有明确影响，建议排期）

| 编号 | 遗留 | 影响 | 建议 | 出处 |
|---|---|---|---|---|
| TODO-01 | **`severity` 筛选只作用于排行/透视/严重度分布，不影响 KPI，且未列入 `ignoredFilters`** | 用户选了 severity 却看不到 KPI 变化，且**无从得知被忽略**（现场 severity 全 HIGH，选了也没差别） | 列入 `ignoredFilters` 或让 KPI 也响应 | 11-17 评审 |
| TODO-02 | `cameraRanking` / `defectRanking` 均 `LIMIT 20` | 现场 6 台有数据的相机够用，产线多时需放宽或分页 | 规模扩大前加分页 | 11-17 评审 |
| TODO-03 | 页面内 `productId` 筛选在图片通道不生效 | 已有「不生效」角标 + `ignoredFilters` 提示，**保留现状**（物理上无法支持） | 保持 | 11-17 评审 |
| TODO-04 | **清理任务无心跳表** | 任务是否执行只能靠日志判断，**静默停摆无法主动告警** | 加任务心跳表 + 超时告警 | README 已知限制 |
| TODO-05 | 日报**推送通道**（企微/邮件）未接 | 结论仍要人登录点开（背景缺口 1 未闭环） | 待验证内容后接 | 日报 PRD D4 |
| TODO-06 | **异常闭环**（责任人/跟进）未做 | 背景缺口 2 未闭环 | 下一期立项 | 日报 PRD §一 |
| TODO-07 | `X-Image-Count` 预检头在 `fact-product/src/main/resources/application.yml` 注释提及但**代码未实现** | 设备端若依赖它会静默失效 | 实现或从注释移除 | 设备接口文档 §8.3 |
| TODO-08 | PRD 放在 `docs/archive/`（按约定 archive=历史稿非规范），但它是**在建**依据；且 PRD §8 列 4 个端点、§13.4 列 6 个（缺 `calendar`/`breakdown`） | 文档位置与内部一致性 | 移出 archive 或明确标注"在建" | 本轮发现 |

### 7.2 ⚪ 技术债（P6，不影响交付）

| 编号 | 遗留 | 规模 | 说明 |
|---|---|---|---|
| TODO-09 | 裸 `Map<String,Object>` 返回 | **56 处** | 约定「新增一律 DTO + 改哪个动哪个」；字段名与前端取值必须**逐字一致** |
| TODO-10 | 前端散落硬编码色值 | **470 处** | 收敛到 `tokens.css` 的 `--color-*`（现 25 个变量）；`<script>` 里是 ECharts 配置，**画布不解析 `var()`** → 只能改 `<style>` 块 |
| TODO-11 | 跨域直接注入 infra（Repository/Mapper） | **13 处** | ArchUnit `FROZEN_CROSS_DOMAIN` 冻结，**只许清零不许新增** |
| TODO-12 | `fastjson 1.2.83` | 1 处依赖 | 历史 CVE，用量小，建议换 Jackson |
| TODO-13 | `installer/linux/scripts/init-database.sh` 未纳入 `sql/deploy/07_daily_report.sql` | 1 步 | 全新建库不跑 07，靠应用启动期 `SchemaInitializer` 幂等补建 —— **能用**，但与「05 必须进全新库流程」的先例不一致 |

### 7.2.1 评审识别出的结构性技术债（P2/P3，2026-11-12 评审清单）

这些**不影响当前运行**，但影响可维护性。摘录影响面最大的：

| 编号 | 遗留 | 位置 | 影响 |
|---|---|---|---|
| TODO-14 | **纯死代码**：DDD P5 搬迁后 Service 侧残留的 SQL 拼装/RowMapper（约 190 行）无任何调用方 | `defect/app/DefectImageServiceImpl.java:720-912` | 两处同步修改必然分叉；**最划算的一处清理** |
| TODO-15 | 三个私有方法与已拼好的 `where`/`params` 全无使用 | `analytics/api/LineAnalyticsController.java`（多处） | 同上 |
| TODO-16 | **单类超 900 行**，Controller 同时承担建表调度、XLSX 导出、缓存、`@Scheduled` 编排 | `LineAnalyticsController`(1543 行)、`StatsController`(900)、`DefectImageRepository`(1108)、`DefectImageServiceImpl`(1106) | 改一处要通读全类 |
| TODO-17 | **8 处空 catch**（`catch (Exception ignored) {}` 完全无日志） | `LineAnalyticsController`、`StatsController`、`InspectionUploadServiceImpl`、`InspectionUploadRepository`、`SystemSettingsRepository`、`AnalyticsAggregationRepository` | 异常被吞 → 现场看到"数据不对"却查不到原因 |
| TODO-18 | `@Transactional` 标在 Controller 方法上（含"先查重再更新"） | `FinalConfigController` 等 10 处 | 下沉 Service；先查后改存在 TOCTOU |
| TODO-19 | 直接 `updateById`/`insert` 请求体映射的实体，客户端可写 `last_heartbeat_at`/`total_count` | `analytics/api/CameraDashboardController` | 改 DTO 白名单字段 |
| TODO-20 | 审计 IP 直接采信 `X-Forwarded-For`，与 `ClientIpResolver`「默认不信 XFF」相反 | `iam/.../OperationLogAspect` | 复用 `ClientIpResolver` |
| TODO-21 | `SUM(ok)/SUM(total)` 两端 BIGINT → **整数除法**把 `yield_rate` 恒写 0 | `AnalyticsAggregationRepository`、`InspectionUploadRepository` | 改 `* 1.0` 或 `CAST AS DECIMAL` |
| TODO-22 | 硬编码散点（班次 8/20、`companyId=1`、保留期 30/3/6 月、良率 97、`expiresIn=86400`） | 多处 | 现场调整需改代码重发版；班次逻辑与 `ShiftCalculator` 口径各写一遍 → 抽常量/配置项 |

> ⚠️ 上述多数已在后续轮次部分处理（如 `fastjson` 已移除、`global.css` 死文件已处理、记忆失真已修）。
> 引用前请以**当前代码**为准 —— 本表是评审时点的快照。

### 7.3 🔴 现场数据事实（不是 bug，是现实）

这些不是需求缺陷，是现场现状 —— 写进来是为了**别把它们当成系统故障去查**：

| 现象 | 事实 |
|---|---|
| **所有缺陷严重度都是 HIGH** | 设备端上报即如此（服务端 `normalizeSeverity` 逻辑正确，能识别 HIGH/严重/高、MEDIUM/中、其余 LOW）→ **高危占比指标当前无区分度**，是设备/算法侧的问题 |
| 两台真实相机的 `total_count` 几乎相同（差 1） | 两台是**同一物理相机的两路输出**（`product_name` 同为「宝洁_小青片_M码」），共享检测计数基数 —— **不是**缓存串号 |
| 3 台相机完全无心跳 | `last_heartbeat_at` 为 NULL，配置在册但设备未接入 |
| 某产线当日 0 上传 | 可能停线/断网，**也可能只是没排产**（如凌晨时段）→ 页面明确写「只能当线索，不当结论」 |
| 图片口径缺陷率恒 1000‰ | 每张必带缺陷框（分母=分子）→ 已改用计数日增差分作主口径 |
| 现场有 2 个同名旧看板模板 | 与内置「工厂质量看板（默认）」并列容易混 → 建议归档其一 |

### 7.4 🟠 在建未提交（交付状态提醒）

| 项 | 状态 |
|---|---|
| 产线统计日报全套（后端 6 新文件 + DDL + 测试）、产线统计首屏重构（前端 4 文件）、3 个浏览器脚本、PRD | **已部署现场**（11-17 两次部署，verify.sh 42/42），**代码未提交** |
| `147d021` 相机原位复活 + 统计补产线维 | 已推送 |
| 本文（需求 PRD 总纲） | 见 git 记录 |

---

## 八、给下一位接手人的三句话

1. **改任何代码前先读 [`踩坑速查.md`](踩坑速查.md)**（20 节真实事故）—— 本仓库的坑大多"看起来是需求问题、实际是实现细节错了"。
2. **相机身份永远是 `camera_code`**。现场相机名称跨产线重名，前端有 `utils/camera-identity.js` 兜底，后端一律 code-first；用名称匹配会错配数据。
3. **数字要诚实**：算不出来就显示「— / 待积累 / 未接入」，不支持的筛选要显式上报。**静默吞掉筛选比算错更伤信任。**

