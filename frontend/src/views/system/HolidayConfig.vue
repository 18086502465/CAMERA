<template>
  <div class="holiday-config">
    <!-- 页面标题栏 -->
    <div class="page-header">
      <h2 class="page-title">节假日配置</h2>
      <el-button type="primary" @click="handleCreate">
        <el-icon><Plus /></el-icon>
        添加配置
      </el-button>
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
      <el-table-column prop="holidayDate" label="日期" width="120" align="center" />
      <el-table-column prop="holidayName" label="名称" min-width="120" />
      <el-table-column prop="holidayType" label="类型" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="row.holidayType === '节假日' ? 'danger' : 'warning'" size="small">
            {{ row.holidayType }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="isWorkday" label="是否工作日" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="row.isWorkday ? 'success' : 'info'" size="small">
            {{ row.isWorkday ? '是' : '否' }}
          </el-tag>
        </template>
      </el-table-column>
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
        <el-form-item label="日期" prop="holidayDate">
          <el-date-picker
            v-model="form.holidayDate"
            type="date"
            placeholder="选择日期"
            value-format="YYYY-MM-DD"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="名称" prop="holidayName">
          <el-input v-model="form.holidayName" placeholder="请输入名称" />
        </el-form-item>
        <el-form-item label="类型" prop="holidayType">
          <el-select v-model="form.holidayType" placeholder="请选择类型" style="width: 100%">
            <el-option label="节假日" value="节假日" />
            <el-option label="调休" value="调休" />
            <el-option label="特殊工作日" value="特殊工作日" />
          </el-select>
        </el-form-item>
        <el-form-item label="是否工作日" prop="isWorkday">
          <el-switch v-model="form.isWorkday" />
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
import { getHolidayConfigList, createHolidayConfig, updateHolidayConfig, deleteHolidayConfig } from '@/api/holiday'
import { Plus } from '@element-plus/icons-vue'

const loading = ref(false)
const submitLoading = ref(false)
const tableData = ref([])
const dialogVisible = ref(false)
const dialogTitle = ref('添加节假日配置')
const isEdit = ref(false)
const currentId = ref(null)
const formRef = ref(null)

const pagination = reactive({
  page: 1,
  size: 10,
  total: 0
})

const form = reactive({
  holidayDate: '',
  holidayName: '',
  holidayType: '节假日',
  isWorkday: false,
  description: ''
})

const rules = {
  holidayDate: [{ required: true, message: '请选择日期', trigger: 'change' }],
  holidayName: [{ required: true, message: '请输入名称', trigger: 'blur' }],
  holidayType: [{ required: true, message: '请选择类型', trigger: 'change' }]
}

const loadData = async () => {
  loading.value = true
  try {
    const res = await getHolidayConfigList({
      page: pagination.page,
      size: pagination.size
    })
    tableData.value = res.data.data.records || res.data.data || []
    pagination.total = res.data.data.total || 0
  } catch (e) {
    ElMessage.error('加载数据失败')
  } finally {
    loading.value = false
  }
}

const handleCreate = () => {
  isEdit.value = false
  dialogTitle.value = '添加节假日配置'
  dialogVisible.value = true
}

const handleEdit = (row) => {
  isEdit.value = true
  dialogTitle.value = '编辑节假日配置'
  currentId.value = row.id
  Object.assign(form, {
    holidayDate: row.holidayDate,
    holidayName: row.holidayName,
    holidayType: row.holidayType,
    isWorkday: row.isWorkday,
    description: row.description
  })
  dialogVisible.value = true
}

const handleDelete = async (row) => {
  try {
    await ElMessageBox.confirm(`确定删除节假日配置"${row.holidayName}"吗？`, '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await deleteHolidayConfig(row.id)
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
      await updateHolidayConfig(currentId.value, { ...form })
      ElMessage.success('更新成功')
    } else {
      await createHolidayConfig({ ...form })
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
    holidayDate: '',
    holidayName: '',
    holidayType: '节假日',
    isWorkday: false,
    description: ''
  })
  formRef.value?.resetFields()
}

const formatTime = (time) => {
  if (!time) return '-'
  return new Date(time).toLocaleString('zh-CN')
}

onMounted(() => {
  loadData()
})
</script>

<style scoped>
.holiday-config {
  padding: 20px;
  background: var(--color-bg-card);
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

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>

