# 产线缺陷在线AI检测系统前端设计规范

> 版本：v1.0  
> 生成依据：当前前端项目真实代码，重点读取 `package.json`、`vite.config.js`、`src/router/index.js`、`src/components/**`、`src/views/**`、`src/styles/global.css`、`src/App.vue`、`src/main.js`。  
> 适用范围：`D:\45344\0004-软件源码\frontend` 前端工程。  
> 重要说明：本文只基于当前代码归纳设计规范；代码无法确认的内容统一标注为“待确认”。

---

## 1. 项目整体视觉风格

### 1.1 设计定位

当前界面定位为工业产线监控类后台系统，视觉风格接近 Ant Design / Element Plus 后台管理系统：

- **信息密度中高**：大量使用表格、KPI 卡片、筛选器、图表和分页。
- **数据优先**：页面首屏通常先展示统计卡片、状态指标，再展示表格或图表。
- **蓝色主导**：导航激活、筛选提示、主要操作按钮、链接统一倾向蓝色。
- **白底卡片容器**：页面背景为浅灰，内容用白色卡片承载，配合圆角与轻阴影。
- **状态色强区分**：运行/正常使用绿色，警告/延迟使用橙黄，故障/异常使用红色，离线/禁用使用灰色。
- **工业监控语义**：大量使用“相机、产线、工位、缺陷、良率、上传、心跳”等业务状态，并通过数字、徽标、标签突出风险。

### 1.2 当前主要界面模块

来自 `src/router/index.js` 和页面文件：

| 模块 | 页面/组件 | 设计特征 |
|---|---|---|
| 登录注册 | `views/auth/Login.vue`、`Register.vue` | 深色/渐变背景，居中卡片，大按钮表单 |
| 全局布局 | `components/Layout.vue` | 固定顶部筛选区 + 固定 Tab 导航 + 内容容器 |
| 相机状态 | `views/camera/CameraStatusView.vue` | KPI 栅格 + 搜索/筛选/排序 + 大表格 + 图片预览弹窗 |
| 图像记录 | `components/CameraImageRecords.vue`、`views/stats/ImagesView.vue` | 相机卡片网格、缩略图、历史表格、图片弹窗 |
| 产线总览 | `views/Dashboard.vue` | KPI、图表面板、告警、表格混排 |
| 缺陷/良率统计 | `views/stats/DefectsView.vue`、`YieldView.vue` | KPI + 图表面板 + 数据表格 |
| 系统配置 | `views/system/ConfigView.vue` | 左侧层级树 + 子导航 + 右侧配置表格/导入弹窗 |
| 基础管理页 | 工厂/产线/工位/用户/品牌/缺陷分类等 | 头部操作 + 筛选卡片 + 表格 + 分页 + 表单弹窗 |

---

## 2. 技术栈与样式方案

### 2.1 技术栈

来自 `package.json`：

| 类型 | 技术 | 当前版本 |
|---|---|---|
| 前端框架 | Vue | `^3.5.34` |
| 构建工具 | Vite | `^5.2.0` |
| 路由 | Vue Router | `^4.6.4` |
| 状态管理 | Pinia | `^3.0.4` |
| UI 组件库 | Element Plus | `^2.7.0` |
| 图表 | ECharts | `^5.6.0` |
| HTTP | Axios | `^1.16.1` |
| 图标 | `@element-plus/icons-vue` + 内联 SVG + Emoji | `^2.3.1` |

### 2.2 样式组织方式

当前代码中的样式方案：

1. **Element Plus 原生样式**  
   `src/main.js` 中引入：
   ```js
   import 'element-plus/dist/index.css'
   ```

2. **Vue 单文件组件内 scoped 样式**  
   多数页面和组件使用 `<style scoped>`，例如：
   - `components/Layout.vue`
   - `views/camera/CameraStatusView.vue`
   - `views/system/ConfigView.vue`
   - `components/CameraImageRecords.vue`

3. **App 级基础样式**  
   `src/App.vue` 中设置全局 reset、字体、背景：
   ```css
   * { margin: 0; padding: 0; box-sizing: border-box; }
   body {
     font-family: 'Microsoft YaHei', 'PingFang SC', sans-serif;
     background: #f0f2f5;
     color: #1a1a2e;
   }
   ```

4. **存在但未确认生效的全局规范文件**  
   `src/styles/global.css` 存在完整 CSS 变量、Element Plus 主题覆盖、工具类和响应式规则，但当前 `src/main.js` 未引入该文件，代码检索也未发现其他引用。  
   因此该文件中的 token 可作为“现有设计草稿/待接入规范”，不能视为当前页面一定生效的全局样式。

### 2.3 Tailwind / SCSS / Less / Theme 配置

