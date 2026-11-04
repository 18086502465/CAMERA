import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
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
