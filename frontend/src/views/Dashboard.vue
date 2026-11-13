<template>
  <div class="overview line-overview">
    <div class="filter-bar" v-if="filterInfo">
      <span class="filter-badge">{{ filterInfo }}</span>
    </div>

    <div class="page-intro">
      <div>
        <h2>产线经营总览</h2>
        <p>面向工厂负责人，聚焦产线产量、良率、NG 压力、缺陷风险与低效产线，不展示单台相机工作状态。</p>
      </div>
      <el-button :loading="loading" type="primary" @click="loadData">刷新数据</el-button>
    </div>

    <div class="kpi-row">
      <div class="kpi-card">
        <span class="kpi-label">产线数</span>
        <span class="kpi-value blue">{{ lineStats.length }}</span>
        <span class="kpi-sub">当前筛选范围内</span>
      </div>
      <div class="kpi-card">
        <span class="kpi-label">今日产量</span>
        <span class="kpi-value purple">{{ number(kpiData.todayTotal) }}</span>
        <span class="kpi-sub">已检测产品件数</span>
      </div>
      <div class="kpi-card">
        <span class="kpi-label">综合良率</span>
        <span class="kpi-value" :class="yieldClass(kpiData.todayYieldRate)">{{ kpiData.todayYieldRate }}%</span>
        <span class="kpi-sub">目标 ≥ 97%</span>
      </div>
      <div class="kpi-card">
        <span class="kpi-label">今日 NG</span>
        <span class="kpi-value red">{{ number(kpiData.todayNg) }}</span>
        <span class="kpi-sub">NG 率 {{ ngRate }}%</span>
      </div>
      <div class="kpi-card">
        <span class="kpi-label">风险产线</span>
        <span class="kpi-value orange">{{ riskLineCount }}</span>
        <span class="kpi-sub">良率低于 97% 或 NG 率偏高</span>
      </div>
      <div class="kpi-card">
        <span class="kpi-label">高危缺陷</span>
        <span class="kpi-value red">{{ defectKpi.severe || 0 }}</span>
        <span class="kpi-sub">今日严重缺陷</span>
      </div>
    </div>

    <div class="panel main-line-panel">
      <div class="panel-head">
        <span>产线绩效总览</span>
        <span class="head-sub">按产线聚合产量、OK/NG、良率、缺陷和风险等级</span>
      </div>
      <div class="panel-body p0">
        <el-table :data="linePage" v-loading="loading" stripe class="data-table" empty-text="暂无产线数据">
          <el-table-column type="index" label="#" width="56" :index="lineTableIndex" />
          <el-table-column prop="lineName" label="产线" min-width="170" />
          <el-table-column prop="stationCount" label="工位数" width="90" align="center" />
          <el-table-column prop="productCount" label="产品数" width="90" align="center" />
          <el-table-column label="产量" width="120" align="right"><template #default="{ row }">{{ number(row.total) }}</template></el-table-column>
          <el-table-column label="OK" width="110" align="right"><template #default="{ row }">{{ number(row.ok) }}</template></el-table-column>
          <el-table-column label="NG" width="110" align="right"><template #default="{ row }">{{ number(row.ng) }}</template></el-table-column>
          <el-table-column label="NG率" width="100" align="center"><template #default="{ row }"><span :class="row.ngRate > 5 ? 'r' : row.ngRate > 3 ? 'y' : 'g'">{{ row.ngRate }}%</span></template></el-table-column>
          <el-table-column label="良率" width="100" align="center"><template #default="{ row }"><span :class="yieldClass(row.yield)">{{ row.yield }}%</span></template></el-table-column>
          <el-table-column prop="defectCount" label="缺陷数" width="100" align="center" />
          <el-table-column label="风险" width="110" align="center"><template #default="{ row }"><el-tag :type="riskType(row.riskLevel)" size="small">{{ riskText(row.riskLevel) }}</el-tag></template></el-table-column>
        </el-table>
        <div class="pager-wrap">
          <el-pagination v-model:current-page="linePageNo" v-model:page-size="linePageSize" :page-sizes="pageSizeOptions" :total="lineStats.length" layout="total, sizes, prev, pager, next" size="small" background />
        </div>
      </div>
    </div>

    <div class="panel-grid">
      <div class="panel">
        <div class="panel-head">产线产量 / NG 对比</div>
        <div class="panel-body"><div ref="c1" class="chart" /></div>
      </div>
      <div class="panel">
        <div class="panel-head">产线良率排行</div>
        <div class="panel-body"><div ref="c2" class="chart" /></div>
      </div>
    </div>

    <div class="grid-3">
      <div class="panel">
        <div class="panel-head">低良率产线 Top</div>
        <div class="panel-body p0">
          <el-table :data="lowYieldLines" size="default" class="data-table" :show-header="false" empty-text="暂无低良率产线">
            <el-table-column type="index" width="48" />
            <el-table-column prop="lineName" />
            <el-table-column width="90"><template #default="{ row }"><span :class="yieldClass(row.yield)" style="font-weight:800">{{ row.yield }}%</span></template></el-table-column>
          </el-table>
        </div>
      </div>
      <div class="panel">
        <div class="panel-head">产品/品牌结构</div>
        <div class="panel-body"><div ref="c3" class="chart chart-sm" /></div>
      </div>
      <div class="panel">
        <div class="panel-head">缺陷类型分布</div>
        <div class="panel-body"><div ref="c4" class="chart chart-sm" /></div>
      </div>
    </div>

    <div class="panel" v-if="alerts.length" style="margin-top:16px">
      <div class="panel-head">实时告警 <span class="head-sub">共 {{ alerts.length }} 条</span></div>
      <div class="panel-body alert-grid">
        <div v-for="a in alerts" :key="a.id" class="alert-item" :class="'lv-'+a.level?.toLowerCase()">
          <span class="alert-icon">{{ a.level === 'CRITICAL' ? '⚠' : '!' }}</span>
          <div class="alert-body">
            <strong>{{ a.title }}</strong>
            <span class="alert-msg">{{ a.message }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, inject, watch, onMounted, nextTick } from 'vue'
