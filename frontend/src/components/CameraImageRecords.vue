<template>
  <section class="image-records" :class="{ embedded }">
    <div v-if="!embedded" class="page-header">
      <div>
        <h2>图像记录</h2>
        <p>按相机聚合缺陷图片，展开后按数据库分页查询，避免只展示全局最近记录导致数量不一致。</p>
      </div>
      <span class="filter-hint" v-if="filterInfo">当前筛选：{{ filterInfo }}</span>
    </div>
    <div v-else class="section-header">
      <div>
        <h3>{{ title }}</h3>
        <p>复用图像记录能力，展示最近缺陷图、严重度和缺陷框明细。</p>
      </div>
      <router-link v-if="showMoreLink" to="/stats/images" class="more-link">进入图像记录</router-link>
    </div>

    <div v-if="!embedded" class="records-toolbar">
      <div class="toolbar-filters">
        <el-input v-model="searchKeyword" clearable class="image-search" placeholder="搜索相机名称、设备编号、工位、文件名" @keyup.enter="loadData" @clear="loadData" />
        <el-select v-model="imageFilters.severity" clearable placeholder="严重度" style="width:120px" @change="loadData">
          <el-option label="HIGH" value="HIGH" />
          <el-option label="MEDIUM" value="MEDIUM" />
          <el-option label="LOW" value="LOW" />
        </el-select>
        <el-input v-model="imageFilters.defectType" clearable placeholder="缺陷类型" style="width:160px" @keyup.enter="loadData" @clear="loadData" />
        <el-date-picker v-model="imageTimeRange" type="datetimerange" range-separator="至" start-placeholder="开始时间" end-placeholder="结束时间" value-format="YYYY-MM-DD HH:mm:ss" style="width:360px" @change="loadData" />
        <el-button type="primary" plain @click="loadData">查询</el-button>
        <el-button @click="resetImageFilters">重置</el-button>
      </div>
      <div class="toolbar-summary">
        <span>相机 {{ filteredCameras.length }} 台</span>
        <span>图片 {{ filteredImageCount }} 张</span>
      </div>
    </div>

    <el-alert
      v-if="historyQueryHint"
      type="info"
      :closable="false"
      show-icon
      title="查询范围包含 31 天前的历史数据：将自动查询月度归档表，结果可能较慢（默认查询近一个月为毫秒级）"
      class="history-query-hint"
    />

    <EmptyState v-if="loading" type="loading" title="图像记录加载中" text="正在加载相机和缺陷图片，请稍候" />

    <el-row :gutter="16" v-else-if="filteredCameras.length > 0">
      <el-col :xs="24" :md="24" v-for="cam in filteredCameras" :key="cam.id" class="cam-col">
        <div class="cam-card">
          <div class="cam-hd" @click="toggleExpand(cam)">
            <div class="cam-hd-left">
              <span class="cam-status" :class="`st-${cam.statusKey}`"><span class="st-dot" />{{ cam.statusName }}</span>
              <strong class="cam-name">{{ cam.name }}</strong>
              <span class="cam-station">{{ cam.station }}</span>
            </div>
            <div class="cam-hd-right">
              <span class="severity-pill high">高 {{ cam.highCount }}</span>
              <span class="severity-pill medium">中 {{ cam.mediumCount }}</span>
              <span class="severity-pill low">低 {{ cam.lowCount }}</span>
              <span class="image-total">共 {{ cam.historyTotal }} 张</span>
              <span class="cam-expand-icon">{{ isExpanded(cam.id) ? '收起' : '展开' }}</span>
            </div>
          </div>

          <div class="cam-body">
            <div class="thumb-label">最近 5 张有效缺陷图</div>
            <div class="thumb-row">
              <div v-for="(img, i) in cam.recentImages" :key="img.imageId || i" class="thumb-item" @click="previewImage(cam, img)" :title="imageTitle(img)">
                <div class="thumb-img-wrap">
                  <img v-if="img.imageUrl && !isInvalidImage(img)" :src="img.imageUrl" alt="缺陷图" @error="markImageError(img)" />
                  <div v-else class="thumb-placeholder invalid">{{ img.imageUrl ? '加载失败' : '无图' }}</div>
                  <span class="thumb-severity" :class="severityKey(img.mainSeverity)">{{ severityText(img.mainSeverity) }}</span>
                </div>
              </div>
              <div v-for="n in emptyThumbCount(cam)" :key="`empty-${cam.id}-${n}`" class="thumb-item empty">
                <div class="thumb-img-wrap empty-thumb">—</div>
              </div>
            </div>
          </div>

          <div v-show="isExpanded(cam.id)" class="cam-history">
            <div class="history-divider" />
            <div class="history-title">历史记录 <span>共 {{ cam.historyTotal }} 条</span></div>
            <el-table :data="historyPageRows(cam)" size="small" stripe class="hist-table">
              <el-table-column label="图片" width="76">
                <template #default="{ row }">
                  <div class="hist-thumb" @click="previewImage(cam, row)">
                    <img v-if="row.imageUrl && !isInvalidImage(row)" :src="row.imageUrl" alt="缺陷图" @error="markImageError(row)" />
                    <span v-else>加载失败</span>
                  </div>
                </template>
              </el-table-column>
              <el-table-column prop="fileName" label="文件名" min-width="170" show-overflow-tooltip />
              <el-table-column prop="captureTimeText" label="拍摄时间" width="160" />
              <el-table-column label="缺陷数" width="76" align="right">
                <template #default="{ row }">{{ row.defectCount || 0 }}</template>
              </el-table-column>
              <el-table-column label="严重度" width="110">
                <template #default="{ row }"><el-tag :type="tagType(row.mainSeverity)" size="small">{{ severityText(row.mainSeverity) }}</el-tag></template>
              </el-table-column>
              <el-table-column label="操作" width="80" fixed="right">
                <template #default="{ row }"><el-button link type="primary" @click.stop="previewImage(cam, row)">查看</el-button></template>
              </el-table-column>
            </el-table>
            <BasePagination :current-page="historyPageOf(cam)" :page-size="historyPageSize" :total="cam.historyTotal" small class="pager" @change="payload => handleHistoryPaginationChange(cam, payload)" />
          </div>
        </div>
      </el-col>
    </el-row>

    <EmptyState v-else text="当前筛选条件下暂无图像记录" />

    <el-dialog v-model="previewVisible" :title="previewTitle" width="820px" :close-on-click-modal="false">
      <div v-if="previewData" class="preview-content">
        <div class="preview-img-wrap">
          <el-button class="preview-nav prev" circle :disabled="!hasPrevImage" @click.stop="switchPreview(-1)">‹</el-button>
          <img v-if="previewData.imageUrl && !isInvalidImage(previewData)" :src="previewData.imageUrl" alt="缺陷图预览" @error="markImageError(previewData)" @dblclick="openZoomViewer" />
          <div v-else class="preview-empty">
            <strong>{{ imageFailureReason(previewData) }}</strong>
            <span>{{ imageFailureAdvice(previewData) }}</span>
            <ul>
              <li>检查图片 URL 是否可访问，服务端文件接口是否返回 200。</li>
              <li>检查 metadata.images[].fileName 与实际上传文件名是否完全一致。</li>
              <li>检查图片是否为空文件、占位图、损坏文件或扩展名与内容不一致。</li>
            </ul>
          </div>
          <el-button class="preview-nav next" circle :disabled="!hasNextImage" @click.stop="switchPreview(1)">›</el-button>
          <el-button class="preview-zoom" plain size="small" @click.stop="openZoomViewer">
            <span class="preview-zoom-icon">⤢</span> 放大查看
          </el-button>
          <div v-if="previewList.length" class="preview-counter">{{ previewIndex + 1 }} / {{ previewList.length }}</div>
        </div>
        <div class="preview-info-grid">
          <div class="info-item"><span>相机</span><strong>{{ previewData.cameraName }}</strong></div>
          <div class="info-item"><span>工位</span><strong>{{ previewData.station }}</strong></div>
          <div class="info-item"><span>文件名</span><strong>{{ previewData.fileName }}</strong></div>
          <div class="info-item"><span>拍摄时间</span><strong>{{ previewData.captureTimeText || '-' }}</strong></div>
        </div>
        <el-table :data="previewData.defects || []" size="small" border class="defect-table">
          <el-table-column prop="type" label="缺陷类型" min-width="120" />
          <el-table-column label="严重度" width="90">
            <template #default="{ row }"><el-tag :type="tagType(row.severity)" size="small">{{ severityText(row.severity) }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="x" label="X" width="80" align="right" />
          <el-table-column prop="y" label="Y" width="80" align="right" />
          <el-table-column prop="width" label="宽" width="80" align="right" />
          <el-table-column prop="height" label="高" width="80" align="right" />
          <el-table-column prop="area" label="面积" width="100" align="right" />
        </el-table>
      </div>
    </el-dialog>

    <ImageViewer
      :visible="zoomVisible"
      :index="zoomIndex"
      :images="zoomImages"
      :title="zoomTitle"
      :subtitle="zoomSubtitle"
      @update:visible="zoomVisible = $event"
      @update:index="syncZoomIndex"
    />
  </section>
