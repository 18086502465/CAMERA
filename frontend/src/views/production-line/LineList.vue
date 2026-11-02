<template>
  <div class="page-container">
    <div class="page-header">
      <h2>📏 产线管理</h2>
      <el-button type="primary" @click="openForm(null)">➕ 新建产线</el-button>
    </div>

    <!-- 筛选栏 -->
    <el-card shadow="never" class="filter-card">
      <el-space wrap>
        <el-select
          v-model="filterFactory"
          placeholder="选择工厂"
          clearable
          style="width: 180px"
          @change="loadData"
        >
          <el-option
            v-for="f in factoryOptions"
            :key="f.factoryId"
            :label="f.factoryName"
            :value="f.factoryId"
          />
        </el-select>
        <el-select v-model="filterStatus" placeholder="状态筛选" clearable @change="loadData" style="width: 130px">
          <el-option label="运行中" value="ACTIVE" />
          <el-option label="待机" value="IDLE" />
          <el-option label="维护中" value="MAINTENANCE" />
        </el-select>
        <el-input
          v-model="keyword"
          placeholder="搜索产线名称/编码..."
          clearable
          style="width: 200px"
          @clear="loadData"
          @keyup.enter="loadData"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button type="primary" @click="loadData">🔍 搜索</el-button>
        <el-button @click="handleReset">重置</el-button>
      </el-space>
    </el-card>

    <!-- 表格 -->
    <el-card shadow="never" class="table-card">
      <el-table :data="tableData" v-loading="loading" stripe border style="width: 100%">
        <el-table-column prop="lineId" label="ID" width="60" />
        <el-table-column prop="factoryName" label="所属工厂" width="160" />
        <el-table-column prop="lineCode" label="产线编码" width="130" />
        <el-table-column prop="lineName" label="产线名称" min-width="140" />
        <el-table-column prop="lineType" label="类型" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small">{{ row.lineType === 'DIAPER' ? '尿不湿' : '卫生巾' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ACTIVE' ? 'success' : row.status === 'MAINTENANCE' ? 'danger' : 'warning'" size="small">
              {{ statusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" min-width="140" show-overflow-tooltip />
        <el-table-column label="操作" width="160" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openForm(row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="handleDelete(row)">删除</el-button>
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

    <!-- 新建/编辑弹窗 -->
    <el-dialog v-model="formVisible" :title="formMode === 'create' ? '新建产线' : '编辑产线'" width="540px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="所属工厂" prop="factoryId">
          <el-select v-model="form.factoryId" placeholder="请选择工厂" style="width: 100%">
            <el-option
              v-for="f in factoryOptions"
              :key="f.factoryId"
              :label="f.factoryName"
              :value="f.factoryId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="产线编码" prop="lineCode">
          <el-input v-model="form.lineCode" placeholder="如：LINE001" />
        </el-form-item>
        <el-form-item label="产线名称" prop="lineName">
          <el-input v-model="form.lineName" placeholder="如：尿不湿产线A" />
        </el-form-item>
        <el-form-item label="产线类型" prop="lineType">
          <el-select v-model="form.lineType" placeholder="请选择类型" style="width: 100%">
            <el-option label="尿不湿" value="DIAPER" />
            <el-option label="卫生巾" value="SANITARY" />
          </el-select>
        </el-form-item>
        <el-form-item label="状　态" prop="status" v-if="formMode === 'edit'">
          <el-select v-model="form.status" style="width: 100%">
            <el-option label="运行中" value="ACTIVE" />
            <el-option label="待机" value="IDLE" />
            <el-option label="维护中" value="MAINTENANCE" />
          </el-select>
        </el-form-item>
        <el-form-item label="描　述" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="3" placeholder="备注说明" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitLoading" @click="handleSubmit">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { Search } from '@element-plus/icons-vue'
import { getLineList, createLine, updateLine, deleteLine } from '../../api/line'
import { getFactoryList } from '../../api/factory'
import { ElMessage, ElMessageBox } from '@/utils/message.js'

const keyword = ref('')
const filterFactory = ref(null)
const filterStatus = ref(null)
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)
const tableData = ref([])
const loading = ref(false)
const formVisible = ref(false)
const formMode = ref('create')
const submitLoading = ref(false)
const formRef = ref(null)
const factoryOptions = ref([])

const form = reactive({
  lineId: null,
  factoryId: null,
  lineCode: '',
  lineName: '',
  lineType: 'DIAPER',
  status: 'ACTIVE',
  description: ''
})

const rules = {
  factoryId: [{ required: true, message: '请选择工厂', trigger: 'change' }],
  lineCode: [{ required: true, message: '请输入产线编码', trigger: 'blur' }],
  lineName: [{ required: true, message: '请输入产线名称', trigger: 'blur' }]
}

const statusText = (s) => {
  const m = { ACTIVE: '运行中', IDLE: '待机', MAINTENANCE: '维护中' }
  return m[s] || s
}

const loadData = async () => {
  loading.value = true
  try {
    const params = { page: currentPage.value, size: pageSize.value }
    if (filterFactory.value) params.factoryId = filterFactory.value
    if (filterStatus.value) params.status = filterStatus.value
    if (keyword.value) params.keyword = keyword.value
    const res = await getLineList(params)
    tableData.value = res.data.data.records || []
    total.value = res.data.data.total || 0
  } catch (e) {
    ElMessage.error('加载产线列表失败')
    console.error(e)
  } finally {
    loading.value = false
  }
}

const loadFactories = async () => {
  try {
    const res = await getFactoryList({ size: 100 })
    factoryOptions.value = res.data.data.records || []
  } catch (e) { console.error(e) }
}

const openForm = (row) => {
  if (row) {
    formMode.value = 'edit'
    Object.assign(form, {
      lineId: row.lineId,
      factoryId: row.factoryId,
      lineCode: row.lineCode,
      lineName: row.lineName,
      lineType: row.lineType || 'DIAPER',
      status: row.status || 'ACTIVE',
      description: row.description || ''
    })
  } else {
    formMode.value = 'create'
    Object.assign(form, {
      lineId: null, factoryId: null, lineCode: '', lineName: '',
      lineType: 'DIAPER', status: 'ACTIVE', description: ''
    })
  }
  formVisible.value = true
}

const handleSubmit = async () => {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  submitLoading.value = true
  try {
    if (formMode.value === 'create') {
      await createLine({ ...form })
      ElMessage.success('新建成功')
    } else {
      await updateLine(form.lineId, { ...form })
      ElMessage.success('修改成功')
    }
    formVisible.value = false
    loadData()
  } catch (e) {
    const msg = e.response?.data?.message || '操作失败'
    ElMessage.error(msg)
  } finally {
    submitLoading.value = false
  }
}

const handleDelete = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确定删除产线「${row.lineName}」吗？`,
      '确认删除',
      { confirmButtonText: '确定删除', cancelButtonText: '取消', type: 'warning' }
    )
    await deleteLine(row.lineId)
    ElMessage.success('删除成功')
    loadData()
  } catch (e) { /* 用户取消 */ }
}

const handleReset = () => {
  keyword.value = ''
  filterFactory.value = null
  filterStatus.value = null
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

onMounted(() => {
  loadFactories()
  loadData()
})
</script>

<style scoped>
.page-container { padding: 0; }
.page-header {
  display: flex; justify-content: space-between; align-items: center;
  margin-bottom: 16px;
}
.page-header h2 { margin: 0; font-size: 18px; color: #1a1a2e; }
.filter-card { margin-bottom: 16px; }
.table-card { margin-bottom: 16px; }
.pagination-wrap { margin-top: 14px; display: flex; justify-content: flex-end; }
</style>