import * as echarts from 'echarts'
import { getDashboardOverview, getDefectStats } from '@/api/stats'
import { getCameraList } from '@/api/camera'
import request from '@/utils/request'

const pageSizeOptions = [10, 20, 50, 100]
const linePageNo = ref(1)
const linePageSize = ref(10)
const c1 = ref(null), c2 = ref(null), c3 = ref(null), c4 = ref(null)
let charts = []

const filterParams = inject('filterParams', ref({ factoryId: null, lineIds: [], stationIds: [] }))
const filterLabels = inject('filterLabels', ref({ factoryName: '', lineNames: [], stationNames: [] }))

const loading = ref(false)
const kpiData = ref({ todayTotal: 0, todayOk: 0, todayNg: 0, todayYieldRate: 0 })
const cameraRows = ref([])
const overviewLineRanking = ref([])
const brandStats = ref([])
const defectOverview = ref(null)
const alerts = ref([])

const localDate = () => new Date(Date.now() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 10)
const toNumber = (v) => Number.isFinite(Number(v || 0)) ? Number(v || 0) : 0
const number = (v) => toNumber(v).toLocaleString()
const percent = (ok, total) => total ? Number((ok / total * 100).toFixed(2)) : 0
const ngRate = computed(() => kpiData.value.todayTotal ? Number((kpiData.value.todayNg / kpiData.value.todayTotal * 100).toFixed(1)) : 0)
const defectKpi = computed(() => defectOverview.value?.kpi || {})

const filterInfo = computed(() => {
  const { factoryId, lineIds, stationIds } = filterParams.value
  if (!factoryId && lineIds.length === 0 && stationIds.length === 0) return ''
  const parts = []
  if (factoryId) parts.push(`工厂: ${filterLabels.value.factoryName || ('ID' + factoryId)}`)
  if (lineIds.length > 0) parts.push(`产线: ${filterLabels.value.lineNames?.join('、') || (lineIds.length + ' 条')}`)
  if (stationIds.length > 0) parts.push(`工位: ${filterLabels.value.stationNames?.join('、') || (stationIds.length + ' 个')}`)
  return parts.join(' · ')
})

const lineNameMap = computed(() => {
  const map = new Map()
  overviewLineRanking.value.forEach(l => map.set(l.lineId, l.lineName || `产线 ${l.lineId}`))
  cameraRows.value.forEach(c => { if (c.lineName) map.set(c.lineId, c.lineName) })
  return map
})

