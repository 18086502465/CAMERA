<template>
  <div class="page-container">
    <div class="page-header">
      <h2>📋 检测记录</h2>
      <div>
        <el-radio-group v-model="activeTab" @change="handleTabChange">
          <el-radio-button value="realtime">实时数据</el-radio-button>
          <el-radio-button value="history">历史数据</el-radio-button>
        </el-radio-group>
      </div>
    </div>

    <!-- 搜索栏 -->
    <el-card shadow="never" class="filter-card">
      <el-space wrap>
        <el-input
          v-model="cameraName"
          placeholder="相机名称"
          style="width: 160px"
          clearable
        />
        <el-select v-model="filterLine" placeholder="产线筛选" clearable style="width: 160px">
          <el-option
            v-for="l in lineOptions"
            :key="l.lineId"
            :label="l.lineName"
            :value="l.lineId"
          />
        </el-select>
        <el-date-picker
          v-model="timeRange"
          type="daterange"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          style="width: 260px"
        />
        <el-button type="primary" @click="handleSearch">🔍 搜索</el-button>
        <el-button @click="handleReset">重置</el-button>
        <el-button type="success" @click="handleExport">📊 导出 Excel</el-button>
      </el-space>
    </el-card>

    <!-- 表格 -->
    <el-card shadow="never" class="table-card">
      <el-table :data="tableData" v-loading="loading" stripe border style="width: 100%">
        <el-table-column prop="startTime" label="开始时间" width="160" sortable />
        <el-table-column prop="productName" label="产品名称" min-width="140" />
        <el-table-column prop="cameraName" label="相机名称" width="120" />
        <el-table-column prop="totalCount" label="总数" width="80" align="right" sortable />
        <el-table-column prop="okCount" label="OK" width="80" align="right" sortable />
        <el-table-column prop="ngCount" label="NG" width="80" align="right" sortable />
        <el-table-column label="良率" width="90" align="center">
          <template #default="{ row }">
            <span :style="{ color: row.totalCount > 0 && (row.okCount / row.totalCount) >= 0.95 ? '#67c23a' : '#f56c6c' }">
              {{ row.totalCount > 0 ? ((row.okCount / row.totalCount) * 100).toFixed(1) : '0.0' }}%
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="incrementalTotal" label="增量总数" width="100" align="right" />
        <el-table-column prop="incrementalOk" label="增量OK" width="100" align="right" />
        <el-table-column prop="incrementalNg" label="增量NG" width="100" align="right" />
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
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from '@/utils/message.js'

const activeTab = ref('realtime')
const cameraName = ref('')
const filterLine = ref(null)
const timeRange = ref(null)
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)
const tableData = ref([])
const loading = ref(false)
const lineOptions = ref([])

// 模拟数据（后端 DetectionRecordController 完成后替换为真实 API）
const mockData = () => {
  const rows = []
  for (let i = 0; i < pageSize.value; i++) {
    const total = Math.floor(Math.random() * 200) + 50
    const ok = Math.floor(total * (0.9 + Math.random() * 0.1))
    rows.push({
      startTime: '2026-05-28 16:' + String(i).padStart(2, '0') + ':00',
      productName: '008小护-K8',
      cameraName: i % 2 === 0 ? '正面检测' : '背面检测',
      totalCount: total,
      okCount: ok,
      ngCount: total - ok,
      incrementalTotal: Math.floor(Math.random() * 20) + 1,
      incrementalOk: Math.floor(Math.random() * 18) + 1,
      incrementalNg: Math.floor(Math.random() * 5)
    })
  }
  return rows
}

const loadData = async () => {
  loading.value = true
  try {
    // TODO: 替换为真实 API
    // const params = { page: currentPage.value, size: pageSize.value }
    // if (cameraName.value) params.cameraName = cameraName.value
    // if (filterLine.value) params.lineId = filterLine.value
    // if (timeRange.value) {
    //   params.startTime = timeRange.value[0].toISOString()
    //   params.endTime = timeRange.value[1].toISOString()
    // }
    // const res = await getDetectionRecords(params)
    // tableData.value = res.data.data.records || []
    // total.value = res.data.data.total || 0

    // 模拟延迟
    await new Promise(r => setTimeout(r, 300))
    tableData.value = mockData()
    total.value = 987
  } catch (e) {
    console.error('加载检测记录失败:', e)
    ElMessage.error('数据加载失败')
  } finally {
    loading.value = false
  }
}

const handleSearch = () => {
  currentPage.value = 1
  loadData()
}

const handleReset = () => {
  cameraName.value = ''
  filterLine.value = null
  timeRange.value = null
  currentPage.value = 1
  loadData()
}

const handleTabChange = () => {
  currentPage.value = 1
  loadData()
}

const handleSizeChange = (size) => {
  pageSize.value = size
  currentPage.value = 1
  loadData()
}

const handlePageChange = (page) => {
  currentPage.value = page
  loadData()
}

const handleExport = () => {
  ElMessage.info('导出功能：后端接口完成后实现')
  // window.open(`/api/detection/records/export?${new URLSearchParams({...}).toString()}`)
}

onMounted(() => loadData())
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

