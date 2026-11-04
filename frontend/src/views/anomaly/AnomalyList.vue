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
    // ★ 2026-11-04：接入真实 API（原为模拟数据，现场会误判为真实告警）
    //   后端 /api/alerts/active 返回未解决告警（最多 20 条），字段：
    //   id / type / level / title / message / lineId / workstationId / createTime
    //   这里映射为表格列所需的字段，并按严重度做本地过滤。
    const res = await getAlertList()
    // axios 响应拦截器不做剥离：res.data = ResultVO{code,message,data}
    // → 真正的告警体在 res.data.data
    const payload = (res && res.data && res.data.data) || {}
    const rows = payload.alerts || []

    // 严重度映射：后端 level 用 HIGH/MEDIUM/LOW/CRITICAL/WARNING/INFO
    const levelMap = {
      CRITICAL: 'HIGH', HIGH: 'HIGH', ERROR: 'HIGH',
      MEDIUM: 'MEDIUM', WARNING: 'MEDIUM',
      LOW: 'LOW', INFO: 'LOW'
    }

    let mapped = rows.map(a => ({
      alertId: a.id,
      severity: levelMap[String(a.level || '').toUpperCase()] || 'LOW',
      // 后端未随告警返回工厂/产线/工位名称，只给 id，这里留空由后端补齐
      factoryName: a.factoryName || '',
      lineName: a.lineName || '',
      stationName: a.stationName || '',
      message: a.message || a.title || '',
      detectTime: a.createTime || '',
      isActive: true,
      suggestedAction: a.suggestedAction || ''
    }))

    // 前端筛选（后端 /active 只返回未处理的）
    if (filterSeverity.value) {
      mapped = mapped.filter(r => r.severity === filterSeverity.value)
    }
    if (filterActive.value !== null) {
      const wantActive = filterActive.value === 'true'
      mapped = mapped.filter(r => r.isActive === wantActive)
    }

    // 后端无分页接口，这里本地分页
    total.value = mapped.length
    const start = (currentPage.value - 1) * pageSize.value
    tableData.value = mapped.slice(start, start + pageSize.value)
  } catch (e) {
    ElMessage.error('加载告警列表失败')
    tableData.value = []
    total.value = 0
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
    // ★ 2026-11-04：接入真实 API（原为模拟提交，点了没反应）
    //   后端 /api/alerts/{id}/resolve 为 POST，标记 is_resolved=1。
    //   注意：后端当前不接收处理措施文本，措施仅保存在前端会话中；
    //   待后端补充 suggestedAction 字段后即可持久化。
    await resolveAlert(resolveRow.value.alertId, { suggestedAction: resolveAction.value })
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

