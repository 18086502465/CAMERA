<template>
  <div class="page" tabindex="0" @keydown="onKeydown">
    <div class="page-head">
      <h2>产线统计日报</h2>
      <span class="head-hint">① 选产线 → ② 点日期 → ③ 看当天各维度</span>
    </div>

    <!-- ========== 段① 产线切换（最上面，决定下面看谁） ========== -->
    <div class="scope-bar">
      <span class="scope-label">产线</span>
      <div class="scope-tabs">
        <button v-for="l in lines" :key="l.lineId" type="button"
          :class="['scope-tab', { active: l.lineId === lineId }]" @click="selectLine(l.lineId)">
          {{ l.lineName }}
        </button>
      </div>
      <div class="scope-actions">
        <el-button size="small" plain :loading="exporting" @click="handleExport">导出本月</el-button>
        <el-button size="small" type="primary" plain :loading="loading" @click="loadAll">刷新</el-button>
      </div>
    </div>

    <!-- ========== 段② 月历（点日期 → 下面换成那天） ========== -->
    <div class="panel">
      <div class="panel-head">
        <el-button link class="month-nav" @click="shiftMonth(-1)">‹</el-button>
        <b class="month-label">{{ monthLabel }}</b>
        <el-button link class="month-nav" @click="shiftMonth(1)">›</el-button>
        <el-button link size="small" class="today-btn" @click="goToday">回到今天</el-button>
        <span class="head-spacer"></span>
        <span class="legend">
          <span class="legend-label">缺陷量</span>
          <i class="lv lv1"></i><i class="lv lv2"></i><i class="lv lv3"></i><i class="lv lv4"></i>
          <span class="legend-label">多</span>
        </span>
      </div>

      <div class="panel-body">
        <div class="weekday-row">
          <span v-for="w in WEEKDAYS" :key="w">{{ w }}</span>
        </div>
        <div class="calendar" v-loading="loading">
          <div v-for="(blank, i) in leadingBlanks" :key="'b' + i" class="day-cell blank"></div>
          <div v-for="c in calendarDays" :key="c.date"
            :class="['day-cell', cellClass(c)]"
            :title="cellTitle(c)"
            @click="pickDate(c)">
            <div class="day-num">{{ Number(c.date.slice(8, 10)) }}</div>
            <template v-if="!c.isFuture">
              <template v-if="c.hasData">
                <div class="day-rate">{{ fmt(c.defectCount) }} 框</div>
                <div class="day-sub" :class="{ muted: c.counterRateKilo == null }">{{ rateText(c) }}</div>
              </template>
              <div v-else class="day-empty">{{ c.missingSnapshot ? '无快照' : '无数据' }}</div>
            </template>
            <span v-if="c.isToday" class="today-dot">今</span>
          </div>
        </div>
        <div class="cal-foot">
          <span>本月有数据 <b>{{ summary.dataDays || 0 }}</b> 天</span>
          <span>累计检测 <b>{{ fmt(summary.totalDetect) }}</b></span>
          <span>累计缺陷框 <b>{{ fmt(summary.totalDefect) }}</b></span>
          <span v-if="summary.avgCounterRateKilo != null">平均缺陷率 <b>{{ summary.avgCounterRateKilo }}‰</b></span>
          <span v-if="summary.worstDate">最多的一天 <b>{{ summary.worstDate }}</b>（{{ fmt(summary.worstDefect) }} 框）</span>
        </div>
      </div>
    </div>

    <!-- ========== 段③ 当日维度（换日期只刷这一段） ========== -->
    <div class="panel">
      <div class="panel-head">
        <b>{{ selectedDate || '—' }}</b>
        <span class="head-scope">{{ scopeName }}</span>
        <el-tag v-if="breakdown && !breakdown.missing" size="small" style="margin-left:8px" disable-transitions
          :type="breakdown.dataSource === 'REALTIME' ? 'warning' : 'info'">
          {{ breakdown.dataSource === 'REALTIME' ? '实时（当天未完）' : '日终快照' }}
        </el-tag>
        <span class="head-spacer"></span>
        <el-button v-if="isAdmin && selectedDate && selectedDate <= todayStr" link type="primary" size="small"
          :loading="regenerating" @click="handleRegenerate">补算这一天</el-button>
      </div>

      <div class="panel-body" v-loading="detailLoading">
        <el-empty v-if="breakdown && breakdown.missing" description="这一天没有日报快照（服务上线前或生成失败），可用「补算这一天」生成" />
        <template v-else-if="breakdown">
          <div class="kpi-row">
            <div class="kpi-card">
              <span class="kpi-label">检测总数（日增）</span>
              <strong class="kpi-value">{{ deltaText(kpi.inspDelta) }}</strong>
              <small>心跳计数差分</small>
            </div>
            <div class="kpi-card">
              <span class="kpi-label">缺陷数（日增）</span>
              <strong class="kpi-value err">{{ deltaText(kpi.ngDelta) }}</strong>
              <small>NG 计数差分</small>
            </div>
            <div class="kpi-card">
              <span class="kpi-label">缺陷率（‰ · 计数）</span>
              <strong class="kpi-value">{{ deltaText(kpi.counterRateKilo) }}</strong>
              <!-- ★ 2026-11-20 复测 D-4：计数列为 null 时原来只显示「待积累」，
                   用户会以为「这天没数据」。后端现在如实回传原因，这里显示出来。 -->
              <small>{{ kpi.counterPendingReason || 'NG 日增 / 检测日增 × 1000' }}</small>
            </div>
            <div class="kpi-card">
              <span class="kpi-label">在线相机</span>
              <strong class="kpi-value">{{ kpi.cameraOnline ?? 0 }}/{{ kpi.cameraTotal ?? 0 }}</strong>
              <small>当日有上传的 REAL 相机</small>
            </div>
          </div>

          <div class="grid4">
            <!-- 缺陷类型 TOP5 -->
            <div class="mini-card">
              <div class="mini-head">缺陷类型 TOP5<span class="mini-hint">按缺陷框数</span></div>
              <div v-if="(breakdown.typeBreakdown || []).length" class="bar-list">
                <div v-for="t in breakdown.typeBreakdown" :key="t.type" class="bar-item">
                  <span class="bar-name" :title="t.type">{{ t.type }}</span>
                  <span class="bar-track"><i :style="{ width: barWidth(t.count, maxTypeCount) }"></i></span>
                  <span class="bar-val">{{ fmt(t.count) }}<small v-if="t.share != null">{{ t.share }}%</small></span>
                </div>
              </div>
              <div v-else class="mini-empty">{{ detailsAvailable ? '当天无缺陷框' : '明细已归档（仅保留汇总）' }}</div>
              <!-- ★ D-3：TOP5 排行只列出前 5 种，这里补一句全量，
                   避免把「列出的几根柱子之和」误当成当日总量。 -->
              <div v-if="(breakdown.typeBreakdown || []).length" class="mini-foot">
                全量 {{ fmt(breakdown.detailDetectCountTotal ?? 0) }} 张 /
                {{ breakdown.detailCameraTotal ?? 0 }} 台相机有上传
              </div>
            </div>

            <!-- 相机维度 -->
            <div class="mini-card">
              <div class="mini-head">相机维度 TOP5
                <span class="mini-hint">
                  <!-- ★ 2026-11-20 复测 D-3：TOP10 是有上限的，
                       原来「明细合计」其实是 TOP10 之和，与上方「检测图片数」对不上
                       （实测 19812 vs 16455，差 16.9%）。后端现在回传全量与截断标记。 -->
                  <template v-if="breakdown.detailTruncated">
                    已列 {{ (breakdown.cameraBreakdown || []).length }}/{{ breakdown.detailCameraTotal }} 台（明细上限截断）
                  </template>
                  <template v-else>按缺陷框数</template>
                </span>
              </div>
              <div v-if="(breakdown.cameraBreakdown || []).length" class="bar-list">
                <div v-for="c in topCameras" :key="c.cameraCode" class="bar-item">
                  <span class="bar-name" :title="c.cameraName || c.cameraCode">{{ c.cameraName || c.cameraCode }}</span>
                  <span class="bar-track"><i :style="{ width: barWidth(c.defectCount, maxCameraCount) }"></i></span>
                  <span class="bar-val">{{ fmt(c.defectCount) }}<small>{{ fmt(c.detectCount) }} 张</small></span>
                </div>
              </div>
              <div v-else class="mini-empty">{{ detailsAvailable ? '当天无相机上传' : '明细已归档（仅保留汇总）' }}</div>
            </div>

            <!-- 时段分布 -->
            <div class="mini-card">
              <div class="mini-head">时段分布<span class="mini-hint">每根=1 小时缺陷框数</span></div>
              <div v-if="(breakdown.hourly || []).length" class="spark">
                <div v-for="h in breakdown.hourly" :key="h.hour" class="spark-col" :title="`${h.hour} 时：${h.count} 框`">
                  <i :style="{ height: barWidth(h.count, maxHourCount, 6) }"></i>
                </div>
                <div class="spark-axis">
                  <span>0</span><span>6</span><span>12</span><span>18</span><span>23</span>
                </div>
              </div>
              <div v-else class="mini-empty">{{ detailsAvailable ? '当天无上传' : '明细已归档（仅保留汇总）' }}</div>
            </div>

            <!-- 当日异常 -->
            <div class="mini-card">
              <div class="mini-head">当日异常<span class="mini-hint">只能当线索，不当结论</span></div>
              <ul class="anomaly-list">
                <li>
                  <span>温度告警</span>
                  <b :class="{ err: (anomalies.tempAlarmCount || 0) > 0 }">{{ anomalies.tempAlarmCount || 0 }} 条</b>
                </li>
                <li>
                  <span>离线相机</span>
                  <b :class="{ err: (anomalies.offlineCameras || 0) > 0 }">{{ anomalies.offlineCameras || 0 }} 台</b>
                </li>
                <li>
                  <span>白天 0 上传</span>
                  <b :class="{ warn: idleRanges.length > 0 }">{{ idleText }}</b>
                </li>
                <li>
                  <span>样本</span>
                  <b>{{ fmt(kpi.detectCount) }} 张<span v-if="kpi.sampleInsufficient" class="muted">（不足 100，仅供参考）</span></b>
                </li>
              </ul>
            </div>
          </div>

          <div class="caliber">
            统计口径：{{ caliber.rateFormula }} ｜ 相机范围：{{ caliber.cameraType }}
            ｜ 相机排名最低样本：{{ caliber.rankMinSample }} 张 ｜ 数据源：{{ breakdown.dataSource === 'REALTIME' ? '实时计算（当天）' : '日终快照' }}
            <br />{{ caliber.counterNote }}
            <br />{{ breakdown.idleNote }}
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, inject, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getDailyReportCalendar, getDailyReportBreakdown, regenerateDailyReport, exportDailyReport } from '@/api/analytics'
import { useUserStore } from '../../stores/user'

