<template>
  <div ref="chartRef" :style="{ width: '100%', height: height }"></div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import * as echarts from 'echarts'

/**
 * 通用 ECharts 封装组件。
 *
 * <p><b>为什么要有这个组件</b>（2026-11-02 新增）：
 * 项目里原有 11 处直接调用 echarts.init()，其中 <b>8 处漏掉了 resize 处理</b>
 * （Dashboard.vue、CameraDetail.vue、DefectsView.vue、DefectTrend.vue、
 * LineDashboard.vue、ProductShiftYield.vue、StatsOverview.vue、YieldView.vue），
 * 表现是<b>浏览器窗口缩放后图表不会自适应、出现错位或留白</b>。
 *
 * <p>只有 StatusPieChart.vue / RecognizeBarChart.vue / LineAnalyticsView.vue
 * 三处做对了 resize + dispose。
 *
 * <p>本组件把「init → setOption → resize → dispose」这套正确做法固化下来，
 * 让后续新增图表不再重复踩坑。已有 8 处历史组件的问题属于
 * 「与本需求无关的存量缺陷」，不在本次修复范围（避免引入回归风险），
 * 但可以逐步迁移到本组件。
 *
 * <p><b>用法</b>：
 * <pre>
 * &lt;EChart :option="chartOption" height="280px" /&gt;
 * </pre>
 */
const props = defineProps({
  /** ECharts 配置项 */
  option: { type: Object, required: true },
  /** 图表高度 */
  height: { type: String, default: '280px' }
})

const chartRef = ref(null)
let chart = null
let resizeHandler = null

const initChart = () => {
  if (!chartRef.value) return
  if (chart) {
    chart.dispose()
    chart = null
  }
  chart = echarts.init(chartRef.value)
  chart.setOption(props.option, true)
}

// 深度监听 option 变化并刷新图表
watch(
  () => props.option,
  (val) => {
    if (chart) {
      // notMerge=true：数据点数量变化时（如历史点从 0 变成 120），
      // merge 模式会残留旧系列的点，导致曲线错乱
      chart.setOption(val, true)
    } else {
      nextTick(initChart)
    }
  },
  { deep: true }
)

// 窗口尺寸变化时重绘 —— 这是原有 8 处组件缺失的关键步骤
resizeHandler = () => chart && chart.resize()
window.addEventListener('resize', resizeHandler)

onMounted(() => {
  initChart()
  // 布局稳定后再 resize 一次，避免容器初始宽度为 0 导致画布尺寸错误
  nextTick(() => chart && chart.resize())
})

onBeforeUnmount(() => {
  if (resizeHandler) {
    window.removeEventListener('resize', resizeHandler)
    resizeHandler = null
  }
  if (chart) {
    chart.dispose()
    chart = null
  }
})
</script>