const defectCountByLine = computed(() => {
  const stationToLine = new Map(cameraRows.value.map(c => [c.workstationId, c.lineId]))
  const agg = new Map()
  ;(defectOverview.value?.topDefects || []).forEach(d => {
    const lineId = stationToLine.get(d.stationId)
    if (!lineId) return
    agg.set(lineId, (agg.get(lineId) || 0) + 1)
  })
  return agg
})

const lineStats = computed(() => {
  const map = new Map()
  cameraRows.value.forEach(cam => {
    const lineId = cam.lineId || 0
    const item = map.get(lineId) || {
      lineId,
      lineName: lineNameMap.value.get(lineId) || (lineId ? `产线 ${lineId}` : '未配置产线'),
      ok: 0,
      ng: 0,
      total: 0,
      stationSet: new Set(),
      productSet: new Set(),
      defectCount: 0,
      yield: 0,
      ngRate: 0,
      riskLevel: 'LOW'
    }
    item.ok += toNumber(cam.okCount)
    item.ng += toNumber(cam.ngCount)
    item.total += toNumber(cam.totalCount)
    if (cam.workstationId) item.stationSet.add(cam.workstationId)
    if (cam.productName) item.productSet.add(cam.productName)
    map.set(lineId, item)
  })
  const rows = Array.from(map.values()).map(item => {
    item.yield = percent(item.ok, item.total)
    item.ngRate = item.total ? Number((item.ng / item.total * 100).toFixed(1)) : 0
    item.stationCount = item.stationSet.size
    item.productCount = item.productSet.size || 1
    item.defectCount = defectCountByLine.value.get(item.lineId) || 0
    item.riskLevel = item.yield < 94 || item.ngRate > 5 || item.defectCount >= 5 ? 'HIGH' : (item.yield < 97 || item.ngRate > 3 || item.defectCount > 0 ? 'MEDIUM' : 'LOW')
    delete item.stationSet
    delete item.productSet
    return item
  })
  return rows.sort((a, b) => b.total - a.total)
})

const linePage = computed(() => lineStats.value.slice((linePageNo.value - 1) * linePageSize.value, linePageNo.value * linePageSize.value))
const lineTableIndex = (index) => (linePageNo.value - 1) * linePageSize.value + index + 1
const lowYieldLines = computed(() => [...lineStats.value].sort((a, b) => a.yield - b.yield).slice(0, 5))
const riskLineCount = computed(() => lineStats.value.filter(l => l.riskLevel !== 'LOW').length)

const yieldClass = (value) => value >= 97 ? 'g' : value >= 94 ? 'y' : 'r'
const riskText = (level) => ({ HIGH: '高风险', MEDIUM: '关注', LOW: '正常' }[level] || '正常')
const riskType = (level) => ({ HIGH: 'danger', MEDIUM: 'warning', LOW: 'success' }[level] || 'info')

const unwrapPage = (res) => {
  const data = res?.data?.data ?? res?.data ?? {}
  if (Array.isArray(data.records)) return data.records
  if (Array.isArray(data)) return data
  return []
}

