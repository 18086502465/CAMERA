import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
// ★ 顺序不能改：Element 自己的 CSS 也在 :root 定义同名 --el-* 变量，
//   同权重下后加载者胜出。把 tokens.css 放到 element-plus 的 CSS 之前，
//   覆盖会静默失效（不报错，只是颜色回到 Element 默认值）。
import './styles/tokens.css'
import zhCn from 'element-plus/dist/locale/zh-cn.min.js'
import router from './router'
import App from './App.vue'
import { startResourceVersionGuard } from './utils/resource-version'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.mount('#app')

// ★ 2026-11-04：启动前端资源版本守卫
//   部署后旧页面会因懒加载 chunk 失效而「点击没反应」，
//   这里做主动版本检测 + 动态 import 失败时自愈刷新。
startResourceVersionGuard()
