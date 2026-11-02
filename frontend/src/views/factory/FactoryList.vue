<template>
  <div class="page-container">
    <div class="page-header">
      <h2>🏭 工厂管理</h2>
      <el-button type="primary" @click="openForm(null)">➕ 新建工厂</el-button>
    </div>

    <!-- 搜索栏 -->
    <el-card shadow="never" class="filter-card">
      <el-space wrap>
        <el-input
          v-model="keyword"
          placeholder="搜索工厂编码/名称..."
          clearable
          style="width: 220px"
          @clear="loadData"
          @keyup.enter="loadData"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button type="primary" @click="loadData">🔍 搜索</el-button>
        <el-button @click="keyword='';loadData()">重置</el-button>
      </el-space>
    </el-card>

    <!-- 表格 -->
    <el-card shadow="never" class="table-card">
      <el-table :data="tableData" v-loading="loading" stripe border style="width: 100%">
        <el-table-column prop="factoryId" label="ID" width="70" />
        <el-table-column prop="factoryCode" label="工厂编码" width="130" />
        <el-table-column prop="factoryName" label="工厂名称" min-width="160" />
        <el-table-column prop="location" label="地址" min-width="160" show-overflow-tooltip />
        <el-table-column prop="description" label="描述" min-width="140" show-overflow-tooltip />
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.isActive ? 'success' : 'info'" size="small">
              {{ row.isActive ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="170" />
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
    <el-dialog v-model="formVisible" :title="formMode === 'create' ? '新建工厂' : '编辑工厂'" width="520px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="工厂编码" prop="factoryCode">
          <el-input v-model="form.factoryCode" placeholder="如：FACT001" />
        </el-form-item>
        <el-form-item label="工厂名称" prop="factoryName">
          <el-input v-model="form.factoryName" placeholder="如：XX卫生用品有限公司" />
        </el-form-item>
        <el-form-item label="地　址" prop="location">
          <el-input v-model="form.location" placeholder="如：福建省泉州市" />
        </el-form-item>
        <el-form-item label="描　述" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="3" placeholder="备注说明" />
        </el-form-item>
        <el-form-item v-if="formMode === 'edit'" label="状　态">
          <el-switch v-model="form.isActive" active-text="启用" inactive-text="停用" :active-value="1" :inactive-value="0" />
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
import { getFactoryList, createFactory, updateFactory, deleteFactory } from '../../api/factory'
import { ElMessage, ElMessageBox } from '@/utils/message.js'

const keyword = ref('')
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)
const tableData = ref([])
const loading = ref(false)
const formVisible = ref(false)
const formMode = ref('create') // 'create' | 'edit'
const submitLoading = ref(false)
const formRef = ref(null)

const form = reactive({
  factoryId: null,
  factoryCode: '',
  factoryName: '',
  location: '',
  description: '',
  isActive: 1
})

const rules = {
  factoryCode: [{ required: true, message: '请输入工厂编码', trigger: 'blur' }],
  factoryName: [{ required: true, message: '请输入工厂名称', trigger: 'blur' }]
}

const loadData = async () => {
  loading.value = true
  try {
    const params = { page: currentPage.value, size: pageSize.value }
    if (keyword.value) params.keyword = keyword.value
    const res = await getFactoryList(params)
    tableData.value = res.data.data.records || []
    total.value = res.data.data.total || 0
  } catch (e) {
    ElMessage.error('加载工厂列表失败')
    console.error(e)
  } finally {
    loading.value = false
  }
}

const openForm = (row) => {
  if (row) {
    formMode.value = 'edit'
    form.factoryId = row.factoryId
    form.factoryCode = row.factoryCode
    form.factoryName = row.factoryName
    form.location = row.location || ''
    form.description = row.description || ''
    form.isActive = row.isActive
  } else {
    formMode.value = 'create'
    form.factoryId = null
    form.factoryCode = ''
    form.factoryName = ''
    form.location = ''
    form.description = ''
    form.isActive = 1
  }
  formVisible.value = true
}

const handleSubmit = async () => {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  submitLoading.value = true
  try {
    if (formMode.value === 'create') {
      await createFactory({ ...form })
      ElMessage.success('新建成功')
    } else {
      await updateFactory(form.factoryId, { ...form })
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
      `确定删除工厂「${row.factoryName}」吗？此操作不可恢复。`,
      '确认删除',
      { confirmButtonText: '确定删除', cancelButtonText: '取消', type: 'warning' }
    )
    await deleteFactory(row.factoryId)
    ElMessage.success('删除成功')
    loadData()
  } catch (e) {
    // 用户取消
  }
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