const loadData = async () => {
  loading.value = true
  const fp = filterParams.value
  try {
    const today = localDate()
    const [overviewRes, camRes, defRes] = await Promise.all([
      getDashboardOverview({ factoryId: fp.factoryId || undefined, date: today }),
      getCameraList({
        factoryId: fp.factoryId || undefined,
        lineId: fp.lineIds?.length === 1 ? fp.lineIds[0] : undefined,
        stationId: fp.stationIds?.length === 1 ? fp.stationIds[0] : undefined,
        page: 1,
        size: 999
      }),
      getDefectStats({ factoryId: fp.factoryId || undefined, startDate: today, endDate: today })
    ])
    const ov = overviewRes.data?.data || overviewRes.data || {}
    kpiData.value = {
      todayTotal: ov.todayTotal || 0,
      todayOk: ov.todayOk || 0,
      todayNg: ov.todayNg || 0,
      todayYieldRate: ov.todayYieldRate || 0
    }
    overviewLineRanking.value = ov.lineRanking || []
    brandStats.value = (ov.brandStats || []).map(b => ({ ...b, yield: b.yieldRate || b.yield || 0 }))

    const f = filterParams.value || {}
    const cams = unwrapPage(camRes).map(c => ({
      id: c.id,
      lineId: c.lineId,
      lineName: c.lineName || '',
      workstationId: c.workstationId,
      productName: c.productName || '',
      okCount: c.okCount || c.correctCount || 0,
      ngCount: c.ngCount || c.abnormalCount || 0,
      totalCount: c.totalCount || c.recognizeCount || 0
    })).filter(c => {
      if ((f.lineIds || []).length && !f.lineIds.includes(c.lineId)) return false
      if ((f.stationIds || []).length && !f.stationIds.includes(c.workstationId)) return false
      return true
    })
    cameraRows.value = cams

    if (!kpiData.value.todayTotal && cams.length) {
      const total = cams.reduce((s, c) => s + toNumber(c.totalCount), 0)
      const ok = cams.reduce((s, c) => s + toNumber(c.okCount), 0)
      const ng = cams.reduce((s, c) => s + toNumber(c.ngCount), 0)
      kpiData.value = { todayTotal: total, todayOk: ok, todayNg: ng, todayYieldRate: percent(ok, total) }
    }
    defectOverview.value = defRes.data?.data || defRes.data || null
    try {
      const alertRes = await request.get('/alerts/active')
      const ad = alertRes.data?.data || alertRes.data || {}
      alerts.value = (ad.alerts || []).filter(a => !`${a.title || ''}${a.message || ''}`.includes('相机离线'))
    } catch (e) { alerts.value = [] }
  } catch (e) {
    console.warn('产线总览加载失败', e)
    cameraRows.value = []
    overviewLineRanking.value = []
    brandStats.value = []
    defectOverview.value = null
  } finally {
    loading.value = false
    await nextTick()
    initCharts()
  }
}

const emptyGraphic = (show, text = '暂无数据') => show ? {
  type: 'text', left: 'center', top: 'middle', style: { text, fill: '#9ca3af', fontSize: 14, fontWeight: 500 }
} : undefined

const initCharts = () => {
  charts.forEach(c => c.dispose())
  charts = []
  const lines = lineStats.value
  if (c1.value) {
    const c = echarts.init(c1.value)
    c.setOption({
      tooltip: { trigger: 'axis' }, legend: { data: ['产量', 'NG'] }, graphic: emptyGraphic(!lines.length, '暂无产线产量数据'),
      grid: { t: 44, r: 20, b: 36, l: 58 },
      xAxis: { type: 'category', data: lines.map(l => l.lineName), axisLabel: { fontSize: 10 } }, yAxis: { type: 'value' },
      series: [
        { name: '产量', type: 'bar', data: lines.map(l => l.total), itemStyle: { color: '#3b82f6', borderRadius: [4, 4, 0, 0] }, barMaxWidth: 52 },
        { name: 'NG', type: 'bar', data: lines.map(l => l.ng), itemStyle: { color: '#ef4444', borderRadius: [4, 4, 0, 0] }, barMaxWidth: 52 }
      ]
    })
    charts.push(c)
  }
  if (c2.value) {
    const sorted = [...lines].sort((a, b) => b.yield - a.yield)
    const c = echarts.init(c2.value)
    c.setOption({
      tooltip: { trigger: 'axis' }, graphic: emptyGraphic(!sorted.length, '暂无良率数据'),
      grid: { t: 28, r: 24, b: 36, l: 52 },
      xAxis: { type: 'category', data: sorted.map(l => l.lineName), axisLabel: { fontSize: 10 } },
      yAxis: { type: 'value', min: 85, max: 100 },
      series: [{ name: '良率', type: 'bar', data: sorted.map(l => l.yield), itemStyle: { color: (p) => p.value >= 97 ? '#22c55e' : p.value >= 94 ? '#f59e0b' : '#ef4444', borderRadius: [4, 4, 0, 0] }, markLine: { data: [{ yAxis: 97, name: '目标' }], lineStyle: { color: '#22c55e', type: 'dashed' } } }]
    })
    charts.push(c)
  }
  if (c3.value) {
    const brands = brandStats.value
    const c = echarts.init(c3.value)
    c.setOption({
      tooltip: {}, graphic: emptyGraphic(!brands.length, '暂无产品/品牌数据'),
      series: [{ type: 'pie', radius: ['48%', '72%'], data: brands.map(b => ({ name: b.name || '未上报产品', value: b.total || 0 })), label: { fontSize: 11 } }]
    })
    charts.push(c)
  }
  if (c4.value) {
    const types = (defectOverview.value?.byType || []).map(t => ({ name: t.defectType, value: t.count }))
    const c = echarts.init(c4.value)
    c.setOption({
      tooltip: {}, graphic: emptyGraphic(!types.length, '暂无缺陷类型数据'),
      series: [{ type: 'pie', radius: ['48%', '72%'], data: types, label: { fontSize: 11 } }]
    })
    charts.push(c)
  }
}

