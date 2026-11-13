<template>
  <div class="auth-container">
    <div class="auth-card">
      <div class="auth-logo">
        <span class="logo-icon">📷</span>
        <h1>产线缺陷在线AI检测系统</h1>
      </div>

      <h2 class="auth-title">用户注册</h2>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        @keyup.enter="handleRegister"
        label-width="0"
      >
        <el-form-item prop="username">
          <el-input
            v-model="form.username"
            placeholder="请输入用户名（3-20字符）"
            size="large"
            :prefix-icon="User"
          />
        </el-form-item>

        <el-form-item prop="realName">
          <el-input
            v-model="form.realName"
            placeholder="请输入真实姓名"
            size="large"
            :prefix-icon="Edit"
          />
        </el-form-item>

        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码（6-20字符）"
            size="large"
            show-password
            :prefix-icon="Lock"
          />
        </el-form-item>

        <el-form-item prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            placeholder="请再次输入密码"
            size="large"
            show-password
            :prefix-icon="Lock"
          />
        </el-form-item>

        <el-form-item prop="factoryId">
          <el-select
            v-model="form.factoryId"
            placeholder="请选择所属工厂"
            size="large"
            style="width: 100%"
          >
            <el-option
              v-for="f in factoryOptions"
              :key="f.factoryId"
              :label="f.factoryName"
              :value="f.factoryId"
            />
          </el-select>
        </el-form-item>

        <el-alert
          v-if="errorMsg"
          :title="errorMsg"
          type="error"
          show-icon
          :closable="false"
          style="margin-bottom: 16px"
        />

        <el-alert
          v-if="successMsg"
          :title="successMsg"
          type="success"
          show-icon
          :closable="false"
          style="margin-bottom: 16px"
        />

        <el-form-item>
          <el-button
            type="primary"
            size="large"
            :loading="loading"
            @click="handleRegister"
            style="width: 100%"
          >
            注　册
          </el-button>
        </el-form-item>
      </el-form>

      <div class="auth-footer">
        已有账号？
        <router-link to="/login">立即登录</router-link>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { User, Lock, Edit } from '@element-plus/icons-vue'
import { register } from '../../api/auth'
import { getFactoryList } from '../../api/factory'
import { ElMessage } from '@/utils/message.js'

const router = useRouter()
const formRef = ref(null)
const loading = ref(false)
const errorMsg = ref('')
const successMsg = ref('')
const factoryOptions = ref([])

const form = reactive({
  username: '',
  realName: '',
  password: '',
  confirmPassword: '',
  factoryId: null
})

const validatePass2 = (rule, value, callback) => {
  if (value !== form.password) {
    callback(new Error('两次输入的密码不一致'))
  } else {
    callback()
  }
}

const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 20, message: '用户名长度为3-20字符', trigger: 'blur' }
  ],
  realName: [{ required: true, message: '请输入真实姓名', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 20, message: '密码长度为6-20字符', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    { validator: validatePass2, trigger: 'blur' }
  ],
  factoryId: [{ required: true, message: '请选择所属工厂', trigger: 'change' }]
}

const loadFactories = async () => {
  try {
    const res = await getFactoryList({ size: 100 })
    factoryOptions.value = res.data.data.records || []
  } catch (e) {
    console.error('加载工厂列表失败', e)
  }
}

const handleRegister = async () => {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  errorMsg.value = ''
  successMsg.value = ''
  try {
    await register({
      username: form.username,
      realName: form.realName,
      password: form.password,
      factoryId: form.factoryId
    })
    successMsg.value = '注册成功，即将跳转登录页...'
    ElMessage.success('注册成功')
    setTimeout(() => router.push('/login'), 1500)
  } catch (e) {
    const res = e.response?.data
    errorMsg.value = res?.message || '注册失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

onMounted(() => loadFactories())
</script>

<style scoped>
.auth-container {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}

.auth-card {
  width: 440px;
  background: var(--color-bg-card);
  border-radius: 16px;
  padding: 40px 36px 32px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.15);
}

.auth-logo {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  margin-bottom: 8px;
}

.logo-icon {
  font-size: 32px;
}

.auth-logo h1 {
  font-size: 20px;
  font-weight: 700;
  color: #1a1a2e;
  margin: 0;
}

.auth-title {
  text-align: center;
  font-size: 18px;
  color: #606266;
  font-weight: 500;
  margin: 16px 0 28px;
}

.auth-footer {
  text-align: center;
  margin-top: 20px;
  font-size: 14px;
  color: #909399;
}

.auth-footer a {
  color: #409eff;
  text-decoration: none;
  font-weight: 500;
}
</style>