const userStore = useUserStore()
const isAdmin = computed(() => userStore.isAdmin)

// 布局级联筛选只在首次进入时作为默认产线参考（之后以页面内胶囊为准）
const filterParams = inject('filterParams', ref({ factoryId: null, lineIds: [], stationIds: [] }))

const WEEKDAYS = ['一', '二', '三', '四', '五', '六', '日']
const LINE_KEY = 'daily-report:lineId'

const month = ref(currentMonth())
const lineId = ref(initialLineId())
const lines = ref([{ lineId: 0, lineName: '全厂' }])
const calendarDays = ref([])
const summary = ref({})
const caliber = ref({})
const selectedDate = ref('')
const breakdown = ref(null)
const loading = ref(false)
const detailLoading = ref(false)
const exporting = ref(false)
const regenerating = ref(false)

function pad(n) { return n < 10 ? '0' + n : String(n) }
function todayStrOf(d) { return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}` }
function currentMonth() { const d = new Date(); return `${d.getFullYear()}-${pad(d.getMonth() + 1)}` }
const todayStr = todayStrOf(new Date())

function initialLineId() {
  try {
    const saved = Number(localStorage.getItem(LINE_KEY))
    if (Number.isFinite(saved) && saved > 0) return saved
  } catch (e) { /* 隐私模式忽略 */ }
  const injected = (filterParams.value?.lineIds || [])[0]
  return injected ? Number(injected) : 0
}

const monthLabel = computed(() => `${Number(month.value.slice(0, 4))} 年 ${Number(month.value.slice(5, 7))} 月`)
const scopeName = computed(() => (lines.value.find(l => l.lineId === lineId.value)?.lineName) || '全厂')

/** 当月 1 日是周几（周一起始）→ 前面补几个空格 */
const leadingBlanks = computed(() => {
  if (!calendarDays.value.length) return 0
  const first = calendarDays.value[0].date
  const d = new Date(`${first}T00:00:00`)
  return (d.getDay() + 6) % 7
})

const kpi = computed(() => breakdown.value?.kpi || {})
const anomalies = computed(() => breakdown.value?.anomalies || {})
const detailsAvailable = computed(() => breakdown.value?.detailsAvailable !== false)
const topCameras = computed(() => (breakdown.value?.cameraBreakdown || []).slice(0, 5))
const maxTypeCount = computed(() => Math.max(1, ...((breakdown.value?.typeBreakdown || []).map(t => Number(t.count) || 0))))
const maxCameraCount = computed(() => Math.max(1, ...((breakdown.value?.cameraBreakdown || []).map(t => Number(t.defectCount) || 0))))
const maxHourCount = computed(() => Math.max(1, ...((breakdown.value?.hourly || []).map(t => Number(t.count) || 0))))

/**
 * 0 上传小时：夜间（20:00–08:00）本来就不排产，全算进去会天天报「13 小时无上传」，
 * 变成噪音。所以只把**白天 08:00–20:00**的 0 上传当异常线索，夜间段单独提一句。
 */
const idleRanges = computed(() => {
  const day = (anomalies.value.idleHours || []).filter(h => h >= 8 && h <= 19)
  if (!day.length) return []
  const ranges = []
  let start = day[0], prev = day[0]
  for (let i = 1; i < day.length; i++) {
    if (day[i] === prev + 1) { prev = day[i]; continue }
    ranges.push([start, prev]); start = day[i]; prev = day[i]
  }
  ranges.push([start, prev])
  return ranges.map(([a, b]) => (a === b ? `${pad(a)}:00` : `${pad(a)}:00–${pad(b + 1)}:00`))
})

const idleText = computed(() => {
  const night = (anomalies.value.idleHours || []).filter(h => h < 8 || h > 19).length
  if (!idleRanges.value.length) return night ? '无（夜间未排产）' : '无'
  return `${idleRanges.value.join('、')}`
})

function fmt(n) {
  if (n === null || n === undefined || Number.isNaN(Number(n))) return '—'
  return Number(n).toLocaleString('zh-CN')
}
function deltaText(v) {
  if (v === null || v === undefined) return '待积累'
  return fmt(v)
}
function barWidth(v, max, minPct = 0) {
  const pct = max > 0 ? (Number(v) || 0) * 100 / max : 0
  return `max(${pct.toFixed(1)}%, ${minPct}px)`
}

/**
 * 日历格副值：缺陷率。
 *
 * 为什么历史日大多是「率待积累」：缺陷率 = NG 计数日增 / 检测总数日增，依赖心跳累计值
 * 的日终快照差分；补算历史日期时当前计数已含之后的上传，拍下来必然算错，所以补算一律
 * 不拍计数（见 generateFor 的 stampCounters 语义）。图片通道又每张必带缺陷框（图片口径
 * 恒 1000‰），不能拿来充数。结论：历史日只给「缺陷量」，缺陷率从第二份日终快照起才有。
 */
function rateText(c) {
  if (c.counterRateKilo != null) return `${c.counterRateKilo}‰`
  return '率待积累'
}

/**
 * 色阶分档：按「当月有数据日的缺陷框数」分位（P50/P75/P90），而不是固定阈值 ——
 * 现场缺陷量跨月波动大，固定阈值会让某个月全红或全浅，失去区分度。
 */
const thresholds = computed(() => {
  const vals = calendarDays.value.filter(c => c.hasData).map(c => Number(c.defectCount) || 0).sort((a, b) => a - b)
  if (!vals.length) return []
  const q = p => vals[Math.min(vals.length - 1, Math.floor(vals.length * p))]
  return [q(0.5), q(0.75), q(0.9)]
})

function cellClass(c) {
  if (c.isFuture) return 'future'
  if (!c.hasData) return 'nodata'
  const v = Number(c.defectCount) || 0
  const [p50, p75, p90] = thresholds.value
  let lv = 1
  if (v > p90) lv = 4
  else if (v > p75) lv = 3
  else if (v > p50) lv = 2
  return [`hasdata`, `lv${lv}`, { selected: c.date === selectedDate.value, today: c.isToday }]
}

function cellTitle(c) {
  if (c.isFuture) return `${c.date}（未来）`
  if (!c.hasData) return `${c.date}：${c.missingSnapshot ? '无快照' : '无检测数据'}`
  const parts = [`${c.date}`, `缺陷框 ${c.defectCount}`, `检测图片 ${c.detectCount}`]
  if (c.counterRateKilo != null) parts.push(`缺陷率 ${c.counterRateKilo}‰`)
  if (c.tempAlarmCount) parts.push(`温度告警 ${c.tempAlarmCount}`)
  return parts.join(' · ')
}

async function loadCalendar() {
  loading.value = true
  try {
    const params = { month: month.value }
    if (lineId.value > 0) params.lineId = lineId.value
    const res = await getDailyReportCalendar(params)
    const data = res.data?.data || {}
    calendarDays.value = data.days || []
    summary.value = data.summary || {}
    caliber.value = data.caliber || {}
    if (Array.isArray(data.lines) && data.lines.length) lines.value = data.lines
    ensureSelection()
  } catch (e) {
    ElMessage.error('加载月历失败：' + (await errorMessage(e)))
  } finally { loading.value = false }
}

/** 选中日期：保持当前选中（同月内）；否则今天；再否则最近一个有数据的日子 */
function ensureSelection() {
  const days = calendarDays.value
  if (!days.length) return
  const keep = days.find(d => d.date === selectedDate.value && !d.isFuture)
  if (keep) return
  const today = days.find(d => d.isToday)
  if (today) { selectedDate.value = today.date; loadBreakdown(); return }
  const lastData = [...days].reverse().find(d => d.hasData && !d.isFuture)
  if (lastData) { selectedDate.value = lastData.date; loadBreakdown() }
}

async function loadBreakdown() {
  if (!selectedDate.value) return
  detailLoading.value = true
  try {
    const params = lineId.value > 0 ? { lineId: lineId.value } : {}
    const res = await getDailyReportBreakdown(selectedDate.value, params)
    breakdown.value = res.data?.data || null
    if (breakdown.value?.caliber) caliber.value = breakdown.value.caliber
  } catch (e) {
    breakdown.value = null
    ElMessage.error('加载当日维度失败：' + (await errorMessage(e)))
  } finally { detailLoading.value = false }
}

function pickDate(c) {
  if (c.isFuture) return
  if (c.date === selectedDate.value) return
  selectedDate.value = c.date
  loadBreakdown()
}

function selectLine(id) {
  if (id === lineId.value) return
  lineId.value = id
  try { localStorage.setItem(LINE_KEY, String(id)) } catch (e) { /* 忽略 */ }
  selectedDate.value = ''
  loadCalendar()
}

function shiftMonth(delta) {
  const [y, m] = month.value.split('-').map(Number)
  const d = new Date(y, m - 1 + delta, 1)
  month.value = `${d.getFullYear()}-${pad(d.getMonth() + 1)}`
  selectedDate.value = ''
  loadCalendar()
}

function goToday() {
  month.value = todayStr.slice(0, 7)
  selectedDate.value = todayStr
  loadCalendar().then(() => loadBreakdown())
}

function onKeydown(e) {
  if (!selectedDate.value) return
  if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
    const idx = calendarDays.value.findIndex(d => d.date === selectedDate.value)
    if (idx < 0) return
    const next = calendarDays.value[idx + (e.key === 'ArrowRight' ? 1 : -1)]
    if (!next || next.isFuture) return
    e.preventDefault()
    pickDate(next)
  } else if (e.key === 'Home') {
    e.preventDefault()
    goToday()
  }
}

async function loadAll() {
  await loadCalendar()
  if (selectedDate.value) await loadBreakdown()
}

async function handleRegenerate() {
  if (!selectedDate.value) return
  try {
    await ElMessageBox.confirm(`重算 ${selectedDate.value} 的日报快照？重跑幂等覆盖，不影响其他日期。`, '补算确认', { type: 'warning' })
  } catch (e) { return }
  regenerating.value = true
  try {
    const res = await regenerateDailyReport(selectedDate.value)
    const body = res.data || {}
    if (body.code && Number(body.code) !== 200) throw new Error(body.message || '补算失败')
    ElMessage.success('补算完成')
    await loadAll()
  } catch (e) {
    ElMessage.error('补算失败：' + (await errorMessage(e)))
  } finally { regenerating.value = false }
}

async function handleExport() {
  exporting.value = true
  try {
    const [y, m] = month.value.split('-').map(Number)
    const lastDay = new Date(y, m, 0).getDate()
    const params = { startDate: `${month.value}-01`, endDate: `${month.value}-${pad(lastDay)}` }
    if (lineId.value > 0) params.lineId = lineId.value
    const res = await exportDailyReport(params)
    const blob = new Blob([res.data], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `产线统计日报-${scopeName.value}-${month.value}.xlsx`
    a.click()
    URL.revokeObjectURL(url)
    ElMessage.success('导出成功')
  } catch (e) {
    ElMessage.error('导出失败：' + (await errorMessage(e)))
  } finally { exporting.value = false }
}

/**
 * 提取错误文案。
 *
 * ★ 2026-11-20 复测：导出用 `responseType: 'blob'` → **错误响应体也是 Blob**
 *   （不是 JSON 对象）→ `e.response.data.message` 是 undefined
 *   → 只能显示「未知错误」，用户完全不知道哪里错了。
 *   现在对 Blob 类型先读出文本再解析 JSON，取出 message。
 */
async function errorMessage(e) {
  const data = e?.response?.data
  if (data instanceof Blob) {
    try {
      const text = await data.text()
      const parsed = JSON.parse(text)
      if (parsed?.message) return parsed.message
      if (text && text.length < 200) return text
    } catch (_) {
      // 非 JSON（极少见），落下面的兜底
    }
    return e?.message || '未知错误'
  }
  return data?.message || e?.message || String(e)
}

// 顶部布局级联的产线是全局筛选，变化时跟随（页面内胶囊点击不回写级联，两者不打架）
watch(() => (filterParams.value?.lineIds || []).join(','), (v, old) => {
  if (v === old) return
  const next = Number((filterParams.value?.lineIds || [])[0] || 0)
  if (next === lineId.value) return
  lineId.value = next
  selectedDate.value = ''
  loadCalendar()
})

onMounted(loadAll)
</script>

<style scoped>
.page { padding: 16px 20px; outline: none; }
.page-head { display: flex; align-items: baseline; gap: 12px; margin-bottom: 10px; }
.page-head h2 { margin: 0; font-size: 18px; color: var(--color-text); }
.head-hint { font-size: 12px; color: var(--color-text-secondary); }

/* ---------- 段① 产线切换 ---------- */
.scope-bar {
  display: flex; align-items: center; gap: 12px; flex-wrap: wrap;
  background: var(--color-bg-card); border: 1px solid var(--color-border);
  border-radius: 8px; padding: 10px 14px; margin-bottom: 14px;
}
.scope-label { font-size: 12px; color: var(--color-text-secondary); }
.scope-tabs { display: flex; gap: 6px; flex-wrap: wrap; flex: 1; }
.scope-tab {
  border: 1px solid var(--color-border); background: var(--color-bg-card);
  color: var(--color-text-regular); border-radius: 16px; padding: 5px 14px;
  font-size: 13px; cursor: pointer; transition: all .15s;
}
.scope-tab:hover { border-color: var(--color-border-hover); color: var(--color-primary); }
.scope-tab.active { background: var(--color-primary); border-color: var(--color-primary); color: #fff; font-weight: 600; }
.scope-actions { display: flex; gap: 8px; }

/* ---------- 段② 月历 ---------- */
.panel { background: var(--color-bg-card); border: 1px solid var(--color-border); border-radius: 8px; margin-bottom: 14px; overflow: hidden; }
.panel-head { padding: 10px 14px; display: flex; align-items: center; gap: 6px; border-bottom: 1px solid var(--color-divider); }
.month-nav { font-size: 18px; padding: 0 4px; }
.month-label { font-size: 15px; color: var(--color-text); }
.today-btn { margin-left: 8px; }
.head-spacer { flex: 1; }
.head-scope { font-size: 12px; color: var(--color-text-secondary); margin-left: 8px; }
.legend { display: flex; align-items: center; gap: 4px; font-size: 12px; color: var(--color-text-secondary); }
.legend-label { margin: 0 2px; }
.legend .lv { display: inline-block; width: 14px; height: 12px; border-radius: 2px; }
.panel-body { padding: 12px 14px; }

.weekday-row { display: grid; grid-template-columns: repeat(7, 1fr); gap: 6px; margin-bottom: 6px; }
.weekday-row span { text-align: center; font-size: 12px; color: var(--color-text-secondary); }
.calendar { display: grid; grid-template-columns: repeat(7, 1fr); gap: 6px; }
.day-cell {
  position: relative; min-height: 74px; border-radius: 6px; padding: 6px 8px;
  border: 1px solid var(--color-border); background: var(--color-bg-card);
  cursor: pointer; transition: box-shadow .15s, border-color .15s;
}
.day-cell.blank { border: none; background: transparent; cursor: default; }
.day-cell.future { background: var(--color-bg-subtle); border-style: dashed; cursor: default; color: var(--color-text-placeholder); }
.day-cell.nodata { background: var(--color-bg-subtle); border-color: var(--color-border); cursor: pointer; }
.day-cell.hasdata:hover { border-color: var(--color-border-hover); }
.day-cell.selected { border-color: var(--color-primary); box-shadow: 0 0 0 2px var(--color-primary-bg) inset, 0 0 0 1px var(--color-primary); }
.day-num { font-size: 12px; color: var(--color-text-secondary); }
.day-rate { font-size: 15px; font-weight: 700; color: var(--color-text); margin-top: 2px; }
.day-sub { font-size: 11px; color: var(--color-text-secondary); }
.day-empty { font-size: 11px; color: var(--color-text-placeholder); margin-top: 6px; }
.today-dot {
  position: absolute; top: 4px; right: 6px; font-size: 10px; color: #fff;
  background: var(--color-primary); border-radius: 8px; padding: 0 4px; line-height: 14px;
}
/* 热力档位：色阶由数据分位驱动，只能用 error 色的透明度梯度表达，文字保持深色可读 */
.day-cell.lv1 { background: color-mix(in srgb, var(--color-error) 7%, var(--color-bg-card)); }
.day-cell.lv2 { background: color-mix(in srgb, var(--color-error) 16%, var(--color-bg-card)); }
.day-cell.lv3 { background: color-mix(in srgb, var(--color-error) 30%, var(--color-bg-card)); }
.day-cell.lv4 { background: color-mix(in srgb, var(--color-error) 48%, var(--color-bg-card)); }
.day-cell.lv4 .day-num, .day-cell.lv4 .day-sub { color: var(--color-text-regular); }
.lv1 { background: color-mix(in srgb, var(--color-error) 7%, var(--color-bg-card)); }
.lv2 { background: color-mix(in srgb, var(--color-error) 16%, var(--color-bg-card)); }
.lv3 { background: color-mix(in srgb, var(--color-error) 30%, var(--color-bg-card)); }
.lv4 { background: color-mix(in srgb, var(--color-error) 48%, var(--color-bg-card)); }

.cal-foot {
  display: flex; gap: 18px; flex-wrap: wrap; margin-top: 10px; padding-top: 8px;
  border-top: 1px dashed var(--color-border); font-size: 12px; color: var(--color-text-secondary);
}
.cal-foot b { color: var(--color-text); }

/* ---------- 段③ 当日维度 ---------- */
.kpi-row { display: flex; gap: 10px; flex-wrap: wrap; }
.kpi-card {
  flex: 1; min-width: 150px; background: var(--color-bg-subtle); border-radius: 8px;
  padding: 10px 14px; display: flex; flex-direction: column; gap: 2px;
}
.kpi-label { font-size: 12px; color: var(--color-text-secondary); }
.kpi-value { font-size: 22px; font-weight: 700; color: var(--color-text); }
.kpi-value.err { color: var(--color-error); }
.kpi-card small { font-size: 11px; color: var(--color-text-placeholder); }

.grid4 { display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px; margin-top: 12px; }
.mini-card { border: 1px solid var(--color-border); border-radius: 8px; padding: 10px 12px; background: var(--color-bg-card); }
.mini-head { font-size: 13px; font-weight: 600; color: var(--color-text); display: flex; align-items: baseline; gap: 8px; margin-bottom: 8px; }
.mini-hint { font-size: 11px; font-weight: 400; color: var(--color-text-placeholder); }
/* ★ D-3：明细全量脚注 */
.mini-foot { margin-top: 8px; padding-top: 8px; border-top: 1px dashed var(--color-border); color: var(--color-text-placeholder); font-size: 11px; line-height: 1.5; }
.mini-empty { font-size: 12px; color: var(--color-text-placeholder); padding: 14px 0; text-align: center; }

.bar-list { display: flex; flex-direction: column; gap: 7px; }
.bar-item { display: grid; grid-template-columns: 68px 1fr 74px; align-items: center; gap: 8px; }
.bar-name { font-size: 12px; color: var(--color-text-regular); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.bar-track { height: 8px; background: var(--color-bg-subtle); border-radius: 4px; overflow: hidden; }
.bar-track i { display: block; height: 100%; background: var(--color-error-light); border-radius: 4px; }
.bar-val { font-size: 12px; color: var(--color-text); text-align: right; }
.bar-val small { color: var(--color-text-placeholder); margin-left: 4px; }

.spark { display: flex; align-items: flex-end; gap: 3px; height: 88px; position: relative; padding-bottom: 14px; }
.spark-col { flex: 1; height: 100%; display: flex; align-items: flex-end; }
.spark-col i { display: block; width: 100%; min-height: 0; background: var(--color-warning); border-radius: 2px 2px 0 0; }
.spark-axis { position: absolute; left: 0; right: 0; bottom: 0; display: flex; justify-content: space-between; font-size: 10px; color: var(--color-text-placeholder); }

.anomaly-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
.anomaly-list li { display: flex; justify-content: space-between; align-items: baseline; font-size: 12px; color: var(--color-text-secondary); gap: 10px; }
.anomaly-list b { font-size: 13px; color: var(--color-text); text-align: right; font-weight: 600; }
.anomaly-list b.err { color: var(--color-error); }
.anomaly-list b.warn { color: var(--color-warning-dark); }

.caliber {
  margin-top: 12px; font-size: 12px; color: var(--color-text-secondary);
  border-top: 1px dashed var(--color-border); padding-top: 8px; line-height: 1.7;
}
.muted { color: var(--color-text-placeholder); font-weight: 400; }

@media (max-width: 1100px) {
  .grid4 { grid-template-columns: 1fr; }
}
</style>