| 项 | 当前代码结论 |
|---|---|
| Tailwind | 未发现 `tailwind.config.*`，未发现 Tailwind 依赖，待确认是否计划引入 |
| SCSS/Less | 未发现 `.scss` / `.less` 文件，当前主要为 CSS 和 Vue scoped CSS |
| Element Plus Theme | `src/styles/global.css` 有 `--el-color-primary` 等变量覆盖，但未被引入，生效状态待确认 |
| Vite Alias | `@` 指向 `src`，来自 `vite.config.js` |
| 本地代理 | `/api` 代理到 `http://localhost:8091`，来自 `vite.config.js` |

---

## 3. 色彩系统

> 说明：色彩来自当前实际 CSS 与样式统计。当前项目同时存在 Tailwind 风格色值、Element Plus 默认色值、Ant Design 风格变量，存在一定不一致，后续建议收敛。

### 3.1 品牌主色 / 链接色

| Token 建议 | 当前常用色值 | 使用场景 | 代码来源 |
|---|---:|---|---|
| Primary / Link | `#2563eb` | Tab 激活、链接、筛选提示、配置页导航、表格 hover | `Layout.vue`、`ConfigView.vue`、`CameraStatusView.vue` |
| Primary Strong | `#3b82f6` | 系统配置按钮、徽标、KPI 蓝色数值 | `Layout.vue`、`Dashboard.vue` |
| Primary Draft | `#1677FF` | `global.css` 中定义的主色，待确认是否接入 | `src/styles/global.css` |
| Primary Hover | `#1d4ed8`、`#4096FF` | 自定义按钮 hover / global.css 变量 | `Layout.vue`、`global.css` |
| Primary Pale BG | `#eff6ff`、`#dbeafe`、`#e0f2fe` | 筛选提示、导航 hover、低强调标签 | 多处页面 |

### 3.2 背景与容器色

| Token 建议 | 当前色值 | 使用场景 |
|---|---:|---|
| Page Background | `#f0f2f5` | `App.vue` body 背景 |
| Page Background Alt | `#f5f5f5` | 旧统计页背景、`global.css` |
| Surface / Card | `#fff` / `#FFFFFF` | 卡片、表格容器、顶部栏、配置内容 |
| Surface Subtle | `#f8fafc` | 表头、卡片内浅背景、预览失败背景 |
| Surface Divider | `#f1f5f9`、`#f0f0f0` | 分割线、历史记录分隔、面板边界 |
| Dark Preview BG | `#0f172a` | 图片预览背景、登录页深色背景的一部分 |

### 3.3 文本色

| Token 建议 | 当前色值 | 使用场景 |
|---|---:|---|
| Text Primary | `#111827`、`#0f172a`、`#1a1a2e`、`#1f1f1f` | 标题、KPI 主数字、正文主信息 |
| Text Regular | `#262626`、`#334155` | 面板标题、预览标题 |
| Text Secondary | `#64748b`、`#6b7280`、`#737373`、`#8c8c8c`、`#909399` | 描述、辅助信息、表格弱信息 |
| Text Muted | `#94a3b8`、`#9ca3af`、`#BFBFBF` | 空状态、占位、暂无 |
| Text Inverse | `#fff` | 深色背景按钮、缩略图严重度浮标 |

### 3.4 语义色

| 语义 | 主要色值 | 背景色 | 当前使用 |
|---|---:|---:|---|
| 成功 / 运行 / OK | `#15803d`、`#16a34a`、`#22c55e`、`#52C41A`、`#67c23a` | `#dcfce7`、`#F6FFED` | 运行中、OK数、正常指标、KPI 成功 |
| 警告 / 延迟 / 待机 | `#b45309`、`#d97706`、`#f59e0b`、`#FA8C16`、`#e6a23c` | `#fef3c7`、`#fffbeb`、`#FFF7E6` | 待机、延迟、WARNING、导入警告 |
| 危险 / 故障 / 缺陷 | `#b91c1c`、`#dc2626`、`#ef4444`、`#FF4D4F`、`#f56c6c` | `#fee2e2`、`#fef2f2`、`#FFF1F0` | 故障、缺陷数、高危缺陷、错误消息 |
| 离线 / 停用 / 中性 | `#475569`、`#64748b`、`#909399`、`#303133` | `#f1f5f9` | 离线、停止、暂无、禁用类状态 |

### 3.5 色彩维护建议

当前色值存在多套体系并存：

- Tailwind 风格：`#2563eb`、`#64748b`、`#111827`
- Element Plus 默认：`#409eff`、`#67c23a`、`#f56c6c`
- Ant Design 风格：`#1677FF`、`#52C41A`、`#FF4D4F`

建议后续统一为一套 token，并优先将 `src/styles/global.css` 接入或拆分为正式主题文件。当前未接入前，不建议新增更多散落色值。

---

## 4. 字体与排版

### 4.1 字体族

当前实际全局字体来自 `src/App.vue`：

```css
font-family: 'Microsoft YaHei', 'PingFang SC', sans-serif;
```

`src/styles/global.css` 中还定义了更完整的字体栈，但未确认生效：

