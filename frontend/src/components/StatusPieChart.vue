<template>
  <div class="chart-card">
    <div class="chart-title">相机状态分布</div>
    <div ref="chartRef" class="chart-box"></div>
  </div>
</template>

<script setup>
import { ref, onMounted, watch, onBeforeUnmount } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({ stats: { type: Object, default: () => ({}) } })
const chartRef = ref(null)
let chart = null

const initChart = () => {
  if (!chartRef.value) return
  chart = echarts.init(chartRef.value)
  updateChart()
}

const updateChart = () => {
  if (!chart) return
  const s = props.stats
  chart.setOption({
    tooltip: { trigger: 'item', formatter: '{b}: {c} 台 ({d}%)' },
    legend: { bottom: 0, textStyle: { fontSize: 12 } },
    series: [{
      type: 'pie',
      radius: ['45%', '75%'],
      center: ['50%', '45%'],
      avoidLabelOverlap: false,
      label: { show: false },
      emphasis: { label: { show: true, fontSize: 18, fontWeight: 'bold' } },
      data: [
        { value: s.running || 0, name: '运行中', itemStyle: { color: '#67c23a' } },
        { value: s.stopped || 0, name: '已停止', itemStyle: { color: '#909399' } },
        { value: s.fault || 0, name: '故障', itemStyle: { color: '#f56c6c' } },
        { value: s.standby || 0, name: '待机', itemStyle: { color: '#e6a23c' } },
        { value: s.offline || 0, name: '离线', itemStyle: { color: '#303133' } }
      ].filter(d => d.value > 0)
    }]
  })
}

onMounted(() => initChart())
watch(() => props.stats, updateChart, { deep: true })

// 窗口大小变化时重绘
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
