<template>
  <div class="login-page">
    <div class="login-container">
      <div class="logo-area">📷</div>
      <h1 class="title">产线缺陷在线AI检测系统</h1>
      <p class="subtitle">相机缺陷在线检测 · 状态上报 · 图像追溯平台</p>

      <el-form ref="loginFormRef" :model="form" :rules="rules" @submit.prevent="handleLogin" style="margin-top:24px">
        <el-form-item prop="username" class="custom-item">
          <label class="field-label">用户名</label>
          <el-input v-model="form.username" placeholder="请输入用户名" size="large" />
        </el-form-item>

        <el-form-item prop="password" class="custom-item">
          <label class="field-label">密码</label>
          <el-input v-model="form.password" type="password" placeholder="请输入密码" size="large" show-password />
        </el-form-item>

        <div v-if="errorMsg" class="error-msg show">{{ errorMsg }}</div>
        <el-button type="primary" size="large" :loading="loading" class="login-btn" native-type="submit">登录</el-button>
      </el-form>

      <div class="version">Version 2.0</div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../../stores/user'

const router = useRouter()
const userStore = useUserStore()
const loginFormRef = ref(null)
const form = reactive({ username: 'admin', password: '' })
const errorMsg = ref('')
const loading = ref(false)

const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 2, max: 20, message: '用户名长度 2-20 个字符', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 4, max: 32, message: '密码长度 4-32 个字符', trigger: 'blur' }
  ]
}

onMounted(() => {
  userStore.logout()
})

const handleLogin = async () => {
  if (!loginFormRef.value) return
  try {
    await loginFormRef.value.validate()
  } catch {
    return
  }

  loading.value = true
  errorMsg.value = ''
  try {
    await userStore.login(form.username, form.password)
    router.replace('/camera-status')
  } catch (e) {
    errorMsg.value = e.response?.data?.message || '登录失败，请检查用户名和密码'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page { min-height: 100vh; display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg, #0f172a 0%, #1e3a8a 50%, #172554 100%); position: relative; overflow: hidden; }
.login-page::before { content:''; position:absolute; top:-50%; left:-50%; width:200%; height:200%; background: radial-gradient(circle at 30% 40%, rgba(59,130,246,.08) 0%, transparent 50%), radial-gradient(circle at 70% 60%, rgba(37,99,235,.06) 0%, transparent 50%); animation: bgFloat 20s ease-in-out infinite; }
@keyframes bgFloat { 0%,100% { transform:translate(0,0) rotate(0deg) } 33% { transform:translate(2%,-1%) rotate(1deg) } 66% { transform:translate(-1%,2%) rotate(-1deg) } }
.login-container { background: rgba(255,255,255,.98); border-radius: 20px; padding: 48px 40px; width: 420px; max-width: 90vw; box-shadow: 0 25px 60px rgba(0,0,0,.35); position: relative; z-index: 1; animation: slideUp .5s ease-out; }
@keyframes slideUp { from { opacity:0; transform:translateY(30px) } to { opacity:1; transform:translateY(0) } }
.logo-area { width: 80px; height: 80px; margin: 0 auto 20px; background: linear-gradient(135deg, #dbeafe, #bfdbfe); border-radius: 20px; display: flex; align-items: center; justify-content: center; font-size: 40px; box-shadow: 0 8px 24px rgba(37,99,235,.15); }
.title { text-align: center; font-size: 24px; font-weight: 700; color: #1e293b; margin-bottom: 6px; }
.subtitle { text-align: center; font-size: 14px; color: #64748b; }
.custom-item { margin-bottom: 4px; }
.custom-item :deep(.el-form-item__content) { flex-direction: column; align-items: flex-start; }
.field-label { display: block; font-size: 13px; font-weight: 600; color: #1e293b; margin-bottom: 6px; }
.custom-item :deep(.el-form-item__error) { position: static; padding-top: 4px; }
.error-msg { padding: 10px 14px; background: #fef2f2; color: #dc2626; border: 1px solid #fecaca; border-radius: 8px; font-size: 13px; text-align: center; margin-bottom: 12px; animation: shake .3s ease-in-out; }
@keyframes shake { 0%,100% { transform:translateX(0) } 25% { transform:translateX(-6px) } 75% { transform:translateX(6px) } }
.login-btn { width: 100%; height: 44px; font-size: 16px; font-weight: 600; border-radius: 10px; }
.version { text-align: center; margin-top: 20px; font-size: 11px; color: #94a3b8; }
</style>
