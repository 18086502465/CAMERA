<template>
  <div class="page gallery-page">
    <h2>🖼 缺陷图库</h2>
    <div class="filter-bar" style="margin-bottom:16px;display:flex;gap:8px;flex-wrap:wrap;align-items:center">
      <el-select v-model="filters.lineId" placeholder="产线" clearable size="small" style="width:140px">
        <el-option v-for="l in lineOptions" :key="l.value" :label="l.label" :value="l.value" />
      </el-select>
      <el-select v-model="filters.stationId" placeholder="工位" clearable size="small" style="width:140px">
        <el-option v-for="s in availableStations" :key="s.value" :label="s.label" :value="s.value" />
      </el-select>
      <el-select v-model="filters.defectType" placeholder="缺陷类型" clearable size="small" style="width:120px">
        <el-option v-for="t in defectTypes" :key="t" :label="t" :value="t" />
      </el-select>
      <el-select v-model="filters.severity" placeholder="严重程度" clearable size="small" style="width:110px">
        <el-option label="严重" value="严重" /><el-option label="重大" value="重大" /><el-option label="轻微" value="轻微" />
      </el-select>
      <el-date-picker v-model="filters.dateRange" type="daterange" start-placeholder="开始" end-placeholder="结束" size="small" value-format="YYYY-MM-DD" />
      <el-button type="primary" size="small" @click="doSearch">搜索</el-button>
    </div>

    <!-- 瀑布流网格 -->
    <div class="image-grid" v-if="filteredImages.length > 0">
      <div v-for="img in filteredImages" :key="img.id" class="grid-item" @click="previewData=img;previewVisible=true">
        <div class="grid-img"><span style="font-size:32px">📷</span></div>
        <div class="grid-info">
          <span class="gi-type">{{ img.type }}</span>
          <el-tag :type="img.severity==='严重'?'danger':img.severity==='重大'?'warning':'info'" size="small">{{ img.severity }}</el-tag>
          <span style="font-size:11px;color:#909399">{{ img.time }}</span>
        </div>
      </div>
    </div>
    <EmptyState v-else text="暂无匹配的缺陷图像" />

    <el-pagination
      v-if="filteredImageList.length > 0"
      v-model:current-page="currentPage"
      v-model:page-size="pageSize"
      :page-sizes="pageSizeOptions"
      :total="filteredImageList.length"
      layout="total, sizes, prev, pager, next"
      style="margin-top:16px;justify-content:center"
      background
    />

    <!-- 图片预览 -->
    <el-dialog v-model="previewVisible" title="缺陷详情" width="720px" :close-on-click-modal="false">
      <div v-if="previewData" style="text-align:center">
        <div style="width:100%;height:380px;background:linear-gradient(135deg,#1e293b,#475569);display:flex;align-items:center;justify-content:center;border-radius:8px;margin-bottom:12px">
          <span style="font-size:72px;opacity:0.5">📷</span>
        </div>
        <div style="display:grid;grid-template-columns:1fr 1fr 1fr;gap:8px;text-align:left">
          <div><strong>产线</strong>: {{ previewData.line }}</div><div><strong>工位</strong>: {{ previewData.station }}</div><div><strong>时间</strong>: {{ previewData.time }}</div>
          <div><strong>缺陷</strong>: {{ previewData.type }}</div><div><strong>严重度</strong>: <el-tag :type="previewData.severity==='严重'?'danger':'warning'" size="small">{{ previewData.severity }}</el-tag></div>
          <div><strong>面积</strong>: {{ previewData.area }} mm²</div>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, inject, watch, onMounted } from 'vue'
import { getDefectList } from '@/api/defect'
import { getCascadeAll } from '@/api/cascade'
import EmptyState from '@/components/EmptyState.vue'

const filters = reactive({ lineId: null, stationId: null, defectType: '', severity: '', dateRange: [] })
const currentPage = ref(1)
const pageSizeOptions = [10, 20, 50, 100]
const pageSize = ref(10)
const previewVisible = ref(false)
const previewData = ref(null)

/* ---- Cascade data ---- */
const lineOptions = ref([])
const stationOptions = ref([])

const defectTypes = ['表面划痕', '边缘破损', '尺寸偏差', '颜色不均', '污渍异物']
const images = ref([])

const stationLabel = (s = {}) => {
  if (s.stationLabel) return s.stationLabel
  const code = String(s.stationCode || '').trim()
  const name = String(s.stationName || s.workstationName || '').trim()
  if (code && name) {
    const lowerCode = code.toLowerCase()
    const lowerName = name.toLowerCase()
    if (lowerName === lowerCode || lowerName.startsWith(lowerCode) || lowerName.startsWith(lowerCode + '-') || lowerName.startsWith(lowerCode + '_') || lowerName.startsWith(lowerCode + '/')) return name
    return `${code} ${name}`
  }
  return name || code || '未命名工位'
}
const lineLabel = (l = {}) => {
  const code = String(l.lineCode || '').trim()
  const name = String(l.lineName || '').trim()
  if (code && name) return `${code} ${name}`
  return name || code || '未命名产线'
}
const lineLabelById = (id) => lineOptions.value.find(l => l.value === id)?.label
const stationLabelById = (id) => stationOptions.value.find(s => s.value === id)?.label

