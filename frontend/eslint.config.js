import pluginVue from 'eslint-plugin-vue'

/**
 * 前端静态检查（渐进式）。
 *
 * 只启用 Vue 3 的 essential 规则集 —— 目标是「抓错误级问题，不管代码风格」：
 *   - 模板里引用未定义的变量/组件
 *   - v-for 缺 key、v-if 与 v-for 同元素
 *   - 未使用的组件注册、重复属性
 *
 * 刻意**不**启用：
 *   - `js.configs.recommended`（存量代码量大，no-unused-vars 之类会瞬间爆红，
 *     且这类问题不影响运行）
 *   - 任何格式规则（Prettier 的职责，本项目未引入）
 *
 * 因此本配置零警告起步，一旦报警就是真问题。后续要收紧时，先本地全量跑一遍，
 * 确认存量错误数可接受再上 CI —— 本仓库吃过「CI 规则写错导致长期假红」的亏。
 */
export default [
  {
    ignores: ['dist/**', 'node_modules/**', 'public/**', 'scripts/**'],
  },
  ...pluginVue.configs['flat/essential'],
  {
    rules: {
      // 纯命名风格（Layout.vue / Login.vue 这类单词组件名在本项目是既定约定），
      // 不影响运行，关掉以免掩盖真正的错误级告警。
      'vue/multi-word-component-names': 'off',
    },
  },
]
