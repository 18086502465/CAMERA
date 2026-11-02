<template>
  <div class="page-container">
    <div class="page-header">
      <h2>👤 个人中心</h2>
    </div>

    <el-row :gutter="24">
      <!-- 用户信息卡片 -->
      <el-col :xs="24" :md="8">
        <el-card shadow="never" class="profile-card">
          <div class="avatar-section">
            <el-avatar :size="96" class="profile-avatar">
              {{ userStore.userName?.charAt(0) || 'U' }}
            </el-avatar>
            <h3>{{ userStore.userName }}</h3>
            <el-tag :type="userStore.isAdmin ? 'danger' : 'info'" size="small">
              {{ userStore.isAdmin ? '管理员' : '普通用户' }}
            </el-tag>
            <p class="user-factory" v-if="profile?.factoryName">
              🏭 {{ profile.factoryName }}
            </p>
          </div>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="用户名">{{ profile?.username || '-' }}</el-descriptions-item>
            <el-descriptions-item label="真实姓名">{{ profile?.realName || '-' }}</el-descriptions-item>
            <el-descriptions-item label="角色">{{ profile?.role === 'ADMIN' ? '管理员' : '普通用户' }}</el-descriptions-item>
            <el-descriptions-item label="所属工厂">{{ profile?.factoryName || '未绑定' }}</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>

      <!-- 修改密码 -->
      <el-col :xs="24" :md="16">
        <el-card shadow="never">
          <template #header>
            <span style="font-weight:600;font-size:15px">🔒 修改密码</span>
          </template>
          <el-form
            ref="pwdFormRef"
            :model="pwdForm"
            :rules="pwdRules"
            label-width="100px"
            style="max-width: 460px"
          >
            <el-form-item label="旧密码" prop="oldPassword">
              <el-input v-model="pwdForm.oldPassword" type="password" show-password />
            </el-form-item>
            <el-form-item label="新密码" prop="newPassword">
              <el-input v-model="pwdForm.newPassword" type="password" show-password />
            </el-form-item>
            <el-form-item label="确认新密码" prop="confirmPassword">
              <el-input v-model="pwdForm.confirmPassword" type="password" show-password />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="pwdLoading" @click="handleChangePassword">确认修改</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useUserStore } from '../../stores/user'
import { getProfile, updatePassword } from '../../api/auth'
import { ElMessage } from '@/utils/message.js'

const userStore = useUserStore()
const profile = ref(null)
const pwdLoading = ref(false)
const pwdFormRef = ref(null)

const pwdForm = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

const validatePass2 = (rule, value, callback) => {
  if (value !== pwdForm.newPassword) {
    callback(new Error('两次输入的密码不一致'))
  } else {
    callback()
  }
}

const pwdRules = {
  oldPassword: [{ required: true, message: '请输入旧密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 20, message: '密码长度为6-20字符', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    { validator: validatePass2, trigger: 'blur' }
  ]
}

const loadProfile = async () => {
  try {
    const res = await getProfile()
    profile.value = res.data.data
    // 同步到 store
    userStore.user = res.data.data
  } catch (e) {
    console.error('加载个人信息失败', e)
  }
}

const handleChangePassword = async () => {
  const valid = await pwdFormRef.value.validate().catch(() => false)
  if (!valid) return
  pwdLoading.value = true
  try {
    await updatePassword({
      oldPassword: pwdForm.oldPassword,
      newPassword: pwdForm.newPassword
    })
    ElMessage.success('密码修改成功，请重新登录')
    pwdForm.oldPassword = ''
    pwdForm.newPassword = ''
    pwdForm.confirmPassword = ''
    // 3秒后退出登录
    setTimeout(() => userStore.logout() || (window.location.href = '/login'), 2000)
  } catch (e) {
    const msg = e.response?.data?.message || '密码修改失败'
    ElMessage.error(msg)
  } finally {
    pwdLoading.value = false
  }
}

onMounted(() => {
  loadProfile()
})
</script>

<style scoped>
.page-container { padding: 0; }
.page-header {
  margin-bottom: 16px;
}
.page-header h2 { margin: 0; font-size: 18px; color: #1a1a2e; }
.profile-card { margin-bottom: 16px; }
.avatar-section {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 16px 0 20px;
}
.avatar-section h3 { margin: 0; font-size: 18px; color: #1a1a2e; }
.profile-avatar { background: #409eff; color: #fff; font-size: 36px; }
.user-factory { font-size: 13px; color: #909399; margin: 4px 0 0; }
</style>