const loadCascade = async () => {
  try {
    const res = await getCascadeAll()
    const d = res.data?.data || res.data || {}
    const tree = d.factories || []
    const linesFromTree = tree.flatMap(f => (f.lines || []).map(l => ({ ...l, factoryId: f.factoryId })))
    const stationsFromTree = tree.flatMap(f => (f.lines || []).flatMap(l => (l.stations || []).map(s => ({ ...s, lineId: l.lineId }))))
    const lines = linesFromTree.length ? linesFromTree : (d.lines || d.flattenedLines || [])
    const stations = stationsFromTree.length ? stationsFromTree : (d.workstations || d.flattenedWorkstations || [])
    lineOptions.value = lines.map(l => ({ label: lineLabel(l), value: l.lineId }))
    stationOptions.value = stations.map(s => ({
      label: stationLabel(s),
      value: s.stationId || s.workstationId,
      lineId: s.lineId,
    }))
  } catch (e) { console.warn('GalleryView cascade load error:', e) }
}

/* ---- Cascaded station list ---- */
const availableStations = computed(() => {
  if (!filters.lineId) return stationOptions.value
  return stationOptions.value.filter(s => s.lineId === filters.lineId)
})

// Reset station when line changes
watch(() => filters.lineId, () => { filters.stationId = null })

/* ---- Global filter ---- */
const filterParams = inject('filterParams', ref({ factoryId: null, lineIds: [], stationIds: [] }))

const fetchImages = async () => {
  try {
    const fp = filterParams.value
    const dr = filters.dateRange
    const params = {
      startDate: dr?.length === 2 ? dr[0] : '2026-01-01',
      endDate: dr?.length === 2 ? dr[1] : new Date(Date.now() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 10),
    }
    if (fp.factoryId) params.factoryId = fp.factoryId
    const res = await getDefectList(params)
    const list = res.data?.data || res.data || []
    images.value = (Array.isArray(list) ? list : []).map((d, i) => ({
      id: d.detectionId || i + 1,
      lineId: d.lineId,
      workstationId: d.workstationId,
      line: d.lineLabel || d.lineName || lineLabelById(d.lineId) || (d.lineId ? `产线${d.lineId}` : '-'),
      station: d.stationLabel || d.stationName || stationLabelById(d.workstationId) || '未配置工位',
      type: d.defectType || '未知',
      severity: d.defectSize ? (Number(d.defectSize) > 50 ? '严重' : Number(d.defectSize) >= 20 ? '重大' : '轻微') : '轻微',
      area: d.defectSize || '0',
      time: d.detectionTime || '',
    }))
  } catch (e) { console.warn('GalleryView fetch error:', e) }
}

/* ---- Search / Filter ---- */
const doSearch = () => {
  currentPage.value = 1
  // filtering is reactive — computed will update
}

const filteredImageList = computed(() => {
  let list = images.value
  if (filters.lineId) list = list.filter(i => i.lineId === filters.lineId)
  if (filters.stationId) list = list.filter(i => i.workstationId === filters.stationId)
  if (filters.defectType) list = list.filter(i => i.type === filters.defectType)
  if (filters.severity) list = list.filter(i => i.severity === filters.severity)
  return list
})

const filteredImages = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredImageList.value.slice(start, start + pageSize.value)
})

// Reset page on filter change
watch(() => [filters.lineId, filters.stationId, filters.defectType, filters.severity, filters.dateRange],
  () => { currentPage.value = 1 }, { deep: true })
watch(pageSize, () => { currentPage.value = 1 })

onMounted(async () => {
  await loadCascade()
  await fetchImages()
})
</script>

<style scoped>
.gallery-page { max-width: 1440px; margin: 0 auto; padding: 20px 16px; }
.image-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.grid-item { cursor: pointer; border: 1px solid #e4e7ed; border-radius: 8px; overflow: hidden; transition: box-shadow .2s; }
.grid-item:hover { box-shadow: 0 4px 12px rgba(0,0,0,.1); }
.grid-img { height: 140px; background: #f9fafb; display: flex; align-items: center; justify-content: center; }
.grid-info { padding: 8px; display: flex; flex-wrap: wrap; gap: 4px; align-items: center; }
.gi-type { font-size: 13px; font-weight: 600; color: #1a1a2e; flex: 1; }
</style>
