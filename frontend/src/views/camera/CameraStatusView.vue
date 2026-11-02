<template>
  <div class="camera-status-page">
    <div class="page-head">
      <div>
        <h2>📷 相机状态</h2>
        <p>默认首页，按相机聚合运行状态、资源指标、上传状态和最近缺陷图。</p>
      </div>
      <div class="head-actions">
        <router-link to="/stats/images" class="image-link">图像记录</router-link>
        <el-button type="primary" :loading="loading" @click="refreshAll">刷新</el-button>
      </div>
    </div>

    <div class="kpi-grid">
      <el-card shadow="never" class="kpi"><span>相机总数</span><strong>{{ stats.total }}</strong></el-card>
      <el-card shadow="never" class="kpi success"><span>运行中</span><strong>{{ stats.running }}</strong></el-card>
      <el-card shadow="never" class="kpi warning"><span>上传延迟/离线</span><strong>{{ stats.uploadDelay + stats.uploadOffline }}</strong></el-card>
      <el-card shadow="never" class="kpi danger"><span>故障/离线</span><strong>{{ stats.fault + stats.offline }}</strong></el-card>
      <el-card shadow="never" class="kpi"><span>近批次图片</span><strong>{{ stats.imageCount }}</strong></el-card>
      <el-card shadow="never" class="kpi danger"><span>高危缺陷</span><strong>{{ stats.highCount }}</strong></el-card>
    </div>

    <el-card shadow="never" class="table-card">
      <template #header>
        <div class="card-title">
          <span>相机上传</span>
          <small>展示实时状态、资源指标和最近上传图像，所属产线/工位来自系统配置。</small>
        </div>
      </template>

      <div class="table-toolbar">
        <el-input
          v-model="searchKeyword"
          clearable
          class="toolbar-search"
          placeholder="搜索相机名称、设备编号、工位、故障信息"
          @input="tablePage = 1"
        />
        <el-select v-model="statusFilter" class="toolbar-select" placeholder="运行状态" @change="tablePage = 1">
          <el-option label="全部状态" value="all" />
          <el-option label="运行" value="1" />
          <el-option label="故障" value="2" />
          <el-option label="停止" value="0" />
          <el-option label="待机" value="3" />
          <el-option label="离线" value="9" />
        </el-select>
        <el-select v-model="uploadFilter" class="toolbar-select" placeholder="上传状态" @change="tablePage = 1">
          <el-option label="全部上传" value="all" />
          <el-option label="正常" value="normal" />
          <el-option label="延迟" value="delay" />
          <el-option label="离线" value="offline" />
          <el-option label="暂无" value="none" />
        </el-select>
        <el-switch
          v-model="abnormalFirst"
          inline-prompt
          active-text="异常优先"
          inactive-text="默认排序"
          @change="tablePage = 1"
        />
      </div>

      <EmptyState v-if="loadError && !loading" type="error" title="相机状态加载失败" :text="loadError" />
      <el-table v-else :data="pagedRows" v-loading="loading" stripe border style="width: 100%">
        <el-table-column prop="cameraName" label="相机名称" min-width="130" fixed />
        <el-table-column prop="stationName" label="工位名称" min-width="130" />
        <el-table-column prop="cameraCode" label="相机编号" width="130" />
        <el-table-column prop="productName" label="当前检测产品" min-width="140" show-overflow-tooltip />
        <el-table-column prop="recognizeCount" label="检测总数" width="96" align="center" />
        <el-table-column label="OK数" width="86" align="center"><template #default="{ row }"><span class="ok-count">{{ row.correctCount }}</span></template></el-table-column>
        <el-table-column label="缺陷数" width="86" align="center"><template #default="{ row }"><span class="defect-count">{{ row.abnormalCount }}</span></template></el-table-column>
        <el-table-column label="运行状态" width="96" align="center"><template #default="{ row }"><el-tag :type="runStatusType(row.statusCode)" size="small">{{ runStatusText(row.statusCode) }}</el-tag></template></el-table-column>
        <el-table-column label="CPU" width="92" align="center"><template #default="{ row }"><span class="metric-pill" :class="metricClass(row.cpuUsage, 'usage')">{{ formatPercent(row.cpuUsage) }}</span></template></el-table-column>
        <el-table-column label="内存" width="92" align="center"><template #default="{ row }"><span class="metric-pill" :class="metricClass(row.memoryUsage, 'usage')">{{ formatPercent(row.memoryUsage) }}</span></template></el-table-column>
        <el-table-column label="温度" width="92" align="center"><template #default="{ row }"><span class="metric-pill" :class="metricClass(row.temperature, 'temperature')">{{ formatTemperature(row.temperature) }}</span></template></el-table-column>
        <el-table-column label="故障信息" min-width="180" show-overflow-tooltip><template #default="{ row }"><span v-if="row.errorMessage && row.errorMessage !== '-'" class="fault-text">{{ row.errorMessage }}</span><span v-else class="muted">-</span></template></el-table-column>
        <el-table-column label="心跳状态" width="96" align="center"><template #default="{ row }"><el-tag :type="timelinessType(row.heartbeatState)" size="small">{{ timelinessText(row.heartbeatState) }}</el-tag></template></el-table-column>
        <el-table-column label="上传状态" width="96" align="center"><template #default="{ row }"><el-tag :type="timelinessType(row.uploadState)" size="small">{{ timelinessText(row.uploadState) }}</el-tag></template></el-table-column>
        <el-table-column prop="reportTime" label="最近心跳" width="160" />
        <el-table-column prop="lastUploadTime" label="最近上传" width="160" />
        <el-table-column label="图像/缺陷" width="112" align="center"><template #default="{ row }"><span class="count-stack"><span>图 {{ row.imageCount }}</span><span>缺 {{ row.defectCount }}</span></span></template></el-table-column>
        <el-table-column label="严重度" width="150" align="center"><template #default="{ row }"><span class="severity-stack"><em class="high">高 {{ row.highCount }}</em><em class="medium">中 {{ row.mediumCount }}</em><em class="low">低 {{ row.lowCount }}</em></span></template></el-table-column>
        <el-table-column label="最近图像" width="170" align="center" fixed="right"><template #default="{ row }"><div class="image-actions"><el-button v-if="row.latestImage" link type="primary" @click="openPreview(row)">查看</el-button><el-button link type="success" @click="goImageRecords(row)">图像记录</el-button></div></template></el-table-column>
        <template #empty>
          <EmptyState text="当前筛选条件下暂无相机状态数据" />
        </template>
      </el-table>

      <div class="table-pager">
        <span class="pager-tip">每页 {{ tablePageSize }} 台，可选 10 / 20 / 50 / 100</span>
        <BasePagination v-model:current-page="tablePage" v-model:page-size="tablePageSize" :total="filteredRows.length" small />
      </div>
    </el-card>

    <el-dialog v-model="previewVisible" :title="previewTitle" width="820px" destroy-on-close>
      <div v-if="previewImageData" class="preview-content">
        <div class="preview-box" :class="{ invalid: isInvalidImage(previewImageData) }">
          <img v-if="previewImageData.imageUrl && !isInvalidImage(previewImageData)" :src="previewImageData.imageUrl" alt="最近图像" @error="markImageError(previewImageData)" />
          <div v-else class="preview-empty">
            <strong>{{ imageFailureReason(previewImageData) }}</strong>
            <span>{{ imageFailureAdvice(previewImageData) }}</span>
            <ul class="failure-checklist">
              <li>确认上传文件是真实 JPEG / PNG / BMP / WebP 图片，不是空文件或占位图。</li>
              <li>确认文件名与 metadata.images[].fileName 完全一致，包含扩展名。</li>
              <li>确认图片宽高大于 2px，且文件未被传输截断。</li>
            </ul>
          </div>
        </div>
        <div class="preview-meta">
          <div><span>文件名</span><strong>{{ previewImageData.fileName }}</strong></div>
          <div><span>拍摄时间</span><strong>{{ previewImageData.captureTimeText || '-' }}</strong></div>
          <div><span>上传时间</span><strong>{{ previewImageData.uploadTimeText || '-' }}</strong></div>
          <div><span>缺陷数</span><strong>{{ previewImageData.defectCount || 0 }}</strong></div>
        </div>
        <el-table :data="previewImageData.defects || []" size="small" border>
          <el-table-column prop="type" label="缺陷类型" min-width="120" />
          <el-table-column label="严重度" width="90"><template #default="{ row }"><el-tag :type="severityTag(row.severity)" size="small">{{ severityText(row.severity) }}</el-tag></template></el-table-column>
          <el-table-column prop="x" label="X" width="80" align="right" />
          <el-table-column prop="y" label="Y" width="80" align="right" />
          <el-table-column prop="width" label="宽" width="80" align="right" />
          <el-table-column prop="height" label="高" width="80" align="right" />
          <el-table-column prop="area" label="面积" width="100" align="right" />
        </el-table>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, inject, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { getCameraList, getDefectImageCameraSummaries } from '@/api/camera'
