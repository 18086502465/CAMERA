<template>
  <div class="page">
    <div class="page-header">
      <h2>⚠ 缺陷统计</h2>
      <span class="filter-hint" v-if="filterInfo">{{ filterInfo }}</span>
    </div>
    <div class="kpi-row">
      <div class="kpi-card"><span class="kpi-label">总缺陷数</span><span class="kpi-value" style="color:#ef4444">{{ kpiData.total }}</span></div>
      <div class="kpi-card"><span class="kpi-label">高危</span><span class="kpi-value" style="color:#dc2626">{{ kpiData.severe }}</span></div>
      <div class="kpi-card"><span class="kpi-label">中危</span><span class="kpi-value" style="color:#f59e0b">{{ kpiData.major }}</span></div>
      <div class="kpi-card"><span class="kpi-label">低危</span><span class="kpi-value" style="color:#3b82f6">{{ kpiData.minor }}</span></div>
    </div>
    <!-- Filter Bar -->
    <div class="filter-bar" style="margin-bottom:12px;display:flex;gap:10px;flex-wrap:wrap">
      <el-select v-model="localFilter.defectType" placeholder="缺陷类型" clearable size="default" style="width:140px" @change="refetch">
        <el-option v-for="t in defectTypes" :key="t" :label="t" :value="t" />
      </el-select>
      <el-select v-model="localFilter.severity" placeholder="严重程度" clearable size="default" style="width:120px" @change="refetch">
        <el-option label="高危" value="HIGH" /><el-option label="中危" value="MEDIUM" /><el-option label="低危" value="LOW" />
      </el-select>
      <el-date-picker v-model="localFilter.dateRange" type="daterange" start-placeholder="开始" end-placeholder="结束" size="default" value-format="YYYY-MM-DD" style="width:260px" @change="refetch" />
    </div>
    <div class="panel-grid">
      <div class="panel"><div class="panel-head">严重程度趋势</div><div class="panel-body"><div ref="c1" class="chart" /></div></div>
      <div class="panel"><div class="panel-head">缺陷类型分布</div><div class="panel-body"><div ref="c2" class="chart" /></div></div>
    </div>
    <div class="panel-grid">
      <div class="panel"><div class="panel-head">位置热力图</div><div class="panel-body"><div ref="c3" class="chart" /></div></div>
      <div class="panel"><div class="panel-head">时段缺陷分布</div><div class="panel-body"><div ref="c4" class="chart" /></div></div>
    </div>
    <div class="panel-grid">
      <div class="panel"><div class="panel-head">区域缺陷分布</div><div class="panel-body"><div ref="c5" class="chart" /></div></div>
      <div class="panel"><div class="panel-head">Top 缺陷详情</div><div class="panel-body p0">
        <el-table :data="topList" stripe size="default" class="data-table">
          <el-table-column prop="camera" label="相机" width="90" /><el-table-column prop="station" label="工位" />
          <el-table-column prop="type" label="类型" />
          <el-table-column label="严重度" width="76"><template #default="{row}"><el-tag :type="severityTag(row.sev)" size="small">{{ severityText(row.sev) }}</el-tag></template></el-table-column>
          <el-table-column prop="area" label="区域" width="90" show-overflow-tooltip />
          <el-table-column prop="size" label="面积" width="80" align="right" />
          <el-table-column prop="time" label="时间" width="80" />
        </el-table>
      </div></div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, inject, watch, onMounted, nextTick } from 'vue'
import * as echarts from 'echarts'
import { getDefectStats, getDefectHeatmap, getDefectTrend } from '@/api/stats'

const c1 = ref(null), c2 = ref(null), c3 = ref(null), c4 = ref(null), c5 = ref(null)
let charts = []

/* ---- Local Filter ---- */
const localFilter = ref({ defectType: '', severity: '', dateRange: [] })
const defectTypes = ['表面划痕', '边缘破损', '尺寸偏差', '颜色不均', '污渍异物']

/* ---- Filter ---- */
const filterParams = inject('filterParams', ref({ factoryId: null, lineIds: [], stationIds: [] }))
const filterLabels = inject('filterLabels', ref({ factoryName: '', lineNames: [], stationNames: [] }))
const today = new Date(Date.now() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 10)

/* ---- Reactive data ---- */
const kpiData = ref({ total: 0, severe: 0, major: 0, minor: 0 })
const heatmapData = ref({ areas: [], sizeLevels: [], gridData: [] })
const topList = ref([])
const trendData = ref({ timeLabels: [], defectTypes: [], defectTypeSeries: [], totalSeries: [] })
const defectTypeData = ref([])
const areaData = ref([]) // [{area, count}] for area bar chart
const hourlyData = ref([]) // [{hour, severe, major, minor}] for hourly bar