```css
-apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif
```

### 4.2 字号层级

当前代码中常见字号：`11px`、`12px`、`13px`、`14px`、`15px`、`16px`、`17px`、`18px`、`20px`、`22px`、`24px`、`28px`、`32px`、`34px`、`36px`、`40px`、`64px`。

建议按当前代码归纳为以下层级：

| 层级 | 字号 | 字重 | 使用场景 | 代码示例来源 |
|---|---:|---:|---|---|
| Display / 大数字 | `34px` / `36px` | `700` / `800` | 产线总览、缺陷/良率 KPI 大数字 | `Dashboard.vue`、`DefectsView.vue`、`YieldView.vue` |
| KPI 数值 | `28px` | `700` | 相机状态 KPI | `CameraStatusView.vue` |
| 页面主标题 | `22px` | `700` | 看板页面标题、相机状态标题 | `Dashboard.vue`、`CameraStatusView.vue` |
| 管理页标题 | `20px` / `18px` | `600` | 基础管理页、配置页标题 | `CompanyList.vue`、`FactoryList.vue` |
| 面板标题 | `16px` | `600` / `700` | 图表面板、表格标题、配置弹层标题 | 统计页、`Dashboard.vue` |
| 强调文本 | `15px` | `600` / `700` | 表格标题、相机名称、状态标题 | `CameraImageRecords.vue`、`CameraTable.vue` |
| 正文/控件 | `14px` | `400` / `500` | Element Plus 默认文本、表单、按钮 | Element Plus 默认 + 多页面 |
| 辅助说明 | `13px` | `400` / `600` | 描述文案、筛选提示、表单 label | 多页面 |
| 小标签/状态 | `12px` | `600` / `700` / `800` | 状态 pill、表格弱信息、缩略图标签 | 多页面 |
| 微型标识 | `10px` / `11px` | `400` / `700` | 顶部筛选数量、版本号、图片浮标 | `Layout.vue`、`Login.vue` |

### 4.3 文本规则

- 标题通常使用 `font-weight: 700` 或 `600`。
- KPI 数字使用 `font-weight: 700/800`，需要颜色强调。
- 辅助文本倾向 `#64748b` / `#6b7280`，字号 `13px`。
- 状态徽标与 pill 使用 `12px`，字重 `700/800`，提高识别度。
- 表格中长文本使用 `show-overflow-tooltip` 或 CSS `text-overflow: ellipsis`。
- 数字等宽字体规范仅在 `global.css` 中定义，实际页面未统一接入，待确认。

---

## 5. 间距规范

### 5.1 基准

`src/styles/global.css` 中以 8px 为基准定义了间距变量，但未确认生效。实际页面中广泛使用 4、6、8、10、12、14、16、18、20、24、32、40、48 等间距。

建议采用 **4px 最小单位 + 8px 主节奏**：

| Token 建议 | 值 | 当前使用场景 |
|---|---:|---|
| `space-1` | `4px` | 标签内细节间距、徽标 margin |
| `space-2` | `8px` | 按钮组 gap、表单 label 间距、信息项 gap |
| `space-3` | `10px` / `12px` | 工具栏 gap、配置树项、状态徽标 padding |
| `space-4` | `14px` / `16px` | 卡片内边距、页面模块间距、筛选区间距 |
| `space-5` | `18px` / `20px` | 面板头部、页面 padding、卡片大内边距 |
| `space-6` | `24px` | 主内容区 padding、配置页右侧内容 padding |
| `space-7` | `32px` | 登录/弹窗/空状态较大留白 |
| `space-8` | `40px` / `48px` | 登录卡片内边距、认证页布局 |

### 5.2 页面级间距

| 场景 | 当前规范 |
|---|---|
| 主布局内容区 | `.cnt`：`max-width: 1440px; padding: 24px 20px; margin: 0 auto` |
| 1280 以下 | `.cnt`：`padding: 16px 12px` |
| 基础管理页 | `padding: 20px` 或 `.page-container { padding: 0 }` 后由全局布局提供外边距 |
| 配置页 | 右侧 `.config-content`：`padding: 24px`，900 以下为 `16px` |
| 卡片间距 | `gap: 12px/14px/16px/20px`，视页面复杂度而定 |
| 表格与分页 | 表格下方分页通常 `margin-top: 14px/16px` 或 `padding-top: 14px` |

---

## 6. 圆角、边框、阴影

### 6.1 圆角系统

| Token 建议 | 当前值 | 使用场景 |
|---|---:|---|
| Radius XS | `4px` | 缩略图严重度浮标、小图占位 |
| Radius SM | `6px` | 系统配置按钮、历史缩略图、Element Plus 基础圆角 |
| Radius MD | `8px` | 标准管理页容器、图片卡片、错误消息、普通卡片 |
| Radius LG | `10px` | 状态卡、小按钮、表格 action 容器 |
| Radius XL | `12px` | KPI 卡片、图表面板、图片记录卡片、空状态 |
| Radius XXL | `14px` | 系统配置大容器、导入区域、摘要卡片 |
| Radius Auth | `16px` / `20px` | 注册卡片、登录卡片、登录 logo |
| Radius Pill | `999px` | 状态徽标、筛选提示、配置数量、操作按钮组 |

