<template>
  <div class="workstation-list">
    <!-- 页面标题栏 -->
    <div class="page-header">
      <h2 class="page-title">工位管理</h2>
      <el-button type="primary" @click="handleCreate">
        <el-icon><Plus /></el-icon>
        新增工位
      </el-button>
    </div>

    <!-- 搜索栏 -->
    <div class="filter-bar">
      <el-input
        v-model="searchKeyword"
        placeholder="搜索工位名称..."
        clearable
        @input="handleSearch"
        style="width: 300px"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>

      <el-select
        v-model="filterLine"
        placeholder="所属产线"
        clearable
        @change="handleSearch"
        style="width: 200px"
      >
        <el-option
          v-for="item in lineOptions"
          :key="item.id"
          :label="item.lineName"
          :value="item.id"
        />
      </el-select>
    </div>

    <!-- 表格 -->
    <el-table
      :data="tableData"
      v-loading="loading"
      border
      stripe
      highlight-current-row
      empty-text="暂无数据"
      style="width: 100%"
    >
      <el-table-column prop="id" label="ID" width="80" align="center" />
      <el-table-column prop="stationName" label="工位名称" min-width="150" />
      <el-table-column prop="stationCode" label="工位编码" width="120" align="center" />
      <el-table-column prop="lineName" label="所属产线" width="150" align="center" />
      <el-table-column prop="cameraName" label="绑定相机" width="150" align="center" />
      <el-table-column prop="sortOrder" label="排序" width="80" align="center" />
      <el-table-column prop="description" label="说明" min-width="150" show-overflow-tooltip />
      <el-table-column label="创建时间" width="160" align="center">
        <template #default="{ row }">
          {{ formatTime(row.createTime) }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="160" align="center" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link size="small" @click="handleEdit(row)">
            编辑
          </el-button>
          <el-button type="danger" link size="small" @click="handleDelete(row)">
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页 -->
    <div class="pagination-wrapper">
      <el-pagination
        v-model:current-page="pagination.page"
        v-model:page-size="pagination.size"
        :page-sizes="[10, 20, 50, 100]"
        :total="pagination.total"
        layout="total, sizes, prev, pager, next, jumper"
        @size-change="handleSizeChange"
        @current-change="handlePageChange"
      />
    </div>

    <!-- 新增/编辑对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogTitle"
      width="500px"
      :close-on-click-modal="false"
      @close="resetForm"
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="100px"
      >
        <el-form-item label="工位名称" prop="stationName">
          <el-input v-model="form.stationName" placeholder="请输入工位名称" />
        </el-form-item>
        <el-form-item label="工位编码" prop="stationCode">
          <el-input v-model="form.stationCode" placeholder="请输入工位编码" />
        </el-form-item>
        <el-form-item label="所属产线" prop="lineId">
          <el-select v-model="form.lineId" placeholder="请选择产线" style="width: 100%">
            <el-option
              v-for="item in lineOptions"
              :key="item.id"
              :label="item.lineName"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="绑定相机" prop="cameraName">
          <el-input v-model="form.cameraName" placeholder="请输入相机名称" />
        </el-form-item>
        <el-form-item label="排序" prop="sortOrder">
          <el-input-number v-model="form.sortOrder" :min="0" />
        </el-form-item>
        <el-form-item label="说明" prop="description">
          <el-input v-model="form.description" placeholder="请输入说明" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitLoading" @click="handleSubmit">
          确定
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from '@/utils/message.js'
import { getWorkstationList, createWorkstation, updateWorkstation, deleteWorkstation } from '@/api/workstation'
import { getLineList } from '@/api/line'
import { Search, Plus } from '@element-plus/icons-vue'

const loading = ref(false)
const submitLoading = ref(false)
const tableData = ref([])
const searchKeyword = ref('')
const filterLine = ref('')
const dialogVisible = ref(false)
const dialogTitle = ref('新增工位')
const isEdit = ref(false)
const currentId = ref(null)
const formRef = ref(null)

const lineOptions = ref([])

const pagination = reactive({
  page: 1,
  size: 10,
  total: 0
})

const form = reactive({
  stationName: '',
  stationCode: '',
  lineId: null,
  cameraName: '',
  sortOrder: 0,
  description: ''
})

const rules = {
  stationName: [{ required: true, message: '请输入工位名称', trigger: 'blur' }],
  stationCode: [{ required: true, message: '请输入工位编码', trigger: 'blur' }],
  lineId: [{ required: true, message: '请选择产线', trigger: 'change' }]
}

const loadLineOptions = async () => {
  try {
    const res = await getLineList()
    lineOptions.value = res.data.data || res.data || []
  } catch (e) {
    ElMessage.error('加载产线选项失败')
  }
}

const loadData = async () => {
  loading.value = true
  try {
    const res = await getWorkstationList({
      page: pagination.page,
      size: pagination.size,
      keyword: searchKeyword.value,
      lineId: filterLine.value
    })
    tableData.value = res.data.data.records || res.data.data || []
    pagination.total = res.data.data.total || 0
  } catch (e) {
    ElMessage.error('加载数据失败')
  } finally {
    loading.value = false
  }
}

const handleSearch = () => {
  pagination.page = 1
  loadData()
}

const handleCreate = () => {
  isEdit.value = false
  dialogTitle.value = '新增工位'
  dialogVisible.value = true
}

const handleEdit = (row) => {
  isEdit.value = true
  dialogTitle.value = '编辑工位'
  currentId.value = row.id
  Object.assign(form, {
    stationName: row.stationName,
    stationCode: row.stationCode,
    lineId: row.lineId,
    cameraName: row.cameraName,
    sortOrder: row.sortOrder,
    description: row.description
  })
  dialogVisible.value = true
}

const handleDelete = async (row) => {
  try {
    await ElMessageBox.confirm(`确定删除工位"${row.stationName}"吗？`, '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await deleteWorkstation(row.id)
    ElMessage.success('删除成功')
    loadData()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('删除失败')
    }
  }
}

const handleSubmit = async () => {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  
  submitLoading.value = true
  try {
    if (isEdit.value) {
      await updateWorkstation(currentId.value, { ...form })
      ElMessage.success('更新成功')
    } else {
      await createWorkstation({ ...form })
      ElMessage.success('创建成功')
    }
    dialogVisible.value = false
    loadData()
  } catch (e) {
    ElMessage.error('操作失败')
  } finally {
    submitLoading.value = false
  }
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

const resetForm = () => {
  Object.assign(form, {
    stationName: '',
    stationCode: '',
    lineId: null,
    cameraName: '',
    sortOrder: 0,
    description: ''
  })
  formRef.value?.resetFields()
}

const formatTime = (time) => {
  if (!time) return '-'
  return new Date(time).toLocaleString('zh-CN')
}

onMounted(() => {
  loadLineOptions()
  loadData()
})
</script>

<style scoped>
.workstation-list {
  padding: 20px;
  background: #fff;
  border-radius: 8px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.page-title {
  font-size: 20px;
  font-weight: 600;
  color: #1f1f1f;
  margin: 0;
}

.filter-bar {
  margin-bottom: 16px;
  display: flex;
  gap: 12px;
  align-items: center;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>