</template>

<script setup>
import { computed, inject, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { getCameraList, getDefectImageCameraSummaries, getDefectImageHistory } from '@/api/camera'
import EmptyState from '@/components/EmptyState.vue'
import BasePagination from '@/components/common/BasePagination.vue'
import ImageViewer from '@/components/common/ImageViewer.vue'
// ★ 2026-11-05：相机身份匹配统一走 code-first 工具（名称重名会导致错配）
import { buildCameraSummaryIndex, countCameraNames, findCameraSummary } from '@/utils/camera-identity'

const props = defineProps({
  embedded: { type: Boolean, default: false },
  title: { type: String, default: '图像记录' },
  showMoreLink: { type: Boolean, default: true }
})

const route = useRoute()

const loading = ref(false)
const cameras = ref([])
const summaries = ref([])
const searchKeyword = ref('')
const imageFilters = reactive({ severity: '', defectType: '' })
const imageTimeRange = ref([])

/**
 * 历史查询提示（2026-11-05 月度归档）：
 * 主表只保留近 31 天，更早数据在月表。选到 31 天前时后端自动路由到归档表，
 * 结果正确但可能较慢，这里给用户一个预期。
 */
const historyQueryHint = computed(() => {
  const range = imageTimeRange.value
  if (!range || range.length !== 2) return false
  const start = new Date(String(range[0]).replace(/-/g, '/')).getTime()
  if (Number.isNaN(start)) return false
  return start < Date.now() - 31 * 24 * 3600 * 1000
})
const expandedMap = reactive({})
const cameraPages = reactive({})
const cameraHistoryState = reactive({})
const previewVisible = ref(false)
const previewData = ref(null)
const previewList = ref([])
const previewIndex = ref(-1)
const previewCamera = ref(null)
const zoomVisible = ref(false)
const zoomIndex = ref(0)
const historyPageSize = ref(10)
const filterParams = inject('filterParams', ref({ factoryId: null, lineIds: [], stationIds: [] }))
const filterLabels = inject('filterLabels', ref({ factoryName: '', lineNames: [], stationNames: [] }))

const sameId = (a, b) => String(a ?? '') === String(b ?? '')
const unwrapPage = res => {
  const data = res?.data?.data ?? res?.data ?? {}
  if (Array.isArray(data)) return data
  if (Array.isArray(data.records)) return data.records
  return []
}
const unwrapList = res => {
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
const toTimeText = value => {
  if (!value) return ''
  if (typeof value === 'string') return value.replace('T', ' ')
  if (Array.isArray(value)) {
    const [y, m, d, hh = 0, mm = 0, ss = 0] = value
    return `${y}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')} ${String(hh).padStart(2, '0')}:${String(mm).padStart(2, '0')}:${String(ss).padStart(2, '0')}`
  }
  return String(value)
}
const parseTime = value => {
  const text = toTimeText(value)
  if (!text) return 0
  const t = new Date(text.replace(/-/g, '/')).getTime()
  return Number.isNaN(t) ? 0 : t
}
const normalizeStatus = status => {
  const s = String(status || '').toUpperCase()
  if (s === 'RUNNING' || s === 'ONLINE' || s === '1') return { key: 'running', name: '运行' }
  if (s === 'STOPPED' || s === '0') return { key: 'stopped', name: '停止' }
  if (s === 'FAULT' || s === '2') return { key: 'fault', name: '故障' }
  if (s === 'STANDBY' || s === 'PAUSED' || s === '3') return { key: 'standby', name: '待机' }
  return { key: 'offline', name: '离线' }
}
const severityKey = severity => {
  const s = String(severity || '').toUpperCase()
  if (s === 'HIGH' || s === '高' || s === '严重') return 'high'
  if (s === 'MEDIUM' || s === '中' || s === '重大') return 'medium'
  return 'low'
}
const severityText = severity => ({ high: '高', medium: '中', low: '低' }[severityKey(severity)] || '低')
const tagType = severity => ({ high: 'danger', medium: 'warning', low: 'info' }[severityKey(severity)] || 'info')
const isInvalidImage = img => {
  if (!img || !img.imageUrl || img.loadError) return true
  const w = Number(img.width || 0)
  const h = Number(img.height || 0)
  return (w > 0 && w <= 2) || (h > 0 && h <= 2)
}
const markImageError = img => { if (img) img.loadError = true }
const imageFailureReason = img => {
  if (!img || !img.imageUrl) return '未找到可展示的图片文件'
  if (img.loadError) return '浏览器加载图片失败'
  const w = Number(img.width || 0)
  const h = Number(img.height || 0)
  if ((w > 0 && w <= 2) || (h > 0 && h <= 2)) return `图片尺寸异常：${w || '-'} x ${h || '-'}`
  return '图片解析失败或文件内容异常'
}
const imageFailureAdvice = img => {
  if (!img || !img.imageUrl) return '处理建议：确认本批次 metadata 与 multipart 文件齐全，必要时重新上传该图片。'
  if (img.loadError) return '处理建议：确认上传文件未被清理、文件路径未变化，或联系后端排查文件读取权限。'
  return '处理建议：请设备端重新生成原始图片，避免上传空文件、占位图或已损坏图片。'
}
const normalizeImage = (img = {}) => {
  const defects = Array.isArray(img.defects) ? img.defects : []
  const mainSeverity = defects.find(d => severityKey(d.severity) === 'high')?.severity ||
    defects.find(d => severityKey(d.severity) === 'medium')?.severity ||
    defects[0]?.severity || 'LOW'
  return {
    ...img,
    fileName: img.fileName || img.originalFileName || '-',
    captureTimeText: toTimeText(img.captureTime || img.uploadTime),
    uploadTimeText: toTimeText(img.uploadTime),
    receiveTimeText: toTimeText(img.receiveTime || img.createTime),
    createTimeText: toTimeText(img.createTime || img.receiveTime),
    sortTime: toTimeText(img.receiveTime || img.createTime || img.uploadTime || img.captureTime),
    mainSeverity,
    defectCount: img.defectCount ?? defects.length,
    defects
  }
}
const cameraIdentityKey = item => item?.cameraCode || item?.deviceId || item?.cameraId || item?.cameraName || 'unknown'
const latestTimeOfGroup = (group = []) => group.reduce((max, img) => Math.max(max, parseTime(img.sortTime || img.receiveTime || img.createTime || img.uploadTime || img.captureTime)), 0)
const buildImageQueryParams = (base = {}) => {
  const params = buildScopedParams(base)
  const routeCameraId = String(route.query.cameraId || '').trim()
  const routeCameraName = String(route.query.cameraName || '').trim()
  if (routeCameraId) params.cameraId = routeCameraId
  if (!routeCameraId && routeCameraName) params.cameraName = routeCameraName
  const keyword = searchKeyword.value.trim()
  if (keyword) params.keyword = keyword
  if (imageFilters.severity) params.severity = imageFilters.severity
  if (imageFilters.defectType && imageFilters.defectType.trim()) params.defectType = imageFilters.defectType.trim()
  if (imageTimeRange.value && imageTimeRange.value.length === 2) {
    params.startTime = imageTimeRange.value[0]
    params.endTime = imageTimeRange.value[1]
  }
  return params
}
const clearHistoryCache = () => Object.keys(cameraHistoryState).forEach(key => delete cameraHistoryState[key])
const loadData = async () => {
  loading.value = true
  try {
    const [camRes, summaryRes] = await Promise.all([
      getCameraList(buildScopedParams({ page: 1, size: 999 })),
      getDefectImageCameraSummaries(buildImageQueryParams({ latestLimit: 5 }))
    ])
    cameras.value = unwrapPage(camRes)
    summaries.value = unwrapList(summaryRes).map(summary => ({
      ...summary,
      latestImages: Array.isArray(summary.latestImages) ? summary.latestImages.map(normalizeImage) : [],
      imageCount: Number(summary.imageCount || 0),
      defectCount: Number(summary.defectCount || 0),
      highCount: Number(summary.highCount || 0),
      mediumCount: Number(summary.mediumCount || 0),
      lowCount: Number(summary.lowCount || 0),
      latestSortTime: parseTime(summary.latestTime)
    }))
    clearHistoryCache()
  } catch (e) {
    console.warn('加载图像记录失败:', e)
    cameras.value = []
    summaries.value = []
    clearHistoryCache()
  } finally {
    loading.value = false
  }
}
const resetImageFilters = () => {
  searchKeyword.value = ''
  imageFilters.severity = ''
  imageFilters.defectType = ''
  imageTimeRange.value = []
  loadData()
}
const stationLabel = (item = {}) => {
  if (item.stationLabel) return item.stationLabel
  const code = String(item.stationCode || '').trim()
  const name = String(item.stationName || item.workstationName || '').trim()
  if (code && name) {
    const lowerCode = code.toLowerCase()
    const lowerName = name.toLowerCase()
    if (lowerName === lowerCode || lowerName.startsWith(lowerCode) || lowerName.startsWith(`${lowerCode}-`) || lowerName.startsWith(`${lowerCode}_`) || lowerName.startsWith(`${lowerCode}/`)) return name
    return `${code} ${name}`
  }
  return name || code || '未配置工位'
}
const cameraPageKey = cam => String(cam?.id || cam?.name || 'unknown')
const allCards = computed(() => {
  // ★ 2026-11-05：改为 **code-first** 索引（详见 utils/camera-identity.js）。
  //   此前把 cameraName 也当键 → 现场跨产线重名（ZK54 的 CV1_Cam1 与 ZK23 的
  //   CV1_Cam1 同名）会让一台相机拿到另一台的统计，图片数与缺陷数翻倍。
  const summaryIndex = buildCameraSummaryIndex(summaries.value)
  const summaryGroups = summaryIndex.byCode
  const cameraNameCounts = countCameraNames(cameras.value)
  const matchedSummaryKeys = new Set()
  const cards = cameras.value.map((cam, index) => {
    const name = cam.cameraName || cam.cameraCode || `相机-${index + 1}`
    // ★ code-first：先按 code，名称仅在两侧都唯一时兜底（重名宁可 0 不错配）
    const summary = findCameraSummary(summaryIndex, cam, cameraNameCounts)
    if (summary) matchedSummaryKeys.add(summary)
    const cardId = cam.id || cam.cameraId || cam.cameraCode || name
    const historyState = cameraHistoryState[cameraPageKey({ id: cardId, name })] || {}
    const group = Array.isArray(historyState.rows) ? historyState.rows : (summary?.latestImages || [])
    const displayImages = (summary?.latestImages || group).filter(img => !isInvalidImage(img)).slice(0, 5)
    const status = normalizeStatus(cam.status)
    return {
      id: cardId,
      keys: [cameraIdentityKey(cam), cam.cameraCode, cam.cameraId, name].filter(Boolean),
      historyCameraId: summary?.cameraId || cam.cameraCode || cam.cameraId,
      historyCameraName: summary?.cameraName || cam.cameraName,
      name,
      station: stationLabel(cam),
      stationId: cam.stationId || cam.workstationId,
      lineId: cam.lineId || cam.productionLineId,
      factoryId: cam.factoryId,
      statusKey: status.key,
      statusName: status.name,
      highCount: Number(summary?.highCount || 0),
      mediumCount: Number(summary?.mediumCount || 0),
      lowCount: Number(summary?.lowCount || 0),
      recentImages: displayImages,
      history: group,
      historyTotal: Number(historyState.total ?? summary?.imageCount ?? group.length ?? 0),
      latestSortTime: Number(summary?.latestSortTime || 0) || latestTimeOfGroup(group) || parseTime(cam.lastUploadAt || cam.lastHeartbeatAt || cam.updateTime)
    }
  })
  summaries.value.forEach(summary => {
    if (!matchedSummaryKeys.has(summary)) {
      const group = summary.latestImages || []
      const first = group[0] || summary
      const key = summary.cameraId || summary.cameraCode || summary.cameraName || `summary-${cards.length + 1}`
      const cardId = `image-${key}`
      const historyState = cameraHistoryState[cameraPageKey({ id: cardId, name: first.cameraName || key })] || {}
      cards.push({
        id: cardId,
        keys: [key, summary.cameraCode, summary.cameraId, summary.cameraName].filter(Boolean),
        historyCameraId: summary.cameraId || key,
        historyCameraName: summary.cameraName,
        name: first.cameraName || key,
        station: stationLabel(summary),
        stationId: summary.workstationId,
        lineId: summary.lineId,
        factoryId: summary.factoryId,
        statusKey: 'offline',
        statusName: '未配置',
        highCount: Number(summary.highCount || 0),
        mediumCount: Number(summary.mediumCount || 0),
        lowCount: Number(summary.lowCount || 0),
        recentImages: group.filter(img => !isInvalidImage(img)).slice(0, 5),
        history: Array.isArray(historyState.rows) ? historyState.rows : group,
        historyTotal: Number(historyState.total ?? summary.imageCount ?? group.length ?? 0),
        latestSortTime: Number(summary.latestSortTime || 0) || latestTimeOfGroup(group)
      })
    }
  })
  return cards.sort((a, b) => (b.latestSortTime || 0) - (a.latestSortTime || 0) || (b.historyTotal || 0) - (a.historyTotal || 0))
})
const filteredCameras = computed(() => {
  const f = filterParams.value || {}
  const lineIds = f.lineIds || []
  const stationIds = f.stationIds || []
  const keyword = searchKeyword.value.trim().toLowerCase()
  const routeCameraId = String(route.query.cameraId || '').trim().toLowerCase()
  const routeCameraName = String(route.query.cameraName || '').trim().toLowerCase()
  return allCards.value.filter(c => {
    const cameraKeys = [c.name, ...(c.keys || [])].filter(Boolean).map(v => String(v).toLowerCase())
    if (routeCameraId && !cameraKeys.includes(routeCameraId)) return false
    if (routeCameraName && !cameraKeys.includes(routeCameraName)) return false
    if (f.factoryId && !sameId(c.factoryId, f.factoryId)) return false
    if (lineIds.length && !lineIds.some(id => sameId(id, c.lineId))) return false
    if (stationIds.length && !stationIds.some(id => sameId(id, c.stationId))) return false
    if (keyword && !cameraMatchesKeyword(c, keyword)) return false
    return true
  })
})
const filteredImageCount = computed(() => filteredCameras.value.reduce((sum, cam) => sum + Number(cam.historyTotal || 0), 0))
const cameraMatchesKeyword = (cam, keyword) => {
  const imageText = (cam.history || []).map(img => [img.fileName, img.originalFileName, img.captureTimeText, img.uploadTimeText, img.receiveTimeText].filter(Boolean).join(' ')).join(' ')
  const haystack = [cam.name, cam.station, cam.statusName, ...(cam.keys || []), imageText].filter(Boolean).join(' ').toLowerCase()
  return haystack.includes(keyword)
}
const filterInfo = computed(() => {
  const f = filterParams.value || {}
  const labels = filterLabels.value || {}
  const parts = []
  const routeCameraId = String(route.query.cameraId || '').trim()
  const routeCameraName = String(route.query.cameraName || '').trim()
  if (routeCameraId || routeCameraName) parts.push(`相机 ${routeCameraName || routeCameraId}`)
  if (f.factoryId) parts.push(labels.factoryName || `工厂 ${f.factoryId}`)
  if ((f.lineIds || []).length) parts.push((labels.lineNames || []).join('、') || `产线 ${f.lineIds.length} 条`)
  if ((f.stationIds || []).length) parts.push((labels.stationNames || []).join('、') || `工位 ${f.stationIds.length} 个`)
  return parts.join(' / ')
})
const isExpanded = id => !!expandedMap[id]
const emptyThumbCount = cam => Math.max(0, 5 - cam.recentImages.length)
const historyPageOf = cam => cameraPages[cameraPageKey(cam)] || 1
const setHistoryPage = (cam, page) => {
  const maxPage = Math.max(1, Math.ceil(Number(cam?.historyTotal || cam?.history?.length || 0) / historyPageSize.value))
  cameraPages[cameraPageKey(cam)] = Math.min(Math.max(Number(page) || 1, 1), maxPage)
}
const loadCameraHistory = async (cam, page = historyPageOf(cam), size = historyPageSize.value) => {
  if (!cam) return
  const key = cameraPageKey(cam)
  const params = buildImageQueryParams({ page, size })
  delete params.limit
  if (cam.historyCameraId) params.cameraId = cam.historyCameraId
  if (!cam.historyCameraId && cam.historyCameraName) params.cameraName = cam.historyCameraName
  const res = await getDefectImageHistory(params)
  const data = res?.data?.data ?? res?.data ?? {}
  cameraHistoryState[key] = {
    rows: Array.isArray(data.records) ? data.records.map(normalizeImage) : [],
    total: Number(data.total || 0)
  }
  setHistoryPage({ ...cam, historyTotal: Number(data.total || 0) }, data.page || page)
}
const toggleExpand = async cam => {
  if (!cam?.id) return
  expandedMap[cam.id] = !expandedMap[cam.id]
  if (expandedMap[cam.id]) await loadCameraHistory(cam, historyPageOf(cam), historyPageSize.value)
}
const handleHistoryPageSizeChange = async size => {
  historyPageSize.value = size
  await Promise.all(filteredCameras.value.filter(cam => isExpanded(cam.id)).map(cam => loadCameraHistory(cam, 1, historyPageSize.value)))
}
const handleHistoryPaginationChange = async (cam, payload = {}) => {
  if (payload.pageSize && payload.pageSize !== historyPageSize.value) {
    await handleHistoryPageSizeChange(payload.pageSize)
    return
  }
  setHistoryPage(cam, payload.currentPage || 1)
  await loadCameraHistory(cam, historyPageOf(cam), historyPageSize.value)
}
const historyPageRows = cam => cam.history || []
const imageTitle = img => `${img.fileName} / 缺陷 ${img.defectCount || 0} / ${severityText(img.mainSeverity)}`
const previewTitle = computed(() => previewData.value ? `缺陷详情 - ${previewData.value.cameraName}` : '缺陷详情')
const hasPrevImage = computed(() => previewIndex.value > 0)
const hasNextImage = computed(() => previewIndex.value >= 0 && previewIndex.value < previewList.value.length - 1)
const sameImage = (a, b) => {
  if (!a || !b) return false
  const aKey = a.imageId || a.fileName || a.imageUrl
  const bKey = b.imageId || b.fileName || b.imageUrl
  return aKey && bKey && String(aKey) === String(bKey)
}
const setPreviewData = (cam, img) => {
  if (!img) return
  previewData.value = { ...img, cameraName: cam?.name || img.cameraName || '-', station: cam?.station || stationLabel(img) }
}
const previewImage = (cam, img) => {
  const list = Array.isArray(cam?.history) && cam.history.length ? cam.history : [img]
  const index = list.findIndex(item => sameImage(item, img))
  previewCamera.value = cam
  previewList.value = list
  previewIndex.value = index >= 0 ? index : 0
  setPreviewData(cam, previewList.value[previewIndex.value] || img)
  previewVisible.value = true
}
const switchPreview = delta => {
  const nextIndex = previewIndex.value + delta
  if (nextIndex < 0 || nextIndex >= previewList.value.length) return
  previewIndex.value = nextIndex
  setPreviewData(previewCamera.value, previewList.value[nextIndex])
  if (zoomVisible.value) zoomIndex.value = nextIndex
}
/* ---- 全屏放大查看（与弹窗预览双向同步） ---- */
const zoomImages = computed(() => previewList.value.map((img, i) => ({
  url: img?.imageUrl || '',
  name: img?.fileName || `第 ${i + 1} 张`,
  key: img?.imageId || img?.fileName || i
})))
const zoomTitle = computed(() => {
  const name = previewCamera.value?.name || previewData.value?.cameraName || '缺陷图片'
  return `${name} - 放大查看`
})
const zoomSubtitle = computed(() => {
  const fileName = previewData.value?.fileName
  return fileName ? `${fileName}（${previewIndex.value + 1}/${previewList.value.length}）` : ''
})
const openZoomViewer = () => {
  if (!previewList.value.length && !previewData.value) return
  if (previewIndex.value < 0) previewIndex.value = 0
  zoomIndex.value = previewIndex.value
  zoomVisible.value = true
}
/** 放大查看中切换图片时，反向同步弹窗预览 */
const syncZoomIndex = index => {
  zoomIndex.value = index
  if (index < 0 || index >= previewList.value.length) return
  previewIndex.value = index
  setPreviewData(previewCamera.value, previewList.value[index])
}
onMounted(loadData)
watch(filterParams, loadData, { deep: true })
watch(() => route.query, () => loadData(), { deep: true })
watch(filteredCameras, cams => { cams.forEach(cam => setHistoryPage(cam, historyPageOf(cam))) })
watch(previewVisible, visible => {
  if (!visible) {
    previewList.value = []
    previewIndex.value = -1
    previewCamera.value = null
    previewData.value = null
  }
})
defineExpose({ reload: loadData })
</script>

<style scoped>
.image-records{display:flex;flex-direction:column;gap:16px}.page-header,.section-header{display:flex;align-items:flex-start;justify-content:space-between;gap:16px}.page-header h2,.section-header h3{margin:0 0 6px;color:#111827}.page-header h2{font-size:22px}.section-header h3{font-size:18px}.page-header p,.section-header p{margin:0;color:#6b7280;font-size:13px}.filter-hint,.more-link{font-size:13px;color:var(--color-primary);background:var(--color-primary-bg);padding:6px 12px;border-radius:999px;text-decoration:none;white-space:nowrap}.records-toolbar{display:flex;align-items:flex-start;justify-content:space-between;gap:12px;padding:12px 14px;background:var(--color-bg-card);border:1px solid var(--color-border);border-radius:12px;box-shadow:0 1px 3px rgba(15,23,42,.04)}.toolbar-filters{display:flex;align-items:center;gap:10px;flex-wrap:wrap;min-width:0}.image-search{width:320px;max-width:100%}.toolbar-summary{display:flex;align-items:center;gap:8px;color:var(--color-text-secondary);font-size:12px;white-space:nowrap}.toolbar-summary span,.image-total{padding:4px 10px;border-radius:999px;background:var(--color-bg-page);border:1px solid var(--color-border)}.cam-col{margin-bottom:16px}.cam-card{background:var(--color-bg-card);border-radius:12px;border:1px solid var(--color-border);box-shadow:0 1px 3px rgba(15,23,42,.05);overflow:hidden;transition:box-shadow .2s}.cam-card:hover{box-shadow:0 8px 20px rgba(15,23,42,.08)}.cam-hd{display:flex;justify-content:space-between;align-items:center;gap:12px;padding:14px 18px;cursor:pointer;border-bottom:1px solid var(--color-bg-subtle)}.cam-hd:hover{background:var(--color-bg-page)}.cam-hd-left,.cam-hd-right{display:flex;align-items:center;gap:10px;min-width:0}.cam-hd-left{flex:1}.cam-name{color:#111827;font-size:15px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.cam-station,.cam-expand-icon,.image-total{color:var(--color-text-secondary);font-size:12px;white-space:nowrap}.cam-status{display:inline-flex;align-items:center;gap:5px;font-size:12px;font-weight:700;padding:2px 9px;border-radius:999px;white-space:nowrap}.st-dot{width:6px;height:6px;border-radius:50%}.st-running{color:#15803d;background:var(--color-success-bg)}.st-running .st-dot{background:var(--color-success-light)}.st-stopped{color:#475569;background:var(--color-bg-subtle)}.st-stopped .st-dot{background:var(--color-text-secondary)}.st-standby{color:#b45309;background:var(--color-warning-bg)}.st-standby .st-dot{background:var(--color-warning)}.st-fault{color:#b91c1c;background:var(--color-error-bg)}.st-fault .st-dot{background:var(--color-error-light)}.st-offline{color:var(--color-text-secondary);background:var(--color-bg-subtle)}.st-offline .st-dot{background:var(--color-text-placeholder)}.severity-pill{font-size:12px;padding:2px 8px;border-radius:999px;font-weight:700}.severity-pill.high{color:#b91c1c;background:var(--color-error-bg)}.severity-pill.medium{color:#b45309;background:var(--color-warning-bg)}.severity-pill.low{color:var(--color-primary-active);background:#dbeafe}.cam-body{padding:14px 18px}.thumb-label{color:var(--color-text-secondary);font-size:12px;margin-bottom:10px}.thumb-row{display:flex;gap:8px;flex-wrap:wrap}.thumb-item{cursor:pointer}.thumb-img-wrap{width:96px;height:72px;border-radius:8px;border:1px solid var(--color-border);background:var(--color-bg-page);position:relative;overflow:hidden;display:flex;align-items:center;justify-content:center;color:#cbd5e1}.thumb-img-wrap img{width:100%;height:100%;object-fit:cover;transition:transform .2s}.thumb-img-wrap:hover img{transform:scale(1.08)}.thumb-placeholder{font-size:12px;color:var(--color-text-placeholder)}.thumb-placeholder.invalid{color:#b45309;background:#fffbeb;width:100%;height:100%;display:flex;align-items:center;justify-content:center}.thumb-severity{position:absolute;right:4px;bottom:4px;color:#fff;font-size:11px;line-height:1;padding:3px 6px;border-radius:4px}.thumb-severity.high{background:var(--color-error-light)}.thumb-severity.medium{background:var(--color-warning)}.thumb-severity.low{background:var(--color-primary-hover)}.empty-thumb{cursor:default;font-size:20px}.cam-history{padding:0 18px 16px}.history-divider{height:1px;background:var(--color-bg-subtle);margin-bottom:14px}.history-title{font-size:13px;font-weight:700;color:#374151;margin-bottom:10px}.history-title span{color:var(--color-text-placeholder);font-weight:400}.hist-thumb{width:42px;height:32px;border-radius:6px;overflow:hidden;border:1px solid var(--color-border);background:var(--color-bg-page);display:flex;align-items:center;justify-content:center;cursor:pointer}.hist-thumb img{width:100%;height:100%;object-fit:cover}.pager{margin-top:12px;justify-content:center}.preview-content{display:flex;flex-direction:column;gap:16px}.preview-img-wrap{position:relative;min-height:320px;max-height:520px;background:var(--color-text);border-radius:12px;overflow:hidden;display:flex;align-items:center;justify-content:center}.preview-img-wrap img{max-width:100%;max-height:520px;object-fit:contain}.preview-nav{position:absolute;top:50%;z-index:2;transform:translateY(-50%);width:40px;height:40px;border:none;background:rgba(255,255,255,.9);color:var(--color-text);font-size:24px;font-weight:800;box-shadow:0 6px 18px rgba(15,23,42,.22)}.preview-nav.prev{left:14px}.preview-nav.next{right:14px}.preview-zoom{position:absolute;right:14px;top:12px;z-index:3;background:rgba(255,255,255,.94);border-color:rgba(148,163,184,.6);color:var(--color-text);font-weight:600;box-shadow:0 4px 12px rgba(15,23,42,.2)}.preview-zoom:hover{background:var(--color-bg-card);color:var(--color-primary);border-color:var(--color-primary)}.preview-zoom-icon{font-size:14px;line-height:1;margin-right:2px}.preview-img-wrap img{cursor:zoom-in}.preview-counter{position:absolute;left:50%;bottom:12px;transform:translateX(-50%);z-index:2;padding:4px 10px;border-radius:999px;background:rgba(15,23,42,.72);color:#fff;font-size:12px;font-weight:700}.preview-empty{display:flex;flex-direction:column;align-items:center;justify-content:center;gap:8px;padding:32px;color:var(--color-text-secondary);text-align:center;background:var(--color-bg-page);width:100%;min-height:320px}.preview-empty strong{color:var(--color-text-regular);font-size:15px}.preview-empty span{max-width:520px;font-size:13px;line-height:1.6}.preview-info-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.info-item{display:flex;gap:8px;padding:9px 12px;background:var(--color-bg-page);border-radius:8px;min-width:0}.info-item span{color:var(--color-text-secondary);font-size:12px;flex:none}.info-item strong{color:#111827;font-size:13px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.defect-table{margin-top:2px}@media (max-width:1280px){.cam-hd{align-items:flex-start;flex-direction:column}.preview-info-grid{grid-template-columns:1fr}}@media (max-width:720px){.records-toolbar{align-items:stretch;flex-direction:column}.toolbar-filters{align-items:stretch;flex-direction:column}.image-search{width:100%}.toolbar-summary{justify-content:flex-start}}
</style>
