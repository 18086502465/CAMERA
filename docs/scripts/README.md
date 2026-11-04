# 排查与验证脚本索引

> 目录：`docs/scripts/`
> **用途**：避免每次排查都重写一次性 SSH/Python 探针。
> **约定**：
> 1. 脚本内**不写明文口令**，一律从环境变量读取（`MYSQL_PASSWORD` / `SUDO_PASSWORD` / `CAMERA_ADMIN_PASSWORD`）。
> 2. Shell 脚本必须是 **LF 行尾**（Windows 上写的 CRLF 传到 Linux 会报 `set: pipefail: invalid option name`）。
> 3. **只读排查**优先标 `probe_*`；会改数据的标 `fix_*` / `cleanup_*`，执行前须确认。

---

## 一、部署与运维

| 脚本 | 用途 | 用法 |
|---|---|---|
| `deploy_jar.sh` | 部署新 JAR 到服务器（原子替换 + 健康检查 + **失败自动回滚**） | `SUDO_PASSWORD='xxx' bash deploy_jar.sh`（JAR 放 `/tmp/time-bucket-demo.jar`） |
| `service.sh` | **统一运维入口**（与线上同名副本一致） | `bash service.sh {health\|version\|config-check\|rollback\|cleanup\|status\|logs\|start\|stop\|restart}` |
| `apply_config_separation.sh` | 配置去密钥改造的服务器落地（**一次性**，改造完成可删） | `sudo bash apply_config_separation.sh` |
| `sync_static.py` | 把 `frontend/dist` 同步到后端 `static/` 并清理陈旧 chunk | `python sync_static.py` |

---

## 二、数据修复（**会改数据，执行前务必确认**）

| 脚本 | 用途 | 备注 |
|---|---|---|
| `fix_zk19_data.sh` | 逻辑删除 ZK19 空产线及其 2 个空工位 | 需 `MYSQL_PASSWORD` |
| `cleanup_test_widgets.sh` | 清理看板里混入的测试图表 | 幂等，重复执行删 0 条 |
| `cleanup_test_dashboards.sh` | 清理「按钮验证」遗留的空看板模板 | 幂等 |

---

## 三、验证（回归）

| 脚本 | 验证内容 | 用法 |
|---|---|---|
| `test_install_password_source.sh` | **P0-1**：`install.sh` 口令来源（不把占位符当口令、重装保留现场口令、JWT_SECRET 删后写回） | `bash test_install_password_source.sh`（本地，无需服务器） |
| `verify_p0_fixes.sh` | **P0**：心跳匹配不串号 + API 路径修正 + 假数据接入 | 在服务器上执行 `bash verify_p0_fixes.sh <管理员口令>` |
| `verify_config_separation.py` | 配置去密钥后的业务功能（登录、4 个业务接口、设备心跳、图片数） | `python verify_config_separation.py`（需 paramiko 与环境变量） |
| `verify_zk19_fix.py` | ZK19 修复后的接口与数据完整性 | 同上 |

---

## 四、只读探针（不改数据）

| 脚本 | 回答什么问题 |
|---|---|
| `probe_api_paths.sh` | **前端 API 路径与后端端点是否对得上**（曾发现 `/users` vs `/user` 的 404） |
| `probe_camera_identity.sh` | 相机的身份字段现状：`camera_code` 唯一性、`camera_name` 是否重名、各表索引 |
| `probe_hb_collision.sh` | 两台**重名**相机的计数与心跳时间是否各自独立更新 |
| `probe_hb_match_target.sh` | 按 `camera_name` 匹配时 SQL 实际命中哪一行（`LIMIT 1` 无 ORDER BY 的不确定性） |
| `probe_hb_vs_upload.sh` | 心跳接口与图片上传接口各自是否真在用 |
| `probe_hb_final.sh` | 心跳串号是否已发生的最终认定 |
| `probe_detection_data.sh` | 各事实表的行数（判断某通道是否真的在用） |

---

## 五、GitHub 推送

| 脚本 | 用途 |
|---|---|
| `push_via_github_api.py` | 走 GitHub Git Data API 推送（原生 `git push` 被代理拦截时的备选）。令牌从环境变量 `GITHUB_TOKEN` 读取 |

> ⚠️ 优先直接 `git push`；仅在代理对 `github.com` 返回 502 时才用本脚本。

---

## 五之二、部署后静态资源版本保留（2026-11-04 新增）

**背景**：Vite chunk 文件名带内容哈希，每次构建全部改名 → 部署前打开的旧页面
点懒加载路由会 404 → vue-router 导航静默中断（"点了没反应"，URL 都不变）。