import EmptyState from '@/components/EmptyState.vue'
import BasePagination from '@/components/common/BasePagination.vue'
import { useCameraStatusTable } from './useCameraStatusTable'

const router = useRouter()

const loading = ref(false)
const loadError = ref('')
const rows = ref([])
const recentImages = ref([])
const previewVisible = ref(false)
const previewImageData = ref(null)
const tablePage = ref(1)
const tablePageSize = ref(10)
const searchKeyword = ref('')
const statusFilter = ref('all')
const uploadFilter = ref('all')
const abnormalFirst = ref(false)
const filterParams = inject('filterParams', ref({ factoryId: null, lineIds: [], stationIds: [] }))
let timer = null

const sameId = (a, b) => String(a ?? '') === String(b ?? '')
const { filteredRows, pagedRows, stats } = useCameraStatusTable({ rows, tablePage, tablePageSize, searchKeyword, statusFilter, uploadFilter, abnormalFirst, filterParams, sameId })

const toTimeText = (value) => {
  if (!value) return ''
  if (typeof value === 'string') return value.replace('T', ' ')
  if (Array.isArray(value)) {
    const [y, m, d, hh = 0, mm = 0, ss = 0] = value
    return `${y}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')} ${String(hh).padStart(2, '0')}:${String(mm).padStart(2, '0')}:${String(ss).padStart(2, '0')}`
  }
  return String(value)
}

