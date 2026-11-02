<template>
  <div class="app">
    <div class="topbar-sticky">
      <header class="hdr">
      <div class="hdr-left">
        <span class="hdr-logo">📷 产线缺陷在线AI检测系统</span>
        <span class="shift-badge" :class="shiftClass">{{ shiftLabel }} {{ shiftTime }}</span>
      </div>
      <div class="hdr-center">
        <div class="hdr-select">
          <span class="hs-label">工厂</span>
          <el-select v-model="selectedFactory" size="default" placeholder="选择工厂" style="width:200px" @change="onFactoryChange">
            <el-option v-for="f in factories" :key="f.value" :label="f.label" :value="f.value" />
          </el-select>
        </div>
        <div class="hdr-select">
          <span class="hs-label">产线 <span v-if="selectedLines.length" class="badge">{{ selectedLines.length }}</span></span>
          <el-select v-model="selectedLines" multiple collapse-tags collapse-tags-tooltip size="default" placeholder="全部产线" style="width:240px" @change="onLineChange">
            <el-option v-for="l in availableLines" :key="l.value" :label="l.label" :value="l.value" />
          </el-select>
        </div>
        <div class="hdr-select">
          <span class="hs-label">工位 <span v-if="selectedStations.length" class="badge">{{ selectedStations.length }}</span></span>
          <el-select v-model="selectedStations" multiple collapse-tags collapse-tags-tooltip size="default" placeholder="全部工位" style="width:240px">
            <el-option v-for="s in availableStations" :key="s.value" :label="s.label" :value="s.value" />
          </el-select>
        </div>
        <el-button class="cascade-refresh" :loading="cascadeLoading" :icon="Refresh" @click="loadCascade(true)">
          刷新配置
        </el-button>
      </div>
      <div class="hdr-right">
        <router-link v-if="isAdmin" to="/system/config" class="syscfg-btn">
          <el-icon><Setting /></el-icon> 系统配置
        </router-link>
        <el-dropdown @command="handleUserCmd">
          <span class="user-trigger">
            👤 <span class="user-role">{{ userRoleLabel }}</span>
            <span class="user-sep">·</span>
            <span class="user-name">{{ loginUserName }}</span> ▾
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
      </header>

      <nav class="tb">
      <router-link to="/camera-status" class="tb-item" active-class="active">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M15 10l4.5-2.5v9L15 14"/><rect x="3" y="6" width="12" height="12" rx="2"/><circle cx="8" cy="12" r="1.5"/></svg>
        相机状态
      </router-link>
      <router-link to="/stats/images" class="tb-item" active-class="active">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/></svg>
        图像记录
      </router-link>
      <router-link to="/line-analytics" class="tb-item" active-class="active">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 3v18h18"/><rect x="7" y="12" width="3" height="5" rx="1"/><rect x="12" y="8" width="3" height="9" rx="1"/><rect x="17" y="5" width="3" height="12" rx="1"/></svg>
        产线统计
      </router-link>
      <!-- 服务器监控（2026-11-02 新增）：所有登录用户可见，故不加 v-if="isAdmin" -->
      <router-link to="/server-monitor" class="tb-item" active-class="active">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="3" width="20" height="14" rx="2"/><path d="M8 21h8M12 17v4"/><path d="M6 11l3-3 2 2 4-4 3 3"/></svg>
        服务器监控
      </router-link>
      </nav>
    </div>

    <main class="cnt">
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { ref, computed, provide, onBeforeUnmount, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'
import { Refresh, Setting } from '@element-plus/icons-vue'
import { getCascadeAll } from '../api/cascade'

const router = useRouter()
const userStore = useUserStore()

const factories = ref([])
const allLines = ref([])
const allStations = ref([])
const cascadeLoading = ref(false)

const selectedFactory = ref('ALL')
const selectedLines = ref([])
const selectedStations = ref([])

const stationLabel = (s = {}) => {
  if (s.stationLabel) return s.stationLabel
  const code = String(s.stationCode || '').trim()
  const name = String(s.stationName || s.workstationName || '').trim()
  if (code && name) {
    const lowerCode = code.toLowerCase()
    const lowerName = name.toLowerCase()
    if (lowerName === lowerCode || lowerName.startsWith(lowerCode) || lowerName.startsWith(lowerCode + '-') || lowerName.startsWith(lowerCode + '_') || lowerName.startsWith(lowerCode + '/')) return name
    return `${code} ${name}`
  }
  return name || code || '未命名工位'
}

const loadCascade = async (keepSelection = false) => {
  cascadeLoading.value = true
  try {
    const res = await getCascadeAll()
    const data = res.data?.data || res.data || {}
    const tree = data.factories || []
    factories.value = [{ value: 'ALL', label: '全部工厂' }, ...tree.map(f => ({ value: f.factoryId, label: f.factoryName }))]
    allLines.value = tree.flatMap(f =>
      (f.lines || []).map(l => ({ value: l.lineId, label: l.lineName, factoryId: f.factoryId }))
    )
    allStations.value = tree.flatMap(f =>
      (f.lines || []).flatMap(l =>
        (l.stations || []).map(s => ({ value: s.stationId || s.workstationId, label: stationLabel(s), lineId: l.lineId }))
      )
    )
    if (selectedFactory.value !== 'ALL' && !factories.value.some(f => f.value === selectedFactory.value)) {
      selectedFactory.value = 'ALL'
    }
    if (!keepSelection) {
      selectedLines.value = selectedLines.value.filter(id => allLines.value.some(l => l.value === id))
      selectedStations.value = selectedStations.value.filter(id => allStations.value.some(s => s.value === id))
    }
  } catch (e) {
    console.warn('加载级联数据失败，使用空列表', e)
  } finally {
    cascadeLoading.value = false
  }
}

const onConfigCascadeRefresh = () => loadCascade(true)

onMounted(() => {
  loadCascade(true)
  window.addEventListener('config-cascade-refresh', onConfigCascadeRefresh)
})

onBeforeUnmount(() => {
  window.removeEventListener('config-cascade-refresh', onConfigCascadeRefresh)
})

const availableLines = computed(() => selectedFactory.value === 'ALL' ? allLines.value : allLines.value.filter(l => l.factoryId === selectedFactory.value))
const availableStations = computed(() => {
  if (selectedLines.value.length === 0) {
    const lids = availableLines.value.map(l => l.value)
    return allStations.value.filter(s => lids.includes(s.lineId))
  }
  return allStations.value.filter(s => selectedLines.value.includes(s.lineId))
})

const onFactoryChange = () => {
  selectedLines.value = []
  selectedStations.value = []
}
const onLineChange = (vals) => {
  if (!vals || vals.length === 0) {
    selectedStations.value = []
  } else {
    selectedStations.value = selectedStations.value.filter(sid => {
      const st = allStations.value.find(x => x.value === sid)
      return st && vals.includes(st.lineId)
    })
  }
}

provide('filterParams', computed(() => ({
  factoryId: selectedFactory.value === 'ALL' ? null : selectedFactory.value,
  lineIds: selectedLines.value,
  stationIds: selectedStations.value,
})))

provide('filterLabels', computed(() => ({
  factoryName: factories.value.find(f => f.value === selectedFactory.value)?.label || '',
  lineNames: allLines.value.filter(l => selectedLines.value.includes(l.value)).map(l => l.label),
  stationNames: allStations.value.filter(s => selectedStations.value.includes(s.value)).map(s => s.label),
})))

const handleUserCmd = (cmd) => {
  if (cmd === 'logout') {
    userStore.logout()
    router.push('/login')
  }
}

const loginUserName = computed(() => userStore.user?.username || userStore.userName || '用户')
const isAdmin = computed(() => userStore.user?.role === 1 || userStore.user?.role === 'ADMIN')
const userRoleLabel = computed(() => isAdmin.value ? '系统管理员' : '普通用户')

const shiftLabel = computed(() => {
  const h = new Date().getHours()
  return (h >= 8 && h < 20) ? '白班' : '夜班'
})
const shiftTime = computed(() => {
  const h = new Date().getHours()
  return (h >= 8 && h < 20) ? '08:00-20:00' : '20:00-08:00'
})
const shiftClass = computed(() => {
  const h = new Date().getHours()
  return (h >= 8 && h < 20) ? 'shift-day' : 'shift-night'
})
</script>

<style scoped>
.topbar-sticky { position: sticky; top: 0; z-index: 200; background: #fff; box-shadow: 0 2px 10px rgba(15,23,42,.06); }
.hdr { height: 52px; background: #fff; border-bottom: 1px solid #e5e5e5; display: flex; align-items: center; padding: 0 20px; gap: 16px; }
.hdr-left { display: flex; align-items: center; gap: 12px; }
.hdr-logo { font-size: 17px; font-weight: 700; color: #262626; }
.shift-badge { font-size: 12px; padding: 3px 14px; border-radius: 12px; font-weight: 600; }
.shift-day { background: #fef3c7; color: #92400e; }
.shift-night { background: #e0e7ff; color: #3730a3; }
.hdr-center { display: flex; gap: 16px; flex: 1; }
.hdr-select { display: flex; align-items: center; gap: 8px; }
.hs-label { font-size: 13px; color: #525252; font-weight: 600; white-space: nowrap; }
.badge { display: inline-block; background: #3b82f6; color: #fff; font-size: 10px; padding: 1px 6px; border-radius: 8px; margin-left: 4px; }
.hdr-right { display: flex; align-items: center; gap: 12px; }
.syscfg-btn { display: flex; align-items: center; gap: 6px; background: #3b82f6; color: #fff; padding: 7px 16px; border-radius: 6px; font-size: 13px; font-weight: 600; text-decoration: none; transition: background .2s; }
.syscfg-btn:hover { background: #1d4ed8; }
.user-trigger { cursor: pointer; font-size: 13px; color: #525252; display: inline-flex; align-items: center; gap: 4px; }
.user-role { font-weight: 700; color: #1d4ed8; }
.user-name { font-weight: 600; color: #334155; }
.user-sep { color: #cbd5e1; }
.tb { background: #fff; border-bottom: 1px solid #e5e5e5; display: flex; padding: 0 20px; overflow-x: auto; }
.tb-item { display: flex; align-items: center; gap: 8px; padding: 14px 28px; font-size: 15px; color: #737373; text-decoration: none; border-bottom: 3px solid transparent; transition: all .2s; white-space: nowrap; }
.tb-item:hover { color: #2563eb; background: #f8fafc; }
.tb-item.active { color: #2563eb; border-bottom-color: #2563eb; font-weight: 600; }
.cnt { max-width: 1440px; margin: 0 auto; padding: 24px 20px; }
@media (max-width: 1280px) {
  .hdr { height: auto; align-items: flex-start; flex-direction: column; padding: 12px 16px; }
  .hdr-center { flex-wrap: wrap; }
  .cnt { padding: 16px 12px; }
}
</style>