### 6.2 边框

当前常用边框：

| 场景 | 色值 |
|---|---:|
| 标准浅边框 | `#e5e7eb` |
| 顶部栏/导航边框 | `#e5e5e5` |
| 面板分割线 | `#f1f5f9` / `#f0f0f0` |
| 表格/管理页边框 | Element Plus 默认 + `border` 属性 |
| 空状态虚线边框 | `#dbe3ef` |
| 错误状态边框 | `#fecaca` / `#fed7aa` |
| 配置导入提示边框 | `#e0f2fe` |

### 6.3 阴影

| 层级 | 当前值 | 使用场景 |
|---|---|---|
| Shadow 1 | `0 1px 2px rgba(0,0,0,0.05)` | 旧统计卡片、图表卡片 |
| Shadow 2 | `0 1px 3px rgba(0,0,0,.06)` | 看板 KPI、面板 |
| Shadow 3 | `0 1px 3px rgba(15,23,42,.05)` | 图像记录相机卡片 |
| Shadow 4 | `0 2px 8px rgba(0,0,0,0.06)` | 状态卡、相机列表表格卡片 |
| Shadow Sticky | `0 2px 10px rgba(15,23,42,.06)` | 顶部固定栏 |
| Shadow Hover | `0 8px 20px rgba(15,23,42,.08)` | 图像记录卡片 hover |
| Shadow Modal/Auth | `0 20px/25px 60px rgba(...)` | 登录、注册卡片 |

---

## 7. 页面布局规范

### 7.1 全局框架

来自 `components/Layout.vue`：

```text
.app
└─ .topbar-sticky  position: sticky; top: 0; z-index: 200
   ├─ header.hdr   52px 高，白底，工厂/产线/工位筛选
   └─ nav.tb       5 个业务 Tab
└─ main.cnt        max-width: 1440px; padding: 24px 20px
```

当前 5 个顶部 Tab 顺序：

1. 相机状态
2. 图像记录
3. 产线总览
4. 缺陷统计
5. 良率分析

设计规则：

- 顶部栏固定在页面顶部，滚动内容时保持可见。
- 工厂/产线/工位筛选作为全局上下文，应置于顶部中间区域。
- Tab 使用 `router-link`，激活态蓝色文字 + 蓝色底部边线。
- 主内容区统一限制最大宽度 `1440px`，避免大屏过宽。

### 7.2 看板页布局

来自 `Dashboard.vue`、`CameraStatusView.vue`、`DefectsView.vue`、`YieldView.vue`：

- 页面头部：标题 + 描述/筛选提示 + 右侧操作。
- KPI 区：栅格展示，4 或 6 列。
- 内容区：图表面板、表格面板、告警卡片组合。
- 面板容器：白底、`12px` 圆角、轻阴影、头部 `16px 20px`。

典型结构：

```text
.page / .camera-status-page / .overview
├─ .page-header / .page-head / .page-intro
├─ .kpi-row / .kpi-grid
├─ .panel-grid / .grid-3
└─ .table-card / .panel
```

### 7.3 系统配置布局

来自 `views/system/ConfigView.vue`：

- 外层：`.sys-config { max-width: 1320px; margin: 0 auto; }`
- 主容器：`.config-layout` 白底、`14px` 圆角、轻阴影、隐藏溢出。
- 左侧：`.config-sidebar` 固定宽度 `310px`，用于配置层级树和子导航。
- 右侧：`.config-content` 弹性区域，`padding: 24px`。
- 900px 以下改为上下布局。

### 7.4 基础管理页布局

来自工厂、产线、工位、用户、品牌、缺陷分类、操作日志等页面：

```text
.page-container / .company-list / .brand-list / .operation-log
├─ .page-header：标题 + 新增/清空按钮
├─ .filter-card 或 .filter-bar：搜索、筛选、重置
├─ .table-card 或 el-table：数据表格
└─ .pagination-wrap / .pagination-wrapper：右对齐分页
```

设计规则：

- 管理页标题字号通常为 `18px` 或 `20px`。
- 筛选区距离表格 `16px`。
- 分页统一右对齐。
- 新增/编辑使用 `el-dialog`，宽度常见 `520px`。

---

## 8. 响应式设计规则

### 8.1 当前断点

当前代码中实际出现的断点：

