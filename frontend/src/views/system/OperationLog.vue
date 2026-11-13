<template>
  <div class="operation-log-center">
    <div class="log-hero">
      <div>
        <p class="eyebrow">BUSINESS AUDIT LOG</p>
        <h3>完整业务操作日志中心</h3>
        <p>记录用户在配置、看板、接口、导入导出等模块中的关键操作，支持查询、审计、导出、删除和清空。</p>
      </div>
      <div class="log-actions">
        <el-button @click="loadData" :loading="loading">刷新</el-button>
        <el-button type="primary" plain @click="handleExport" :disabled="tableData.length === 0">导出当前条件</el-button>
        <el-button type="danger" @click="handleClear" :disabled="pagination.total === 0">清空日志</el-button>
      </div>
    </div>

    <div class="log-summary">
      <div class="summary-card"><span>匹配日志</span><strong>{{ summary.total ?? pagination.total }}</strong></div>
      <div class="summary-card ok"><span>成功</span><strong>{{ summary.success ?? '-' }}</strong></div>
      <div class="summary-card danger"><span>失败</span><strong>{{ summary.failure ?? '-' }}</strong></div>
      <div class="summary-card info"><span>今日操作</span><strong>{{ summary.today ?? '-' }}</strong></div>
    </div>

    <div class="filter-panel">
      <el-input v-model="filters.keyword" placeholder="搜索操作人 / 内容 / URI / IP / 错误信息" clearable @keyup.enter="handleSearch" style="min-width: 260px">
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-select v-model="filters.operation" placeholder="操作类型" clearable style="width: 140px" @change="handleSearch">
        <el-option label="查询" value="QUERY" />
        <el-option label="新增" value="CREATE" />
        <el-option label="修改" value="UPDATE" />
        <el-option label="删除" value="DELETE" />
        <el-option label="登录" value="LOGIN" />
        <el-option label="导入" value="IMPORT" />
        <el-option label="导出" value="EXPORT" />
      </el-select>
      <el-input v-model="filters.module" placeholder="模块名称" clearable style="width: 150px" @keyup.enter="handleSearch" />
      <el-input v-model="filters.userName" placeholder="操作人" clearable style="width: 130px" @keyup.enter="handleSearch" />
      <el-select v-model="filters.requestMethod" placeholder="请求方法" clearable style="width: 120px" @change="handleSearch">
        <el-option label="GET" value="GET" />
        <el-option label="POST" value="POST" />
        <el-option label="PUT" value="PUT" />
        <el-option label="DELETE" value="DELETE" />
      </el-select>
      <el-input v-model="filters.requestUri" placeholder="请求地址包含" clearable style="width: 170px" @keyup.enter="handleSearch" />
      <el-input v-model="filters.ipAddress" placeholder="IP 地址" clearable style="width: 130px" @keyup.enter="handleSearch" />
      <el-input-number v-model="filters.minExecutionTime" :min="0" :controls="false" placeholder="最小耗时ms" style="width: 130px" @keyup.enter="handleSearch" />
      <el-input-number v-model="filters.maxExecutionTime" :min="0" :controls="false" placeholder="最大耗时ms" style="width: 130px" @keyup.enter="handleSearch" />
      <el-select v-model="filters.result" placeholder="执行结果" clearable style="width: 130px" @change="handleSearch">
        <el-option label="成功" :value="1" />
        <el-option label="失败" :value="0" />
      </el-select>
      <el-date-picker
        v-model="dateRange"
        type="daterange"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        value-format="YYYY-MM-DD"
        style="width: 260px"
        @change="handleSearch"
      />
      <el-button type="primary" @click="handleSearch">查询</el-button>
      <el-button @click="resetFilters">重置</el-button>
    </div>

    <el-table
      :data="tableData"
      v-loading="loading"
      border
      stripe
      highlight-current-row
      empty-text="暂无操作日志"
      class="log-table"
      :header-cell-style="{ background: '#f8fafc', color: '#475569', fontWeight: 700 }"
    >
      <el-table-column type="index" label="#" width="56" :index="tableIndex" />
      <el-table-column prop="operationTime" label="操作时间" width="170" />
      <el-table-column prop="userName" label="操作人" width="120" />
      <el-table-column prop="operationType" label="类型" width="100" align="center">
        <template #default="{ row }"><el-tag :type="operationTag(row.operationType)" size="small">{{ operationText(row.operationType) }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="moduleName" label="业务模块" width="150" show-overflow-tooltip />
      <el-table-column prop="operationDesc" label="操作内容" min-width="220" show-overflow-tooltip />
      <el-table-column prop="requestMethod" label="方法" width="90" align="center"><template #default="{ row }"><code>{{ row.requestMethod || '-' }}</code></template></el-table-column>
      <el-table-column prop="requestUri" label="请求地址" min-width="200" show-overflow-tooltip />
      <el-table-column prop="ipAddress" label="IP" width="130" />
      <el-table-column prop="executionTime" label="耗时" width="90" align="center"><template #default="{ row }">{{ row.executionTime ?? '-' }}ms</template></el-table-column>
      <el-table-column label="结果" width="90" align="center">
        <template #default="{ row }"><span class="result-pill" :class="Number(row.operationResult) === 1 ? 'ok' : 'fail'">{{ Number(row.operationResult) === 1 ? '成功' : '失败' }}</span></template>
      </el-table-column>
      <el-table-column label="操作" width="150" align="center" fixed="right">
        <template #default="{ row }">
          <div class="action-group">
            <el-button type="primary" link size="small" @click="openDetail(row)">详情</el-button>
            <el-button type="danger" link size="small" @click="handleDelete(row)">删除</el-button>
          </div>
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination-wrapper">
      <el-pagination
        v-model:current-page="pagination.page"
        v-model:page-size="pagination.size"
        :page-sizes="[10, 20, 50, 100]"
        :total="pagination.total"
        layout="total, sizes, prev, pager, next, jumper"
        background
        @size-change="handleSizeChange"
        @current-change="handlePageChange"
      />
    </div>

    <el-drawer v-model="detailVisible" title="操作日志详情" size="560px">
      <div v-if="currentLog" class="detail-box">
        <div class="detail-row"><span>日志ID</span><b>{{ currentLog.logId }}</b></div>
        <div class="detail-row"><span>操作人</span><b>{{ currentLog.userName || '-' }}</b></div>
        <div class="detail-row"><span>操作类型</span><b>{{ operationText(currentLog.operationType) }}</b></div>
        <div class="detail-row"><span>业务模块</span><b>{{ currentLog.moduleName || '-' }}</b></div>
        <div class="detail-row"><span>操作时间</span><b>{{ currentLog.operationTime || '-' }}</b></div>
        <div class="detail-row"><span>请求方法</span><b>{{ currentLog.requestMethod || '-' }}</b></div>
        <div class="detail-row"><span>请求地址</span><b>{{ currentLog.requestUri || '-' }}</b></div>
        <div class="detail-row"><span>IP 地址</span><b>{{ currentLog.ipAddress || '-' }}</b></div>
        <div class="detail-row"><span>执行耗时</span><b>{{ currentLog.executionTime ?? '-' }}ms</b></div>
        <div class="detail-row"><span>执行结果</span><b>{{ Number(currentLog.operationResult) === 1 ? '成功' : '失败' }}</b></div>
        <div class="detail-block"><span>操作内容</span><pre>{{ currentLog.operationDesc || '-' }}</pre></div>
        <div class="detail-block"><span>请求参数</span><pre>{{ currentLog.requestParams || '-' }}</pre></div>
        <div class="detail-block" v-if="currentLog.errorMessage"><span>错误信息</span><pre class="error">{{ currentLog.errorMessage }}</pre></div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from '@/utils/message.js'
