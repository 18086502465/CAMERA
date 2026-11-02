<template>
  <div class="page-container">
    <div class="page-header">
      <h2>👥 用户管理</h2>
      <el-button type="primary" @click="openForm(null)">➕ 新建用户</el-button>
    </div>

    <!-- 搜索栏 -->
    <el-card shadow="never" class="filter-card">
      <el-space wrap>
        <el-input
          v-model="keyword"
          placeholder="搜索用户名/姓名..."
          clearable
          style="width: 220px"
          @clear="loadData"
          @keyup.enter="loadData"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-select v-model="filterRole" placeholder="角色筛选" clearable @change="loadData" style="width: 120px">
          <el-option label="管理员" value="ADMIN" />
          <el-option label="普通用户" value="USER" />
        </el-select>
        <el-button type="primary" @click="loadData">🔍 搜索</el-button>
        <el-button @click="keyword='';filterRole=null;loadData()">重置</el-button>
      </el-space>
    </el-card>

    <!-- 表格 -->
    <el-card shadow="never" class="table-card">
      <el-table :data="tableData" v-loading="loading" stripe border style="width: 100%">
        <el-table-column prop="userId" label="ID" width="60" />
        <el-table-column prop="username" label="用户名" width="130" />
        <el-table-column prop="realName" label="真实姓名" width="120" />
        <el-table-column label="角色" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.role === 'ADMIN' ? 'danger' : 'info'" size="small">
              {{ row.role === 'ADMIN' ? '管理员' : '普通用户' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="factoryName" label="所属工厂" width="160" />
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.isDisabled ? 'info' : 'success'" size="small">
              {{ row.isDisabled ? '禁用' : '启用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lastLoginAt" label="最后登录" width="160" />
        <el-table-column label="操作" width="180" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openForm(row)">编辑</el-button>
            <el-button
              link
              :type="row.isDisabled ? 'success' : 'warning'"
              size="small"
              @click="handleToggleStatus(row)"
            >
              {{ row.isDisabled ? '启用' : '禁用' }}
            </el-button>
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
    <el-dialog v-model="formVisible" :title="formMode === 'create' ? '新建用户' : '编辑用户'" width="520px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" :disabled="formMode === 'edit'" placeholder="登录用户名" />
        </el-form-item>
        <el-form-item label="真实姓名" prop="realName">
          <el-input v-model="form.realName" placeholder="真实姓名" />
        </el-form-item>
        <el-form-item label="密　码" prop="password" v-if="formMode === 'create'">
          <el-input v-model="form.password" type="password" show-password placeholder="留空则不修改" />
        </el-form-item>
        <el-form-item label="角　色" prop="role">
          <el-select v-model="form.role" style="width: 100%">
            <el-option label="管理员" value="ADMIN" />
            <el-option label="普通用户" value="USER" />
          </el-select>
        </el-form-item>
        <el-form-item label="所属工厂" prop="factoryId">
          <el-select v-model="form.factoryId" placeholder="普通用户需选择工厂" clearable style="width: 100%">
            <el-option
              v-for="f in factoryOptions"
              :key="f.factoryId"
              :label="f.factoryName"
              :value="f.factoryId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="状　态" v-if="formMode === 'edit'">
          <el-switch v-model="form.isDisabled" active-text="禁用" inactive-text="启用" :active-value="1" :inactive-value="0" />
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
import { getUserList, createUser, updateUser, deleteUser } from '../../api/user'
import { getFactoryList } from '../../api/factory'
import { ElMessage, ElMessageBox } from '@/utils/message.js'

const keyword = ref('')
const filterRole = ref(null)
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
  userId: null,
  username: '',
  realName: '',
  password: '',
  role: 'USER',
  factoryId: null,
  isDisabled: 0
})

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  realName: [{ required: true, message: '请输入真实姓名', trigger: 'blur' }],
  role: [{ required: true, message: '请选择角色', trigger: 'change' }]
}

// 模拟数据（后端 UserController 完成后替换为真实 API）
const mockUsers = () => {
  const rows = []
  for (let i = 0; i < pageSize.value; i++) {
    rows.push({
      userId: (currentPage.value - 1) * pageSize.value + i + 1,
      username: i === 0 ? 'admin' : `user${i}`,
      realName: i === 0 ? '管理员' : `用户${i}`,
      role: i === 0 ? 'ADMIN' : 'USER',
      factoryName: i % 2 === 0 ? 'XX卫生用品' : 'YY纸业',
      factoryId: i % 2 === 0 ? 1 : 2,
      isDisabled: i === 2 ? 1 : 0,
      lastLoginAt: i === 0 ? '2026-05-28 14:30:00' : ''
    })
  }
  return rows
}

const loadData = async () => {
  loading.value = true
  try {
    // TODO: 替换为真实 API
    // const params = { page: currentPage.value, size: pageSize.value }
    // if (keyword.value) params.keyword = keyword.value
    // if (filterRole.value) params.role = filterRole.value
    // const res = await getUserList(params)
    // tableData.value = res.data.data.records || []
    // total.value = res.data.data.total || 0

    await new Promise(r => setTimeout(r, 200))
    tableData.value = mockUsers()
    total.value = 25
  } catch (e) {
    ElMessage.error('加载用户列表失败')
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
      userId: row.userId,
      username: row.username,
      realName: row.realName,
      password: '',
      role: row.role,
      factoryId: row.factoryId || null,
      isDisabled: row.isDisabled
    })
  } else {
    formMode.value = 'create'
    Object.assign(form, {
      userId: null, username: '', realName: '', password: '',
      role: 'USER', factoryId: null, isDisabled: 0
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
      await createUser({ ...form })
      ElMessage.success('新建成功')
    } else {
      await updateUser(form.userId, { ...form })
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

const handleToggleStatus = async (row) => {
  const action = row.isDisabled ? '启用' : '禁用'
  try {
    await ElMessageBox.confirm(`确定${action}用户「${row.username}」吗？`, '确认操作', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await updateUser(row.userId, { isDisabled: row.isDisabled ? 0 : 1 })
    ElMessage.success(`${action}成功`)
    loadData()
  } catch (e) { /* 用户取消 */ }
}

const handleDelete = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确定删除用户「${row.username}」吗？此操作不可恢复。`,
      '确认删除',
      { confirmButtonText: '确定删除', cancelButtonText: '取消', type: 'warning' }
    )
    await deleteUser(row.userId)
    ElMessage.success('删除成功')
    loadData()
  } catch (e) { /* 用户取消 */ }
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

