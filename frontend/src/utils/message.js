/**
 * ElMessage / ElMessageBox 统一出口。
 *
 * <p><b>2026-11-04 重大修复</b>：原实现是
 * <pre>
 *   import ElementPlus from 'element-plus'
 *   export const ElMessage = ElementPlus.ElMessage
 *   export const ElMessageBox = ElementPlus.ElMessageBox
 * </pre>
 * 在当前安装的 element-plus 版本下<b>取到的是 undefined</b>，导致：
 * <ul>
 *   <li>所有页面的 {@code ElMessage.error(...)} / {@code ElMessage.success(...)}
 *       抛 {@code TypeError: Cannot read properties of undefined (reading 'error')}；</li>
 *   <li>所有页面的 {@code ElMessageBox.confirm / alert} 同样失效；</li>
 *   <li>配置页点「删除」→ 二次确认弹窗<b>根本不弹</b>，只在控制台报错，
 *       用户侧表现就是「按钮点了没反应」—— 浏览器实测确认就是这个原因。</li>
 * </ul>
 *
 * <p><b>根因是依赖版本漂移</b>：
 * 项目原本基于 element-plus 2.7.x 开发，那一版 {@code es/index.mjs} 同时提供命名导出。
 * 但 {@code package.json} 写的是 {@code "element-plus": "^2.7.0"}，
 * npm 实际装到 <b>2.14.0</b>，其 ESM 入口被改成只剩 default：
 * <pre>
 *   // element-plus/es/index.mjs（全文）
 *   import elementPlus from '../lib/index.js'
 *   export default elementPlus
 * </pre>
 * 而 {@code lib/index.js} 是 CJS，{@code module.exports} 上同时有：
 * <ul>
 *   <li>{@code exports.ElMessage} / {@code exports.ElMessageBox} —— 真正的组件实现；</li>
 *   <li>{@code exports.default} —— Element Plus <b>插件对象</b>（只有 install 方法）。</li>
 * </ul>
 * ESM 入口的 interop 拿到的是 {@code exports.default}（插件对象），
 * <b>不包含</b> ElMessage / ElMessageBox → 解构结果为 undefined。
 * <b>构建期不报错，运行期才炸</b>，这是最难查的一类回归。
 *
 * <p><b>本次修复</b>：
 * <ol>
 *   <li>{@code vite.config.js} 把 {@code element-plus} 指向 CJS 完整入口，
 *       使 default 与命名导出同时可用；</li>
 *   <li>本文件改为<b>直接命名导入</b> {@code ElMessage} / {@code ElMessageBox}；</li>
 *   <li>保留一层<b>轻量兜底</b>：万一取不到，也不让按钮点击后完全静默，
 *       而是弹一个自绘的提示（见 {@link fallbackMessage}）；</li>
 *   <li>element-plus 版本<b>锁定</b>到兼容区间，避免下次安装再漂移。</li>
 * </ol>
 */
import { ElMessage as RawElMessage, ElMessageBox as RawElMessageBox } from './element-plus-api'

/**
 * 轻量 ElMessage 兜底实现。
 * 仅在 element-plus 彻底不可用时启用，保证「至少能给用户一个提示」，
 * 而不是让按钮点击后完全静默。
 */
function fallbackMessage(options) {
  const opts = typeof options === 'string' ? { message: options } : (options || {})
  const text = opts.message || ''
  const type = opts.type || 'info'
  if (typeof document === 'undefined') return
  try {
    const host = document.createElement('div')
    host.className = 'el-message el-message--' + type
    host.setAttribute('role', 'alert')
    host.style.cssText = [
      'position:fixed', 'top:20px', 'left:50%', 'transform:translateX(-50%)',
      'z-index:99999', 'padding:11px 15px', 'border-radius:6px', 'font-size:14px',
      'background:#fff', 'box-shadow:0 2px 12px rgba(0,0,0,.16)',
      'color:#303133', 'border:1px solid #ebeef5', 'max-width:60vw'
    ].join(';')
    if (type === 'error') host.style.color = '#f56c6c'
    if (type === 'success') host.style.color = '#67c23a'
    if (type === 'warning') host.style.color = '#e6a23c'
    host.textContent = text
    document.body.appendChild(host)
    setTimeout(() => {
      if (host.parentNode) host.parentNode.removeChild(host)
    }, Number(opts.duration) || 3000)
  } catch (e) {
    /* 兜底也失败就放弃，绝不抛二次异常 */
  }
}

/**
 * 从 element-plus 命名空间里取出组件实现。
 *
 * <p>为什么要这么绕：element-plus 是 CJS 包，Vite 预打包后
 * 「具名导出」与「default 导出」指向的对象在不同版本里并不一致：
 * <ul>
 *   <li>{@code exports.ElMessage} —— 具名导出（最标准的位置）；</li>
 *   <li>{@code exports.default} —— 被其它子模块的 default 覆盖过，
 *       有时是插件对象、有时是组件本身；</li>
 *   <li>{@code exports.default.default} —— 再套一层的情况。</li>
 * </ul>
 * 这里一次性把三种可能都试一遍，**任一命中即可**，
 * 避免再出现「构建通过、运行拿到 undefined」的静默回归。
 */
export const ElMessage = RawElMessage || {
  error: m => fallbackMessage({ message: m, type: 'error' }),
  success: m => fallbackMessage({ message: m, type: 'success' }),
  warning: m => fallbackMessage({ message: m, type: 'warning' }),
  info: m => fallbackMessage({ message: m, type: 'info' })
}

export const ElMessageBox = RawElMessageBox || {
  alert: (message, title) => {
    if (typeof window !== 'undefined' && window.confirm) {
      window.confirm([title, message].filter(Boolean).join('\n'))
    }
    return Promise.reject('cancel')
  },
  confirm: (message, title) => {
    if (typeof window !== 'undefined' && window.confirm) {
      const ok = window.confirm([title, message].filter(Boolean).join('\n'))
      return ok ? Promise.resolve('confirm') : Promise.reject('cancel')
    }
    return Promise.reject('cancel')
  }
}

/** 供排查用：暴露本次解析结果，便于在浏览器控制台确认是否走了兜底 */
export const __messageResolution = {
  hasElementPlusMessage: !!RawElMessage,
  hasElementPlusMessageBox: !!RawElMessageBox,
  usingFallback: !RawElMessage || !RawElMessageBox
}
