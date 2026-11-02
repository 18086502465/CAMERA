<template>
  <div class="page">
    <div class="page-header">
      <h2>📈 良率分析</h2>
      <span class="filter-hint" v-if="filterInfo">{{ filterInfo }}</span>
    </div>
    <div class="kpi-row">
      <div class="kpi-card"><span class="kpi-label">综合良率</span><span class="kpi-value" style="color:#22c55e">{{ kpiData.overall }}%</span></div>
      <div class="kpi-card"><span class="kpi-label">白班良率</span><span class="kpi-value" style="color:#3b82f6">{{ formatKpiPercent(kpiData.day) }}</span></div>
      <div class="kpi-card"><span class="kpi-label">夜班良率</span><span class="kpi-value" style="color:#1e3a8a">{{ formatKpiPercent(kpiData.night) }}</span></div>
      <div class="kpi-card"><span class="kpi-label">最佳工位</span><span class="kpi-value" style="color:#f59e0b">{{ kpiData.best || '-' }}</span></div>
    </div>
    <!-- Filter Bar -->
    <div class="filter-bar" style="margin-bottom:16px;display:flex;gap:10px;flex-wrap:wrap;align-items:center">
      <el-select v-model="granularity" placeholder="时间粒度" size="default" style="width:130px" @change="refetch">
        <el-option label="30分钟" value="30min" />
        <el-option label="1小时" value="1hour" />
        <el-option label="按天" value="daily" />
      </el-select>
      <el-select v-model="selectedBrand" placeholder="品牌" clearable size="default" style="width:140px" @change="refetch">
        <el-option v-for="b in brandOptions" :key="b.value" :label="b.label" :value="b.value" />
      </el-select>
      <el-date-picker v-model="filterDateRange" type="daterange" start-placeholder="开始" end-placeholder="结束" size="default" value-format="YYYY-MM-DD" style="width:260px" @change="refetch" />
    </div>
    <div class="panel"><div class="panel-head">良率趋势</div><div class="panel-body"><div ref="c1" class="chart chart-lg" /></div></div>
    <div class="panel-grid">
      <div class="panel"><div class="panel-head">白班 vs 夜班 对比</div><div class="panel-body"><div ref="c2" class="chart" /></div></div>
      <div class="panel"><div class="panel-head">工位良率排行</div><div class="panel-body"><div ref="c3" class="chart" /></div></div>
    </div>
    <div class="panel-grid">
      <div class="panel"><div class="panel-head">品牌良率对比</div><div class="panel-body"><div ref="c4" class="chart" /></div></div>
      <div class="panel"><div class="panel-head">
        30分钟粒度良率表
        <el-date-picker v-model="tableDate" type="date" placeholder="选择日期" size="small" value-format="YYYY-MM-DD" style="width:150px;margin-left:12px" @change="refetch" />
      </div><div class="panel-body p0">
        <el-table :data="detailTable" stripe size="default" max-height="340" class="data-table">
          <el-table-column prop="time" label="时间段" width="140" /><el-table-column prop="total" label="总数" width="70" />
          <el-table-column prop="ok" label="OK" width="60" /><el-table-column prop="ng" label="NG" width="60" />
          <el-table-column label="良率" width="80"><template #default="{row}"><span :style="{ color: row.yield >= 97 ? '#22c55e' : row.yield >= 94 ? '#f59e0b' : '#ef4444', fontWeight: 600 }">{{ row.yield }}%</span></template></el-table-column>
        </el-table>
      </div></div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, inject, watch, onMounted, nextTick } from 'vue'
import * as echarts from 'echarts'
import { getYieldTrend, getProductShiftYield, getStationRanking, getBrandCompare } from '@/api/stats'

const c1 = ref(null), c2 = ref(null), c3 = ref(null), c4 = ref(null)
let charts = []

/* ---- Filter ---- */
const filterParams = inject('filterParams', ref({ factoryId: null, lineIds: [], stationIds: [] }))
const filterLabels = inject('filterLabels', ref({ factoryName: '', lineNames: [], stationNames: [] }))
const today = new Date(Date.now() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 10)
const defaultStartDate = '2026-01-01'
const tableDate = ref(today)
const granularity = ref('30min')
const selectedBrand = ref(null)
const filterDateRange = ref([defaultStartDate, today])
const brandOptions = [
  { value: 1, label: '亲宝宝' }, { value: 2, label: '狮子王国' }, { value: 3, label: 'BC夏季' }
]

