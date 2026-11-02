<template>
  <div class="server-monitor-page">
    <div class="page-head">
      <div>
        <h2>🖥️ 服务器监控</h2>
        <p>目标服务器（本机）CPU、内存、磁盘空间与磁盘 I/O 实时指标</p>
      </div>
      <div class="head-actions">
        <el-select v-model="interval" class="interval-select" @change="restartTimer">
          <el-option :value="5000" label="5 秒" />
          <el-option :value="10000" label="10 秒" />
          <el-option :value="30000" label="30 秒" />
          <el-option :value="60000" label="60 秒" />
        </el-select>
        <el-switch v-model="autoRefresh" active-text="自动" @change="onAutoRefreshChange" />
        <el-button type="primary" :loading="loading" @click="loadData">刷新</el-button>
      </div>
    </div>

    <!-- 采集不可用时的提示 -->
    <el-alert
      v-if="errorCode"
      type="error"
      show-icon
      :closable="false"
      class="error-alert"
      title="服务器指标采集不可用"
      :description="lastError || '采集器初始化失败，请检查 JNA native 库是否可正常加载（java.io.tmpdir 需可写）。'"
    />

    <!-- 数据过期提示（保留上一次成功值） -->
    <el-alert
      v-else-if="stale"
      type="warning"
      show-icon
      :closable="false"
      class="error-alert"
      title="数据可能不是最新的"
      :description="`最近一次采集失败：${lastError || '未知原因'}。当前展示的是上一次成功采集的数据。`"
    />

    <!-- KPI 卡片 -->
    <div class="kpi-grid">
      <el-card shadow="never" class="kpi">
        <span>CPU 使用率</span>
        <strong>{{ fmtPercent(cpuUsage) }}</strong>
        <el-progress
          v-if="cpuUsage !== null"
          :percentage="Math.min(100, Math.round(cpuUsage))"
          :color="progressColor(cpuUsage)"
          :show-text="false"
          class="kpi-bar"
        />
        <small v-else>等待首个采样点</small>
      </el-card>

      <el-card shadow="never" class="kpi">
        <span>内存使用率</span>
        <strong>{{ fmtPercent(memoryUsage) }}</strong>
        <el-progress
          v-if="memoryUsage !== null"
          :percentage="Math.min(100, Math.round(memoryUsage))"
          :color="progressColor(memoryUsage)"
          :show-text="false"
          class="kpi-bar"
        />
        <small v-else>等待首个采样点</small>
      </el-card>

      <el-card shadow="never" class="kpi" :class="{ danger: maxDiskUsage > 85 }">
        <span>磁盘最大占用</span>
        <strong>{{ fmtPercent(maxDiskUsage) }}</strong>
        <small v-if="maxDiskMount">{{ maxDiskMount }}</small>
        <small v-else>无分区信息</small>
      </el-card>

      <el-card shadow="never" class="kpi">
        <span>磁盘写入速率</span>
        <strong>{{ fmtRate(writeBytesPerSec) }}</strong>
        <small v-if="sampleInterval > 0">读 {{ fmtRate(readBytesPerSec) }} · 写 {{ fmtRate(writeBytesPerSec) }}</small>
        <small v-else>首次采样，速率需下次刷新后有效</small>
      </el-card>
    </div>

    <!-- 图表区 -->
    <div class="chart-grid">
      <el-card shadow="never" class="chart-card">
        <template #header>
          <div class="card-title">
            <span>CPU 使用率趋势</span>
            <small>最近 {{ historyWindowLabel }}</small>
          </div>
        </template>
        <EChart :option="cpuChartOption" height="240px" />
      </el-card>

      <el-card shadow="never" class="chart-card">
        <template #header>
          <div class="card-title">
            <span>内存使用率趋势</span>
            <small>最近 {{ historyWindowLabel }}</small>
          </div>
        </template>
        <EChart :option="memoryChartOption" height="240px" />
      </el-card>

      <el-card shadow="never" class="chart-card">
        <template #header>
          <div class="card-title">
            <span>磁盘读写速率</span>
            <small v-if="sampleInterval > 0">采样间隔 {{ (sampleInterval / 1000).toFixed(0) }} 秒</small>
            <small v-else>首次采样暂无速率</small>
          </div>
        </template>
        <EChart :option="ioRateChartOption" height="240px" />
      </el-card>

      <el-card shadow="never" class="chart-card">
        <template #header>
          <div class="card-title">
            <span>磁盘 IOPS</span>
            <small>每秒读写操作数</small>
          </div>
        </template>
        <EChart :option="iopsChartOption" height="240px" />
      </el-card>
    </div>

    <!-- 分区容量表 -->
    <el-card shadow="never" class="table-card">
      <template #header>
        <div class="card-title">
          <span>磁盘分区容量</span>
          <small>各挂载点的总量、可用量与使用率</small>
        </div>
      </template>
      <el-table :data="fileSystems" size="small" style="width: 100%" empty-text="暂无分区信息">
        <el-table-column prop="mount" label="挂载点" min-width="140" />
        <el-table-column prop="type" label="类型" width="100" />
        <el-table-column label="总量" width="120" align="right">
          <template #default="{ row }">{{ fmtBytes(row.totalBytes) }}</template>
        </el-table-column>
        <el-table-column label="已用" width="120" align="right">
          <template #default="{ row }">{{ fmtBytes(row.usedBytes) }}</template>
        </el-table-column>
        <el-table-column label="可用" width="120" align="right">
          <template #default="{ row }">{{ fmtBytes(row.usableBytes) }}</template>
        </el-table-column>
        <el-table-column label="使用率" min-width="200">
          <template #default="{ row }">
            <el-progress
              :percentage="Math.min(100, Math.round(row.usagePercent || 0))"
              :color="progressColor(row.usagePercent)"
              :format="(p) => p + '%'"
            />
          </template>
        </el-table-column>
        <el-table-column label="读写" width="90" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.writable" type="success" size="small">读写</el-tag>
            <el-tag v-else type="info" size="small">只读</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 服务器信息 -->
    <el-card shadow="never" class="info-card">
      <template #header>
        <div class="card-title">
          <span>服务器信息</span>
          <small v-if="collectedAt">采集时刻 {{ collectedAt }}</small>
        </div>
      </template>
      <el-descriptions :column="3" border size="small">
        <el-descriptions-item label="主机名">{{ server.hostName || '--' }}</el-descriptions-item>
        <el-descriptions-item label="操作系统">{{ server.osName || '--' }}</el-descriptions-item>
        <el-descriptions-item label="架构">{{ server.arch || '--' }}</el-descriptions-item>
        <el-descriptions-item label="CPU 型号">{{ server.cpuModel || '--' }}</el-descriptions-item>
        <el-descriptions-item label="核心数">
          {{ server.cpuCores || '--' }} 物理 / {{ server.cpuLogical || '--' }} 逻辑
        </el-descriptions-item>
        <el-descriptions-item label="JVM 进程 CPU">{{ fmtPercent(server.processCpuLoad) }}</el-descriptions-item>
        <el-descriptions-item label="系统负载">
          <span v-if="cpu.loadAverage1 !== null && cpu.loadAverage1 !== undefined">
            {{ cpu.loadAverage1 }} / {{ cpu.loadAverage5 }} / {{ cpu.loadAverage15 }}
          </span>
          <span v-else>不适用（Windows 无此指标）</span>
        </el-descriptions-item>
        <el-descriptions-item label="物理内存">{{ fmtBytes(memory.totalBytes) }}</el-descriptions-item>
        <el-descriptions-item label="可用内存">{{ fmtBytes(memory.availableBytes) }}</el-descriptions-item>
        <el-descriptions-item label="系统运行时长">{{ fmtDuration(server.uptimeSeconds) }}</el-descriptions-item>
        <el-descriptions-item label="JVM 运行时长">{{ fmtDuration(server.jvmUptimeSeconds) }}</el-descriptions-item>
        <el-descriptions-item label="磁盘累计写入">{{ fmtBytes(diskIo.writeTotalBytes) }}</el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, onActivated, onDeactivated } from 'vue'
