<template>
  <div class="page-container">
    <div class="page-header">
      <h2>⚠️ 异常告警</h2>
      <div class="header-actions">
        <el-select v-model="filterSeverity" placeholder="严重程度" clearable @change="loadData" style="width: 130px">
          <el-option label="严重" value="HIGH" />
          <el-option label="中等" value="MEDIUM" />
          <el-option label="提示" value="LOW" />
        </el-select>
        <el-select v-model="filterActive" placeholder="处理状态" clearable @change="loadData" style="width: 130px">
          <el-option label="未处理" value="true" />
          <el-option label="已处理" value="false" />
        </el-select>
        <el-button type="primary" @click="loadData">🔍 查询</el-button>
        <el-button @click="handleReset">重置</el-button>
      </div>
    </div>

    <el-card shadow="never" class="table-card">
      <el-table :data="tableData" v-loading="loading" stripe border style="width: 100%">
        <el-table-column prop="alertId" label="ID" width="60" />
        <el-table-column label="严重程度" width="100" align="center">
          <template #default="{ row }">
            <el-tag
              :type="row.severity === 'HIGH' ? 'danger' : row.severity === 'MEDIUM' ? 'warning' : 'info'"
              size="small"
              effect="dark"
            >
              {{ severityText(row.severity) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="factoryName" label="工厂" width="140" />
        <el-table-column prop="lineName" label="产线" width="140" />
        <el-table-column prop="stationName" label="工位" width="120" />
        <el-table-column prop="message" label="告警内容" min-width="200" show-overflow-tooltip />
        <el-table-column prop="detectTime" label="检测时间" width="160" sortable />
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.isActive ? 'danger' : 'success'" size="small">
              {{ row.isActive ? '未处理' : '已处理' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" align="center" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.isActive"
              link
              type="primary"
              size="small"
              @click="handleResolve(row)"
            >
              处理
            </el-button>
            <span v-else style="color: #67c23a; font-size: 12px;">已处理</span>
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

    <!-- 处理弹窗 -->
    <el-dialog v-model="resolveVisible" title="处理告警" width="520px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="告警内容">
          <el-input :model-value="resolveRow?.message" type="textarea" :rows="3" disabled />
        </el-form-item>
        <el-form-item label="处理措施">
          <el-input v-model="resolveAction" type="textarea" :rows="4" placeholder="请输入处理措施..." />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resolveVisible = false">取消</el-button>
        <el-button type="primary" :loading="resolveLoading" @click="submitResolve">确认处理</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getAlertList, resolveAlert } from '../../api/anomaly'
import { ElMessage, ElMessageBox } from '@/utils/message.js'

const filterSeverity = ref(null)
const filterActive = ref(null)
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)
const tableData = ref([])
const loading = ref(false)
const resolveVisible = ref(false)
const resolveRow = ref(null)
const resolveAction = ref('')
const resolveLoading = ref(false)

const severityText = (s) => {
  const m = { HIGH: '严重', MEDIUM: '中等', LOW: '提示' }
  return m[s] || s
}

const loadData = async () => {
  loading.value = true
  try {
    const params = { page: currentPage.value, size: pageSize.value }
    if (filterSeverity.value) params.severity = filterSeverity.value
    if (filterActive.value !== null) params.isActive = filterActive.value === 'true'
    // TODO: 替换为真实 API
    // const res = await getAlertList(params)
    // tableData.value = res.data.data.records || []
    // total.value = res.data.data.total || 0

    // 模拟数据
    await new Promise(r => setTimeout(r, 200))
    const mockRows = []
    for (let i = 0; i < pageSize.value; i++) {
      mockRows.push({
        alertId: (currentPage.value - 1) * pageSize.value + i + 1,
        factoryName: 'XX卫生用品',
        lineName: '尿不湿产线A',
        stationName: i % 2 === 0 ? '接头检测工位' : '尺寸检测工位',
        detectTime: '2026-05-28 14:' + String(i).padStart(2, '0') + ':00',
        severity: i % 3 === 0 ? 'HIGH' : i % 3 === 1 ? 'MEDIUM' : 'LOW',
        message: i % 3 === 0 ? 'A产线接头缺陷异常较多，请检查材料供应' : '尺寸偏差超过阈值，请校准检测参数',
        isActive: i % 2 === 0,
        suggestedAction: i % 2 === 0 ? '' : '已检查材料张力，重新校准完成'
      })
    }
    tableData.value = mockRows
    total.value = 56
  } catch (e) {
    ElMessage.error('加载告警列表失败')
  } finally {
    loading.value = false
  }
}

const handleResolve = (row) => {
  resolveRow.value = row
  resolveAction.value = ''
  resolveVisible.value = true
}

const submitResolve = async () => {
  if (!resolveAction.value.trim()) {
    ElMessage.warning('请输入处理措施')
    return
  }
  resolveLoading.value = true
  try {
    // TODO: 替换为真实 API
    // await resolveAlert(resolveRow.value.alertId, { suggestedAction: resolveAction.value })
    await new Promise(r => setTimeout(r, 300))
    ElMessage.success('告警已处理')
    resolveVisible.value = false
    loadData()
  } catch (e) {
    ElMessage.error('处理失败')
  } finally {
    resolveLoading.value = false
  }
}

const handleReset = () => {
  filterSeverity.value = null
  filterActive.value = null
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

onMounted(() => loadData())
</script>

<style scoped>
.page-container { padding: 0; }
.page-header {
  display: flex; justify-content: space-between; align-items: center;
  margin-bottom: 16px; flex-wrap: wrap; gap: 10px;
}
.page-header h2 { margin: 0; font-size: 18px; color: #1a1a2e; }
.header-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.table-card { margin-bottom: 16px; }
.pagination-wrap { margin-top: 14px; display: flex; justify-content: flex-end; }
</style>

