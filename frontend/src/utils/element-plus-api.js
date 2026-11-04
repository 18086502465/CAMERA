/**
 * element-plus 组件实现的**直接引用入口**。
 *
 * <p><b>为什么要单独一个文件（2026-11-04 实测定位）</b>：
 * element-plus 是 CJS 包，package.json 的 {@code exports} 字段限制了深导入
 * （{@code element-plus/lib/...} 会被 Vite 拒绝：ERR_PACKAGE_PATH_NOT_EXPORTED）。
 * 而它的 ESM 入口 {@code es/index.mjs} 只有 default 导出，
 * 预打包后命名空间里**不含** {@code ElMessage} / {@code ElMessageBox}。
 *
 * <p><b>解决办法</b>：用<b>相对路径</b>直接指向 node_modules 里的 CJS 组件文件 ——
 * 相对路径<b>不受 package.json exports 约束</b>，Vite 能正常解析；
 * 且 esbuild 预打包 CJS 时会把 {@code exports.ElMessage} 正确转成 ESM 具名导出。
 *
 * <p>验证方式：浏览器控制台看 {@code src/utils/message.js} 导出的
 * {@code __messageResolution} 是否全为 true。
 */

// element-plus/lib/components/message/index.js  →  exports.ElMessage
export { ElMessage } from '../../node_modules/element-plus/lib/components/message/index.js'
// element-plus/lib/components/message-box/index.js → exports.ElMessageBox
export { ElMessageBox } from '../../node_modules/element-plus/lib/components/message-box/index.js'
