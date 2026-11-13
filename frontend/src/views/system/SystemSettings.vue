<template>
  <div class="settings-center">
    <div class="settings-hero">
      <div>
        <p class="eyebrow">SYSTEM SETTINGS CENTER</p>
        <h3>系统设置中心</h3>
        <p>集中维护运行展示、相机在线阈值、上传限制、告警阈值和数据保留策略。保存前请确认现场规则，保存后立即写入数据库。</p>
      </div>
      <div class="settings-actions">
        <el-button @click="loadSettings" :loading="loading">刷新</el-button>
        <el-button type="primary" @click="handleSave" :loading="saving">保存设置</el-button>
      </div>
    </div>

    <div class="settings-kpis">
      <div class="settings-kpi"><span>设置项</span><strong>{{ summary.settingCount || settings.length }}</strong></div>
      <div class="settings-kpi"><span>可编辑项</span><strong>{{ summary.editableCount || editableCount }}</strong></div>
      <div class="settings-kpi"><span>保留策略</span><strong>{{ summary.policyCount || retentionPolicies.length }}</strong></div>
      <div class="settings-kpi"><span>启用策略</span><strong>{{ summary.enabledPolicyCount || enabledPolicyCount }}</strong></div>
    </div>

    <el-alert
      class="settings-alert"
      type="warning"
      show-icon
      :closable="false"
      title="修改阈值会影响页面状态和运维策略；上传目录、日志目录等安装级路径仍以外部 application.yml 为准。"
    />

    <div class="settings-grid" v-loading="loading">
      <section v-for="group in visibleGroups" :key="group.key" class="settings-card">
        <div class="card-head">
          <span class="card-icon">{{ group.icon }}</span>
          <div>
            <h4>{{ group.title }}</h4>
            <p>{{ group.desc }}</p>
          </div>
        </div>
        <div class="setting-list">
          <div v-for="item in group.items" :key="item.settingKey" class="setting-row">
            <div class="setting-label">
              <b>{{ item.settingName }}</b>
              <small>{{ item.description }}</small>
            </div>
            <div class="setting-control">
              <el-input-number
                v-if="isNumberType(item)"
                v-model="item.editValue"
                :min="0"
                :precision="item.valueType === 'DECIMAL' ? 1 : 0"
                controls-position="right"
                style="width: 150px"
                :disabled="Number(item.isEditable) !== 1"
              />
              <el-input
                v-else
                v-model="item.editValue"
                clearable
                :disabled="Number(item.isEditable) !== 1"
              />
              <em v-if="item.unit">{{ item.unit }}</em>
            </div>
          </div>
        </div>
      </section>
    </div>

    <section class="retention-card">
      <div class="card-head retention-head">
        <span class="card-icon">🧹</span>
        <div>
          <h4>数据保留与清理策略</h4>
          <p>用于缺陷图片、缺陷框、检测明细、聚合数据、操作日志的保留周期控制。生产环境建议先备份再清理。</p>
          <p class="retention-warn">
            ⚠️ 此表仅记录运维意向，<strong>当前不会驱动任何清理任务</strong>——改动这里不会改变实际清理行为。
            真实生效值见《配置项清单》§五：图片日期目录 730 天（<code>cleanup-defect-images.sh</code>）、
            主表转月表 31 天（<code>app.archive.images.hot-days</code>）、<code>camera_images</code> 30 天与操作日志 6 个月（<code>DataCleanupTask</code> 常量）。
          </p>
        </div>
      </div>
      <el-table :data="retentionPolicies" border stripe class="settings-table">
        <el-table-column prop="dataType" label="数据类型" min-width="180">
          <template #default="{ row }"><code>{{ row.dataType }}</code></template>
        </el-table-column>
        <el-table-column label="保留天数" width="160" align="center">
          <template #default="{ row }">
            <el-input-number v-model="row.retentionDays" :min="1" :max="3650" controls-position="right" style="width: 130px" />
          </template>
        </el-table-column>
        <el-table-column label="动作" width="150" align="center">
          <template #default="{ row }">
            <el-select v-model="row.actionType" style="width: 120px">
              <el-option label="删除" value="DELETE" />
              <el-option label="归档" value="ARCHIVE" />
              <el-option label="保留" value="KEEP" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="110" align="center">
          <template #default="{ row }"><el-switch v-model="row.enabledBool" /></template>
        </el-table-column>
        <el-table-column label="更新时间" width="180"><template #default="{ row }">{{ row.updateTime || '-' }}</template></el-table-column>
      </el-table>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from '@/utils/message'