| 断点 | 使用场景 |
|---:|---|
| `1280px` | 顶部栏换行、KPI 栅格降列、图表面板单列、相机状态页响应式 |
| `1100px` | 系统配置摘要卡 7 列降为 3 列 |
| `900px` | 系统配置左右布局改上下布局 |
| `768px` | 状态卡 5 列降为 2 列；旧全局响应式草稿中也有平板规则 |
| `576px` | 仅 `global.css` 中定义，当前未确认生效 |
| `992px` | 仅 `global.css` 中定义，当前未确认生效 |

### 8.2 响应式规则

- **1280px 以下**：
  - 顶部筛选栏允许换行。
  - 主内容左右 padding 从 `24px 20px` 降至 `16px 12px`。
  - KPI 从 6 列降为 2/3 列。
  - 双列/三列图表面板降为单列。
  - 相机图片预览信息从 2 列降为 1 列。

- **900px 以下**：
  - 系统配置页从左树右表改为上下结构。
  - 配置导航从纵向改为横向换行。
  - 操作按钮区允许换行到下一行。

- **当前待确认项**：
  - `body { min-width: 1280px; }` 只存在于未引入的 `src/styles/global.css`，当前是否需要强制最小宽度待确认。
  - 移动端 576/768/992 断点的全局规则未确认生效。

---

## 9. 常用组件样式规范

## 10. 按钮、输入框、表格、卡片、弹窗、导航等组件说明

> 本章合并说明常用组件规范，均来自当前 Element Plus 用法和自定义 CSS。

### 10.1 按钮 Button

当前按钮来源：`el-button`、自定义 `.syscfg-btn`、配置页 `.action-btn`。

| 类型 | 当前样式/行为 | 使用场景 |
|---|---|---|
| 主按钮 | `el-button type="primary"`，默认 Element Plus 样式；部分页面有 `:loading` | 查询、新增、提交、刷新 |
| 次按钮 | 普通 `el-button` | 重置、取消 |
| 文本按钮 | `el-button link type="primary/danger" size="small"` | 表格行内编辑、删除、查看 |
| 顶部系统配置按钮 | `.syscfg-btn`：蓝底白字、`7px 16px`、圆角 `6px`，hover 深蓝 | 顶部进入系统配置 |
| 配置页行内按钮 | `.action-btn`：胶囊形，编辑蓝底浅色、删除红底浅色，hover 反色 | 系统配置表格操作 |

交互规则：

- 主按钮 hover 应加深或变亮蓝色。
- 危险操作使用红色文本或红色胶囊按钮。
- 删除操作应保留二次确认，当前系统配置页已有依赖校验和风险提示相关逻辑。
- Loading 状态使用 Element Plus `:loading`。
- Disabled 样式主要依赖 Element Plus 默认，未发现统一自定义 disabled 样式，待确认。

### 10.2 输入框 / 选择器 Input & Select

当前常用模式：

- 搜索输入：`el-input clearable`，常带 placeholder，如“搜索相机名称、设备编号、工位、故障信息”。
- 筛选下拉：`el-select clearable` 或固定状态筛选。
- 顶部全局筛选：工厂单选、产线/工位多选，`collapse-tags`、`collapse-tags-tooltip`。
- 表单输入：`el-form` + `el-form-item` + `el-input`，弹窗内宽度通常 `100%`。

常见宽度：

| 场景 | 宽度 |
|---|---:|
| 顶部工厂选择 | `200px` |
| 顶部产线/工位多选 | `240px` |
| 相机状态搜索框 | `340px`，`max-width: 100%` |
| 相机状态状态筛选 | `132px` |
| 相机列表搜索 | `260px` |
| 普通筛选 select | `120px` / `150px` |
| 表单字段 | `100%` |

### 10.3 表格 Table

当前表格大量使用 Element Plus：`el-table`、`el-table-column`，常见属性：

- `stripe`
- `border`
- `v-loading`
- `show-overflow-tooltip`
- `fixed="right"` 操作列
- `align="center"` 或 `align="right"` 用于状态/数字列

表格设计规则：

| 区域 | 当前规范 |
|---|---|
| 表头 | 相机状态表头覆盖为 `#f8fafc`，`#475569`，字重 `700` |
| 行高/内边距 | 相机状态单元格 `padding: 10px 0`；全局草稿中为 `12px 16px` |
| Hover | 多个页面使用 `#eff6ff` 作为表格行 hover 背景 |
| 操作列 | 固定右侧，通常 100–180px |
| 数字列 | 常用右对齐或居中；数字等宽未统一生效，待确认 |
| 空数据 | 部分使用 Element Plus 默认，图像记录使用 `EmptyState` |

分页规则：

- 当前项目已广泛使用 `[5, 10, 20, 50]`。
- 表格分页布局常见：`total, sizes, prev, pager, next` 或带 `jumper`。
- 分页容器右对齐，复杂表格可加提示文案。

### 10.4 卡片 Card / Panel

当前卡片形态：

