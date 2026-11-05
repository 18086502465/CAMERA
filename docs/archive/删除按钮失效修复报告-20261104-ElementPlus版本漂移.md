# 配置页删除按钮「点了没反应」修复报告 — 2026-11-04

> 服务器：`10.2.77.72`，服务 `camera-detection.service`，端口 `8091`
> 现象：配置页相机 tab 点「删除」按钮无任何反应
> 结论：**根因是 element-plus 版本漂移导致 `ElMessage`/`ElMessageBox` 全站静默失效**，
> 已修复并上线，**真实 Chrome 浏览器实测通过**

---

## 一、排查过程

### 1.1 先排除后端

浏览器实测 + 接口实测两条线并行：

| 检查项 | 结果 |
|---|---|
| `GET /api/cameras/{id}/dependencies` | HTTP 200，数据正常 |
| `DELETE /api/cameras/{id}` | HTTP 200，`"删除成功"` |
| 删除后再查 | HTTP 404（确实删掉了） |
| 数据库状态 | `camera_status.is_active=0`、`dim_camera.is_deleted=1` |

**后端完全正常** → 问题在浏览器侧。

### 1.2 用真实 Chrome 复现（关键一步）

装了 `puppeteer-core` 驱动本机 Chrome，完整模拟用户操作：

```
=== 4. 点击「删除」按钮 ===
  点击结果: {"ok":true,"row":"1 CV1_Cam1 DA8119487 杭州临平二期工厂 ZK54 棉芯污点检测 编辑 删除"}
=== 5. 点击后的 DOM 状态 ===
  可见 MessageBox: []          ← 弹窗根本没出现
  ElMessage: []
=== 6. 本次点击产生的网络请求 ===
  GET /api/cameras/9/dependencies -> 200   ← 请求发出了，但后续没了
=== 8. 捕获到的错误 ===
  [console.error] TypeError: Cannot read properties of undefined (reading 'error')
```

**点击 → 发依赖检查请求 → JS 抛 TypeError → 弹窗没出现、也没有任何提示**。

### 1.3 拿到精确堆栈

Vue 会把组件内的异常交给全局 `errorHandler`，直接 hook 它拿原始错误：

```
=== Vue errorHandler 捕获 ===
  name : TypeError
  msg  : Cannot read properties of undefined (reading 'error')
  stack: at _a (http://10.2.77.72:8091/assets/ConfigView-DvlSpIvn.js:10:14234)
```

反查打包产物 `ConfigView-DvlSpIvn.js` 第 10 行第 14234 列：

```js
_a=async(a,e)=>{   // ← deleteRow
  ...
  try{
    await Ca(a,u,y);                    // ← confirmDeleteRisk
    const R=await J.delete("/"+he(a)+"/"+u);
    Oe(R),await xe(),$e(),Y.success("删除成功")
  }catch(R){
    R!=="cancel"&&Y.error("删除失败："+(((W=(f=R.response)==null?void 0:f.data)==null?void 0:W.message)||R.message||R))
  }
}
```

报错字符落在 **`Y.error`** 的 `error` 上 → **`Y`（即 `ElMessage`）是 `undefined`**。

### 1.4 确认 ElMessage 为 undefined

直接 import 线上打包出的 message chunk：

```js
const m = await import('/assets/message-DU-vAoEY.js')
// → { ElMessageType: "undefined", ElMessageBoxType: "undefined" }
```

**全站 `ElMessage` / `ElMessageBox` 都是 undefined。**

---

## 二、根本原因

### 2.1 直接原因

`frontend/src/utils/message.js`（原实现）：

```js
import ElementPlus from 'element-plus'
export const ElMessage = ElementPlus.ElMessage
export const ElMessageBox = ElementPlus.ElMessageBox
```

这两个 `undefined` 让**全站所有提示与弹窗静默失效**：

