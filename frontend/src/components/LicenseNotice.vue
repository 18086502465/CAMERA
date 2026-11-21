<template>
  <transition name="lic-fade">
    <div v-if="visible" class="lic-notice" @click.stop>
      <div class="lic-card" :class="{ 'is-missing': isMissing }">
        <div class="lic-head">
          <span class="lic-icon">{{ isMissing ? '⚠️' : '⛔' }}</span>
          <span class="lic-title">{{ isMissing ? '未安装正式授权' : '服务器授权已到期' }}</span>
        </div>
        <div class="lic-msg">{{ message }}</div>
        <div class="lic-sub">
          功能不受影响，仍可正常操作。<template v-if="!isMissing">
            每次点击页面都会重复本提醒，续期后自动消失。
          </template>请前往「系统管理 → 服务器授权」下载机器码包申请续期，或联系供应商。
        </div>
        <div class="lic-actions">
          <el-button size="small" type="primary" plain @click="goLicensePage">前往授权页</el-button>
          <el-button size="small" @click="dismiss">我知道了</el-button>
        </div>
      </div>
    </div>
  </transition>
</template>

<script setup>
/**
 * 全局到期浮层（2026-11-21 LIC-18，承接商务决策 7）。
 *
 * <p>三条硬约束，改代码前先读设计文档 §6：
 * <ul>
 *   <li><b>非模态</b>：禁止改成带遮罩的 el-dialog —— 遮罩会吃掉点击，
 *       用户必须先关窗才能操作，与「不影响功能」的对外承诺直接矛盾；</li>
 *   <li><b>每次点击重新弹出</b>：用户关掉后，下一次页面点击会再弹（300ms 防抖
 *       防止「关窗那一下」自己触发）；不拦路由、不拦请求；</li>
 *   <li><b>触发条件只有两种</b>：EXPIRED；或 MISSING 且试用期已结束。
 *       试用期内（TRIAL / 试用中的 MISSING）只走授权页内的横幅，不打扰。</li>
 * </ul>
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getLicenseStatus } from '../api/license'
import { getToken, isTokenExpired } from '../utils/auth'

const router = useRouter()
const state = ref(null)
const visible = ref(false)
const dismissed = ref(false)
let suppressUntil = 0
let timer = null

const isMissing = computed(() => state.value?.status === 'MISSING')
const message = computed(() => state.value?.message || '授权已到期，请尽快续期')

const isExpired = computed(() => {
  const s = state.value
  if (!s) return false
  if (s.status === 'EXPIRED') return true
  // 未装授权且试用期已结束：同 EXPIRED 提示，文案区分「未安装」
  if (s.status === 'MISSING' && s.trial && s.trialDaysLeft != null && s.trialDaysLeft <= 0) {
    return true
  }
  return false
})

const loadStatus = async () => {
  // 未登录时不请求也不展示：登录页弹授权提醒没有意义，还会污染 401 分流
  if (!getToken() || isTokenExpired()) return
  try {
    const res = await getLicenseStatus()
    const body = res?.data
    if (body && body.code === 200 && body.data) {
      state.value = body.data
      if (isExpired.value && !dismissed.value) {
        visible.value = true
      }
    }
  } catch (e) {
    // 静默：浮层是提醒性质，失败不打扰业务
  }
}

const dismiss = () => {
  visible.value = false
  dismissed.value = true
  suppressUntil = Date.now() + 300
}

const goLicensePage = () => {
  visible.value = false
  dismiss()
  router.push('/system/license').catch(() => {})
}

/** 任何点击都会让已关闭的提醒重新弹出（决策 7）。 */
const onDocClick = () => {
  if (!isExpired.value || !state.value) return
  if (Date.now() < suppressUntil) return
  if (!visible.value) {
    visible.value = true
  }
}

onMounted(() => {
  loadStatus()
  timer = setInterval(loadStatus, 60_000)
  document.addEventListener('click', onDocClick, true)
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
  document.removeEventListener('click', onDocClick, true)
})
</script>

<style scoped>
/* 容器不吃点击：pointer-events none，只有卡片本身可交互 */
.lic-notice {
  position: fixed;
  top: 12px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 3000;
  pointer-events: none;
  width: min(560px, calc(100vw - 32px));
}
.lic-card {
  pointer-events: auto;
  background: #fff;
  border: 1px solid var(--el-color-danger-light-5, #f89898);
  border-left: 4px solid var(--el-color-danger, #f56c6c);
  border-radius: 8px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.18);
  padding: 14px 16px;
}
.lic-card.is-missing { border-left-color: var(--el-color-warning, #e6a23c); }
.lic-head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.lic-icon { font-size: 20px; line-height: 1; }
.lic-title { font-size: 15px; font-weight: 700; color: var(--el-color-danger, #f56c6c); }
.lic-card.is-missing .lic-title { color: var(--el-color-warning, #e6a23c); }
.lic-msg { font-size: 13px; color: var(--el-text-color-regular, #606266); line-height: 1.6; }
.lic-sub { font-size: 12px; color: var(--el-text-color-secondary, #909399); line-height: 1.7; margin: 6px 0 10px; }
.lic-actions { display: flex; gap: 8px; justify-content: flex-end; }

.lic-fade-enter-active, .lic-fade-leave-active { transition: opacity 0.2s, transform 0.2s; }
.lic-fade-enter-from, .lic-fade-leave-to { opacity: 0; transform: translate(-50%, -8px); }
</style>