import { getOperationLogList, getOperationLogSummary, deleteOperationLog, clearOperationLogs, exportOperationLogs } from '@/api/operation-log'
import { Search } from '@element-plus/icons-vue'

const loading = ref(false)
const tableData = ref([])
const dateRange = ref([])
const summary = ref({})
const detailVisible = ref(false)
const currentLog = ref(null)

const filters = reactive({
  keyword: '', operation: '', module: '', result: null,
  userName: '', requestMethod: '', requestUri: '', ipAddress: '',
  minExecutionTime: null, maxExecutionTime: null
})
const pagination = reactive({ page: 1, size: 10, total: 0 })

const tableIndex = (index) => (pagination.page - 1) * pagination.size + index + 1

const buildParams = () => {
  const params = {
    page: pagination.page,
    size: pagination.size,
    keyword: filters.keyword || undefined,
    operation: filters.operation || undefined,
    module: filters.module || undefined,
    result: filters.result === null || filters.result === '' ? undefined : filters.result,
    userName: filters.userName || undefined,
    requestMethod: filters.requestMethod || undefined,
    requestUri: filters.requestUri || undefined,
    ipAddress: filters.ipAddress || undefined,
    minExecutionTime: filters.minExecutionTime === null || filters.minExecutionTime === '' ? undefined : filters.minExecutionTime,
    maxExecutionTime: filters.maxExecutionTime === null || filters.maxExecutionTime === '' ? undefined : filters.maxExecutionTime
  }
  if (dateRange.value && dateRange.value.length === 2) {
    params.startDate = dateRange.value[0]
    params.endDate = dateRange.value[1]
  }
  return params
}