import EChart from '@/components/common/EChart.vue'
import { getServerMetrics } from '@/api/server-metrics'

// ---------- 状态 ----------
const loading = ref(false)
const autoRefresh = ref(true)
const interval = ref(5000)
const stale = ref(false)
const lastError = ref('')
const errorCode = ref('')
const collectedAt = ref('')

const server = ref({})
const cpu = ref({})
const memory = ref({})
const diskIo = ref({})
const fileSystems = ref([])
const history = ref([])

let timer = null
let inFlight = false

// ---------- 派生数据 ----------
const cpuUsage = computed(() => (cpu.value ? cpu.value.usagePercent : null))
const memoryUsage = computed(() => (memory.value ? memory.value.usagePercent : null))

// 磁盘速率：首次采样为 null，必须显示 '--' 而不是 0
const readBytesPerSec = computed(() => (diskIo.value ? diskIo.value.readBytesPerSec : null))
const writeBytesPerSec = computed(() => (diskIo.value ? diskIo.value.writeBytesPerSec : null))
const readOpsPerSec = computed(() => (diskIo.value ? diskIo.value.readOpsPerSec : null))
const writeOpsPerSec = computed(() => (diskIo.value ? diskIo.value.writeOpsPerSec : null))
const sampleInterval = computed(() => (diskIo.value ? diskIo.value.sampleIntervalMillis : 0))

