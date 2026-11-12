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
        <el-tooltip content="导出接口尚未实现，暂不可用" placement="top">
          <span>
            <el-button type="success" disabled>📊 导出 Excel</el-button>
          </span>
        </el-tooltip>
      </el-space>
      <el-alert
        v-if="historyQueryHint"
        type="info"
        :closable="false"
        show-icon
        title="查询范围包含 31 天前的历史数据：将自动查询月度归档表，结果可能较慢"
        style="margin-top: 10px"
      />
    </el-card>

    <!-- 表格 -->
    <el-card shadow="never" class="table-card">
      <el-table :data="tableData" v-loading="loading" stripe border style="width: 100%">
        <el-table-column prop="startTime" label="开始时间" width="160" sortable />
        <el-table-column prop="productName" label="产品名称" min-width="140" />
        <el-table-column prop="cameraName" label="相机名称" width="120" />
        <el-table-column prop="totalCount" label="缺陷图数" width="90" align="right" sortable />
        <el-table-column label="OK" width="80" align="right">
          <template #default="{ row }">
            <!-- 该通道无良品样本，OK 数不可计算 → 显示「—」而非 0 -->
            <span v-if="row.okCount == null" style="color: #94a3b8">—</span>
            <span v-else>{{ row.okCount }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="ngCount" label="NG" width="80" align="right" sortable />
        <el-table-column label="良率" width="90" align="center">
          <template #default="{ row }">
            <!-- 无良品样本 → 良率不可计算，显示「—」并给出说明 -->
            <el-tooltip
              v-if="row.okCount == null"
              content="当前数据源只上报缺陷图片，没有良品样本，良率无法计算"
              placement="top"
            >
              <span style="color: #94a3b8">—</span>
            </el-tooltip>
            <span
              v-else
              :style="{ color: row.totalCount > 0 && (row.okCount / row.totalCount) >= 0.95 ? '#67c23a' : '#f56c6c' }"
            >
              {{ row.totalCount > 0 ? ((row.okCount / row.totalCount) * 100).toFixed(1) : '0.0' }}%
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="incrementalTotal" label="增量总数" width="100" align="right" />
        <el-table-column label="增量OK" width="100" align="right">
          <template #default="{ row }">
            <span v-if="row.incrementalOk == null" style="color: #94a3b8">—</span>
            <span v-else>{{ row.incrementalOk }}</span>
          </template>
        </el-table-column>
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
import { computed, ref, onMounted } from 'vue'
import { ElMessage } from '@/utils/message.js'
import { getDefectImageHistory } from '../../api/camera'

const activeTab = ref('realtime')
const cameraName = ref('')
const filterLine = ref(null)
const timeRange = ref(null)

/** 历史查询提示：范围早于 31 天时后端会走月度归档表（2026-11-05） */
const historyQueryHint = computed(() => {
  const range = timeRange.value
  if (!range || range.length !== 2) return false
  const start = new Date(String(range[0]).replace(/-/g, '/')).getTime()
  if (Number.isNaN(start)) return false
  return start < Date.now() - 31 * 24 * 3600 * 1000
})
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)
const tableData = ref([])
const loading = ref(false)
const lineOptions = ref([])

// 时间格式化为后端要求的 "yyyy-MM-dd HH:mm:ss"
const formatTime = (d) => {
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} `
    + `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

const loadData = async () => {
  loading.value = true
  try {
    // ★ 2026-11-04：接入真实 API（原为模拟数据）
    //   数据源选择：现场设备实际走 `defect-images/upload` 通道，写的是
    //   `defect_image_file`（实测 189,391 行）；而 `product_detection_history`
    //   （前端原 defect.js 指向的表）**实测 0 行** —— 那是冻结契约的
    //   batch-upload 通道，现场未启用。若接那个接口，页面会从"有数据"变成"空表"。
    //   因此改用现场真实通道 GET /api/v1/defect-images/history。
    const params = { page: currentPage.value, size: pageSize.value }
    if (cameraName.value) params.cameraName = cameraName.value
    if (filterLine.value) params.lineId = filterLine.value
    if (timeRange.value && timeRange.value.length === 2) {
      params.startTime = formatTime(timeRange.value[0])
      params.endTime = formatTime(timeRange.value[1])
    }

    const res = await getDefectImageHistory(params)
    // axios 响应拦截器不做剥离：res.data = ResultVO → 真正的分页体在 res.data.data
    const payload = (res && res.data && res.data.data) || {}
    const records = payload.records || []

    // 该通道只上报**缺陷图**，没有良品样本 → OK数与良率不可计算。
    // 按项目口径：显示「—」并说明，绝不显示误导性的 0%。
    tableData.value = records.map(r => ({
      ...r,
      startTime: r.captureTime || r.uploadTime || r.createTime || '',
      productName: r.productName || '',
      cameraName: r.cameraName || r.cameraId || '',
      totalCount: r.defectCount != null ? r.defectCount : 0,
      okCount: null,
      ngCount: r.defectCount != null ? r.defectCount : 0,
      incrementalTotal: r.defectCount != null ? r.defectCount : 0,
      incrementalOk: null,
      incrementalNg: r.defectCount != null ? r.defectCount : 0
    }))
    total.value = payload.total || 0
  } catch (e) {
    console.error('加载检测记录失败:', e)
    ElMessage.error('数据加载失败')
    tableData.value = []
    total.value = 0
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

// 导出按钮已置灰（见模板 disabled）：后端没有 /detection/records/export 端点，
// 原实现点了只弹「后端接口完成后实现」—— 给了可用外观却不可用，现场会当成故障反复点。
// 实现导出后再回来加回 handleExport 与 window.open。


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

