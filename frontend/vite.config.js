import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

/**
 * 构建期自检：断言 element-plus 的 ElMessage / ElMessageBox 解析正常。
 *
 * <p>2026-11-04 踩过的坑：element-plus 版本漂移导致这两个导出变成 `undefined`，
 * **构建期完全不报错**，但运行时全站提示与弹窗静默失效
 * （用户侧表现是「点删除没反应」）。
 * 这个插件把问题提前到构建期暴露，避免再次靠浏览器实测才发现。
 */
function elementPlusGuard() {
  return {
    name: 'element-plus-guard',
    apply: 'build',
    generateBundle(_options, bundle) {
      // 检查**承载 message 封装的 chunk**（assets/message-*.js）：
      // 它的导入绑定必须能解析到 element-plus 的真实实现。
      //
      // 判定方法（按可靠性排序）：
      //  1. 找出 element-plus message 组件的真实实现所在的 chunk
      //     —— message 组件定义里带 __name:"message"；
      //  2. 在所有 chunk 里找出「导出了这个实现」的 chunk；
      //  3. message-* chunk 从哪个 chunk import 了它，
      //     该 import 的来源必须包含真实实现（而不是被 tree-shake 后的空壳）。
      //
      // 简化且可靠的做法：直接看 message-* chunk 里 import 的**来源 chunk**，
      // 如果那个来源 chunk 里含 __name:"message" 组件定义 → 正常。
      const chunkByName = {}
      for (const [fileName, chunk] of Object.entries(bundle)) {
        chunkByName[fileName] = chunk.code || ''
      }

      const problems = []
      let checked = 0
      for (const [fileName, code] of Object.entries(chunkByName)) {
        if (!fileName.startsWith('assets/message-') || !fileName.endsWith('.js')) continue
        checked++
        // 找到它 import 的所有来源 chunk
        const sources = [...code.matchAll(/from"\.\/([^"]+)"/g)].map(m => 'assets/' + m[1])
        const sourceHasImpl = sources.some(src => {
          const c = chunkByName[src] || ''
          return /__name:\s*"message"/.test(c) || /__name:\s*"messageBox"/.test(c)
        })
        if (!sourceHasImpl) {
          problems.push(
            `${fileName} 的 ElMessage/ElMessageBox 导入源（${sources.join(', ') || '无'}）` +
            '中找不到 element-plus 的 message 组件实现 —— 解析为 undefined'
          )
        }
      }
      if (checked === 0) {
        problems.push('产物中找不到 assets/message-*.js —— message 封装 chunk 异常')
      }

      if (problems.length) {
        this.error(
          '[element-plus-guard] 构建中止：\n  - ' + problems.join('\n  - ') +
          '\n  → 全站 ElMessage 提示与 ElMessageBox 弹窗会静默失效（构建不报错、运行才炸），' +
          '用户侧表现为「点按钮没反应」。' +
          '\n  → 请检查 vite.config.js 的 alias / optimizeDeps 配置，' +
          '以及 package.json 里 element-plus 的版本。' +
          '\n  → 参见 docs/删除按钮失效修复报告-20261104-ElementPlus版本漂移.md'
        )
      } else {
        this.warn('[element-plus-guard] element-plus 组件解析正常 ✓')
      }
    }
  }
}

export default defineConfig({
  plugins: [vue(), elementPlusGuard()],
  resolve: {
    alias: [
      { find: '@', replacement: fileURLToPath(new URL('./src', import.meta.url)) },
      /**
       * ★ element-plus 的**精确匹配**必须指向 CJS 完整入口，不能用默认 ESM 入口。
       *
       * 原因（2026-11-04 实测定位）：
       *   element-plus 2.14.0 的 `es/index.mjs` 全文只有两行：
       *     import elementPlus from '../lib/index.js'
       *     export default elementPlus
       *   ESM 入口的 interop 拿到的是 `lib/index.js` 的 `exports.default`
       *   —— 那是 Element Plus 的**插件对象**（只有 install 方法），
       *   **不包含** ElMessage / ElMessageBox。
       *   它们在 `lib/index.js` 的**顶层命名导出**上：
       *     exports.ElMessage / exports.ElMessageBox
       *
       *   于是 `ElementPlus.ElMessage` 得到 undefined，
       *   全站 ElMessage / ElMessageBox 静默失效（构建不报错、运行才炸），
       *   表现为「配置页点删除没反应」。
       *
       * ⚠️ 必须用**精确匹配**（正则）而不是前缀匹配：
       *   前缀匹配会把 `element-plus/dist/index.css` 也改写成
       *   `element-plus/lib/index.js/dist/index.css` → 找不到文件，构建失败。
       *
       * ⚠️ 升级 element-plus 时必须重新验证：
       *   本插件会在构建时自动断言（见 elementPlusGuard）。
       */
      {
        find: /^element-plus$/,
        replacement: fileURLToPath(new URL('./node_modules/element-plus/lib/index.js', import.meta.url))
      }
    ]
  },
  optimizeDeps: {
    /**
     * ★ 必须让 Vite 预打包 element-plus 的 **CJS 完整入口**。
     *
     * element-plus 的 ESM 入口（es/index.mjs）只有 default 导出，
     * 预打包后命名空间里**不含** ElMessage / ElMessageBox
     * → `import { ElMessage } from 'element-plus'` 拿到 undefined，
     * → 全站提示与弹窗静默失效。
     *
     * 显式把 CJS 入口加入 include 后，esbuild 会把
     * `exports.ElMessage` / `exports.ElMessageBox` 正确转成 ESM 命名导出。
     */
    include: [
      'element-plus/lib/index.js'
    ]
  },
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8091',
        changeOrigin: true
      }
    }
  }
})
