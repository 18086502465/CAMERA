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
        <span class="target-setting">
          <span class="legend-label">目标</span>
          <input class="target-input" type="number" min="0" step="100" v-model.number="targetBoxes"
            @change="persistTarget" title="每日缺陷框数目标线；留空或 0 表示未设目标" />
          <span class="legend-label">框/天</span>
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
            <span v-if="isBest(c)" class="day-badge best" title="本月最佳日（缺陷框最少，已排除今天）">优</span>
            <span v-else-if="isWorst(c)" class="day-badge worst" title="本月最差日（缺陷框最多，已排除今天）">差</span>
            <template v-if="!c.isFuture">
              <template v-if="c.hasData">
                <div class="day-rate">{{ fmt(c.defectCount) }} 框</div>
                <div class="day-sub" :class="{ muted: c.counterRateKilo == null }">{{ rateText(c) }}</div>
                <div v-if="dodOf(c) != null" class="day-dod" :class="dodOf(c)>0?'up':(dodOf(c)<0?'down':'flat')"
                  :title="dodTitle(c)">
                  {{ dodText(c) }}
                </div>
                <div v-if="overTarget(c)" class="day-over" title="缺陷框数超过设定的目标线">超标</div>
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
          <span v-if="summary.worstDate">最多 <b>{{ summary.worstDate }}</b>（{{ fmt(summary.worstDefect) }} 框）</span>
          <span v-if="summary.bestDate">最少 <b>{{ summary.bestDate }}</b>（{{ fmt(summary.bestDefect) }} 框）</span>
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
          <!-- ★ P1-3：明细筛选（工位 / 相机 / 缺陷类型），真正落到四个明细块 -->
          <div class="detail-filter">
            <span class="df-label">明细筛选</span>
            <el-select v-model="detailFilters.workstationId" clearable placeholder="全部工位" size="small"
              style="width:130px" @change="loadBreakdown">
              <el-option v-for="w in workstationOptions" :key="w.workstationId" :label="w.workstationName || w.stationName || w.workstationId" :value="w.workstationId" />
            </el-select>
            <el-select v-model="detailFilters.cameraId" clearable filterable placeholder="全部相机" size="small"
              style="width:150px" @change="loadBreakdown">
              <el-option v-for="c in cameraOptions" :key="c.id ?? c.cameraId" :label="c.cameraName || c.cameraCode || c.label" :value="c.id ?? c.cameraId" />
            </el-select>
            <el-select v-model="detailFilters.shiftId" clearable placeholder="全部班次" size="small"
              style="width:120px" @change="loadBreakdown">
              <el-option v-for="s in shiftOptions" :key="s.shiftId" :label="s.shiftName" :value="s.shiftId" />
            </el-select>
            <el-select v-model="detailFilters.defectType" clearable filterable allow-create placeholder="全部缺陷类型" size="small"
              style="width:150px" @change="loadBreakdown">
              <el-option v-for="t in defectTypeOptions" :key="t.value" :label="t.label" :value="t.value" />
            </el-select>
            <el-button link size="small" @click="resetDetailFilters">清除</el-button>
            <span v-if="breakdown.detailFilterMessage" class="df-note">{{ breakdown.detailFilterMessage }}</span>
          </div>
          <!-- 今日质量判断条：把散落的 KPI 收敛成一句可拍板结论；数据不足时诚实说不确定 -->
          <div class="verdict" :class="verdict.level">
            <span class="verdict-dot"></span>
            <b class="verdict-label">{{ verdict.label }}</b>
            <span class="verdict-text">{{ verdict.text }}</span>
          </div>
          <!-- ★ G20：明细筛选激活时，明确标注 KPI 区口径边界，避免厂长误以为已筛选 -->
          <div v-if="detailFilterActive" class="kpi-scope-note">
            <b>口径提示</b>：下方 KPI 与月历为<b>所选产线整体</b>，未随上方明细筛选（
            <template v-if="breakdown.detailFilterNames">{{ breakdown.detailFilterNames.join('、') }}</template>
            <template v-else>工位/相机/班次/缺陷类型</template>）变化；筛选结果见下方四宫格。
          </div>
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
            <div class="kpi-card" :title="caliber.ratePlain">
              <span class="kpi-label">缺陷率（‰ · 计数）<span class="plain-tag">?</span></span>
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
            <!-- ★ P1-2：心跳口径直通率（设备 correctCount 差分；语义待校准，绝不当良率显示） -->
            <div class="kpi-card" :title="caliber.passPlain">
              <span class="kpi-label">直通率 <span class="plain-tag">?</span> <span class="calib-tag">心跳口径·待校准</span></span>
              <strong class="kpi-value">{{ kpi.heartbeatPassRate != null ? kpi.heartbeatPassRate + '%' : '待积累' }}</strong>
              <small>{{ caliber.heartbeatPassNote || '识别正确计数差分，非终检良率' }}</small>
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
              <div class="mini-head">异常全景<span class="mini-hint">五类相机告警 · 只能当线索，不当结论</span></div>
              <!-- ★ P0-4：五类告警当日数 + 近 7 日趋势迷你条 -->
              <div v-if="alertTypes.length" class="alert-types">
                <div v-for="t in alertTypes" :key="t.type" class="alert-type-row">
                  <span class="alert-name" :title="t.type">{{ t.label }}</span>
                  <span class="alert-spark" :title="alertTrendTitle(t)">
                    <i v-for="(n, i) in t.daily" :key="i" :style="{ height: barWidth(n, alertMax, 2) }"
                      :class="{ hot: n > 0 }"></i>
                  </span>
                  <b class="alert-total" :class="{ err: t.total > 0 }">{{ t.total }}</b>
                </div>
                <div class="alert-axis"><span>7 日前</span><span>今日</span></div>
              </div>
              <div v-else class="mini-empty">{{ alertNote || '近 7 日无相机告警' }}</div>
              <ul class="anomaly-list">
                <li>
                  <span>温度告警（当日）</span>
                  <b :class="{ err: (anomalies.tempAlarmCount || 0) > 0 }">{{ anomalies.tempAlarmCount || 0 }} 条</b>
                </li>
                <li>
                  <span>未上报相机</span>
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

          <!-- ★ P1-1：设备健康趋势（心跳历史采样，分钟级；无历史时诚实提示） -->
          <div class="device-trend">
            <div class="mini-head">
              设备健康趋势
              <el-select v-model="trendCameraCode" clearable filterable size="small" placeholder="选相机看趋势"
                style="width:170px" @change="loadHeartbeatTrend">
                <el-option v-for="c in cameraOptions" :key="c.cameraCode || c.code || c.id"
                  :label="c.cameraName || c.cameraCode || c.label" :value="c.cameraCode || c.code" />
              </el-select>
              <span class="mini-hint">近 24h · 心跳每分钟采样，无数据不伪造</span>
            </div>
            <div v-if="trendCameraCode && trendPoints.length" class="trend-chart">
              <svg viewBox="0 0 600 90" preserveAspectRatio="none" class="trend-svg">
                <polyline :points="trendLinePoints" fill="none" stroke="var(--color-primary)" stroke-width="2" />
                <line v-if="trendWarnY != null" x1="0" :y1="trendWarnY" x2="600" :y2="trendWarnY"
                  stroke="var(--color-error)" stroke-width="1" stroke-dasharray="4 3" />
              </svg>
              <div class="trend-axis"><span>24 小时前</span><span>温度峰值 {{ trendMax }}℃ · 最低 {{ trendMin }}℃</span><span>现在</span></div>
            </div>
            <div v-else class="mini-empty">
              {{ trendCameraCode ? trendNote || '该相机近 24h 暂无心跳历史采样（功能刚上线需积累，或该时段未上报）' : '选择一台相机查看温度趋势' }}
            </div>
          </div>

          <div class="caliber">
            统计口径：{{ caliber.rateFormula }} ｜ 相机范围：{{ caliber.cameraType }}
            ｜ 相机排名最低样本：{{ caliber.rankMinSample }} 张 ｜ 数据源：{{ breakdown.dataSource === 'REALTIME' ? '实时计算（当天）' : '日终快照' }}
            <br />{{ caliber.counterNote }}
            <br />{{ breakdown.idleNote }}
            <br /><span class="caliber-plain"><b>白话解读（给厂长）：</b>{{ caliber.ratePlain }} {{ caliber.passPlain }} {{ caliber.counterPlain }}</span>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, inject, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getDailyReportCalendar, getDailyReportBreakdown, regenerateDailyReport, exportDailyReport, getAnalyticsMetadata } from '@/api/analytics'
