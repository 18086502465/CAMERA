<template>
  <div class="table-card">
    <div class="table-header">
      <span class="table-title">相机详情列表</span>
      <div class="table-actions">
        <el-input
          v-model="keyword"
          placeholder="搜索相机名称/设备号/产品..."
          clearable
          @clear="handleSearch"
          @keyup.enter="handleSearch"
          style="width: 260px"
        />
        <el-select v-model="filterStatus" placeholder="状态筛选" clearable @change="handleSearch" style="width: 120px">
          <el-option label="运行中" :value="1" />
          <el-option label="已停止" :value="0" />
          <el-option label="故障" :value="2" />
          <el-option label="待机" :value="3" />
          <el-option label="离线" :value="9" />
        </el-select>
        <el-button type="primary" @click="handleSearch">
          🔍 查询
        </el-button>
        <el-button @click="handleReset">重置</el-button>
      </div>
    </div>

    <el-table
      :data="tableData"
      v-loading="loading"
      stripe
      border
      style="width: 100%"
      :default-sort="{ prop: 'reportTime', order: 'descending' }"
    >
      <el-table-column prop="cameraName" label="相机名称" min-width="120" fixed />
      <el-table-column prop="deviceId" label="设备编号" min-width="110" />
      <el-table-column prop="productName" label="当前产品" min-width="140" show-overflow-tooltip />
      <el-table-column prop="cameraStatus" label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="statusType(row.cameraStatus)" size="small">
            {{ statusText(row.cameraStatus) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="采集图片" width="100" align="center">
        <template #default="{ row }">
          <div
            v-if="row.latestImagePath"
            style="width:60px;height:60px;border-radius:6px;overflow:hidden;cursor:pointer;position:relative"
            @click="openImageGallery(row)"
          >
            <img :src="imageUrl(row.latestImagePath)" style="width:100%;height:100%;object-fit:cover" />
            <span style="position:absolute;bottom:2px;right:4px;background:rgba(0,0,0,0.6);color:#fff;font-size:10px;padding:0 4px;border-radius:2px">🖼</span>
          </div>
          <span v-else style="color:#c0c4cc;font-size:12px">暂无</span>
        </template>
      </el-table-column>
      <el-table-column prop="recognizeCount" label="识别总数" width="100" align="right" sortable />
      <el-table-column prop="correctCount" label="正确数" width="90" align="right" sortable />
      <el-table-column prop="abnormalCount" label="异常数" width="90" align="right" sortable />
      <el-table-column label="准确率" width="90" align="center">
        <template #default="{ row }">
          <span :style="{ color: accuracyColor(row) }">
            {{ calcAccuracy(row) }}%
          </span>
        </template>
      </el-table-column>
      <el-table-column label="CPU" width="80" align="center">
        <template #default="{ row }">
          <el-progress
            :percentage="Math.round(row.cpuUsage || 0)"
            :color="progressColor(row.cpuUsage)"
            :stroke-width="6"
            :show-text="false"
          />
          <span class="progress-text">{{ row.cpuUsage ?? '-' }}%</span>
        </template>
      </el-table-column>
      <el-table-column label="内存" width="80" align="center">
        <template #default="{ row }">
          <el-progress
            :percentage="Math.round(row.memoryUsage || 0)"
            :color="progressColor(row.memoryUsage)"
            :stroke-width="6"
            :show-text="false"
          />
          <span class="progress-text">{{ row.memoryUsage ?? '-' }}%</span>
        </template>
      </el-table-column>
      <el-table-column label="温度" width="80" align="center">
        <template #default="{ row }">
          <span :style="{ color: tempColor(row.temperature) }">
            {{ row.temperature != null ? row.temperature + '°C' : '-' }}
          </span>
        </template>
      </el-table-column>
      <el-table-column prop="reportTime" label="上报时间" width="160" sortable />
      <el-table-column prop="errorMessage" label="故障信息" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">
          <span :style="{ color: row.errorMessage ? '#f56c6c' : '#c0c4cc' }">
            {{ row.errorMessage || '无' }}
          </span>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页 -->
    <div class="pagination-wrap">
      <el-pagination
        v-model:current-page="currentPage"
        v-model:page-size="pageSize"
        :page-sizes="[10, 20, 50, 100]"
        :total="total"
        layout="total, sizes, prev, pager, next, jumper"
        @size-change="handleSizeChange"
        @current-change="handlePageChange"
        background
      />
    </div>

    <!-- 图片相册弹窗 -->
    <el-dialog v-model="galleryVisible" :title="galleryTitle" width="700px" destroy-on-close>
      <div v-if="galleryImages.length > 0" style="display:flex;flex-wrap:wrap;gap:12px;justify-content:center">
        <el-image
          v-for="(img, idx) in galleryImages"
          :key="idx"
          :src="imageUrl(img.imagePath)"
          fit="contain"
          style="width:200px;height:200px;border-radius:8px;cursor:pointer"
          :preview-src-list="galleryImages.map(i => imageUrl(i.imagePath))"
          :initial-index="idx"
          preview-teleported
        />
      </div>
      <div v-else style="text-align:center;padding:40px;color:#999">暂无图片</div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getCameraList, getCameraImages } from '../api/camera'

const keyword = ref('')
const filterStatus = ref(null)
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)
const tableData = ref([])
const loading = ref(false)