const severityKey = (severity) => {
  const s = String(severity || '').toUpperCase()
  if (s === 'HIGH' || s === '高危' || s === '严重') return 'HIGH'
  if (s === 'MEDIUM' || s === '中危' || s === '重大') return 'MEDIUM'
  return 'LOW'
}
const severityText = (severity) => ({ HIGH: '高危', MEDIUM: '中危', LOW: '低危' }[severityKey(severity)])
const severityTag = (severity) => ({ HIGH: 'danger', MEDIUM: 'warning', LOW: 'info' }[severityKey(severity)])

const filterInfo = computed(() => {
  const { factoryId, lineIds, stationIds } = filterParams.value
  if (!factoryId && lineIds.length === 0 && stationIds.length === 0) return ''
  const parts = []
  if (factoryId) parts.push(filterLabels.value.factoryName || ('工厂' + factoryId))
  if (lineIds.length > 0) parts.push('产线: ' + (filterLabels.value.lineNames?.join('、') || (lineIds.length + '条')))
  if (stationIds.length > 0) parts.push('工位: ' + (filterLabels.value.stationNames?.join('、') || (stationIds.length + '个')))
  return parts.join(' · ')
})

const buildParams = () => {
  const { factoryId, lineIds, stationIds } = filterParams.value
  return {
    factoryId: factoryId || undefined,
    lineId: lineIds.length === 1 ? lineIds[0] : undefined,
    stationId: stationIds.length === 1 ? stationIds[0] : undefined,
    startDate: '2026-01-01',
    endDate: today,
  }
}

const fetchAll = async () => {
  const p = buildParams()
  try {
    const [overview, heatmap, trend] = await Promise.all([
      getDefectStats(p),
      getDefectHeatmap(p),
      getDefectTrend(p),
    ])
    const ov = overview.data?.data || overview.data || overview
    const hm = heatmap.data?.data || heatmap.data || heatmap
    const tr = trend.data?.data || trend.data || trend
    if (ov.kpi) kpiData.value = ov.kpi
    if (ov.topDefects) topList.value = ov.topDefects.map(d => ({
      camera: d.cameraName || d.defectCode || '',
      station: d.stationLabel || d.stationName || (d.stationId ? '未配置工位' : '-'),
      type: d.defectType || '',
      sev: d.severityCode || d.severity || '',
      area: d.defectArea || '-',
      size: d.defectSize || d.area || '-',
      time: (d.detectionTime || '').slice(11, 16),
    }))
    if (ov.byType) defectTypeData.value = ov.byType
    if (hm.gridData) heatmapData.value = hm
    if (tr.timeLabels) trendData.value = tr
    // Aggregate area distribution from heatmap data
    if (hm.areas && hm.gridData) {
      areaData.value = hm.areas.map((area, i) => ({
        area,
        count: (hm.gridData[i] || []).reduce((a, b) => a + b, 0),
      })).sort((a, b) => b.count - a.count)
    }
    // Hourly trend from overview
    if (ov.hourlyTrend) hourlyData.value = ov.hourlyTrend
  } catch (e) {
    console.warn('DefectsView fetch error:', e)
  }
}