const maxDiskUsage = computed(() => {
  let max = null
  let mount = ''
  for (const fs of fileSystems.value || []) {
    if (fs.usagePercent !== null && fs.usagePercent !== undefined) {
      if (max === null || fs.usagePercent > max) {
        max = fs.usagePercent
        mount = fs.mount
      }
    }
  }
  return max
})
const maxDiskMount = computed(() => {
  let max = null
  let mount = ''
  for (const fs of fileSystems.value || []) {
    if (fs.usagePercent !== null && fs.usagePercent !== undefined) {
      if (max === null || fs.usagePercent > max) {
        max = fs.usagePercent
        mount = fs.mount
      }
    }
  }
  return mount
})

const historyWindowLabel = computed(() => {
  const n = history.value.length
  if (n === 0) return '暂无数据'
  const seconds = n * (interval.value / 1000)
  if (seconds < 60) return `${Math.round(seconds)} 秒`
  return `${Math.round(seconds / 60)} 分钟`
})

// ---------- 图表配置 ----------
const axisBase = {
  axisLine: { lineStyle: { color: '#dcdfe6' } },
  axisLabel: { color: '#606266', fontSize: 11 },
  splitLine: { lineStyle: { color: '#ebeef5' } }
}

function timeAxis() {
  return {
    type: 'category',
    boundaryGap: false,
    data: history.value.map((p) => formatTime(p.timestamp)),
    ...axisBase,
    splitLine: { show: false }
  }
}

const cpuChartOption = computed(() => ({
  tooltip: { trigger: 'axis', valueFormatter: (v) => (v === null ? '--' : `${v}%`) },
  legend: { bottom: 0, textStyle: { fontSize: 11 } },
  grid: { left: 46, right: 20, top: 20, bottom: 34 },
  xAxis: timeAxis(),
  yAxis: { type: 'value', min: 0, max: 100, axisLabel: { formatter: '{value}%', fontSize: 11 }, ...axisBase },
  series: [
    {
      name: 'CPU 使用率',
      type: 'line',
      smooth: true,
      showSymbol: false,
      lineStyle: { width: 2, color: '#409eff' },
      areaStyle: { color: 'rgba(64,158,255,0.12)' },
      data: history.value.map((p) => p.cpuUsagePercent)
    }
  ]
}))

const memoryChartOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  legend: { bottom: 0, textStyle: { fontSize: 11 } },
  grid: { left: 46, right: 52, top: 20, bottom: 34 },
  xAxis: timeAxis(),
  yAxis: [
    { type: 'value', min: 0, max: 100, axisLabel: { formatter: '{value}%', fontSize: 11 }, ...axisBase },
    { type: 'value', axisLabel: { formatter: (v) => fmtBytes(v), fontSize: 11 }, splitLine: { show: false } }
  ],
  series: [
    {
      name: '内存使用率',
      type: 'line',
      smooth: true,
      showSymbol: false,
      lineStyle: { width: 2, color: '#67c23a' },
      areaStyle: { color: 'rgba(103,194,58,0.12)' },
      data: history.value.map((p) => p.memoryUsagePercent)
    },
    {
      name: '已用内存',
      type: 'line',
      yAxisIndex: 1,
      smooth: true,
      showSymbol: false,
      lineStyle: { width: 1.5, type: 'dashed', color: '#e6a23c' },
      data: history.value.map((p) => p.memoryUsedBytes)
    }
  ]
}))