import { getCascadeAll } from '@/api/cascade'
import { getCameraOptions, getDefectImageFilterOptions, getCameraHeartbeatTrend } from '@/api/camera'
import { useUserStore } from '../../stores/user'

const userStore = useUserStore()
const isAdmin = computed(() => userStore.isAdmin)

// 布局级联筛选只在首次进入时作为默认产线参考（之后以页面内胶囊为准）
const filterParams = inject('filterParams', ref({ factoryId: null, lineIds: [], stationIds: [] }))

const WEEKDAYS = ['一', '二', '三', '四', '五', '六', '日']
const LINE_KEY = 'daily-report:lineId'
const TARGET_KEY = 'daily-report:targetBoxes'

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
const targetBoxes = ref(loadTarget())
// ★ P1-3 / G20：明细筛选状态与候选（含班次）
const detailFilters = ref({ workstationId: null, cameraId: null, defectType: null, shiftId: null })
const workstationOptions = ref([])
const cameraOptions = ref([])
const defectTypeOptions = ref([])
const shiftOptions = ref([])
// G20：明细筛选是否激活（用于 KPI 区「未随筛选」角标，避免误读）
const detailFilterActive = computed(() =>
  !!(detailFilters.value.workstationId || detailFilters.value.cameraId ||
     (detailFilters.value.defectType && String(detailFilters.value.defectType).trim()) ||
     detailFilters.value.shiftId))