| 脚本 | 用途 |
|---|---|
| `recover_legacy_chunks.py` | **从历史 JAR 备份恢复旧版前端 chunk**（部署后旧页面才能继续用）。增量写 JAR，不破坏 MANIFEST |
| `prune_legacy_chunks.py` | 按 `.versions` 清单淘汰过旧的静态版本（保留数可传参，默认 3） |
| `test_chunk_scripts.py` | 本地端到端验证上面两个脚本（重复条目 / MANIFEST / 按版淘汰） |

**构建侧**：`build-release.sh` 会自动保留上一版 chunk（与新版共存），
用 `assets/.versions` 记录归属，`KEEP_STATIC_VERSIONS`（默认 3）控制版数。

**存量修复**（把历史 JAR 备份里的旧 chunk 合并回当前 JAR）：

```bash
sudo python3 recover_legacy_chunks.py     # 恢复（增量写，安全）
sudo python3 prune_legacy_chunks.py 3      # 按版淘汰，保留最近 3 版
sudo systemctl restart camera-detection    # 生效
```

> ⚠️ **顺序很重要**：必须在**部署完成之后**执行，且执行完要重启服务。
> 在部署流程中间改 JAR 会让部署脚本后续步骤失败、服务停在 stopped。
> ⚠️ 绝不能用 `jar cf` 重打包 Spring Boot JAR（会丢 `Main-Class: JarLauncher` /
> `Start-Class` / `classpath.idx`），必须用 Python zipfile 增量写。

---

## 六、浏览器回归脚本（`docs/`，不在本目录）

| 脚本 | 覆盖内容 |
|---|---|
| `验证脚本-P0修复回归.cjs` | 用户接口路径、告警页假数据、检测记录页真实数据源与「—」显示（13 项断言） |
| `验证脚本-ZK19产线修复回归.cjs` | ZK19 下线、徽标单位、相机绑定数（5 项断言） |
| `验证脚本-产线统计优化回归.cjs` | 严重度中文化、KPI 重排、时间快捷档（8 项断言） |
| `验证脚本-摘要区样式回归.cjs` | 配置页摘要区卡片布局（8 项断言） |
| `验证脚本-产线统计看板渲染.cjs` | 看板图表渲染现状 |
| `验证脚本-产线统计请求次数.cjs` | 页面进入时的接口请求次数 |
| `验证脚本-403认证失效处理.cjs` | 401 跳转 + 提示 + 上下文保留 + 登录后回跳 + 开放重定向防护（16 项断言） |
| `验证脚本-403拦截器行为.cjs` | **拦截器分流**：403 ACCESS_DENIED 不跳转 / 401 跳登录（9 项断言） |
| `验证脚本-403权限不足不跳转.cjs` | 用真实普通用户（role=0）验证 403 语义（需先建临时用户） |
| `scripts/verify-legacy-nav.cjs` | 模拟「旧版 index.html」场景，验证旧 chunk 可访问 + 两个导航按钮跳转正常（在 `docs/scripts/`） |

### 登录辅助（2026-11-04 新增）

`docs/scripts/browser-helper.cjs` 封装了登录逻辑，**所有验证脚本都应使用它**：

```js
const { login } = require('./scripts/browser-helper.cjs');
const res = await login(page, 'admin', process.env.CAMERA_ADMIN_PASSWORD);
```

**为什么不能自己写填值逻辑**（三个实测踩坑）：

| 坑 | 现象 | 正确做法 |
|---|---|---|
| `page.type(selector, text)` | 在 **password 类型输入框**上静默失效，值留空 | 用 `page.keyboard.type()` |
| `page.type()` 追加而非覆盖 | 登录页用户名框有默认值 `'admin'`，直接 type 得到 `adminadmin` | 先 `Ctrl+A` + `Backspace` 清空 |
| `evaluate` 里用 value setter 赋值 | Vue 的 v-model 在 input 事件里把值同步回去，赋了等于没赋 | 用真实键盘事件 |

**统一约束**：
- 全部需 `CAMERA_ADMIN_PASSWORD` 环境变量；未设置会导致登录静默失败（密码为空）。
- Node 侧变量**必须显式传参**给 `page.evaluate((pwd) => {...}, ADMIN_PWD)` —— 回调在浏览器上下文执行，访问不到 Node 变量。
- **场景之间有状态依赖**：401 场景会清掉 token；已登录状态访问 `/login` 会被路由守卫重定向走。
  需要进登录页时**必须先清 token**。