// 图片相册
const galleryVisible = ref(false)
const galleryTitle = ref('')
const galleryImages = ref([])

const statusMap = { 0: '已停止', 1: '运行中', 2: '故障', 3: '待机', 9: '离线' }
const statusTypeMap = { 0: 'info', 1: 'success', 2: 'danger', 3: 'warning', 9: '' }

const statusText = (s) => statusMap[s] ?? '未知'
const statusType = (s) => statusTypeMap[s] ?? 'info'

const calcAccuracy = (row) => {
  const total = row.recognizeCount || 0
  if (total === 0) return '0.0'
  return ((row.correctCount || 0) / total * 100).toFixed(1)
}

const accuracyColor = (row) => {
  const rate = parseFloat(calcAccuracy(row))
  if (rate >= 98) return '#67c23a'
  if (rate >= 95) return '#e6a23c'
  return '#f56c6c'
}

const progressColor = (val) => {
  if (val == null || val < 60) return '#67c23a'
  if (val < 85) return '#e6a23c'
  return '#f56c6c'
}

// 图片路径转完整URL（通过 Vite proxy 访问后端）
const imageUrl = (path) => {
  if (!path) return ''
  return path   // 直接使用相对路径，由 vite proxy 转发到后端
}

const openImageGallery = async (row) => {
  galleryTitle.value = row.cameraName + ' - 采集图片'
  galleryVisible.value = true
  galleryImages.value = []
  try {
    const res = await getCameraImages(row.cameraName, 10)
    if (res.data && res.data.data) {
      galleryImages.value = res.data.data
    }
  } catch (e) {
    console.error('加载图片失败', e)
  }
}

const tempColor = (val) => {
  if (val == null || val < 50) return '#67c23a'
  if (val < 70) return '#e6a23c'
  return '#f56c6c'
}

const loadData = async () => {
  loading.value = true
  try {
    const params = {
      page: currentPage.value,
      size: pageSize.value
    }
    if (keyword.value) params.keyword = keyword.value
    if (filterStatus.value != null) params.status = filterStatus.value

    const res = await getCameraList(params)
    const data = res.data.data
    tableData.value = data.records || []
    total.value = data.total || 0
  } catch (e) {
    console.error('加载列表失败:', e)
  } finally {
    loading.value = false
  }
}

const handleSearch = () => {
  currentPage.value = 1
  loadData()
}

const handlePageChange = (page) => {
  currentPage.value = page
  loadData()
}

const handleSizeChange = (size) => {
  pageSize.value = size
  currentPage.value = 1
  loadData()
}

const handleReset = () => {
  keyword.value = ''
  filterStatus.value = null
  currentPage.value = 1
  loadData()
}

onMounted(() => loadData())
</script>

<style scoped>
.table-card {
  background: #fff;
  border-radius: 10px;
  padding: 16px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.06);
}

.table-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 14px;
}

.table-title { font-size: 15px; font-weight: 600; color: #1a1a2e; }

.table-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  align-items: center;
}

.progress-text {
  font-size: 11px;
  color: #606266;
  display: block;
  text-align: center;
}

.pagination-wrap {
  margin-top: 14px;
  display: flex;
  justify-content: flex-end;
}
</style>