const ioRateChartOption = computed(() => ({
  tooltip: {
    trigger: 'axis',
    valueFormatter: (v) => (v === null || v === undefined ? '--' : fmtBytes(v) + '/s')
  },
  legend: { bottom: 0, textStyle: { fontSize: 11 } },
  grid: { left: 62, right: 20, top: 20, bottom: 34 },
  xAxis: timeAxis(),
  yAxis: {
    type: 'value',
    axisLabel: { formatter: (v) => fmtBytes(v) + '/s', fontSize: 11 },
    ...axisBase
  },
  series: [
    {
      name: '读速率',
      type: 'line',
      smooth: true,
      showSymbol: false,
      lineStyle: { width: 2, color: '#909399' },
      areaStyle: { color: 'rgba(144,147,153,0.12)' },
      // null 值会让 ECharts 断线，正好表达"首次采样无数据"
      data: history.value.map((p) => p.readBytesPerSec)
    },
    {
      name: '写速率',
      type: 'line',
      smooth: true,
      showSymbol: false,
      lineStyle: { width: 2, color: '#f56c6c' },
      areaStyle: { color: 'rgba(245,108,108,0.12)' },
      data: history.value.map((p) => p.writeBytesPerSec)
    }
  ]
}))

const iopsChartOption = computed(() => ({
  tooltip: {
    trigger: 'axis',
    valueFormatter: (v) => (v === null || v === undefined ? '--' : `${v} IOPS`)
  },
  legend: { bottom: 0, textStyle: { fontSize: 11 } },
  grid: { left: 52, right: 20, top: 20, bottom: 34 },
  xAxis: timeAxis(),
  yAxis: { type: 'value', axisLabel: { formatter: '{value}', fontSize: 11 }, ...axisBase },
  series: [
    {
      name: '读 IOPS',
      type: 'line',
      smooth: true,
      showSymbol: false,
      lineStyle: { width: 2, color: '#409eff' },
      data: history.value.map((p) => p.readOpsPerSec)
    },
    {
      name: '写 IOPS',
      type: 'line',
      smooth: true,
      showSymbol: false,
      lineStyle: { width: 2, color: '#e6a23c' },
      data: history.value.map((p) => p.writeOpsPerSec)
    }
  ]
}))

// ---------- 数据加载 ----------
async function loadData() {
  // 防止请求重叠：上一次没回来就不发下一个
  if (inFlight) return
  inFlight = true
  loading.value = true
  try {
    const res = await getServerMetrics()
    // 响应拦截器不解包，业务数据在 res.data.data
    const d = res.data && res.data.data
    if (!d) return
    server.value = d.server || {}
    cpu.value = d.cpu || {}
    memory.value = d.memory || {}
    diskIo.value = d.diskIo || {}
    fileSystems.value = d.fileSystems || []
    history.value = d.history || []
    stale.value = !!d.stale
    lastError.value = d.lastError || ''
    errorCode.value = d.errorCode || ''
    collectedAt.value = d.collectedAt || ''
  } catch (e) {
    stale.value = true
    lastError.value = (e && e.message) || '请求失败'
  } finally {
    loading.value = false
    inFlight = false
  }
}

// ---------- 轮询管理 ----------
function startTimer() {
  stopTimer()
  timer = setInterval(loadData, interval.value)
}

function stopTimer() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

function restartTimer() {
  if (autoRefresh.value) {
    startTimer()
  }
}

function onAutoRefreshChange(val) {
  if (val) {
    startTimer()
    loadData()
  } else {
    stopTimer()
  }
}

