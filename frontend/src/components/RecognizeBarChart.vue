<template>
  <div class="chart-card">
    <div class="chart-title">识别统计（Top 10）</div>
    <div ref="chartRef" class="chart-box"></div>
  </div>
</template>

<script setup>
import { ref, onMounted, watch, onBeforeUnmount } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({ data: { type: Array, default: () => [] } })
const chartRef = ref(null)
let chart = null

const initChart = () => {
  if (!chartRef.value) return
  chart = echarts.init(chartRef.value)
  updateChart()
}

const updateChart = () => {
  if (!chart) return
  const names = props.data.map(d => d.camera_name || d.cameraName || '')
  const correct = props.data.map(d => d.correct_count ?? d.correctCount ?? 0)
  const abnormal = props.data.map(d => d.abnormal_count ?? d.abnormalCount ?? 0)

  chart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['正确识别', '异常识别'], bottom: 0, textStyle: { fontSize: 12 } },
    grid: { left: 10, right: 10, top: 10, bottom: 40, containLabel: true },
    xAxis: {
      type: 'category',
      data: names,
      axisLabel: { rotate: 30, fontSize: 11 }
    },
    yAxis: { type: 'value' },
    series: [
      {
        name: '正确识别', type: 'bar', stack: 'total',
        data: correct,
        itemStyle: { color: '#67c23a', borderRadius: [4, 4, 0, 0] },
        barMaxWidth: 30
      },
      {
        name: '异常识别', type: 'bar', stack: 'total',
        data: abnormal,
        itemStyle: { color: '#f56c6c', borderRadius: [4, 4, 0, 0] },
        barMaxWidth: 30
      }
    ]
  })
}

onMounted(() => initChart())
watch(() => props.data, updateChart, { deep: true })

const resizeHandler = () => chart?.resize()
window.addEventListener('resize', resizeHandler)
onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeHandler)
  chart?.dispose()
})
</script>

<style scoped>
.chart-card {
  background: var(--color-bg-card);
  border-radius: 10px;
  padding: 16px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.06);
}
.chart-title { font-size: 15px; font-weight: 600; color: #1a1a2e; margin-bottom: 8px; }
.chart-box { width: 100%; height: 280px; }
</style>