// ★ P1-1：设备健康趋势
const trendCameraCode = ref('')
const trendPoints = ref([])
const trendNote = ref('')

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

/** 目标缺陷框数：按产线存 localStorage，空/0 表示未设目标（区分「异常」与「超标」用） */
function loadTarget() {
  try {
    const v = Number(localStorage.getItem(`${TARGET_KEY}:${lineId.value ?? 0}`))
    return Number.isFinite(v) && v > 0 ? v : 0
  } catch (e) { return 0 }
}
function persistTarget() {
  const v = Math.max(0, Number(targetBoxes.value) || 0)
  targetBoxes.value = v
  try { localStorage.setItem(`${TARGET_KEY}:${lineId.value}`, String(v)) } catch (e) { /* 忽略 */ }
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
// P0-4：五类相机告警的近 7 日趋势（后端 breakdown.alertTrend）
const alertTypes = computed(() => breakdown.value?.alertTrend?.types || [])
const alertNote = computed(() => breakdown.value?.alertTrend?.note || '')
const alertMax = computed(() => Math.max(1, ...alertTypes.value.flatMap(t => (t.daily || []).map(n => Number(n) || 0))))
function alertTrendTitle(t) {
  const days = breakdown.value?.alertTrend?.days || []
  return `${t.label} 近 7 日：${(t.daily || []).map((n, i) => `${days[i] || ''} ${n}`).join('，')}`
}
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

/**
 * 今日质量判断条：把 KPI 收敛成一句可拍板结论。
 * 原则（对齐 PRD「数字不骗人」）：
 *   · 无快照 / 无数据 / 样本不足 → 一律 gray「不确定」，绝不绿；
 *   · 有硬信号（超标 / 温度告警 / 相机未上报）→ red；
 *   · 只有环比恶化 → yellow；其余 → green「暂无异常信号」。
 * 这是「线索不是结论」的聚合，按钮文案避免下质量定论。
 */
const verdict = computed(() => {
  const b = breakdown.value
  if (!b || b.missing) return { level: 'gray', label: '无数据', text: '这一天没有日报快照，无法判断。可用「补算这一天」生成。' }
  const detect = Number(kpi.value.detectCount) || 0
  if (detect <= 0) return { level: 'gray', label: '无数据', text: '这一天没有检测数据，无法判断。' }
  if (kpi.value.sampleInsufficient) {
    return { level: 'gray', label: '样本不足', text: `当日仅 ${fmt(detect)} 张（<100），统计仅供参考，不做健康判断。` }
  }
  const hard = []
  const target = Number(targetBoxes.value) || 0
  const defect = Number(b.defectCount ?? kpi.value.defectCount) || 0
  if (target > 0 && defect > target) hard.push(`缺陷框 ${defect} 超过目标 ${target}`)
  if ((anomalies.value.tempAlarmCount || 0) > 0) hard.push(`温度告警 ${anomalies.value.tempAlarmCount} 条`)
  if ((anomalies.value.offlineCameras || 0) > 0) hard.push(`${anomalies.value.offlineCameras} 台相机当日未上报`)
  if (hard.length) return { level: 'red', label: '需关注', text: hard.join('；') + '。建议下钻对应维度核实。' }
  const dod = dodOf({ date: selectedDate.value })
  if (dod != null && dod >= 20) return { level: 'yellow', label: '略有抬头', text: `缺陷框较前一天上升 ${dod.toFixed(1)}%，未超目标但需留意趋势。` }
  return { level: 'green', label: '暂无异常信号', text: '缺陷量未超目标、无设备告警与相机失联。仅为线索汇总，非质量定论。' }
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
  return [`hasdata`, `lv${lv}`, { selected: c.date === selectedDate.value, today: c.isToday, over: overTarget(c) }]
}

/** 是否本月最佳/最差日（后端 summary 已排除今天，直接按日期比对，不重复计算） */
function isBest(c) { return !!summary.value.bestDate && c.date === summary.value.bestDate && c.hasData }
function isWorst(c) { return !!summary.value.worstDate && c.date === summary.value.worstDate && c.hasData && !c.isToday }

/** 是否超过目标线（目标未设或该日无数据 → 不算超标，避免误报） */
function overTarget(c) {
  const t = Number(targetBoxes.value) || 0
  return t > 0 && c.hasData && (Number(c.defectCount) || 0) > t
}

/**
 * 日环比：缺陷框数相对前一天。天数上涨=红（多），下降=绿（少），与股票无关，这里用缺陷量语义。
 * 前一天缺数据 / 前一天为 0（除零）→ null（诚实留空，不伪造 0%）。
 */
const dodByDate = computed(() => {
  const map = {}
  const days = calendarDays.value.filter(c => c.hasData && !c.isFuture)
  for (let i = 1; i < days.length; i++) {
    const prev = Number(days[i - 1].defectCount) || 0
    const cur = Number(days[i].defectCount) || 0
    // 只比相邻两天：日历已按自然日连续排列，跳过无数据日后的差分会失真，故要求紧邻
    const consecutive = dateDiffDays(days[i - 1].date, days[i].date) === 1
    if (consecutive && prev > 0) map[days[i].date] = Math.round((cur - prev) * 10000.0 / prev) / 100
  }
  return map
})
function dateDiffDays(a, b) {
  return Math.round((new Date(`${b}T00:00:00`) - new Date(`${a}T00:00:00`)) / 86400000)
}
function dodOf(c) {
  const v = dodByDate.value[c.date]
  return v === undefined ? null : v
}
function dodText(c) {
  const v = dodOf(c)
  if (v == null) return ''
  return `${v > 0 ? '▲' : v < 0 ? '▼' : '—'} ${Math.abs(v).toFixed(1)}%`
}
function dodTitle(c) {
  const v = dodOf(c)
  return v == null ? '与前一天无可用基线（前一天无数据或为 0）' : `缺陷框数较前一天${v > 0 ? '上升' : v < 0 ? '下降' : '持平'} ${Math.abs(v).toFixed(1)}%`
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
    // ★ P1-3：把明细筛选真正传给后端（有值才传，避免空串被当成筛选）
    if (detailFilters.value.workstationId) params.workstationId = detailFilters.value.workstationId
    if (detailFilters.value.cameraId) params.cameraId = detailFilters.value.cameraId
    if (detailFilters.value.defectType && String(detailFilters.value.defectType).trim()) {
      params.defectType = String(detailFilters.value.defectType).trim()
    }
    if (detailFilters.value.shiftId) params.shiftId = detailFilters.value.shiftId
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
  targetBoxes.value = loadTarget()
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

/* ★ P1-1：温度趋势迷你折线（纯 SVG，避免再引 ECharts 实例） */
const trendTemps = computed(() => trendPoints.value.map(p => Number(p.temperature)).filter(n => Number.isFinite(n)))
const trendMax = computed(() => trendTemps.value.length ? Math.max(...trendTemps.value) : 0)
const trendMin = computed(() => trendTemps.value.length ? Math.min(...trendTemps.value) : 0)
const trendLinePoints = computed(() => {
  const vals = trendTemps.value
  if (vals.length < 2) return ''
  const lo = Math.min(...vals), hi = Math.max(...vals), span = hi - lo || 1
  return vals.map((v, i) => `${(i * 600 / (vals.length - 1)).toFixed(1)},${(80 - (v - lo) / span * 70).toFixed(1)}`).join(' ')
})
const trendWarnY = computed(() => {
  const vals = trendTemps.value
  if (vals.length < 2) return null
  const lo = Math.min(...vals), hi = Math.max(...vals), span = hi - lo || 1
  const warn = 70 // 温度告警阈值提示线（仅为参考线，真实阈值以设备配置为准）
  return warn >= lo && warn <= hi ? (80 - (warn - lo) / span * 70).toFixed(1) : null
})

async function loadHeartbeatTrend() {
  trendPoints.value = []
  trendNote.value = ''
  if (!trendCameraCode.value) return
  try {
    const res = await getCameraHeartbeatTrend(trendCameraCode.value, 24, 120)
    const data = res.data?.data
    trendPoints.value = Array.isArray(data) ? data : []
    if (!trendPoints.value.length) trendNote.value = '该相机近 24h 暂无心跳历史采样（功能刚上线需积累）'
  } catch (e) {
    trendNote.value = '趋势数据暂不可用：' + (e?.response?.data?.message || e?.message || '未知错误')
  }
}

function resetDetailFilters() {
  detailFilters.value = { workstationId: null, cameraId: null, defectType: null, shiftId: null }
  loadBreakdown()
}

/** 加载明细筛选候选：失败只降级为空选项，不影响日报主流程 */
async function loadFilterOptions() {
  try {
    const [cascade, cams, types, meta] = await Promise.all([
      getCascadeAll().catch(() => null),
      getCameraOptions().catch(() => null),
      getDefectImageFilterOptions().catch(() => null),
      getAnalyticsMetadata().catch(() => null)
    ])
    const rows = cascade?.data?.data || cascade?.data || {}
    // cascade 同时给顶层 workstations[] 与 factories[].lines[].stations[]，优先前者（更全）
    const rawStations = (rows.workstations && rows.workstations.length)
      ? rows.workstations
      : (rows.factories || []).flatMap(f => (f.lines || []).flatMap(l => l.stations || []))
    workstationOptions.value = rawStations.map(s => ({
      workstationId: s.workstationId || s.stationId,
      workstationName: s.stationName || s.workstationName || s.stationCode
    })).filter(w => w.workstationId)
    const camData = cams?.data?.data || cams?.data || []
    cameraOptions.value = Array.isArray(camData) ? camData : (camData.options || camData.cameras || [])
    const typeData = types?.data?.data || types?.data || []
    defectTypeOptions.value = (Array.isArray(typeData) ? typeData : (typeData.options || []))
      .map(t => ({ label: t.label || t.name || t.defectType, value: t.value || t.defectType || t.name }))
      .filter(t => t.value)
    const metaData = meta?.data?.data || meta?.data || {}
    shiftOptions.value = Array.isArray(metaData.shifts) ? metaData.shifts : []
  } catch (e) { /* 候选加载失败不影响主流程 */ }
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
  targetBoxes.value = loadTarget()
  selectedDate.value = ''
  loadCalendar()
})

onMounted(() => {
  loadFilterOptions()
  loadAll()
})
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
.target-setting { display: flex; align-items: center; gap: 3px; margin-left: 4px; font-size: 12px; color: var(--color-text-secondary); }
.target-input {
  width: 62px; padding: 2px 5px; font-size: 12px; text-align: right;
  border: 1px solid var(--color-border); border-radius: 4px;
  background: var(--color-bg-card); color: var(--color-text);
}
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
/* 最佳/最差角标：仅历史日标记，今天不参与（后端已排除） */
.day-badge {
  position: absolute; top: 4px; left: 6px; font-size: 10px; line-height: 14px;
  border-radius: 3px; padding: 0 4px; color: #fff;
}
.day-badge.best { background: var(--color-success, #16a34a); }
.day-badge.worst { background: var(--color-error); }
/* 日环比：缺陷量上升用红、下降用绿（缺陷语义，非涨跌语义） */
.day-dod { font-size: 11px; font-weight: 600; margin-top: 1px; }
.day-dod.up { color: var(--color-error); }
.day-dod.down { color: var(--color-success, #16a34a); }
.day-dod.flat { color: var(--color-text-secondary); }
/* 超标：叠加在分位色阶上的绝对目标判断，用下划线+角标区分于「相对分位异常」 */
.day-cell.over { outline: 2px solid var(--color-error); outline-offset: -2px; }
.day-over { font-size: 10px; font-weight: 700; color: var(--color-error); margin-top: 1px; }
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
/* ★ P1-1：设备健康趋势 */
.device-trend { margin-top: 12px; border: 1px solid var(--color-border); border-radius: 8px; padding: 10px 12px; background: var(--color-bg-card); }
.trend-chart { margin-top: 6px; }
.trend-svg { width: 100%; height: 90px; display: block; background: var(--color-bg-subtle); border-radius: 4px; }
.trend-axis { display: flex; justify-content: space-between; font-size: 10px; color: var(--color-text-placeholder); margin-top: 2px; }
/* P1-3：明细筛选条 */
.detail-filter { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
.df-label { font-size: 12px; color: var(--color-text-secondary); }
.df-note { font-size: 11px; color: var(--color-text-placeholder); flex-basis: 100%; }
/* 今日质量判断条：四档语义色，灰=不确定、绿=无信号、黄=抬头、红=需关注 */
.verdict {
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
  border-radius: 8px; padding: 9px 14px; margin-bottom: 12px;
  font-size: 13px; border: 1px solid transparent;
}
.verdict-dot { width: 10px; height: 10px; border-radius: 50%; flex: none; }
.verdict-label { font-size: 13px; }
.verdict-text { color: var(--color-text-regular); font-size: 12px; }
.verdict.gray { background: var(--color-bg-subtle); border-color: var(--color-border); }
.verdict.gray .verdict-dot { background: var(--color-text-placeholder); }
.verdict.gray .verdict-label { color: var(--color-text-secondary); }
.verdict.green { background: color-mix(in srgb, var(--color-success, #16a34a) 8%, var(--color-bg-card)); border-color: color-mix(in srgb, var(--color-success, #16a34a) 30%, var(--color-bg-card)); }
.verdict.green .verdict-dot { background: var(--color-success, #16a34a); }
.verdict.green .verdict-label { color: var(--color-success, #16a34a); }
.verdict.yellow { background: color-mix(in srgb, var(--color-warning) 10%, var(--color-bg-card)); border-color: color-mix(in srgb, var(--color-warning) 32%, var(--color-bg-card)); }
.verdict.yellow .verdict-dot { background: var(--color-warning); }
.verdict.yellow .verdict-label { color: var(--color-warning-dark); }
.verdict.red { background: color-mix(in srgb, var(--color-error) 9%, var(--color-bg-card)); border-color: color-mix(in srgb, var(--color-error) 30%, var(--color-bg-card)); }
.verdict.red .verdict-dot { background: var(--color-error); }
.verdict.red .verdict-label { color: var(--color-error); }
.kpi-row { display: flex; gap: 10px; flex-wrap: wrap; }
.kpi-card {
  flex: 1; min-width: 150px; background: var(--color-bg-subtle); border-radius: 8px;
  padding: 10px 14px; display: flex; flex-direction: column; gap: 2px;
}
.kpi-label { font-size: 12px; color: var(--color-text-secondary); }
.kpi-value { font-size: 22px; font-weight: 700; color: var(--color-text); }
.kpi-value.err { color: var(--color-error); }
.kpi-card small { font-size: 11px; color: var(--color-text-placeholder); }
.calib-tag { font-size: 10px; font-weight: 400; color: var(--color-warning-dark); background: var(--color-warning-bg); border-radius: 3px; padding: 0 4px; margin-left: 3px; }
/* G19：白话解读标记（? 悬浮给出口径白话翻译，不改变术语本身的准确性） */
.plain-tag { display: inline-block; font-size: 9px; font-weight: 600; line-height: 12px; text-align: center;
  width: 12px; height: 12px; border-radius: 50%; margin-left: 3px; cursor: help;
  color: var(--color-primary); background: var(--color-primary-bg, #eef4fb); }
.kpi-card { position: relative; }
/* G20：明细筛选激活时的 KPI 口径边界提示条 */
.kpi-scope-note { font-size: 11px; line-height: 1.5; color: var(--color-warning-dark);
  background: var(--color-warning-bg, #fff8e6); border: 1px solid var(--color-warning, #f5a623);
  border-radius: 4px; padding: 5px 9px; margin-bottom: 10px; }
.kpi-scope-note b { font-weight: 600; }
.caliber-plain { color: var(--color-text-secondary); }
.caliber-plain b { color: var(--color-primary); }

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

/* P0-4：五类告警近 7 日迷你趋势 */
.alert-types { display: flex; flex-direction: column; gap: 6px; margin-bottom: 10px; padding-bottom: 8px; border-bottom: 1px dashed var(--color-border); }
.alert-type-row { display: grid; grid-template-columns: 56px 1fr 24px; align-items: center; gap: 6px; }
.alert-name { font-size: 11px; color: var(--color-text-regular); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.alert-spark { display: flex; align-items: flex-end; gap: 2px; height: 20px; }
.alert-spark i { flex: 1; min-height: 1px; background: var(--color-border); border-radius: 1px 1px 0 0; }
.alert-spark i.hot { background: var(--color-error-light); }
.alert-total { font-size: 12px; text-align: right; color: var(--color-text); }
.alert-total.err { color: var(--color-error); }
.alert-axis { display: flex; justify-content: space-between; font-size: 10px; color: var(--color-text-placeholder); padding-left: 62px; }
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