// 页面隐藏时暂停轮询，可见时恢复 —— 减少无意义的服务器压力
function onVisibilityChange() {
  if (document.hidden) {
    stopTimer()
  } else if (autoRefresh.value) {
    startTimer()
    loadData()
  }
}

onMounted(() => {
  loadData().then(() => {
    if (autoRefresh.value) startTimer()
  })
  document.addEventListener('visibilitychange', onVisibilityChange)
})

onBeforeUnmount(() => {
  stopTimer()
  document.removeEventListener('visibilitychange', onVisibilityChange)
})

// keep-alive 场景
onActivated(() => {
  if (autoRefresh.value && !timer) {
    startTimer()
    loadData()
  }
})

onDeactivated(() => stopTimer())

// ---------- 格式化 ----------
function fmtPercent(v) {
  if (v === null || v === undefined || Number.isNaN(v)) return '--'
  return `${Number(v).toFixed(1)}%`
}

function fmtBytes(bytes) {
  if (bytes === null || bytes === undefined || Number.isNaN(bytes)) return '--'
  const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB']
  let n = Number(bytes)
  let i = 0
  if (n === 0) return '0 B'
  while (n >= 1024 && i < units.length - 1) {
    n /= 1024
    i++
  }
  return `${n.toFixed(n >= 100 || i === 0 ? 0 : 1)} ${units[i]}`
}

function fmtRate(bytesPerSec) {
  if (bytesPerSec === null || bytesPerSec === undefined || Number.isNaN(bytesPerSec)) return '--'
  return `${fmtBytes(bytesPerSec)}/s`
}

function fmtDuration(seconds) {
  if (seconds === null || seconds === undefined || Number.isNaN(seconds)) return '--'
  const d = Math.floor(seconds / 86400)
  const h = Math.floor((seconds % 86400) / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  if (d > 0) return `${d} 天 ${h} 小时`
  if (h > 0) return `${h} 小时 ${m} 分钟`
  return `${m} 分钟`
}

function formatTime(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  const p = (v) => String(v).padStart(2, '0')
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

function progressColor(percent) {
  if (percent === null || percent === undefined) return '#909399'
  if (percent >= 90) return '#f56c6c'
  if (percent >= 75) return '#e6a23c'
  return '#67c23a'
}
</script>

<style scoped>
.server-monitor-page {
  padding: 4px 2px 24px;
}

.page-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 16px;
}

.page-head h2 {
  margin: 0 0 4px;
  font-size: 20px;
  color: #1a1a2e;
}

.page-head p {
  margin: 0;
  font-size: 13px;
  color: #909399;
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.interval-select {
  width: 96px;
}

.error-alert {
  margin-bottom: 14px;
}

/* KPI 卡片 —— 与相机状态页保持一致的视觉语言 */
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
  margin-bottom: 14px;
}

.kpi :deep(.el-card__body) {
  padding: 16px;
}

.kpi span {
  display: block;
  font-size: 13px;
  color: #909399;
  margin-bottom: 6px;
}

.kpi strong {
  display: block;
  font-size: 26px;
  font-weight: 600;
  color: #1a1a2e;
  line-height: 1.2;
}

.kpi small {
  display: block;
  margin-top: 6px;
  font-size: 12px;
  color: #a8abb2;
}

.kpi.danger strong {
  color: #f56c6c;
}

.kpi-bar {
  margin-top: 8px;
}

/* 图表区 */
.chart-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 14px;
  margin-bottom: 14px;
}

.chart-card :deep(.el-card__body) {
  padding: 12px 16px 16px;
}

.card-title {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 10px;
}

.card-title span {
  font-size: 15px;
  font-weight: 600;
  color: #1a1a2e;
}

.card-title small {
  font-size: 12px;
  color: #909399;
}

/* 表格与信息卡 */
.table-card,
.info-card {
  margin-bottom: 14px;
}

.table-card :deep(.el-card__body),
.info-card :deep(.el-card__body) {
  padding: 14px 16px;
}

/* 窄屏降级为单列 */
@media (max-width: 1100px) {
  .kpi-grid,
  .chart-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 720px) {
  .kpi-grid,
  .chart-grid {
    grid-template-columns: 1fr;
  }
}
</style>
