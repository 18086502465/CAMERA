<template>
  <div class="license-page">
    <div class="page-head">
      <div>
        <h2>🔑 服务器授权</h2>
        <p>本机授权状态、机器码与授权文件更新。授权由供应商离线签发，不需要联网。</p>
      </div>
      <div class="head-actions">
        <el-button :loading="loading" @click="loadStatus">刷新</el-button>
      </div>
    </div>

    <!-- 加载失败必须显式提示：本项目的既有教训是「接口失败、页面照样显示 0」 -->
    <el-alert
      v-if="loadError"
      type="error"
      show-icon
      :closable="false"
      class="gap"
      title="授权状态读取失败"
      :description="loadError"
    />

    <template v-if="state">
      <!-- 状态横幅 -->
      <div class="status-banner" :class="`is-${statusMeta.type}`">
        <div class="status-main">
          <span class="status-icon">{{ statusMeta.icon }}</span>
          <div>
            <div class="status-title">{{ statusMeta.text }}</div>
            <div class="status-desc">{{ state.message }}</div>
          </div>
        </div>
        <el-tag v-if="state.daysLeft !== null && state.status !== 'MISSING'" :type="statusMeta.type" effect="dark">
          剩余 {{ state.daysLeft }} 天
        </el-tag>
      </div>

      <!-- 阻断提示：只在真的会拦的时候出现，避免制造恐慌 -->
      <el-alert
        v-if="state.writeBlocked"
        type="error"
        show-icon
        :closable="false"
        class="gap"
        title="当前策略下会拒绝设备上报"
        :description="`过期策略为「${state.expiredMode}」，超宽限期后上传/心跳会被拒绝。请尽快更新授权。`"
      />
      <el-alert
        v-else-if="state.status === 'EXPIRED' || state.status === 'MISSING' || state.status === 'INVALID'"
        type="warning"
        show-icon
        :closable="false"
        class="gap"
        title="当前不会阻断业务"
        description="过期策略为「warn」：只告警、不拦截。产线照常运行，但仍建议尽快处理授权。"
      />

      <div class="grid">
        <!-- 授权信息 -->
        <el-card shadow="never" class="card">
          <template #header><span class="card-title">授权信息</span></template>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="客户">{{ state.customer || '—' }}</el-descriptions-item>
            <el-descriptions-item label="授权单号">{{ state.licenseId || '—' }}</el-descriptions-item>
            <el-descriptions-item label="签发日期">{{ state.issuedAt || '—' }}</el-descriptions-item>
            <el-descriptions-item label="到期日期">{{ state.expireAt || '—' }}</el-descriptions-item>
            <el-descriptions-item label="相机上限">
              {{ state.maxCameras === -1 ? '不限' : (state.maxCameras ?? '—') }}
            </el-descriptions-item>
            <el-descriptions-item label="过期策略">
              <span class="mono">{{ state.expiredMode }}</span>
              <span class="hint">
                （{{ state.expiredMode === 'warn' ? '只告警不阻断' : state.expiredMode === 'readonly' ? '拦设备写入' : '拦业务查询' }}）
              </span>
            </el-descriptions-item>
            <el-descriptions-item label="授权文件">
              <span class="mono small">{{ state.licenseFile }}</span>
            </el-descriptions-item>
          </el-descriptions>
        </el-card>

        <!-- 机器信息 -->
        <el-card shadow="never" class="card">
          <template #header><span class="card-title">本机信息</span></template>
          <div class="machine-code">
            <div class="mc-label">机器码</div>
            <div class="mc-value">
              <span class="mono big">{{ state.machineCode || '无法采集' }}</span>
              <el-button v-if="state.machineCode" size="small" text type="primary" @click="copyMachineCode">
                复制
              </el-button>
            </div>
            <div class="hint">
              指纹来源：{{ state.fingerprintSource || '无' }}。把机器码发给供应商即可签发绑定本机的授权。
            </div>
          </div>
          <el-divider />
          <el-descriptions :column="1" size="small">
            <el-descriptions-item label="服务器时间">{{ state.serverTime }}</el-descriptions-item>
            <el-descriptions-item label="授权生效">
              <el-tag :type="state.usable ? 'success' : 'info'" size="small">
                {{ state.usable ? '可用' : '受限' }}
              </el-tag>
            </el-descriptions-item>
          </el-descriptions>
          <div v-if="state.machineCode === null" class="hint warn-text">
            读不到主板 UUID / machine-id，说明本机环境受限。此时可用不绑定机器的授权（machineCode='*'）。
          </div>
        </el-card>
      </div>

      <!-- 仅管理员可上传 -->
      <el-card v-if="isAdmin" shadow="never" class="card gap">
        <template #header><span class="card-title">更新授权文件</span></template>
        <div class="upload-row">
          <input ref="fileInput" type="file" accept=".lic,.txt" class="hidden-input" @change="onFileChange" />
          <el-button @click="pickFile">选择 .lic 文件</el-button>
          <span class="hint">{{ selectedFile ? selectedFile.name : '未选择文件' }}</span>
          <el-button type="primary" :loading="uploading" :disabled="!selectedFile" @click="doUpload">
            上传并生效
          </el-button>
          <el-button :loading="reloading" @click="doReload">重新读取</el-button>
        </div>
        <div class="hint">
          上传前会先验签并校验机器绑定，<b>验签失败不会覆盖现有授权</b>；成功后会备份旧文件为
          <span class="mono">license.lic.bak-时间戳</span>。
        </div>
      </el-card>
      <el-alert
        v-else
        type="info"
        show-icon
        :closable="false"
        class="gap"
        title="仅管理员可更新授权"
        description="如需更换授权文件，请联系系统管理员。"
      />
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../../stores/user'
import { getLicenseStatus, reloadLicense, uploadLicense } from '../../api/license'

