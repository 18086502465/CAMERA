<template>
  <div class="page camera-detail">
    <el-button @click="$router.back()" :icon="'ArrowLeft'" style="margin-bottom:16px">返回</el-button>
    <h2>📷 {{ camera.name }}</h2>
    <p style="color:#909399;margin-bottom:16px">{{ camera.station }} · {{ camera.line }}</p>

    <!-- 状态 + 硬件参数 -->
    <el-row :gutter="16">
      <el-col :xs="24" :md="16">
        <el-card><template #header>实时状态</template>
          <div class="status-grid">
            <div class="status-item"><span class="s-label">状态</span><el-tag :type="camera.status==='ONLINE'||camera.status==='RUNNING'?'success':'danger'" size="large">{{ camera.statusName }}</el-tag></div>
            <div class="status-item"><span class="s-label">CPU</span><span class="s-val">{{ camera.cpuUsage }}%</span></div>
            <div class="status-item"><span class="s-label">内存</span><span class="s-val">{{ camera.memoryUsage }}%</span></div>
            <div class="status-item"><span class="s-label">温度</span><span class="s-val">{{ camera.temperature }}°C</span></div>
            <div class="status-item"><span class="s-label">最后心跳</span><span class="s-val" style="font-size:14px">{{ camera.lastHeartbeat }}</span></div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :md="8">
        <el-card><template #header>设备信息</template>
          <el-descriptions :column="1" size="small" border>
            <el-descriptions-item label="相机名称">{{ camera.name }}</el-descriptions-item>
            <el-descriptions-item label="所属工位">{{ camera.station }}</el-descriptions-item>
            <el-descriptions-item label="所属产线">{{ camera.line }}</el-descriptions-item>
            <el-descriptions-item label="最后心跳">{{ camera.lastHeartbeat }}</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
    </el-row>

    <!-- 产量趋势 + 近期图片 -->
    <el-row :gutter="16" style="margin-top:16px">
      <el-col :xs="24" :md="14"><el-card><template #header>今日 产量/良率 趋势</template><div ref="trendChartRef" style="height:280px" /></el-card></el-col>
      <el-col :xs="24" :md="10">
        <el-card><template #header>近期拍摄 ({{ camera.recentImages.length }})</template>
          <div class="recent-grid">
            <div v-for="(img, i) in camera.recentImages" :key="i" class="recent-item" @click="previewVisible = true">
              <div class="recent-placeholder">📷</div>
              <span style="font-size:11px;color:#909399">{{ img.time }}</span>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import * as echarts from 'echarts'
import { getCameraList } from '@/api/camera'

const route = useRoute()
const previewVisible = ref(false)
const trendChartRef = ref(null)
let chart = null

const cameraId = Number(route.params.id)
const camera = ref({
  id: cameraId, name: '加载中...', station: '', line: '',
  status: 'STANDBY', statusName: '',
  cpuUsage: 0, memoryUsage: 0, temperature: 0,
  lastHeartbeat: '',
  recentImages: []
})

const stationLabel = (item = {}) => {
  if (item.stationLabel) return item.stationLabel
  const code = String(item.stationCode || '').trim()
  const name = String(item.stationName || item.workstationName || '').trim()
  if (code && name) {
    const lowerCode = code.toLowerCase()
    const lowerName = name.toLowerCase()
    if (lowerName === lowerCode || lowerName.startsWith(lowerCode) || lowerName.startsWith(lowerCode + '-') || lowerName.startsWith(lowerCode + '_') || lowerName.startsWith(lowerCode + '/')) return name
    return `${code} ${name}`
  }
  return name || code || '未配置工位'
}

const lineLabel = (item = {}) => item.lineName || item.lineCode || (item.lineId ? `产线${item.lineId}` : '-')

const fetchCamera = async () => {
  try {
    const res = await getCameraList({ page: 1, size: 999 })
    const list = res.data?.data?.records || res.data?.records || res.data?.data || []
    const found = list.find(c => c.id === cameraId)
    if (found) {
      camera.value = {
        id: found.id,
        name: found.cameraName || found.cameraCode || 'CAM-' + found.id,
        station: stationLabel(found),
        line: lineLabel(found),
        status: found.status || 'STANDBY',
        statusName: ({ ONLINE: '运行中', RUNNING: '运行中', STOPPED: '已停止', PAUSED: '暂停' })[found.status] || found.status,
        cpuUsage: found.cpuUsage || 0,
        memoryUsage: found.memoryUsage || 0,
        temperature: found.temperature || 0,
        lastHeartbeat: found.lastHeartbeatAt ? found.lastHeartbeatAt.slice(0, 16) : '--',
        recentImages: Array.from({length: 12}, (_, i) => ({ time: '--' }))
      }
    }
  } catch (e) {
    console.warn('CameraDetail fetch error:', e)
  }
}

const initChart = () => {
  if (chart) chart.dispose()
  if (!trendChartRef.value) return
  chart = echarts.init(trendChartRef.value)
  const times = Array.from({length: 24}, (_, i) => `${String(i).padStart(2,'0')}:00`)
  chart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['产量', '良率'] },
    xAxis: { type: 'category', data: times, axisLabel: { interval: 3 } },
    yAxis: [
      { type: 'value', name: '产量' },
      { type: 'value', name: '良率%', min: 94, max: 100 }
    ],
    series: [
      { name: '产量', type: 'bar', data: times.map(() => Math.floor(Math.random()*100+50)), itemStyle: { color: '#3b82f6', borderRadius: [4,4,0,0] }, barMaxWidth: 16 },
      { name: '良率', type: 'line', yAxisIndex: 1, data: times.map(() => +(95+Math.random()*4).toFixed(1)), smooth: true, itemStyle: { color: '#22c55e' } }
    ]
  })
}

onMounted(async () => { await fetchCamera(); await nextTick(); initChart() })
</script>

<style scoped>
.camera-detail { max-width: 1200px; margin: 0 auto; padding: 20px 16px; }
.status-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px; }
.status-item { text-align: center; }
.s-label { display: block; font-size: 12px; color: #909399; margin-bottom: 4px; }
.s-val { display: block; font-size: 22px; font-weight: 700; color: #1a1a2e; }
.recent-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
.recent-item { cursor: pointer; text-align: center; }
.recent-placeholder { width: 100%; aspect-ratio: 4/3; background: var(--color-divider); border-radius: 4px; display: flex; align-items: center; justify-content: center; font-size: 28px; }
.recent-placeholder:hover { background: #e0e0e0; }
</style>