const loadSummary = async () => {
  try {
    const params = buildParams()
    delete params.page
    delete params.size
    const res = await getOperationLogSummary(params)
    summary.value = res.data?.data || res.data || {}
  } catch (e) {
    summary.value = {}
  }
}

const loadData = async () => {
  loading.value = true
  try {
    const res = await getOperationLogList(buildParams())
    const pageData = res.data?.data || res.data || {}
    const records = pageData.records || pageData || []
    tableData.value = Array.isArray(records) ? records.map(normalizeLog) : []
    pagination.total = pageData.total ?? tableData.value.length
    await loadSummary()
  } catch (e) {
    tableData.value = []
    pagination.total = 0
    ElMessage.error('加载操作日志失败：' + (e.response?.data?.message || e.message || e))
  } finally {
    loading.value = false
  }
}

const normalizeLog = (log = {}) => ({
  ...log,
  logId: log.logId || log.id,
  userName: log.userName || log.username || '未知',
  operationType: log.operationType || log.operation || '-',
  moduleName: log.moduleName || log.module || '-',
  operationDesc: log.operationDesc || log.description || log.requestUri || '-',
  operationTime: formatTime(log.operationTime || log.createTime),
  requestMethod: log.requestMethod || '-',
  requestUri: log.requestUri || '-',
  ipAddress: log.ipAddress || '-',
  executionTime: log.executionTime,
  operationResult: log.operationResult ?? log.result ?? 1,
  requestParams: log.requestParams || '',
  errorMessage: log.errorMessage || ''
})

const handleSearch = () => {
  pagination.page = 1
  loadData()
}

const resetFilters = () => {
  filters.keyword = ''
  filters.operation = ''
  filters.module = ''
  filters.result = null
  filters.userName = ''
  filters.requestMethod = ''
  filters.requestUri = ''
  filters.ipAddress = ''
  filters.minExecutionTime = null
  filters.maxExecutionTime = null
  dateRange.value = []
  handleSearch()
}

const handleDelete = async (row) => {
  try {
    await ElMessageBox.confirm(`确定删除日志 #${row.logId}？删除后不可恢复。`, '删除日志确认', {
      confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning'
    })
    await deleteOperationLog(row.logId)
    ElMessage.success('删除成功')
    loadData()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('删除失败：' + (e.response?.data?.message || e.message || e))
  }
}

const handleClear = async () => {
  try {
    await ElMessageBox.confirm('确定清空所有操作日志吗？此操作不可恢复，建议先导出备份。', '清空日志高风险确认', {
      confirmButtonText: '确认清空', cancelButtonText: '取消', type: 'warning'
    })
    await clearOperationLogs()
    ElMessage.success('清空成功')
    pagination.page = 1
    loadData()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('清空失败：' + (e.response?.data?.message || e.message || e))
  }
}

const handleExport = async () => {
  try {
    const params = buildParams()
    delete params.page
    delete params.size
    const res = await exportOperationLogs(params)
    const blob = new Blob([res.data], { type: 'text/csv;charset=utf-8' })
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `操作日志导出-${new Date().toISOString().slice(0, 10)}.csv`
    document.body.appendChild(link)
    link.click()
    link.remove()
    window.URL.revokeObjectURL(url)
    ElMessage.success('导出成功')
  } catch (e) {
    ElMessage.error('导出失败：' + (e.response?.data?.message || e.message || e))
  }
}

const openDetail = (row) => {
  currentLog.value = row
  detailVisible.value = true
}

const handleSizeChange = (val) => {
  pagination.size = val
  pagination.page = 1
  loadData()
}

const handlePageChange = (val) => {
  pagination.page = val
  loadData()
}

