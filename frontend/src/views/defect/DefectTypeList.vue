<template>
  <div class="defect-type-list">
    <!-- 页面标题栏 -->
    <div class="page-header">
      <h2 class="page-title">缺陷分类管理</h2>
      <el-button type="primary" @click="handleCreate">
        <el-icon><Plus /></el-icon>
        新增缺陷分类
      </el-button>
    </div>

    <!-- 搜索栏 -->
    <div class="filter-bar">
      <el-input
        v-model="searchKeyword"
        placeholder="搜索缺陷名称..."
        clearable
        @input="handleSearch"
        style="width: 300px"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>
      <el-select
        v-model="filterArea"
        placeholder="缺陷区域"
        clearable
        @change="handleSearch"
        style="width: 150px; margin-left: 12px"
      >
        <el-option label="内装区" value="内装区" />
        <el-option label="前腰腹区" value="前腰腹区" />
        <el-option label="后腰腹区" value="后腰腹区" />
        <el-option label="内外层复合区" value="内外层复合区" />
      </el-select>
      <el-select
        v-model="filterType"
        placeholder="缺陷类型"
        clearable
        @change="handleSearch"
        style="width: 150px; margin-left: 12px"
      >
        <el-option label="胶滴类型" value="胶滴类型" />
        <el-option label="异物污点类型" value="异物污点类型" />
        <el-option label="接头类型" value="接头类型" />
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
      <el-table-column prop="defectCode" label="缺陷编码" width="120" align="center" />
      <el-table-column prop="defectName" label="缺陷名称" min-width="180" />
      <el-table-column prop="defectArea" label="缺陷区域" width="120" align="center">
        <template #default="{ row }">
          <el-tag size="small">{{ row.defectArea }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="defectType" label="缺陷类型" width="120" align="center">
        <template #default="{ row }">
          <el-tag size="small" type="warning">{{ row.defectType }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="defectSize" label="缺陷面积" width="120" align="center" />
      <el-table-column prop="severity" label="严重程度" width="100" align="center">
        <template #default="{ row }">
          <el-tag
            :type="getSeverityType(row.severity)"
            size="small"
          >
            {{ row.severity }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="sortOrder" label="排序" width="80" align="center" />
      <el-table-column prop="isSystem" label="系统内置" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="row.isSystem ? 'warning' : 'info'" size="small">
            {{ row.isSystem ? '是' : '否' }}
          </el-tag>
        </template>
      </el-table-column>
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
          <el-button 
            type="danger" 
            link 
            size="small" 
            @click="handleDelete(row)"
            :disabled="row.isSystem"
          >
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
      width="600px"
      :close-on-click-modal="false"
      @close="resetForm"
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="100px"
      >
        <el-form-item label="缺陷编码" prop="defectCode">
          <el-input v-model="form.defectCode" placeholder="请输入缺陷编码" />
        </el-form-item>
        <el-form-item label="缺陷名称" prop="defectName">
          <el-input v-model="form.defectName" placeholder="请输入缺陷名称" />
        </el-form-item>
        <el-form-item label="缺陷区域" prop="defectArea">
          <el-select v-model="form.defectArea" placeholder="请选择缺陷区域" style="width: 100%">
            <el-option label="内装区" value="内装区" />
            <el-option label="前腰腹区" value="前腰腹区" />
            <el-option label="后腰腹区" value="后腰腹区" />
            <el-option label="内外层复合区" value="内外层复合区" />
          </el-select>
        </el-form-item>
        <el-form-item label="缺陷类型" prop="defectType">
          <el-select v-model="form.defectType" placeholder="请选择缺陷类型" style="width: 100%">
            <el-option label="胶滴类型" value="胶滴类型" />
            <el-option label="异物污点类型" value="异物污点类型" />
            <el-option label="接头类型" value="接头类型" />
          </el-select>
        </el-form-item>
        <el-form-item label="缺陷面积" prop="defectSize">
          <el-select v-model="form.defectSize" placeholder="请选择缺陷面积" style="width: 100%">
            <el-option label="1mm²以内" value="1mm²以内" />
            <el-option label="1-3mm²" value="1-3mm²" />
            <el-option label="3mm²以上" value="3mm²以上" />
            <el-option label="接头" value="接头" />
          </el-select>
        </el-form-item>
        <el-form-item label="严重程度" prop="severity">
          <el-select v-model="form.severity" placeholder="请选择严重程度" style="width: 100%">
            <el-option label="致命缺陷" value="致命缺陷" />
            <el-option label="严重缺陷" value="严重缺陷" />
            <el-option label="一般缺陷" value="一般缺陷" />
            <el-option label="轻微缺陷" value="轻微缺陷" />
          </el-select>
        </el-form-item>
        <el-form-item label="排序" prop="sortOrder">
          <el-input-number v-model="form.sortOrder" :min="0" />
        </el-form-item>
        <el-form-item label="系统内置" prop="isSystem">
          <el-switch v-model="form.isSystem" />
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
import { getDefectTypeList, createDefectType, updateDefectType, deleteDefectType } from '@/api/defect-type'
import { Search, Plus } from '@element-plus/icons-vue'

const loading = ref(false)
const submitLoading = ref(false)
const tableData = ref([])
const searchKeyword = ref('')
const filterArea = ref('')
const filterType = ref('')
const dialogVisible = ref(false)
const dialogTitle = ref('新增缺陷分类')
const isEdit = ref(false)
const currentId = ref(null)
const formRef = ref(null)

const pagination = reactive({
  page: 1,
  size: 10,
  total: 0
})

const form = reactive({
  defectCode: '',
  defectName: '',
  defectArea: '',
  defectType: '',
  defectSize: '',
  severity: '',
  sortOrder: 0,
  isSystem: false
})

const rules = {
  defectCode: [{ required: true, message: '请输入缺陷编码', trigger: 'blur' }],
  defectName: [{ required: true, message: '请输入缺陷名称', trigger: 'blur' }],
  defectArea: [{ required: true, message: '请选择缺陷区域', trigger: 'change' }],
  defectType: [{ required: true, message: '请选择缺陷类型', trigger: 'change' }],
  defectSize: [{ required: true, message: '请选择缺陷面积', trigger: 'change' }],
  severity: [{ required: true, message: '请选择严重程度', trigger: 'change' }]
}

const getSeverityType = (severity) => {
  const map = {
    '致命缺陷': 'danger',
    '严重缺陷': 'warning',
    '一般缺陷': 'info',
    '轻微缺陷': ''
  }
  return map[severity] || ''
}

const loadData = async () => {
  loading.value = true
  try {
    const res = await getDefectTypeList({
      page: pagination.page,
      size: pagination.size,
      keyword: searchKeyword.value,
      defectArea: filterArea.value,
      defectType: filterType.value
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
  dialogTitle.value = '新增缺陷分类'
  dialogVisible.value = true
}

const handleEdit = (row) => {
  isEdit.value = true
  dialogTitle.value = '编辑缺陷分类'
  currentId.value = row.id
  Object.assign(form, {
    defectCode: row.defectCode,
    defectName: row.defectName,
    defectArea: row.defectArea,
    defectType: row.defectType,
    defectSize: row.defectSize,
    severity: row.severity,
    sortOrder: row.sortOrder,
    isSystem: row.isSystem
  })
  dialogVisible.value = true
}

const handleDelete = async (row) => {
  if (row.isSystem) {
    ElMessage.warning('系统内置数据不能删除')
    return
  }
  
  try {
    await ElMessageBox.confirm(`确定删除缺陷分类"${row.defectName}"吗？`, '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await deleteDefectType(row.id)
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
      await updateDefectType(currentId.value, { ...form })
      ElMessage.success('更新成功')
    } else {
      await createDefectType({ ...form })
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
    defectCode: '',
    defectName: '',
    defectArea: '',
    defectType: '',
    defectSize: '',
    severity: '',
    sortOrder: 0,
    isSystem: false
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
.defect-type-list {
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
  align-items: center;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>