| 类型 | 样式 |
|---|---|
| KPI 卡片 | 白底，`12px` 圆角，`16–24px` padding，轻阴影，数字大字号 |
| 图表面板 | 白底，`8/12px` 圆角，`16px` padding 或头/body 分区 |
| 图像记录卡片 | 白底，`12px` 圆角，`1px #e5e7eb` 边框，hover 增强阴影 |
| 配置摘要卡 | `#f8fafc` 背景，`14px` 圆角，`1px #eef2f7` 边框 |
| 登录/注册卡片 | 大圆角、大阴影、居中布局 |

面板头部规范：

- `panel-head`：`padding: 16px 20px`，字号 `16px`，字重 `600/700`，底部分割线。
- 标题与描述分层：标题深色、描述 `13px` 灰色。

### 10.5 弹窗 Dialog

当前弹窗主要使用 `el-dialog`：

| 场景 | 宽度 |
|---|---:|
| 新增/编辑配置 | `520px` 左右 |
| 图片预览 | `820px` |
| 图片相册 | `700px` / `750px` |
| 缺陷详情 | `720px` |

弹窗规则：

- 图片预览弹窗使用深色背景承载图片：`#0f172a`。
- 图片加载失败显示产品化提示、处理建议和检查清单。
- 表单弹窗底部使用“取消 + 提交”结构，提交按钮使用 loading。
- 高风险删除/导入需要说明风险；系统配置页已有风险提示样式。

### 10.6 导航 Navigation

#### 顶部导航

来自 `Layout.vue`：

- `.topbar-sticky` 固定在顶部。
- `.hdr` 高度 `52px`，白底，底部边框。
- `.tb-item` padding `14px 28px`，字号 `15px`。
- hover：蓝色文字 + `#f8fafc` 背景。
- active：蓝色文字 + 底部 `3px` 蓝色边线 + `font-weight: 600`。

#### 系统配置侧边导航

来自 `ConfigView.vue`：

- `.config-sidebar` 宽 `310px`。
- `.nav-item` 胶囊/左边线复合形态。
- hover 和 active 均使用 `#eff6ff` 背景、`#2563eb` 文字。
- active 额外有左侧 `3px` 蓝色边线和加粗。

### 10.7 状态标签 / Badge / Pill

状态视觉规律：

- 使用 `el-tag` 作为标准标签。
- 自定义 pill 使用 `display: inline-flex`、`border-radius: 999px`、字号 `12px`、字重 `700/800`。
- 状态点 `.st-dot` 宽高 `6px` 或旧状态卡 `12px`。

常见状态：

| 状态 | 文案 | 样式倾向 |
|---|---|---|
| 运行 | 运行/运行中 | 绿字 + 绿浅底 |
| 停止 | 停止/已停止 | 灰字 + 灰浅底 |
| 故障 | 故障 | 红字 + 红浅底 |
| 待机 | 待机 | 橙字 + 黄浅底 |
| 离线 | 离线 | 灰字 + 灰浅底 |
| 严重度高 | 高 | 红色 |
| 严重度中 | 中 | 橙色 |
| 严重度低 | 低 | 蓝色 |

### 10.8 图表 Chart

当前图表使用 ECharts：

- 图表容器类：`.chart`、`.chart-box`、`.chart-container`。
- 高度常见：`300px`、`320px`、`330px`、`350px`、`360px`、`400px`。
- 图表卡片：白底、`8px/12px` 圆角、轻阴影。
- ECharts 色值当前使用：
  - 正确识别：`#67c23a`
  - 异常识别：`#f56c6c`
  - 相机状态饼图：运行绿、停止灰、故障红、待机黄、离线黑灰。
- resize：图表组件监听 `window.resize` 并 dispose。

---

## 11. 交互状态：hover、active、disabled、focus、loading、error

### 11.1 Hover

当前代码中明确存在的 hover：

| 组件 | Hover 行为 |
|---|---|
| 顶部 Tab | 文字蓝色，背景 `#f8fafc` |
| 系统配置按钮 | 背景由 `#3b82f6` 变 `#1d4ed8` |
| 系统配置导航 | 背景 `#eff6ff`，文字蓝色 |
| 配置表格操作按钮 | 编辑/删除按钮 hover 变实色，文字变白 |
| 表格行 | 背景 `#eff6ff` |
| 图像记录卡片 | 阴影增强 |
| 图像记录卡片头 | 背景 `#f8fafc` |
| 缩略图 | 图片 `scale(1.08)` |
| 状态卡 | `translateY(-2px)` |
| 缺陷图库图片卡 | 阴影增强 |

### 11.2 Active / Selected

| 组件 | Active 行为 |
|---|---|
| 顶部 Tab | 蓝色文字 + 蓝色底边 + 加粗 |
| 配置侧边导航 | 蓝色文字 + `#eff6ff` 背景 + 左边线 |
| 配置树当前节点 | `#eff6ff` 背景 + 蓝色文字 |
| 路由守卫 | 未登录跳登录；已登录访问登录页跳相机状态 |

### 11.3 Disabled