const operationText = (operation) => {
  const map = { CREATE: '新增', UPDATE: '修改', DELETE: '删除', LOGIN: '登录', EXPORT: '导出', IMPORT: '导入', QUERY: '查询' }
  return map[operation] || operation || '-'
}

const operationTag = (operation) => {
  const map = { CREATE: 'success', UPDATE: 'primary', DELETE: 'danger', LOGIN: 'info', EXPORT: 'warning', IMPORT: 'warning', QUERY: 'info' }
  return map[operation] || 'info'
}

const formatTime = (time) => {
  if (!time) return '-'
  if (Array.isArray(time)) {
    return `${time[0]}-${String(time[1]).padStart(2, '0')}-${String(time[2]).padStart(2, '0')} ${String(time[3] || 0).padStart(2, '0')}:${String(time[4] || 0).padStart(2, '0')}:${String(time[5] || 0).padStart(2, '0')}`
  }
  if (typeof time === 'string') return time.replace('T', ' ')
  return new Date(time).toLocaleString('zh-CN')
}

onMounted(loadData)
</script>

<style scoped>
.operation-log-center { display: flex; flex-direction: column; gap: 16px; }
.log-hero { display: flex; align-items: center; justify-content: space-between; gap: 18px; padding: 18px 20px; border: 1px solid #dbeafe; border-radius: 18px; background: linear-gradient(135deg, var(--color-primary-bg) 0%, #fff 70%); }
.log-hero .eyebrow { margin: 0 0 6px; font-size: 12px; letter-spacing: .14em; color: var(--color-primary); font-weight: 900; }
.log-hero h3 { margin: 0 0 8px; font-size: 22px; color: var(--color-text); }
.log-hero p { margin: 0; color: var(--color-text-secondary); font-size: 13px; line-height: 1.7; }
.log-actions { display: flex; gap: 8px; flex-wrap: wrap; justify-content: flex-end; }
.log-summary { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.summary-card { padding: 14px; border-radius: 16px; border: 1px solid var(--color-border); background: var(--color-bg-card); box-shadow: 0 1px 4px rgba(15,23,42,.04); }
.summary-card span { display: block; color: var(--color-text-secondary); font-size: 12px; margin-bottom: 6px; }
.summary-card strong { color: var(--color-primary-active); font-size: 24px; }
.summary-card.ok strong { color: var(--color-success); }
.summary-card.danger strong { color: var(--color-error); }
.summary-card.info strong { color: #0891b2; }
.filter-panel { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; padding: 14px; border: 1px solid var(--color-border); border-radius: 16px; background: var(--color-bg-card); }
.log-table { border-radius: 14px; overflow: hidden; }
.log-table code { background: var(--color-bg-subtle); padding: 2px 7px; border-radius: 6px; color: var(--color-primary); }
.result-pill { display: inline-flex; align-items: center; justify-content: center; min-width: 46px; height: 24px; padding: 0 8px; border-radius: 999px; font-size: 12px; font-weight: 800; }
.result-pill.ok { background: var(--color-success-bg); color: #15803d; }
.result-pill.fail { background: var(--color-error-bg); color: #b91c1c; }
.action-group { display: inline-flex; align-items: center; gap: 8px; }
.pagination-wrapper { display: flex; justify-content: flex-end; padding-top: 4px; }
.detail-box { display: flex; flex-direction: column; gap: 12px; }
.detail-row { display: grid; grid-template-columns: 96px 1fr; gap: 10px; align-items: center; padding: 10px 12px; border-radius: 12px; background: var(--color-bg-page); border: 1px solid #eef2f7; }
.detail-row span, .detail-block span { color: var(--color-text-secondary); font-size: 12px; }
.detail-row b { color: var(--color-text); font-size: 13px; word-break: break-all; }
.detail-block { padding: 12px; border-radius: 12px; background: var(--color-bg-page); border: 1px solid #eef2f7; }
.detail-block span { display: block; margin-bottom: 8px; }
.detail-block pre { margin: 0; white-space: pre-wrap; word-break: break-word; font-size: 12px; line-height: 1.6; color: var(--color-text); font-family: Consolas, Monaco, monospace; }
.detail-block pre.error { color: #b91c1c; }
@media (max-width: 980px) {
  .log-hero { flex-direction: column; align-items: flex-start; }
  .log-summary { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 640px) {
  .log-summary { grid-template-columns: 1fr; }
  .pagination-wrapper { justify-content: flex-start; }
}
</style>
