<template>
  <div class="license-page">
    <div class="page-head">
      <div>
        <h2>🔑 服务器授权</h2>
        <p>下载机器码包 → 研发签发 → 导入授权包。授权由供应商离线签发，不需要联网。</p>
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
      <!-- ① 状态区 -->
      <div class="status-banner" :class="`is-${statusMeta.type}`">
        <div class="status-main">
          <span class="status-icon">{{ statusMeta.icon }}</span>
          <div>
            <div class="status-title">{{ statusMeta.text }}</div>
            <div class="status-desc">{{ state.message }}</div>
            <div v-if="state.expiredModeSource" class="status-desc subtle">
              到期策略：<span class="mono">{{ state.expiredMode }}</span>
              （{{ modePlain }}，{{ state.expiredModeSource === 'license' ? '由授权单指定' : '来自服务器配置' }}）
            </div>
          </div>
        </div>
        <div class="status-right">
          <el-tag v-if="state.status === 'TRIAL' && state.trialDaysLeft !== null" type="primary" effect="dark">
            试用剩余 {{ state.trialDaysLeft }} 天
          </el-tag>
          <el-tag v-else-if="state.daysLeft !== null && state.status !== 'MISSING'" :type="statusMeta.type" effect="dark">
            剩余 {{ state.daysLeft }} 天
          </el-tag>
        </div>
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

      <!-- ② 机器码区 + ④ 详情区 -->
      <div class="grid">
        <el-card shadow="never" class="card">
          <template #header><span class="card-title">本机机器码</span></template>
          <div class="machine-code">
            <div class="mc-label">机器码（不可逆推硬件序列号）</div>
            <div class="mc-value">
              <span class="mono big" :class="{ 'is-null': !state.machineCode }">
                {{ state.machineCode || '无法采集' }}
              </span>
              <el-button v-if="state.machineCode" size="small" text type="primary" @click="copyMachineCode">
                复制
              </el-button>
            </div>
            <div class="hint">
              指纹来源：
              <el-tag size="small" type="info" effect="plain">{{ state.fingerprintSource || '无' }}</el-tag>
              <span v-if="state.fingerprintSource === 'machine-id'" class="hint">（重装系统会变码，届时需重签）</span>
            </div>
          </div>
          <el-divider />
          <template v-if="isAdmin">
            <div class="dl-row">
              <el-button type="primary" :loading="downloading" :disabled="!state.machineCode" @click="downloadPackage">
                下载机器码包（.zip）
              </el-button>
              <el-input
                v-model="orderHint"
                placeholder="商务单号（选填，如 LIC-20261121-001）"
                class="order-input"
                clearable
              />
            </div>
            <div class="hint">
              把整个 zip 发给研发，<b>不要截图或手抄机器码</b>（16 位错一位就签错机器）。
              包内含现场相机数，方便研发确认档位够不够。
            </div>
            <div v-if="!state.machineCode" class="hint warn-text">
              读不到主板 UUID / machine-id：请检查 /sys/class/dmi/id/product_uuid 与 /etc/machine-id
              是否可读，或联系研发改用手工机器码模式签发。
            </div>
          </template>
          <template v-else>
            <el-alert type="info" :closable="false" title="仅管理员可下载机器码包" class="hint-alert" />
          </template>
        </el-card>

        <el-card shadow="never" class="card">
          <template #header><span class="card-title">当前授权</span></template>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="客户">{{ state.customer || '—' }}</el-descriptions-item>
            <el-descriptions-item label="授权单号">{{ state.licenseId || '—' }}</el-descriptions-item>
            <el-descriptions-item label="签发日期">{{ state.issuedAt || '—' }}</el-descriptions-item>
            <el-descriptions-item label="到期日期">{{ state.expireAt || '—' }}</el-descriptions-item>
            <el-descriptions-item label="相机上限">
              {{ state.maxCameras === -1 ? '不限' : (state.maxCameras ?? '—') }}
            </el-descriptions-item>
            <el-descriptions-item label="授权文件">
              <span class="mono small">{{ state.licenseFile }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="服务器时间">{{ state.serverTime }}</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </div>

      <!-- ③ 导入区（两步式，仅管理员） -->
      <el-card v-if="isAdmin" shadow="never" class="card gap">
        <template #header><span class="card-title">导入授权包（两步：先预览、再确认）</span></template>

        <div class="upload-row">
          <input ref="fileInput" type="file" accept=".zip,.lic,.txt" class="hidden-input" @change="onFileChange" />
          <el-button @click="pickFile">选择授权包（.zip）</el-button>
          <span class="hint">{{ selectedFile ? selectedFile.name : '支持 .zip 授权包（兼容旧 .lic 直传）' }}</span>
        </div>

        <!-- 第一步：预览摘要 -->
        <div v-if="preview" class="preview-card">
          <div class="preview-title">预览摘要 <span class="hint">（5 分钟内有效，确认前不会覆盖现有授权）</span></div>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="授权单号">{{ preview.orderNo || '—' }}</el-descriptions-item>
            <el-descriptions-item label="客户">{{ preview.customer || '—' }}</el-descriptions-item>
            <el-descriptions-item label="到期日期">{{ preview.expireAt || '—' }}</el-descriptions-item>
            <el-descriptions-item label="剩余天数">{{ preview.daysLeft ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="相机上限">
              {{ preview.maxCameras === -1 ? '不限' : (preview.maxCameras ?? '—') }}
              <span v-if="preview.currentCameras !== null" class="hint">（现场 {{ preview.currentCameras }} 台）</span>
            </el-descriptions-item>
            <el-descriptions-item label="到期策略">{{ preview.expiredModePlain || '跟随服务器配置' }}</el-descriptions-item>
            <el-descriptions-item label="机器码比对">
              <el-tag size="small" :type="preview.machineMatch ? 'success' : 'danger'">
                {{ preview.machineMatch ? '✅ 与本机一致' : '✗ 不一致' }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="签发日期 / 人">
              {{ preview.issuedAt || '—' }}<span v-if="preview.issuedBy"> / {{ preview.issuedBy }}</span>
            </el-descriptions-item>
          </el-descriptions>

          <el-alert
            v-if="preview.quotaExceeded"
            type="warning"
            show-icon
            :closable="false"
            class="gap-small"
            :title="`相机上限 ${preview.maxCameras} 台低于现场实际 ${preview.currentCameras} 台`"
          >
            <el-checkbox v-model="ackQuota">我确认按该上限运行（导入后超出部分无法新增相机）</el-checkbox>
          </el-alert>
          <el-alert
            v-if="!preview.orderMatch"
            type="warning"
            show-icon
            :closable="false"
            class="gap-small"
            :title="`包内 order.json 单号（${preview.orderFileNo}）与票据单号（${preview.orderNo}）不一致，请与研发核对`"
          />

          <div class="confirm-row">
            <el-button
              type="success"
              :loading="importing"
              :disabled="preview.quotaExceeded && !ackQuota"
              @click="doImport"
            >
              确认导入
            </el-button>
            <el-button @click="resetImport">取消</el-button>
          </div>
        </div>

        <!-- 导入结果卡 -->
        <el-alert
          v-if="importResult"
          type="success"
          show-icon
          :closable="false"
          class="gap-small"
          title="授权已生效"
          :description="importResult"
        />
        <el-alert
          v-if="importError"
          type="error"
          show-icon
          :closable="false"
          class="gap-small"
          :title="`导入未成功（${importErrorCode || '未知原因'}）`"
          :description="importError"
        />

        <div class="hint gap-small">
          预览与导入都不修改现有授权，<b>验签失败原授权不受影响</b>；成功后会备份旧文件为
          <span class="mono">license.lic.bak-时间戳</span>，立即生效、无需重启。
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

      <!-- ⑤ 说明区 -->
      <el-card shadow="never" class="card gap">
        <template #header><span class="card-title">授权说明</span></template>
        <ul class="notes">
          <li>授权是<b>合规提示与到期提醒</b>工具：到期后默认（warn）只弹提示，<b>不影响产线功能</b>。</li>
          <li>到期策略由商务在签发时写进授权单，服务器配置无法更改；如需「到期停止设备上报」，请在商务沟通时明确。</li>
          <li>续期：把新的机器码包发回研发重签即可，相机数与期限按合同。</li>
          <li>换机 / 重装系统：机器码会变，需要重新走一遍四步流程。</li>
          <li>授权用于留痕与提醒，不构成防破解的技术手段。</li>
        </ul>
      </el-card>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../../stores/user'
import {
  getLicenseStatus,
  reloadLicense,
  uploadLicense,
  downloadMachineCodePackage,
  previewLicensePackage,
  importLicensePackage
} from '../../api/license'

const STATUS_MAP = {
  DISABLED: { type: 'info', text: '授权校验已关闭', icon: 'ℹ️' },
  MISSING: { type: 'warning', text: '未安装授权', icon: '⚠️' },
  INVALID: { type: 'danger', text: '授权无效', icon: '⛔' },
  MACHINE_MISMATCH: { type: 'danger', text: '授权与本机不符', icon: '⛔' },
  NOT_YET: { type: 'warning', text: '授权尚未生效', icon: '⏳' },
  TRIAL: { type: 'primary', text: '试用中', icon: '🧪' },
  VALID: { type: 'success', text: '授权正常', icon: '✅' },
  EXPIRING: { type: 'warning', text: '授权即将到期', icon: '⏰' },
  EXPIRED: { type: 'danger', text: '授权已过期', icon: '⛔' }
}

const userStore = useUserStore()
const isAdmin = computed(() => userStore.user?.role === 1 || userStore.user?.role === 'ADMIN')

const state = ref(null)
const loading = ref(false)
const loadError = ref('')
const downloading = ref(false)
const orderHint = ref('')
const selectedFile = ref(null)
const fileInput = ref(null)
const preview = ref(null)
const ackQuota = ref(false)
const importing = ref(false)
const importResult = ref('')
const importError = ref('')
const importErrorCode = ref('')

const statusMeta = computed(() => STATUS_MAP[state.value?.status] || { type: 'info', text: state.value?.status || '未知', icon: '❔' })

const modePlain = computed(() => {
  const m = state.value?.expiredMode
  if (m === 'readonly') return '到期后停止设备上报'
  if (m === 'block') return '到期后仅保留登录与授权页'
  return '到期后只弹提示，不影响功能'
})

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

const downloadPackage = async () => {
  downloading.value = true
  try {
    const res = await downloadMachineCodePackage(orderHint.value?.trim() || null)
    const blob = res?.data
    if (!(blob instanceof Blob)) {
      throw new Error('响应格式异常')
    }
    if (blob.type && blob.type.includes('application/json')) {
      // 后端 400：blob 里是错误 JSON
      const text = await blob.text()
      let msg = '下载失败'
      try { msg = JSON.parse(text)?.message || msg } catch (e) { /* 保留默认 */ }
      ElMessage.error(msg)
      return
    }
    // 从 Content-Disposition 取文件名；取不到就用机器码拼
    let filename = ''
    const cd = res?.headers?.['content-disposition'] || ''
    const m = cd.match(/filename="?([^"]+)"?/)
    if (m) filename = decodeURIComponent(m[1])
    if (!filename) filename = `machine-${state.value?.machineCode || 'unknown'}-${Date.now()}.zip`
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
    ElMessage.success('机器码包已下载，请把整个 zip 发给研发')
  } catch (e) {
    // 400 时响应体是 blob 包着的 JSON，解出可读错误
    let msg = e?.message || '下载失败'
    try {
      const raw = e?.response?.data
      if (raw instanceof Blob) {
        msg = JSON.parse(await raw.text())?.message || msg
      } else if (raw?.message) {
        msg = raw.message
      }
    } catch (e2) { /* 保留默认消息 */ }
    ElMessage.error(msg)
  } finally {
    downloading.value = false
  }
}

const pickFile = () => {
  importResult.value = ''
  importError.value = ''
  fileInput.value?.click()
}

const onFileChange = (e) => {
  const f = e.target.files?.[0]
  selectedFile.value = f || null
  preview.value = null
  ackQuota.value = false
  if (f) doPreview(f)
}

const doPreview = async (f) => {
  importError.value = ''
  importErrorCode.value = ''
  try {
    const res = await previewLicensePackage(f)
    const body = res?.data
    if (body && body.code === 200 && body.data) {
      preview.value = body.data
      if (body.data.quotaExceeded) ackQuota.value = false
    } else {
      ElMessage.error(body?.message || '预览失败')
      selectedFile.value = null
    }
  } catch (e) {
    // 错误码在 errorCode 字段（LIC-*），消息可直接展示
    ElMessage.error(e?.response?.data?.message || e?.message || '预览失败')
    selectedFile.value = null
  }
}

const resetImport = () => {
  selectedFile.value = null
  preview.value = null
  ackQuota.value = false
  if (fileInput.value) fileInput.value.value = ''
}

const doImport = async () => {
  if (!selectedFile.value || !preview.value) return
  importing.value = true
  try {
    const res = await importLicensePackage(
      selectedFile.value, preview.value.previewToken, ackQuota.value)
    const body = res?.data
    if (body && body.code === 200) {
      importResult.value = [
        body.data?.licenseId ? `单号 ${body.data.licenseId}` : '',
        body.data?.expireAt ? `到期 ${body.data.expireAt}` : '',
        body.data?.maxCameras != null
          ? `相机上限 ${body.data.maxCameras === -1 ? '不限' : body.data.maxCameras + ' 台'}` : '',
        body.data?.expiredMode ? `策略 ${body.data.expiredMode}` : ''
      ].filter(Boolean).join(' ｜ ')
      importError.value = ''
      resetImport()
      await loadStatus()
      ElMessage.success('授权已生效')
    } else {
      importErrorCode.value = body?.errorCode || ''
      importError.value = body?.message || '导入失败（原授权不受影响，仍按原状态运行）'
    }
  } catch (e) {
    importErrorCode.value = e?.response?.data?.errorCode || ''
    importError.value = e?.response?.data?.message || e?.message || '导入失败（原授权不受影响，仍按原状态运行）'
  } finally {
    importing.value = false
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
.status-desc.subtle { margin-top: 2px; color: var(--el-text-color-secondary); }
.status-right { flex-shrink: 0; }

.grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
@media (max-width: 1000px) { .grid { grid-template-columns: 1fr; } }
.card-title { font-weight: 600; }
.gap { margin-top: 16px; }
.gap-small { margin-top: 10px; }

.machine-code { padding: 4px 0; }
.mc-label { font-size: 12px; color: var(--el-text-color-secondary); margin-bottom: 6px; }
.mc-value { display: flex; align-items: center; gap: 10px; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }
.mono.big { font-size: 22px; letter-spacing: 1px; font-weight: 600; }
.mono.big.is-null { color: var(--el-text-color-disabled); }
.mono.small { font-size: 12px; }
.hint { font-size: 12px; color: var(--el-text-color-secondary); line-height: 1.7; }
.warn-text { color: var(--el-color-warning); margin-top: 8px; }
.hint-alert { margin-top: 4px; }

.dl-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin: 8px 0; }
.order-input { width: 260px; }

.upload-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 10px; }
.hidden-input { display: none; }
.preview-card { border: 1px solid var(--el-border-color-light); border-radius: 6px; padding: 12px; }
.preview-title { font-weight: 600; margin-bottom: 10px; }
.confirm-row { margin-top: 12px; display: flex; gap: 8px; }

.notes { margin: 0; padding-left: 18px; font-size: 13px; line-height: 2; color: var(--el-text-color-regular); }
</style>