const initCharts = () => {
  charts.forEach(c => c.dispose()); charts = []

  // Severity trend
  if (c1.value && trendData.value.timeLabels?.length) {
    const c = echarts.init(c1.value)
    const td = trendData.value
    c.setOption({
      tooltip: { trigger: 'axis' }, legend: { data: td.defectTypes || [] },
      grid: { t: 10, r: 20, b: 20, l: 40 },
      xAxis: { type: 'category', data: td.timeLabels, axisLabel: { interval: Math.max(1, Math.floor(td.timeLabels.length / 12)), fontSize: 10 } },
      yAxis: { type: 'value' },
      series: (td.defectTypeSeries || []).map((s, i) => ({
        name: s.name, type: 'line', data: s.data || [],
        lineStyle: { width: 2 }, smooth: true,
      }))
    }); charts.push(c)
  }

  // Defect type polarArea
  if (c2.value && defectTypeData.value.length) {
    const c = echarts.init(c2.value)
    const types = defectTypeData.value.map(d => d.defectType)
    const counts = defectTypeData.value.map(d => d.count)
    const colors = ['#ef4444', '#f59e0b', '#3b82f6', '#22c55e', '#8b5cf6', '#ec4899']
    c.setOption({
      tooltip: {},
      angleAxis: { show: false },
      radiusAxis: { type: 'category', data: types, z: 10 },
      polar: { radius: ['20%', '80%'] },
      series: [{
        type: 'bar', data: counts.map((v, i) => ({ value: v, itemStyle: { color: colors[i % 6], opacity: 0.75 } })),
        coordinateSystem: 'polar', barCategoryGap: '20%',
      }]
    }); charts.push(c)
  }

  // Heatmap
  if (c3.value && heatmapData.value.areas?.length) {
    const c = echarts.init(c3.value)
    const hm = heatmapData.value
    const hd = []
    for (let y = 0; y < hm.areas.length; y++)
      for (let x = 0; x < hm.sizeLevels.length; x++)
        hd.push([x, y, hm.gridData[y]?.[x] || 0])

    const maxVal = Math.max(1, ...hd.map(d => d[2]))
    c.setOption({
      tooltip: {}, grid: { left: 80, right: 20, top: 20, bottom: 60 },
      xAxis: { type: 'category', data: hm.sizeLevels, position: 'top' },
      yAxis: { type: 'category', data: hm.areas, inverse: true },
      visualMap: { min: 0, max: maxVal, calculable: true, orient: 'horizontal', left: 'center', bottom: 0, inRange: { color: ['#22c55e', '#eab308', '#f97316', '#dc2626'] } },
      series: [{ type: 'heatmap', data: hd, label: { show: true } }]
    }); charts.push(c)
  }

  // C4: hourly defect distribution bar
  if (c4.value && hourlyData.value.length) {
    const c = echarts.init(c4.value)
    const hours = hourlyData.value.map(h => h.hour + ':00')
    const hd = hourlyData.value
    c.setOption({
      tooltip: { trigger: 'axis' },
      grid: { t: 10, r: 20, b: 20, l: 45 },
      xAxis: { type: 'category', data: hours, axisLabel: { fontSize: 10 } },
      yAxis: { type: 'value' },
      series: [{
        type: 'bar', data: hd.map((h, i) => {
          const isDay = i >= 8 && i < 20
          return { value: h.severe + h.major + h.minor, itemStyle: { color: isDay ? '#3b82f6' : '#93c5fd', borderRadius: [4, 4, 0, 0] } }
        }), barMaxWidth: 16,
      }]
    }); charts.push(c)
  }

  // C5: area defect distribution horizontal bar
  if (c5.value && areaData.value.length) {
    const c = echarts.init(c5.value)
    c.setOption({
      tooltip: {}, grid: { l: 80, r: 20, t: 10, b: 20 },
      xAxis: { type: 'value' },
      yAxis: { type: 'category', data: areaData.value.map(a => a.area), inverse: true },
      series: [{
        type: 'bar', data: areaData.value.map(a => a.count),
        itemStyle: { borderRadius: [0, 4, 4, 0], color: '#f59e0b' }, barMaxWidth: 22,
      }]
    }); charts.push(c)
  }
}

onMounted(async () => { await fetchAll(); await nextTick(); initCharts() })
const refetch = async () => { await fetchAll(); await nextTick(); initCharts() }
watch(() => filterParams.value, async () => { await fetchAll(); await nextTick(); initCharts() }, { deep: true })
</script>

<style scoped>
.page { max-width: 1440px; margin: 0 auto; }
.page-header { display: flex; align-items: baseline; gap: 16px; margin-bottom: 16px; }
.page-header h2 { font-size: 22px; font-weight: 700; color: #1a1a2e; margin: 0; }
.filter-hint { font-size: 13px; color: #3b82f6; background: #eff6ff; padding: 3px 12px; border-radius: 12px; }
.kpi-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px; margin-bottom: 16px; }
.kpi-card { background: #fff; border-radius: 12px; padding: 24px 20px; box-shadow: 0 1px 3px rgba(0,0,0,.06); display: flex; flex-direction: column; gap: 6px; text-align: center; }
.kpi-label { font-size: 14px; color: #737373; } .kpi-value { font-size: 36px; font-weight: 700; }
.panel-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-bottom: 16px; }
.panel { background: #fff; border-radius: 12px; overflow: hidden; box-shadow: 0 1px 3px rgba(0,0,0,.06); }
.panel-head { padding: 16px 20px; font-size: 16px; font-weight: 600; color: #262626; border-bottom: 1px solid #e5e5e5; }
.panel-body { padding: 16px 20px; } .p0 { padding: 0; }
.chart { width: 100%; height: 320px; }
.data-table :deep(.el-table__row:hover) { background: #eff6ff !important; }
@media (max-width: 1280px) { .kpi-row { grid-template-columns: repeat(2, 1fr); } .panel-grid { grid-template-columns: 1fr; } }
</style>