当前业务代码中有按钮 `disabled` 使用场景，例如操作日志“清空日志”在无数据时禁用；Element Plus 负责默认 disabled 样式。  
未发现全局自定义 disabled 样式，待确认是否需要统一。

### 11.4 Focus

`src/styles/global.css` 中存在：

```css
:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
}
```

但该文件未确认引入，因此当前实际 focus 样式主要依赖浏览器和 Element Plus 默认。建议后续正式接入可访问性 focus token。

### 11.5 Loading

当前 loading 来源：

- 表格：`v-loading="loading"`
- 按钮：`:loading="loading"` / `submitLoading` / `pwdLoading`
- 空状态：`EmptyState` 支持 `type="loading"`
- `global.css` 中存在 `.loading-state` 和 `.loading-spinner`，但未确认生效。

### 11.6 Error / Warning

当前错误状态：

| 场景 | 样式 |
|---|---|
| 登录错误 | `.error-msg`：红浅底、红边框、shake 动画 |
| 注册错误 | `el-alert type="error"` |
| 图片解析失败 | 图片预览中显示原因、建议、检查清单 |
| 空状态错误 | `EmptyState type="error"`：橙色背景/边框 |
| 导入错误/警告 | `.result-list.error/warn` 红/黄浅底 + 边框 |
| 表格故障信息 | `.fault-text` 红字 + 红浅底 pill |

---

## 12. 图标、插画、空状态、错误状态规范

### 12.1 图标来源

当前项目图标来源混合：

1. **Emoji**：大量用于产品 logo、页面标题、按钮和状态，如 `📷`、`🖼️`、`👤`、`➕`、`🔍`、`⚠️`。
2. **Element Plus Icons**：如 `Setting`、`Refresh`、`Search` 等。
3. **内联 SVG**：顶部 5 个业务 Tab 使用 inline SVG。
4. **图片缩略图**：缺陷图像记录、图库、预览。

建议：

- 导航和功能按钮优先使用统一图标体系，Emoji 保留在标题/空状态中需谨慎。
- 对纯装饰性图标应避免影响读屏；当前待确认。
- 内联 SVG 需要统一尺寸，目前顶部 Tab 为 `20x20`。

### 12.2 空状态

当前已有 `components/EmptyState.vue`：

- 默认类型：`empty`
- 支持类型：`empty`、`error`、`loading`
- 默认图标：`📭`、`⚠️`、`⏳`
- 容器：`min-height: 180px`、`padding: 44px 20px`、虚线边框、`12px` 圆角。

使用规则：

- 无数据列表/图像记录优先使用 `EmptyState`。
- 错误状态应传入 `type="error"`，并说明用户可执行动作。
- 加载状态如需占位可传入 `type="loading"`，但当前更多使用 Element Plus `v-loading`。

### 12.3 图片异常状态

来自 `CameraStatusView.vue`、`CameraImageRecords.vue`：

- 图片 URL 不存在、加载失败、尺寸异常时展示产品化文案。
- 图片失败区域使用浅灰背景、虚线边框或浅色容器。
- 预览弹窗中需要展示：失败原因、处理建议、检查清单。
- 缩略图失败时显示“加载失败”或“无图”。

---

## 13. 可访问性建议

> 当前代码中已部分具备可访问性基础，但仍需要加强。以下建议基于当前实现。

### 13.1 已有基础

- `index.html` 设置 `lang="zh-CN"`。
- 多数图片设置了 `alt`，如缺陷图、最近图像。
- 表单使用 Element Plus `el-form` 和校验规则。
- 导航使用 `router-link`，语义优于纯按钮。
- `global.css` 有 `:focus-visible` 和 `.sr-only`，但未确认生效。

### 13.2 建议补强

1. **接入统一 focus 样式**  
   当前 focus 样式只在未确认生效的 `global.css` 中，建议正式引入或迁移到 `App.vue`。

2. **减少纯 Emoji 承担语义**  
   Emoji 可能被读屏读出不一致，建议功能图标用 Element Plus Icons 或 SVG，并补充文本。

3. **颜色不要作为唯一信息来源**  
   当前严重度除颜色外已有“高/中/低”文案，符合要求；后续继续保持。

4. **表格操作按钮应有明确文本**  
   当前“编辑/删除/查看”有文本，符合要求。

5. **弹窗焦点管理**  
   Element Plus 默认具备弹窗基础能力，但业务弹窗打开后的初始焦点和关闭后焦点恢复未在代码中显式确认，待确认。

6. **响应式和键盘可用性**  
   图像卡片 `.cam-hd` 当前为 `div` + click，键盘可达性待确认；建议改为 button 或增加 `role`/`tabindex`/键盘事件。

---

## 14. 后续维护建议

### 14.1 建立正式 Design Token

当前最大问题是色值和样式散落。建议将以下 token 固化：

