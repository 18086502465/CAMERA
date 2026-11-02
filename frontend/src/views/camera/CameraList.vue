<template>
  <div class="page-container">
    <div class="page-header">
      <h2>📷 相机监控</h2>
      <div class="header-actions">
        <el-radio-group v-model="activeTab" @change="handleTabChange">
          <el-radio-button value="factory">按工厂</el-radio-button>
          <el-radio-button value="line">按产线</el-radio-button>
        </el-radio-group>
      </div>
    </div>

    <!-- 筛选栏 -->
    <el-card shadow="never" class="filter-card">
      <el-space wrap>
        <el-select
          v-model="filterFactory"
          placeholder="选择工厂"
          clearable
          style="width: 180px"
          @change="handleFilterChange"
        >
          <el-option
            v-for="f in factoryOptions"
            :key="f.factoryId"
            :label="f.factoryName"
            :value="f.factoryId"
          />
        </el-select>

        <el-select
          v-model="filterLine"
          placeholder="选择产线"
          clearable
          style="width: 180px"
          @change="handleFilterChange"
        >
          <el-option
            v-for="l in lineOptions"
            :key="l.lineId"
            :label="l.lineName"
            :value="l.lineId"
          />
        </el-select>

        <el-input
          v-model="keyword"
          placeholder="搜索相机名称..."
          clearable
          style="width: 220px"
          @clear="handleFilterChange"
          @keyup.enter="handleFilterChange"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>

        <el-select v-model="filterStatus" placeholder="状态筛选" clearable @change="handleFilterChange" style="width: 120px">
          <el-option label="运行中" :value="1" />
          <el-option label="故障" :value="2" />
          <el-option label="待机" :value="3" />
          <el-option label="离线" :value="9" />
        </el-select>

        <el-button type="primary" @click="handleFilterChange">🔍 查询</el-button>
        <el-button @click="handleReset">重置</el-button>
      </el-space>
    </el-card>

    <!-- 表格 -->
    <el-card shadow="never" class="table-card">
      <el-table :data="tableData" v-loading="loading" stripe border style="width: 100%">
        <el-table-column prop="cameraName" label="相机名称" min-width="120" fixed />
        <el-table-column prop="deviceId" label="设备编号" width="120" />
        <el-table-column prop="factoryName" label="所属工厂" width="140" />
        <el-table-column prop="lineName" label="所属产线" width="140" />
        <el-table-column label="状态" width="90" align="center">
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
              @click="openGallery(row)"
            >
              <img :src="row.latestImagePath" style="width:100%;height:100%;object-fit:cover" />
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
            <span :style="{ color: accuracyColor(row) }">{{ calcAccuracy(row) }}%</span>
          </template>
        </el-table-column>
        <el-table-column prop="reportTime" label="上报时间" width="160" sortable />
        <el-table-column label="操作" width="120" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openGallery(row)">查看图片</el-button>
          </template>
        </el-table-column>
      </el-table>

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
    </el-card>

    <!-- 图片相册弹窗 -->
    <el-dialog v-model="galleryVisible" :title="galleryTitle" width="750px" destroy-on-close>
      <div v-if="galleryImages.length > 0" style="display:flex;flex-wrap:wrap;gap:12px;justify-content:center">
        <el-image
          v-for="(img, idx) in galleryImages"
          :key="idx"
          :src="getImageUrl(img.imagePath)"
          fit="contain"
          style="width:200px;height:200px;border-radius:8px;cursor:pointer"
          :preview-src-list="galleryImages.map(i => getImageUrl(i.imagePath))"
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
import { Search } from '@element-plus/icons-vue'
import { getCameraList, getCameraImages } from '../../api/camera'
import { getFactoryList } from '../../api/factory'
import { getLineList } from '../../api/line'

const activeTab = ref('factory')
const keyword = ref('')
const filterStatus = ref(null)
const filterFactory = ref(null)
const filterLine = ref(null)
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)
const tableData = ref([])
const loading = ref(false)
const factoryOptions = ref([])
const lineOptions = ref([])

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

const getImageUrl = (path) => path || ''

const openGallery = async (row) => {
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

const loadData = async () => {
  loading.value = true
  try {
    const params = {
      page: currentPage.value,
      size: pageSize.value
    }
    if (keyword.value) params.keyword = keyword.value
    if (filterStatus.value != null) params.status = filterStatus.value
    if (filterFactory.value) params.factoryId = filterFactory.value
    if (filterLine.value) params.lineId = filterLine.value

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

const handleFilterChange = () => {
  currentPage.value = 1
  loadData()
}

const handleReset = () => {
  keyword.value = ''
  filterStatus.value = null
  filterFactory.value = null
  filterLine.value = null
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

const handleTabChange = () => {
  // Tab 切换时不清空筛选，只刷新
  loadData()
}

const loadFactories = async () => {
  try {
    const res = await getFactoryList({ size: 100 })
    factoryOptions.value = res.data.data.records || []
  } catch (e) { console.error(e) }
}

const loadLines = async () => {
  try {
    const params = {}
    if (filterFactory.value) params.factoryId = filterFactory.value
    const res = await getLineList(params)
    lineOptions.value = res.data.data.records || []
  } catch (e) { console.error(e) }
}

onMounted(() => {
  loadFactories()
  loadLines()
  loadData()
})
</script>

<style scoped>
.page-container { padding: 0; }
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.page-header h2 { margin: 0; font-size: 18px; color: #1a1a2e; }
.filter-card { margin-bottom: 16px; }
.table-card { margin-bottom: 16px; }
.pagination-wrap { margin-top: 14px; display: flex; justify-content: flex-end; }
</style>