const filterInfo = computed(() => {
  const { factoryId, lineIds, stationIds } = filterParams.value
  if (!factoryId && lineIds.length === 0 && stationIds.length === 0) return ''
  const parts = []
  if (factoryId) parts.push(filterLabels.value.factoryName || ('工厂' + factoryId))
  if (lineIds.length > 0) parts.push('产线: ' + (filterLabels.value.lineNames?.join('、') || (lineIds.length + '条')))
  if (stationIds.length > 0) parts.push('工位: ' + (filterLabels.value.stationNames?.join('、') || (stationIds.length + '个')))
  return parts.join(' · ')
})

const buildCommonParams = () => {
  const dr = filterDateRange.value
  return {
    lineId: filterParams.value.lineIds?.length === 1 ? filterParams.value.lineIds[0] : undefined,
    brandId: selectedBrand.value || undefined,
    startDate: dr?.length === 2 ? dr[0] : today,
    endDate: dr?.length === 2 ? dr[1] : today,
    granularity: granularity.value,
  }
}

/* ---- Reactive data ---- */
const kpiData = ref({ overall: 0, day: null, night: null, best: '' })
const yieldTrend = ref({ timeLabels: [], totalCounts: [], yieldRates: [], thresholdLine: 95 })
const shiftCompare = ref({ dateLabels: [], dayYieldRates: [], nightYieldRates: [] })
const stationRanking = ref([])
const brandCompare = ref({ dateLabels: [], brandSeries: [] })
const detailTable = ref([])
const formatKpiPercent = (value) => value == null ? '暂无' : `${value}%`

const normalizeRate = (value) => {
  const n = Number(value || 0)
  return n > 0 && n <= 1 ? +(n * 100).toFixed(2) : +n.toFixed(2)
}

const weightedYield = (totals = [], rates = []) => {
  let total = 0
  let ok = 0
  totals.forEach((count, i) => {
    const c = Number(count || 0)
    if (c <= 0) return
    total += c
    ok += c * normalizeRate(rates[i]) / 100
  })
  return total > 0 ? +(ok / total * 100).toFixed(1) : 0
}

const fetchAll = async () => {
  const p = buildCommonParams()
  try {
    const shiftParams = {
      brandId: selectedBrand.value || undefined,
      startDate: p.startDate,
      endDate: p.endDate
    }
    const [yt, sy, sr, bc] = await Promise.all([
      getYieldTrend(p).catch(() => null),
      getProductShiftYield(shiftParams).catch(() => null),
      getStationRanking(p).catch(() => null),
      getBrandCompare(p).catch(() => null),
    ])

    if (yt) {
      const d = yt.data?.data || yt.data || yt
      const normalizedRates = (d.yieldRates || []).map(normalizeRate)
      yieldTrend.value = { ...d, yieldRates: normalizedRates }
      // Build detail table from yield trend
      detailTable.value = (d.timeLabels || []).map((label, i) => ({
        time: label,
        total: d.totalCounts?.[i] || 0,
        ok: Math.round((d.totalCounts?.[i] || 0) * (normalizedRates[i] || 0) / 100),
        ng: (d.totalCounts?.[i] || 0) - Math.round((d.totalCounts?.[i] || 0) * (normalizedRates[i] || 0) / 100),
        yield: normalizedRates[i] || 0,
      })).slice(-30)
      // Compute overall KPI
      kpiData.value.overall = weightedYield(d.totalCounts || [], normalizedRates)
    }

    if (sy) {
      const d = sy.data?.data || sy.data || sy
      const dayRates = (d.dayYieldRates || []).map(normalizeRate)
      const nightRates = (d.nightYieldRates || []).map(normalizeRate)
      shiftCompare.value = { ...d, dayYieldRates: dayRates, nightYieldRates: nightRates }
      const dayValid = dayRates.filter(v => v > 0)
      const nightValid = nightRates.filter(v => v > 0)
      const dayAvg = dayValid.length ? +((dayValid.reduce((a, b) => a + b, 0) / dayValid.length).toFixed(1)) : null
      const nightAvg = nightValid.length ? +((nightValid.reduce((a, b) => a + b, 0) / nightValid.length).toFixed(1)) : null
      kpiData.value.day = dayAvg
      kpiData.value.night = nightAvg
    }

    if (sr) {
      const d = sr.data?.data || sr.data || sr
      stationRanking.value = Array.isArray(d) ? d : []
      if (stationRanking.value.length) kpiData.value.best = stationRanking.value[0]?.stationName || ''
    }

    if (bc) {
      const d = bc.data?.data || bc.data || bc
      brandCompare.value = d
    }
  } catch (e) {
    console.warn('YieldView fetch error:', e)
  }
}