const parseTime = (value) => {
  const text = toTimeText(value)
  if (!text) return null
  const date = new Date(text.replace(/-/g, '/'))
  return Number.isNaN(date.getTime()) ? null : date
}

const deriveTimeliness = (value) => {
  const date = parseTime(value)
  if (!date) return 'none'
  const diff = (Date.now() - date.getTime()) / 1000
  if (diff <= 30) return 'normal'
  if (diff <= 120) return 'delay'
  return 'offline'
}

const toStatusCode = (row) => {
  if (typeof row.cameraStatus === 'number') return row.cameraStatus
  if (typeof row.cameraStatusCode === 'number') return row.cameraStatusCode
  const status = String(row.status || '').toUpperCase()
  if (status === 'RUNNING' || status === 'ONLINE') return 1
  if (status === 'STOPPED') return 0
  if (status === 'FAULT') return 2
  if (status === 'STANDBY' || status === 'PAUSED') return 3
  if (status === 'OFFLINE') return 9
  return 9
}

const severityKey = (severity) => {
  const s = String(severity || '').toUpperCase()
  if (s === 'HIGH' || s === '高' || s === '严重') return 'high'
  if (s === 'MEDIUM' || s === '中' || s === '重大') return 'medium'
  return 'low'
}
const severityText = (severity) => ({ high: '高', medium: '中', low: '低' }[severityKey(severity)] || '低')
const severityTag = (severity) => ({ high: 'danger', medium: 'warning', low: 'info' }[severityKey(severity)] || 'info')

const normalizeImage = (img) => ({
  ...img,
  captureTimeText: toTimeText(img.captureTime),
  uploadTimeText: toTimeText(img.uploadTime),
  receiveTimeText: toTimeText(img.receiveTime || img.createTime),
  createTimeText: toTimeText(img.createTime || img.receiveTime),
  sortTime: toTimeText(img.receiveTime || img.createTime || img.uploadTime || img.captureTime),
  defectCount: img.defectCount ?? (Array.isArray(img.defects) ? img.defects.length : 0),
  highCount: Number(img.highCount || 0),
  mediumCount: Number(img.mediumCount || 0),
  lowCount: Number(img.lowCount || 0)
})