import { getSystemSettings, saveSystemSettings } from '@/api/system-settings'

const loading = ref(false)
const saving = ref(false)
const settings = ref([])
const retentionPolicies = ref([])
const summary = ref({})

const groupMeta = {
  BASIC: { icon: '🏷️', title: '基础展示', desc: '系统名称、站点名称等展示类配置。' },
  CAMERA: { icon: '📷', title: '相机状态阈值', desc: '控制页面在线、延迟、离线判定口径。' },
  UPLOAD: { icon: '🖼️', title: '图片上传限制', desc: '控制设备上传缺陷图片的数量和大小提示。' },
  ALERT: { icon: '🚨', title: '资源告警阈值', desc: '控制 CPU、内存、温度等告警阈值。' },
  ANALYTICS: { icon: '📊', title: '统计聚合', desc: '统计、聚合和看板相关运行配置。' },
  OTHER: { icon: '⚙️', title: '其他设置', desc: '未归类的系统参数。' }
}

const editableCount = computed(() => settings.value.filter(i => Number(i.isEditable) === 1).length)
const enabledPolicyCount = computed(() => retentionPolicies.value.filter(i => i.enabledBool).length)

const visibleGroups = computed(() => {
  const grouped = {}
  settings.value.forEach(item => {
    const key = item.settingGroup || 'OTHER'
    if (!grouped[key]) grouped[key] = []
    grouped[key].push(item)
  })
  return Object.keys(grouped).sort().map(key => ({ key, ...(groupMeta[key] || groupMeta.OTHER), items: grouped[key] }))
})

const isNumberType = (item) => ['INTEGER', 'DECIMAL'].includes(String(item.valueType || '').toUpperCase())

const normalizeSetting = (item = {}) => ({
  ...item,
  editValue: isNumberType(item) ? Number(item.settingValue || 0) : String(item.settingValue ?? '')
})

const normalizePolicy = (item = {}) => ({
  ...item,
  retentionDays: Number(item.retentionDays || 1),
  actionType: item.actionType || 'DELETE',
  enabledBool: Number(item.isEnabled) === 1
})

const loadSettings = async () => {
  loading.value = true
  try {
    const res = await getSystemSettings()
    const data = res.data?.data || res.data || {}
    settings.value = (data.settings || []).map(normalizeSetting)
    retentionPolicies.value = (data.retentionPolicies || []).map(normalizePolicy)
    summary.value = data.summary || {}
  } catch (e) {
    ElMessage.error('系统设置加载失败：' + (e.response?.data?.message || e.message || e))
  } finally {
    loading.value = false
  }
}

const handleSave = async () => {
  try {
    await ElMessageBox.confirm(
      '确认保存系统设置？阈值和保留策略会影响页面判定、数据清理和运维巡检，请确认已和现场口径一致。',
      '保存系统设置',
      { confirmButtonText: '确认保存', cancelButtonText: '取消', type: 'warning' }
    )
    saving.value = true
    const payload = {
      settings: settings.value.map(item => ({
        settingKey: item.settingKey,
        settingValue: String(item.editValue ?? '')
      })),
      retentionPolicies: retentionPolicies.value.map(item => ({
        dataType: item.dataType,
        retentionDays: Number(item.retentionDays || 1),
        actionType: item.actionType,
        isEnabled: item.enabledBool ? 1 : 0
      }))
    }
    const res = await saveSystemSettings(payload)
    const data = res.data?.data || {}
    ElMessage.success(`保存成功：设置 ${data.settingUpdated || 0} 项，策略 ${data.policyUpdated || 0} 项`)
    await loadSettings()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('保存失败：' + (e.response?.data?.message || e.message || e))
  } finally {
    saving.value = false
  }
}