onMounted(loadData)
watch(filterParams, () => { linePageNo.value = 1; loadData() }, { deep: true })
watch(linePageSize, () => { linePageNo.value = 1 })
</script>

<style scoped>
.overview { --brand-500:var(--color-primary-hover);--success:var(--color-success-light);--error:var(--color-error-light); }
.filter-bar { display: flex; align-items: center; margin-bottom: 12px; }
.filter-badge { background: var(--color-primary-bg); color: var(--color-primary); font-size: 13px; padding: 4px 14px; border-radius: 14px; font-weight: 500; }
.page-intro { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 18px; }
.page-intro h2 { margin: 0 0 6px; color: var(--color-text); font-size: 22px; }
.page-intro p { margin: 0; color: var(--color-text-secondary); font-size: 13px; }
.kpi-row { display: grid; grid-template-columns: repeat(6, 1fr); gap: 14px; margin-bottom: 20px; }
.kpi-card { background: var(--color-bg-card); border-radius: 12px; padding: 20px 18px; box-shadow: 0 1px 3px rgba(0,0,0,.06); display: flex; flex-direction: column; gap: 8px; }
.kpi-label { font-size: 13px; color: #737373; }
.kpi-value { font-size: 34px; font-weight: 800; line-height: 1; }
.kpi-value.blue { color:var(--color-primary-hover); } .kpi-value.purple { color:#8b5cf6; } .kpi-value.red, .r { color:var(--color-error-light); } .kpi-value.orange, .y { color:var(--color-warning); } .g { color:var(--color-success-light); }
.kpi-sub { font-size: 12px; color: var(--color-text-placeholder); }
.panel-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; margin-bottom: 20px; }
.grid-3 { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 20px; margin-bottom: 20px; }
.panel { background: var(--color-bg-card); border-radius: 12px; overflow: hidden; box-shadow: 0 1px 3px rgba(0,0,0,.06); }
.main-line-panel { margin-bottom: 20px; }
.panel-head { padding: 16px 20px; font-size: 16px; font-weight: 700; color: #262626; border-bottom: 1px solid #e5e5e5; display: flex; align-items: center; gap: 8px; }
.head-sub { font-weight: 400; font-size: 12px; color: #999; }
.panel-body { padding: 16px 20px; }
.p0 { padding: 0; }
.pager-wrap { padding: 12px; display: flex; justify-content: center; }
.chart { width: 100%; height: 330px; }
.chart-sm { height: 280px; }
.data-table :deep(.el-table__row:hover) { background: var(--color-primary-bg) !important; }
.g, .y, .r { font-weight: 700; }
.alert-grid { display:grid;grid-template-columns:repeat(2,1fr);gap:10px }
.alert-item { display: flex; gap: 10px; padding: 10px 14px; border-radius: 8px; font-size: 13px; }
.alert-item.lv-critical { background: #fef2f2; border-left: 3px solid var(--color-error-light); }
.alert-item.lv-warning { background: #fffbeb; border-left: 3px solid var(--color-warning); }
.alert-icon { font-size: 18px; flex-shrink: 0; }
.alert-body { display: flex; flex-direction: column; gap: 2px; }
.alert-msg { font-size: 12px; color: #737373; }
@media (max-width: 1280px) { .kpi-row { grid-template-columns: repeat(3, 1fr); } .panel-grid, .grid-3 { grid-template-columns: 1fr; } .page-intro { flex-direction: column; } }
</style>