| 表现 | 原因 |
|---|---|
| 配置页点删除没反应 | `ElMessageBox.confirm/alert` 是 undefined → 二次确认框弹不出来 |
| 所有「保存成功/删除失败/加载失败」提示不见 | `ElMessage.success/error` 是 undefined |
| 控制台报 `reading 'error'` | `ElMessage.error(...)` 读 undefined 的 `.error` |

> 影响面**远不止删除按钮** —— 是全站所有页面的所有操作反馈。
> 之前的接口级验证全部通过，正是因为它绕过了前端。

### 2.2 为什么 `ElementPlus.ElMessage` 是 undefined —— 依赖版本漂移

**这是 `package.json` 里 `^2.7.0` 带来的静默回归。**

| 项 | 值 |
|---|---|
| 项目开发时基于 | element-plus **2.7.x**（那一版 `es/index.mjs` 同时提供命名导出） |
| `package.json` 声明 | `"element-plus": "^2.7.0"`（`^` 允许 minor 升级） |
| 实际安装 | **2.14.0** |
| 2.14.0 的 ESM 入口 | `es/index.mjs` **全文只有两行**：<br>`import elementPlus from '../lib/index.js'`<br>`export default elementPlus` |

而 `lib/index.js` 是 CJS，`module.exports` 上有两组东西：

```js
exports.ElMessage      = ...   // ← 真正的组件实现
exports.ElMessageBox   = ...   // ← 真正的组件实现
exports.default       = ...   // ← Element Plus 插件对象（只有 install 方法）
```

ESM 入口的 interop 拿到的是 **`exports.default`（插件对象）**，
它**不包含** `ElMessage` / `ElMessageBox` → 解构结果 `undefined`。

**构建期完全不报错，运行期才炸** —— 这是最难定位的一类回归。
（`npm install` 时没有锁版本，谁装到哪个版本全看运气。）

---

## 三、修复内容

### 3.1 新增 `frontend/src/utils/element-plus-api.js`

```js
// element-plus/lib/components/message/index.js  →  exports.ElMessage
export { ElMessage } from '../../node_modules/element-plus/lib/components/message/index.js'
// element-plus/lib/components/message-box/index.js → exports.ElMessageBox
export { ElMessageBox } from '../../node_modules/element-plus/lib/components/message-box/index.js'
```

**为什么用相对路径**：

| 方案 | 结果 |
|---|---|
| `import { ElMessage } from 'element-plus'` | ✗ ESM 入口无命名导出，Rollup 报 `not exported by` |
| `import ... from 'element-plus/lib/...'` | ✗ `package.json` 的 `exports` 拦截：`ERR_PACKAGE_PATH_NOT_EXPORTED` |
| `import ... from 'element-plus/es/...'` | ✗ 该版本的 `es/components/*/index.mjs` **文件不存在**（npm 包被裁剪，只有 `.map`） |
| **相对路径直指 node_modules** | ✓ **相对路径不受 `exports` 约束**，Vite 正常解析；esbuild 预打包 CJS 时把 `exports.ElMessage` 正确转成 ESM 具名导出 |

### 3.2 改写 `frontend/src/utils/message.js`

- 改为从 `./element-plus-api` 具名导入 `ElMessage` / `ElMessageBox`
- 保留**轻量兜底**：万一取不到，也不让按钮点击后完全静默，
  而是用自绘的 DOM 提示条兜住（`fallbackMessage`）
- 导出 `__messageResolution`，供在浏览器控制台确认是否走了兜底

### 3.3 `frontend/vite.config.js`

element-plus 的 `element-plus` 精确匹配指向 CJS 完整入口，
并把它加入 `optimizeDeps.include`：

```js
alias: [
  { find: '@', replacement: '...' },
  { find: /^element-plus$/, replacement: '.../node_modules/element-plus/lib/index.js' }
],
optimizeDeps: { include: ['element-plus/lib/index.js'] }
```