const initCharts = () => {
  charts.forEach(c => c.dispose()); charts = []

  // Yield trend
  if (c1.value && yieldTrend.value.timeLabels?.length) {
    const c = echarts.init(c1.value)
    const d = yieldTrend.value
    c.setOption({
      tooltip: { trigger: 'axis' }, grid: { t: 10, r: 20, b: 20, l: 45 },
      xAxis: { type: 'category', data: d.timeLabels, axisLabel: { interval: Math.max(1, Math.floor(d.timeLabels.length / 8)), fontSize: 10 } },
      yAxis: { type: 'value', min: 80, max: 100 },
      series: [{
        type: 'line', data: d.yieldRates || [], smooth: true,
        itemStyle: { color: '#3b82f6' }, areaStyle: { color: 'rgba(59,130,246,.08)' },
        markLine: { data: [{ yAxis: d.thresholdLine || 95, lineStyle: { color: '#ef4444', type: 'dashed' }, label: { formatter: '阈值 ' + (d.thresholdLine || 95) + '%' } }] }
      }]
    }); charts.push(c)
  }

  // Shift compare
  if (c2.value && shiftCompare.value.dateLabels?.length) {
    const c = echarts.init(c2.value)
    const d = shiftCompare.value
    c.setOption({
      tooltip: {}, legend: { data: ['白班', '夜班'] },
      grid: { t: 10, r: 20, b: 20, l: 45 },
      xAxis: { type: 'category', data: d.dateLabels, axisLabel: { fontSize: 10 } },
      yAxis: { type: 'value', min: 80, max: 100 },
      series: [
        { name: '白班', type: 'bar', data: d.dayYieldRates || [], itemStyle: { color: '#3b82f6', borderRadius: [4, 4, 0, 0] } },
        { name: '夜班', type: 'bar', data: d.nightYieldRates || [], itemStyle: { color: '#93c5fd', borderRadius: [4, 4, 0, 0] } },
      ]
    }); charts.push(c)
  }

  // Station ranking
  if (c3.value && stationRanking.value.length) {
    const c = echarts.init(c3.value)
    const top10 = stationRanking.value.slice(0, 10)
    c.setOption({
      tooltip: {}, grid: { l: 90, r: 20, t: 10, b: 20 },
      xAxis: { type: 'value', min: 80, max: 100 },
      yAxis: { type: 'category', data: top10.map(s => s.stationName).reverse(), inverse: true },
      series: [{
        type: 'bar', data: top10.map(s => s.yieldRate).reverse(),
        itemStyle: { borderRadius: [0, 4, 4, 0], color: p => Number(p.value) >= 98 ? '#22c55e' : Number(p.value) >= 96 ? '#3b82f6' : '#f59e0b' },
        barMaxWidth: 22
      }]
    }); charts.push(c)
  }

  // Brand compare
  if (c4.value && brandCompare.value.brandSeries?.length) {
    const c = echarts.init(c4.value)
    const bs = brandCompare.value.brandSeries
    c.setOption({
      tooltip: {}, grid: { t: 10, r: 20, b: 20, l: 45 },
      xAxis: { type: 'category', data: bs.map(b => b.brandName) },
      yAxis: { type: 'value', min: 80, max: 100 },
      series: [{ type: 'bar', data: bs.map(b => b.avgYieldRate), itemStyle: { color: '#f59e0b', borderRadius: [4, 4, 0, 0] }, barMaxWidth: 48 }]
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
.panel { background: #fff; border-radius: 12px; overflow: hidden; box-shadow: 0 1px 3px rgba(0,0,0,.06); margin-bottom: 16px; }
.panel-head { padding: 16px 20px; font-size: 16px; font-weight: 600; color: #262626; border-bottom: 1px solid #e5e5e5; }
.panel-body { padding: 16px 20px; } .p0 { padding: 0; }
.chart { width: 100%; height: 320px; } .chart-lg { height: 360px; }
.data-table :deep(.el-table__row:hover) { background: #eff6ff !important; }
@media (max-width: 1280px) { .kpi-row { grid-template-columns: repeat(2, 1fr); } .panel-grid { grid-template-columns: 1fr; } }
</style>