onMounted(loadSettings)
</script>

<style scoped>
.settings-center { display: flex; flex-direction: column; gap: 16px; }
.settings-hero { display: flex; align-items: center; justify-content: space-between; gap: 18px; padding: 18px 20px; border: 1px solid #dbeafe; border-radius: 18px; background: linear-gradient(135deg, var(--color-primary-bg) 0%, #fff 70%); }
.settings-hero .eyebrow { margin: 0 0 6px; font-size: 12px; letter-spacing: .14em; color: var(--color-primary); font-weight: 900; }
.settings-hero h3 { margin: 0 0 8px; font-size: 22px; color: var(--color-text); }
.settings-hero p { margin: 0; color: var(--color-text-secondary); font-size: 13px; line-height: 1.7; }
.settings-actions { display: flex; gap: 8px; flex-shrink: 0; }
.settings-kpis { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.settings-kpi { padding: 14px; border-radius: 16px; border: 1px solid var(--color-border); background: var(--color-bg-card); box-shadow: 0 1px 4px rgba(15,23,42,.04); }
.settings-kpi span { display: block; color: var(--color-text-secondary); font-size: 12px; margin-bottom: 6px; }
.settings-kpi strong { color: var(--color-primary-active); font-size: 24px; }
.settings-alert { border-radius: 12px; }
.settings-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.settings-card, .retention-card { border: 1px solid var(--color-border); border-radius: 18px; background: var(--color-bg-card); box-shadow: 0 1px 4px rgba(15,23,42,.04); padding: 16px; }
.card-head { display: flex; align-items: flex-start; gap: 12px; margin-bottom: 14px; }
.card-icon { width: 38px; height: 38px; display: inline-flex; align-items: center; justify-content: center; border-radius: 14px; background: var(--color-primary-bg); font-size: 20px; }
.card-head h4 { margin: 0 0 5px; color: var(--color-text); font-size: 16px; }
.card-head p { margin: 0; color: var(--color-text-secondary); font-size: 12px; line-height: 1.6; }
.setting-list { display: flex; flex-direction: column; gap: 10px; }
.setting-row { display: grid; grid-template-columns: minmax(0, 1fr) 220px; align-items: center; gap: 12px; padding: 12px; border-radius: 14px; background: var(--color-bg-page); border: 1px solid #eef2f7; }
.setting-label b { display: block; color: var(--color-text); font-size: 13px; margin-bottom: 4px; }
.setting-label small { color: var(--color-text-secondary); font-size: 12px; line-height: 1.5; }
.setting-control { display: flex; align-items: center; gap: 8px; }
.setting-control em { font-style: normal; color: var(--color-text-secondary); font-size: 12px; min-width: 24px; }
.retention-head { margin-bottom: 16px; }
/* 死配置提示：这张表不驱动清理（见 配置项清单 §五），必须让运维一眼看到 */
.retention-warn { margin-top: 6px; padding: 8px 10px; border-left: 3px solid var(--color-warning); background: #fffbeb; color: #92400e; font-size: 12.5px; line-height: 1.6; border-radius: 0 6px 6px 0; }
.retention-warn code { background: rgba(146,64,14,.08); padding: 0 3px; border-radius: 3px; }
.settings-table code { background: var(--color-bg-subtle); padding: 2px 7px; border-radius: 6px; color: var(--color-primary); }
@media (max-width: 1000px) {
  .settings-hero { flex-direction: column; align-items: flex-start; }
  .settings-kpis, .settings-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 720px) {
  .settings-kpis, .settings-grid { grid-template-columns: 1fr; }
  .setting-row { grid-template-columns: 1fr; }
}
</style>