> ⚠️ 必须用**精确匹配**（正则 `/^element-plus$/`）而不是前缀匹配 ——
> 前缀匹配会把 `element-plus/dist/index.css` 也改写成
> `element-plus/lib/index.js/dist/index.css`，导致构建失败。

### 3.4 `frontend/package.json` —— 锁定版本

```diff
- "element-plus": "^2.7.0",
+ "element-plus": "2.14.0",
```

去掉 `^` 防止下次安装再漂移到不兼容的大版本。

### 3.5 新增构建期防护 `frontend/vite.config.js`

```js
function elementPlusGuard() {
  return {
    name: 'element-plus-guard',
    apply: 'build',
    generateBundle(_options, bundle) {
      // 找出 message-* chunk 的 import 来源，
      // 确认来源 chunk 里含 element-plus 的 message 组件实现
      // （组件定义带 __name:"message"）
      ...
    }
  }
}
```

**作用**：把「运行期才炸」变成「**构建期就报错**」。
双向验证过：

| 场景 | 结果 |
|---|---|
| 正常配置 | `[element-plus-guard] element-plus 组件解析正常 ✓`，构建通过 |
| 故意把 `element-plus-api.js` 改成 `export const ElMessage = undefined` | **构建中止**，并明确提示「全站 ElMessage 提示与 ElMessageBox 弹窗会静默失效」 |

> 判定逻辑踩过两次坑：
> ① 只扫 `assets/message-*.js` → 真实实现被 Rollup 拆到别的 chunk，误判失败；
> ② 扫全部 chunk → element-plus 主 chunk 本身就含 message 组件，误判通过。
> 最终采用：**看 message-\* chunk 的 import 来源 chunk 里是否含 `__name:"message"`**。

### 3.6 顺带修复：删除弹窗显示的名称错了

`ConfigView.vue` 的 `deleteRow`：

```js
// 修复前 —— 相机行里同时有 lineName 和 cameraName，lineName 在前
const name = row.factoryName || row.lineName || row.stationName || row.cameraName || row.username
// → 相机删除弹窗显示的是「ZK54」（产线名），用户完全看不懂在删什么

// 修复后 —— 按类型取名称
const name = {
  factory: row.factoryName,
  line: row.lineName,
  workstation: row.stationName,
  camera: row.cameraName,
  user: row.realName || row.username
}[type] || '-'
```

---

## 四、验证方式与结果

### 4.1 真实 Chrome 端到端验证（`docs/验证脚本-浏览器删除按钮回归.cjs`）

```
=== 4. 点击 aaa 行的「删除」===
  确认框: [
    "删除二次确认  确认删除相机「aaa」？  删除后不可恢复。  [取消] [确认删除]"
  ]
=== 5. 点击「确认删除」===
  点到确认: true
  列表中还有 aaa 吗: false | 当前页行数: 6
=== 6. 本次网络请求 ===
  GET    /api/cameras/28/dependencies -> 200  {"data":{"imageCount":0,...,"riskLevel":"LOW"}}
  DELETE /api/cameras/28             -> 200  {"data":"删除成功"}
  GET    /api/cascade/all             -> 200   ← 级联树刷新
  GET    /api/cameras?page=1&size=999 -> 200   ← 列表刷新
=== 7. 错误 ===
  [console.error] Failed to load resource: 404   ← 仅 favicon，与功能无关
```

**弹窗正常弹出 → 确认 → 删除成功 → 列表与级联树刷新 → 零 JS 错误。**

### 4.2 全站五类删除按钮回归（`docs/验证脚本-全站删除按钮回归.cjs`）

> 验证脚本基于 `puppeteer-core` + 本机 Chrome，**需要临时安装**：
> `cd frontend && npm install puppeteer-core --no-save`（用完可 `rm -rf node_modules/puppeteer-core`）