const STATUS_MAP = {
  DISABLED: { type: 'info', text: '授权校验已关闭', icon: 'ℹ️' },
  MISSING: { type: 'warning', text: '未安装授权', icon: '⚠️' },
  INVALID: { type: 'danger', text: '授权无效', icon: '⛔' },
  MACHINE_MISMATCH: { type: 'danger', text: '授权与本机不符', icon: '⛔' },
  NOT_YET: { type: 'warning', text: '授权尚未生效', icon: '⏳' },
  VALID: { type: 'success', text: '授权正常', icon: '✅' },
  EXPIRING: { type: 'warning', text: '授权即将到期', icon: '⏰' },
  EXPIRED: { type: 'danger', text: '授权已过期', icon: '⛔' }
}

const userStore = useUserStore()
const isAdmin = computed(() => userStore.user?.role === 1 || userStore.user?.role === 'ADMIN')

const state = ref(null)
const loading = ref(false)
const loadError = ref('')
const uploading = ref(false)
const reloading = ref(false)
const selectedFile = ref(null)
const fileInput = ref(null)

const statusMeta = computed(() => STATUS_MAP[state.value?.status] || { type: 'info', text: state.value?.status || '未知', icon: '❔' })

const loadStatus = async () => {
  loading.value = true
  loadError.value = ''
  try {
    const res = await getLicenseStatus()
    // 响应拦截器不解包：axios 的 body 在 res.data，业务数据在 res.data.data
    const body = res?.data
    if (body && body.code === 200 && body.data) {
      state.value = body.data
    } else {
      // 不静默：接口 code 非 200 时把 message 显示出来
      loadError.value = body?.message || '接口返回异常'
    }
  } catch (e) {
    loadError.value = e?.response?.data?.message || e?.message || '网络异常'
  } finally {
    loading.value = false
  }
}

const copyMachineCode = async () => {
  const code = state.value?.machineCode
  if (!code) return
  try {
    await navigator.clipboard.writeText(code)
    ElMessage.success('机器码已复制')
  } catch (e) {
    // 非 https / 旧浏览器无 clipboard API，降级到选中提示
    ElMessage.warning(`复制失败，请手动记录：${code}`)
  }
}

const pickFile = () => fileInput.value?.click()

const onFileChange = (e) => {
  const f = e.target.files?.[0]
  selectedFile.value = f || null
}

const doUpload = async () => {
  if (!selectedFile.value) return
  uploading.value = true
  try {
    const res = await uploadLicense(selectedFile.value)
    const body = res?.data
    if (body && body.code === 200) {
      state.value = body.data
      ElMessage.success(body.message || '授权已更新')
      selectedFile.value = null
      if (fileInput.value) fileInput.value.value = ''
    } else {
      ElMessage.error(body?.message || '授权校验未通过')
    }
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '上传失败')
  } finally {
    uploading.value = false
  }
}

const doReload = async () => {
  reloading.value = true
  try {
    const res = await reloadLicense()
    const body = res?.data
    if (body && body.code === 200) {
      state.value = body.data
      ElMessage.success('已重新读取授权文件')
    } else {
      ElMessage.error(body?.message || '重载失败')
    }
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '重载失败')
  } finally {
    reloading.value = false
  }
}

onMounted(loadStatus)
</script>

<style scoped>
.license-page { padding: 4px 0 24px; }
.page-head { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 16px; }
.page-head h2 { margin: 0 0 4px; font-size: 20px; }
.page-head p { margin: 0; color: var(--el-text-color-secondary); font-size: 13px; }
.head-actions { display: flex; gap: 8px; }

.status-banner {
  display: flex; justify-content: space-between; align-items: center;
  padding: 16px 20px; border-radius: 8px; border: 1px solid var(--el-border-color-light);
  background: var(--el-fill-color-blank); margin-bottom: 16px;
}
.status-banner.is-success { border-color: var(--el-color-success-light-5); background: var(--el-color-success-light-9); }
.status-banner.is-warning { border-color: var(--el-color-warning-light-5); background: var(--el-color-warning-light-9); }
.status-banner.is-danger { border-color: var(--el-color-danger-light-5); background: var(--el-color-danger-light-9); }
.status-banner.is-info { border-color: var(--el-border-color); background: var(--el-fill-color-light); }
.status-main { display: flex; gap: 14px; align-items: center; }
.status-icon { font-size: 26px; line-height: 1; }
.status-title { font-size: 16px; font-weight: 600; margin-bottom: 4px; }
.status-desc { font-size: 13px; color: var(--el-text-color-regular); }

.grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
@media (max-width: 1000px) { .grid { grid-template-columns: 1fr; } }
.card-title { font-weight: 600; }
.gap { margin-top: 16px; }

.machine-code { padding: 4px 0; }
.mc-label { font-size: 12px; color: var(--el-text-color-secondary); margin-bottom: 6px; }
.mc-value { display: flex; align-items: center; gap: 10px; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }
.mono.big { font-size: 22px; letter-spacing: 1px; font-weight: 600; }
.mono.small { font-size: 12px; }
.hint { font-size: 12px; color: var(--el-text-color-secondary); line-height: 1.7; }
.warn-text { color: var(--el-color-warning); margin-top: 8px; }

.upload-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 10px; }
.hidden-input { display: none; }
</style>
