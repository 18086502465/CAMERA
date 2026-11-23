/**
 * ECharts 按需入口（页面打开效率优化 2026-11-22）。
 *
 * <p><b>为什么</b>：全站 12 处 `import * as echarts from '@/utils/echarts-core'` 会把 ECharts
 * 全量打进异步 chunk（实测 1.04MB / gzip ~330KB）。全站实际用到的只有
 * bar / line / pie / heatmap / calendar 五类图与若干组件 —— 按需注册后
 * 异步 chunk 减半以上，且功能零变化（未注册的类型构建期不报错，
 * 靠 `docs/scripts/verify_*_browser.cjs` 浏览器回归兜底）。
 *
 * <p><b>用法</b>：全站统一 `import * as echarts from '@/utils/echarts-core'`，
 * 其余 API（init / dispose / graphic.LinearGradient…）与全量包完全一致。
 * 新增图表类型时必须在这里补注册，否则运行时该系列静默不渲染。
 */
import * as echarts from 'echarts/core'
import {
  BarChart,
  LineChart,
  PieChart,
  HeatmapChart
} from 'echarts/charts'
import {
  GridComponent,
  // DefectsView.vue 用了 coordinateSystem:'polar'
  PolarComponent,
  // 日历热力图（LineAnalyticsView defectCalendar）
  CalendarComponent,
  TooltipComponent,
  LegendComponent,
  TitleComponent,
  VisualMapComponent,
  DataZoomComponent,
  MarkLineComponent,
  MarkPointComponent,
  MarkAreaComponent,
  ToolboxComponent,
  DatasetComponent,
  TransformComponent
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { LabelLayout, UniversalTransition } from 'echarts/features'

echarts.use([
  BarChart,
  LineChart,
  PieChart,
  HeatmapChart,
  GridComponent,
  PolarComponent,
  CalendarComponent,
  TooltipComponent,
  LegendComponent,
  TitleComponent,
  VisualMapComponent,
  DataZoomComponent,
  MarkLineComponent,
  MarkPointComponent,
  MarkAreaComponent,
  ToolboxComponent,
  DatasetComponent,
  TransformComponent,
  CanvasRenderer,
  LabelLayout,
  UniversalTransition
])

export * from 'echarts/core'
export default echarts