| 类型 | 点击删除后的弹窗 | 结论 |
|---|---|---|
| 工厂 | 禁止删除 当前工厂「杭州临平一期工厂」存在下级配置或历史数据：产线 2 条，工位 4 条，相机 2 条… | ✓ 名称正确 |
| 产线 | 禁止删除 当前产线「ZK23」存在下级配置或历史数据：工位 2 条，相机 2 条… | ✓ 名称正确 |
| 工位 | 禁止删除 当前工位「棉芯污点检测」存在下级配置或历史数据：相机 1 条… | ✓ 名称正确 |
| 相机 | 禁止删除 当前相机「CV8-Cam1」存在下级配置或历史数据：图片记录 98 条… | ✓ 名称正确 |
| 用户 | 删除二次确认 确认删除用户「系统管理员」？ | ✓ 可删，走二次确认 |

**五类全部正常，零 JS 错误。**

### 4.3 服务与数据状态

```
ActiveState=active  SubState=running  MainPID=161216  NRestarts=0
JAR SHA256 1851520f5cdde22254d4efb18b0882f017b043fb0f0eac1c21e41c9af490de22（本地=远端一致）
单元测试 Tests run 19, Failures 0, Errors 0
```

### 4.4 测试数据与环境

- 本次验证使用**用户自己新建的 `aaa` 相机**（id=28，本就无依赖、属可删对象）；
  浏览器点击删除后它已按业务逻辑被逻辑删除（`is_active=0` / `is_deleted=1`），**可恢复**
- 上一轮遗留的 `ZZTEST*` 测试数据：**残留 0**
- 环境基线：工厂 2 / 产线 3 / 工位 18 / 相机 17 / camera_status 17 / sys_user 1
  （相机 17 是因为 `aaa` 被删除后仍在表中，属逻辑删除的正常状态）
- 临时用户（`zztmpuser` 等）：**0**

---

## 五、影响面说明（重要）

这次修复的**不只是删除按钮**，而是恢复了**全站所有操作反馈**：

| 场景 | 修复前 | 修复后 |
|---|---|---|
| 配置页删除/编辑/保存 | 无任何提示 | 正常弹出确认框与成功提示 |
| 产线统计的看板增删改 | 无提示 | 正常 |
| 相机状态页的各类操作 | 无提示 | 正常 |
| 导入导出 | 无提示 | 正常 |
| 操作日志页的删除/清空 | 无提示 | 正常 |
| 节假日配置、品牌、公司、用户管理的删除 | 无提示 | 正常 |

> 之前之所以没被发现，是因为所有验证都走的**接口级**，
> 绕开了前端这一层。**这次改用真实浏览器复现，才暴露出问题。**

---

## 六、遗留与建议

1. **必须补浏览器级验证**：接口全绿 ≠ 页面能用。
   本项目建议把 `docs/验证脚本-*.cjs`（基于 puppeteer-core + 本机 Chrome）
   纳入日常验收流程。
2. **依赖版本必须锁定**：`package.json` 里所有 `^` 都存在同类漂移风险
   （本项目还有 axios / echarts / vue 等）。建议后续统一锁定到已验证版本。
3. **构建期防护**：可在 `vite.config.js` 加一个自检插件，
   构建时断言 `element-plus` 的 `ElMessage`/`ElMessageBox` 解析结果非 undefined，
   从「运行期才炸」变成「构建期就报错」。
4. **Git 未提交** —— 按用户新约定，本次改动未 `commit`/`push`，等明确指示。

---

## 七、回滚方式

```bash
echo "<SSH 口令>" | sudo -S bash -c '
  systemctl stop camera-detection
  cp -a /opt/camera-detection-system/app/time-bucket-demo.jar.bak-20261104_095400 \
        /opt/camera-detection-system/app/time-bucket-demo.jar
  chown cdsys:cdsys /opt/camera-detection-system/app/time-bucket-demo.jar
  systemctl reset-failed camera-detection
  systemctl start camera-detection
'
```

回滚后核验：`systemctl is-active` + `curl -o /dev/null -w "%{http_code}" http://127.0.0.1:8091/`