const cameraIdentityKeys = (item) => {
  const code = item?.cameraCode || item?.deviceId || item?.cameraId
  const name = item?.cameraName
  return [code, item?.cameraId, item?.cameraCode, name].filter(Boolean)
}

const imageSummaryMap = computed(() => {
  const map = new Map()
  recentImages.value.forEach(summaryRow => {
    const latestImages = Array.isArray(summaryRow.latestImages) ? summaryRow.latestImages : []
    const latestImage = latestImages[0] || null
    const summary = {
      latestImage,
      lastUploadTime: toTimeText(summaryRow.latestTime || latestImage?.receiveTime || latestImage?.createTime || latestImage?.uploadTime),
      sortTime: toTimeText(summaryRow.latestTime || latestImage?.receiveTime || latestImage?.createTime || latestImage?.uploadTime || latestImage?.captureTime),
      imageCount: Number(summaryRow.imageCount || 0),
      defectCount: Number(summaryRow.defectCount || 0),
      highCount: Number(summaryRow.highCount || 0),
      mediumCount: Number(summaryRow.mediumCount || 0),
      lowCount: Number(summaryRow.lowCount || 0)
    }
    cameraIdentityKeys(summaryRow).forEach(k => {
      if (!map.has(k)) map.set(k, summary)
    })
  })
  return map
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

const normalizeRows = (list) => list.map((item, index) => {
  const reportTime = toTimeText(item.reportTime || item.timestamp || item.lastHeartbeatAt)
  const cameraCode = item.cameraCode || item.deviceId || item.cameraId || '-'
  const cameraName = item.cameraName || cameraCode
  const rawDbId = Number(item.id ?? item.cameraStatusId ?? item.statusId)
  const imageSummary = cameraIdentityKeys({ ...item, cameraCode, cameraName }).map(k => imageSummaryMap.value.get(k)).find(Boolean) || {}
  const lastUploadTime = imageSummary.lastUploadTime || toTimeText(item.lastUploadAt) || ''
  return {
    ...item,
    cameraCode,
    cameraName,
    stationName: stationLabel(item),
    productName: item.productName || '-',
    reportTime,
    lastUploadTime,
    latestImage: imageSummary.latestImage || null,
    statusCode: toStatusCode(item),
    recognizeCount: item.recognizeCount ?? item.totalCount ?? 0,
    correctCount: item.correctCount ?? item.okCount ?? 0,
    abnormalCount: item.abnormalCount ?? item.ngCount ?? 0,
    heartbeatState: deriveTimeliness(reportTime),
    uploadState: deriveTimeliness(lastUploadTime),
    imageCount: imageSummary.imageCount || 0,
    defectCount: imageSummary.defectCount || 0,
    highCount: imageSummary.highCount || 0,
    mediumCount: imageSummary.mediumCount || 0,
    lowCount: imageSummary.lowCount || 0,
    errorMessage: item.errorMessage || '-',
    dbSortId: Number.isFinite(rawDbId) ? rawDbId : Number.MAX_SAFE_INTEGER,
    sourceIndex: index
  }
})

const isInvalidImage = (img) => {
  if (!img || !img.imageUrl || img.loadError) return true
  const w = Number(img.width || 0)
  const h = Number(img.height || 0)
  return (w > 0 && w <= 2) || (h > 0 && h <= 2)
}
const markImageError = (img) => { if (img) img.loadError = true }
const imageFailureReason = (img) => {
  if (!img || !img.imageUrl) return '未找到可展示的图片文件'
  if (img.loadError) return '浏览器加载图片失败'
  const w = Number(img.width || 0)
  const h = Number(img.height || 0)
  if ((w > 0 && w <= 2) || (h > 0 && h <= 2)) return `图片尺寸异常：${w || '-'} x ${h || '-'}`
  return '图片解析失败或文件内容异常'
}
const imageFailureAdvice = (img) => {
  if (!img || !img.imageUrl) return '处理建议：检查 metadata 中的 fileName 是否与 multipart 文件名一致，并确认后端上传目录存在该文件。'
  if (img.loadError) return '处理建议：检查图片 URL 是否可访问、文件是否已被清理，或服务端 /api/v1/defect-images/file/** 是否返回 200。'
  return '处理建议：请设备端重新上传未损坏的原始图片，避免上传空文件、占位图或已损坏图片。'
}

const unwrapPage = (res) => {
  const data = res?.data?.data ?? res?.data ?? {}
  if (Array.isArray(data)) return data
  if (Array.isArray(data.records)) return data.records
  return []
}
const unwrapList = (res) => {
  const data = res?.data?.data ?? res?.data ?? []
  return Array.isArray(data) ? data : []
}
const buildScopedParams = (base = {}) => {
  const f = filterParams.value || {}
  const params = { ...base }
  if (f.factoryId) params.factoryId = f.factoryId
  if ((f.lineIds || []).length === 1) params.lineId = f.lineIds[0]
  if ((f.stationIds || []).length === 1) params.stationId = f.stationIds[0]
  return params
}

const loadData = async () => {
  loading.value = true
  loadError.value = ''
  try {
    const [camRes, imgRes] = await Promise.all([
      getCameraList(buildScopedParams({ page: 1, size: 999 })),
      getDefectImageCameraSummaries(buildScopedParams({ latestLimit: 1 }))
    ])
    recentImages.value = unwrapList(imgRes).map(item => ({ ...item, latestImages: Array.isArray(item.latestImages) ? item.latestImages.map(normalizeImage) : [] }))
    rows.value = normalizeRows(unwrapPage(camRes))
  } catch (error) {
    console.error('加载相机状态失败', error)
    rows.value = []
    recentImages.value = []
    loadError.value = error?.response?.data?.message || error?.message || '请检查后端服务、数据库连接和相机状态接口。'
  } finally {
    loading.value = false
  }
}
const refreshAll = async () => { await loadData() }

const runStatusText = (code) => ({ 0: '停止', 1: '运行', 2: '故障', 3: '待机', 9: '离线' }[code] || '未知')
const runStatusType = (code) => ({ 0: 'info', 1: 'success', 2: 'danger', 3: 'warning', 9: 'info' }[code] || 'info')
const timelinessText = (state) => ({ normal: '正常', delay: '延迟', offline: '离线', none: '暂无' }[state] || '暂无')
const timelinessType = (state) => ({ normal: 'success', delay: 'warning', offline: 'danger', none: 'info' }[state] || 'info')
const formatPercent = (value) => value == null ? '-' : `${Number(value).toFixed(1)}%`
const formatTemperature = (value) => value == null ? '-' : `${Number(value).toFixed(1)}℃`
const metricClass = (value, type = 'usage') => {
  if (value == null || value === '') return 'is-empty'
  const num = Number(value)
  if (Number.isNaN(num)) return 'is-empty'
  if (type === 'temperature') {
    if (num > 70) return 'is-danger'
    if (num > 60) return 'is-warning'
    return 'is-normal'
  }
  if (num > 90) return 'is-danger'
  if (num > 75) return 'is-warning'
  return 'is-normal'
}

const previewTitle = computed(() => previewImageData.value ? `${previewImageData.value.cameraName || ''} - 最近图像` : '最近图像')
const openPreview = (row) => {
  previewImageData.value = { ...row.latestImage, cameraName: row.cameraName }
  previewVisible.value = true
}

const goImageRecords = (row) => {
  const cameraId = row.cameraCode && row.cameraCode !== '-' ? row.cameraCode : (row.cameraId || row.deviceId || '')
  router.push({
    path: '/stats/images',
    query: {
      ...(cameraId ? { cameraId } : {}),
      cameraName: row.cameraName
    }
  })
}

onMounted(() => {
  refreshAll()
  timer = window.setInterval(refreshAll, 30000)
})
watch(filterParams, loadData, { deep: true })
watch(filteredRows, () => {
  const maxPage = Math.max(1, Math.ceil(filteredRows.value.length / tablePageSize.value))
  if (tablePage.value > maxPage) tablePage.value = maxPage
  if (tablePage.value < 1) tablePage.value = 1
})
watch(tablePageSize, () => { tablePage.value = 1 })
watch([searchKeyword, statusFilter, uploadFilter, abnormalFirst], () => { tablePage.value = 1 })
onBeforeUnmount(() => { if (timer) window.clearInterval(timer) })
</script>

<style scoped>
.camera-status-page { display: flex; flex-direction: column; gap: 16px; }
.page-head { display: flex; justify-content: space-between; gap: 16px; align-items: flex-start; }
.page-head h2 { margin: 0 0 6px; font-size: 22px; color: #111827; }
.page-head p { margin: 0; color: #64748b; font-size: 14px; }
.head-actions { display: flex; align-items: center; gap: 10px; }
.image-link { text-decoration: none; color: #2563eb; font-size: 13px; font-weight: 700; padding: 8px 12px; border-radius: 8px; background: #eff6ff; }
.kpi-grid { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 12px; }
.kpi { border: 1px solid #e5e7eb; border-radius: 14px; }
.kpi span { display: block; color: #64748b; font-size: 13px; margin-bottom: 8px; }
.kpi strong { display: block; color: #0f172a; font-size: 28px; line-height: 1; }
.kpi.success strong, .ok-count { color: #16a34a; }
.kpi.warning strong { color: #d97706; }
.kpi.danger strong, .defect-count, .fault-text { color: #dc2626; }
.table-card { border-radius: 14px; border: 1px solid #e5e7eb; }
.card-title { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; }
.card-title span { font-weight: 800; color: #0f172a; }
.card-title small { color: #94a3b8; font-weight: 400; }
.table-toolbar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 12px; }
.toolbar-search { width: 360px; max-width: 100%; }
.toolbar-select { width: 142px; }
.table-pager { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-top: 12px; }
.pager-tip, .muted { color: #94a3b8; font-size: 12px; }
.count-stack, .severity-stack { display: inline-flex; flex-direction: column; gap: 2px; font-size: 12px; line-height: 1.25; }
.severity-stack { flex-direction: row; gap: 4px; }
.severity-stack em { font-style: normal; padding: 2px 6px; border-radius: 999px; font-size: 12px; }
.severity-stack .high { color: #b91c1c; background: #fee2e2; }
.severity-stack .medium { color: #b45309; background: #fef3c7; }
.severity-stack .low { color: #1d4ed8; background: #dbeafe; }
.metric-pill { display: inline-flex; justify-content: center; min-width: 58px; padding: 3px 7px; border-radius: 999px; font-size: 12px; font-weight: 700; }
.metric-pill.is-normal { color: #15803d; background: #dcfce7; }
.metric-pill.is-warning { color: #b45309; background: #fef3c7; }
.metric-pill.is-danger { color: #b91c1c; background: #fee2e2; }
.metric-pill.is-empty { color: #64748b; background: #f1f5f9; }
.preview-content { display: flex; flex-direction: column; gap: 14px; }
.preview-box { min-height: 320px; max-height: 520px; border-radius: 12px; background: #0f172a; display: flex; align-items: center; justify-content: center; overflow: hidden; }
.preview-box img { max-width: 100%; max-height: 520px; object-fit: contain; }
.preview-empty { min-height: 320px; width: 100%; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 10px; padding: 28px; color: #64748b; text-align: center; background: #f8fafc; }
.failure-checklist { margin: 8px 0 0; text-align: left; line-height: 1.8; }
.preview-meta { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; }
.preview-meta div { padding: 10px 12px; border-radius: 10px; background: #f8fafc; border: 1px solid #eef2f7; min-width: 0; }
.preview-meta span { display: block; color: #64748b; font-size: 12px; margin-bottom: 4px; }
.image-actions { display: inline-flex; align-items: center; justify-content: center; gap: 4px; }
.preview-meta strong { display: block; color: #0f172a; font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
@media (max-width: 1280px) { .kpi-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); } .preview-meta { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 768px) { .page-head, .table-pager { flex-direction: column; align-items: stretch; } .kpi-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .toolbar-search, .toolbar-select { width: 100%; } }
</style>