- 颜色：Primary、Success、Warning、Danger、Text、Border、Surface。
- 字号：12、13、14、16、18、20、22、28、36。
- 间距：4、8、12、16、20、24、32、40。
- 圆角：4、6、8、10、12、14、999。
- 阴影：card、panel、hover、sticky、auth。

### 14.2 明确 `src/styles/global.css` 的角色

当前 `global.css` 内容很完整，但未引入。建议二选一：

1. **正式引入**：在 `src/main.js` 中引入并验证对现有页面无破坏。
2. **拆分迁移**：把变量和工具类迁移为正式 `theme.css`，逐步替换散落样式。

在未完成前，新增页面应优先参考已经生效的页面 scoped 样式。

### 14.3 抽象公共组件

建议沉淀以下公共组件，减少重复：

| 组件 | 目标 |
|---|---|
| `PageHeader` | 统一页面标题、描述、右侧操作 |
| `FilterBar` | 统一搜索、状态筛选、重置按钮布局 |
| `DataTableCard` | 统一表格卡片、loading、分页 |
| `KpiCard` / `KpiGrid` | 统一 KPI 数字卡片 |
| `StatusPill` | 统一运行、故障、待机、离线等状态 |
| `SeverityPill` | 统一高/中/低严重度 |
| `ImagePreviewDialog` | 统一图片预览、加载失败、缺陷框表格 |

### 14.4 统一表格与分页

当前分页基本已采用 `[5, 10, 20, 50]`，建议继续统一：

- 默认页大小：管理页 `10`，相机状态页 `5`。
- 布局：`total, sizes, prev, pager, next`；需要跳页时再加 `jumper`。
- 分页位置：表格卡片底部右侧；复杂页面可左右结构显示提示。

### 14.5 统一状态色映射

建议保留当前业务语义：

```text
运行/正常/OK      → success green
待机/延迟/WARNING → warning amber
故障/缺陷/CRITICAL → danger red
停止/离线/暂无    → neutral gray
```

同时收敛到一套色值，避免 Element Plus 默认绿、Tailwind 绿、Ant Design 绿混用。

### 14.6 减少内联样式

当前部分组件存在 inline style，例如 `CameraTable.vue` 的图片缩略图和表格内文案颜色。建议迁移到 class，方便统一维护和主题切换。

### 14.7 图表主题化

ECharts 颜色当前分散在组件和页面 setOption 中。建议建立图表主题常量：

- success：正确识别 / 运行中
- danger：异常识别 / 故障
- warning：待机 / 延迟
- neutral：停止 / 离线
- primary：总量 / 产线 / 图像

### 14.8 设计验收清单

新增页面或改版页面应至少检查：

- 是否使用统一顶部布局和 1440 内容宽度。
- 是否有清晰页面标题和辅助描述。
- 筛选区是否支持清空/重置。
- 表格是否有 loading、empty、pagination。
- 状态是否同时使用颜色和文字。
- 图片失败是否有原因和建议。
- 1280px 以下是否不破版。
- 删除/危险操作是否有确认和风险提示。
- 交互 hover/active/loading/error 是否完整。

---

## 15. 代码依据索引

| 规范内容 | 主要来源 |
|---|---|
| 技术栈 | `package.json` |
| Vite 代理与 alias | `vite.config.js` |
| 全局初始化 | `src/main.js` |
| Body 基础样式 | `src/App.vue` |
| 路由与权限 | `src/router/index.js` |
| 顶部布局和 Tab | `src/components/Layout.vue` |
| 空状态 | `src/components/EmptyState.vue` |
| 相机状态页 | `src/views/camera/CameraStatusView.vue` |
| 图像记录 | `src/components/CameraImageRecords.vue`、`src/views/stats/ImagesView.vue` |
| 产线总览 | `src/views/Dashboard.vue` |
| 系统配置 | `src/views/system/ConfigView.vue` |
| 基础管理页 | `src/views/factory/*`、`src/views/production-line/*`、`src/views/workstation/*`、`src/views/user/*`、`src/views/brand/*`、`src/views/defect/*` |
| 统计图表 | `src/views/stats/*`、`src/components/RecognizeBarChart.vue`、`src/components/StatusPieChart.vue` |
| 设计变量草稿 | `src/styles/global.css`（存在但未确认引入） |

---

## 16. 待确认事项

1. `src/styles/global.css` 是否需要正式引入并作为全局设计 token 来源。
2. 色彩体系最终采用 Tailwind 风格、Element Plus 默认风格还是 Ant Design 风格。
3. 是否需要保留 Emoji 作为正式图标体系的一部分。
4. 是否需要强制 `body min-width: 1280px`，还是继续增强移动端响应式。
5. ECharts 是否需要统一主题文件。
6. 登录页和注册页当前背景风格不同，是否需要统一为同一视觉体系。
7. 管理页标题有 `18px` 和 `20px` 两套，是否统一。
8. 表格单元格 padding 当前不同页面不完全一致，是否统一为一个密度标准。
