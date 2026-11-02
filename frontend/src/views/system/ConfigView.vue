<template>
  <div class="sys-config">
    <div class="config-layout">
      <aside class="config-sidebar">
        <div class="sidebar-head">
          <div class="sidebar-mark">层</div>
          <div>
            <div class="sidebar-title">配置层级</div>
            <div class="sidebar-desc">选择节点后，右侧维护对应下一级资料</div>
          </div>
        </div>
        <div class="hierarchy-guide">
          <div class="guide-title">推荐维护顺序</div>
          <div class="guide-flow" aria-label="工厂到相机的配置层级">
            <span>工厂</span>
            <i>→</i>
            <span>产线</span>
            <i>→</i>
            <span>工位</span>
            <i>→</i>
            <span>相机</span>
          </div>
          <p>先维护上级，再绑定下级，避免相机无归属或数据无法筛选。</p>
        </div>
        <div class="tree-tools">
          <button type="button" @click="expandConfigTree(true)">展开层级</button>
          <button type="button" @click="expandConfigTree(false)">收起层级</button>
        </div>
        <el-tree
          ref="configTreeRef"
          class="config-tree"
          :data="configTreeData"
          :props="treeProps"
          node-key="key"
          :default-expand-all="false"
          highlight-current
          :expand-on-click-node="false"
          @node-click="handleTreeSelect"
        >
          <template #default="{ data }">
            <span class="tree-node" :class="`tree-node-${data.type}`">
              <span class="tree-icon">{{ treeIcon(data.type) }}</span>
              <span class="tree-text">
                <span class="tree-label">{{ data.label }}</span>
                <span class="tree-meta">{{ treeTypeLabel(data.type) }}</span>
              </span>
              <span v-if="data.count != null" class="tree-count">{{ data.count }}</span>
            </span>
          </template>
        </el-tree>
        <nav class="sidebar-nav sidebar-tools">
          <button
            v-for="item in navItems"
            :key="item.key"
            class="nav-item"
            :class="{ active: activeMenu === item.key }"
            type="button"
            @click="switchMenu(item.key)"
          >
            <span class="nav-icon">{{ item.icon }}</span>
            <span class="nav-label">{{ item.label }}</span>
          </button>
        </nav>
      </aside>

      <section class="config-content">
        <div class="content-header">
          <el-button type="primary" @click="router.push('/camera-status')" class="back-btn">← 返回看板</el-button>
          <div class="header-copy">
            <h2>{{ contextTitle }}</h2>
            <p>{{ contextDesc }}</p>
          </div>
          <div class="header-actions">
            <el-button type="success" @click="openImportDialog">整体导入配置</el-button>
            <el-button @click="downloadImportTemplate">下载模板</el-button>
          </div>
        </div>

        <OperationLog v-if="activeMenu === 'operationLog'" />
        <div v-else class="content-panel">
          <div v-if="isConfigContext" class="delivery-summary">
            <div class="summary-card"><span>工厂</span><strong>{{ factoryList.length }}</strong></div>
            <div class="summary-card"><span>产线</span><strong>{{ lineList.length }}</strong></div>
            <div class="summary-card"><span>工位</span><strong>{{ stationList.length }}</strong></div>
            <div class="summary-card"><span>相机</span><strong>{{ cameraList.length }}</strong></div>
            <div class="summary-card warn"><span>有产线无工位</span><strong>{{ linesWithoutStations }}</strong></div>
            <div class="summary-card warn"><span>有工位无相机</span><strong>{{ stationsWithoutCameras }}</strong></div>
            <div class="summary-card danger"><span>相机未绑定工位</span><strong>{{ camerasWithoutStation }}</strong></div>
          </div>
          <div class="panel-toolbar">
            <el-button v-if="addTargetType" type="primary" size="default" @click="openDialog(addTargetType)">
              <span class="plus">+</span> 新增{{ typeShortLabel(addTargetType) }}
            </el-button>
            <el-button v-else-if="selectedTreeNode.type === 'camera'" type="primary" plain size="default" @click="editRow('camera', selectedTreeNode.raw)">编辑相机</el-button>
            <span class="toolbar-count">共 {{ currentTotal }} 条</span>
            <span class="toolbar-tip">每页 {{ pageSize }} 条，可选 10 / 20 / 50 / 100</span>
          </div>

          <el-table
            :data="pagedRows"
            stripe
            border
            class="cfg-table"
            v-loading="listLoading"
            :header-cell-style="{ background: '#f8fafc', color: '#475569', fontWeight: 700 }"
          >
            <el-table-column type="index" label="#" width="56" :index="tableIndex" />

            <template v-if="activeMenu === 'factory'">
              <el-table-column prop="factoryName" label="工厂名称" min-width="160" />
              <el-table-column prop="factoryCode" label="工厂编码" width="130"><template #default="{ row }"><code>{{ row.factoryCode || '-' }}</code></template></el-table-column>
              <el-table-column prop="address" label="地址" min-width="200" show-overflow-tooltip />
              <el-table-column label="产线数" width="90" align="center"><template #default="{ row }">{{ lineCountByFactory[row.factoryId] || 0 }}</template></el-table-column>
              <el-table-column label="工位数" width="90" align="center"><template #default="{ row }">{{ stationCountByFactory[row.factoryId] || 0 }}</template></el-table-column>
              <el-table-column label="相机数" width="90" align="center"><template #default="{ row }">{{ cameraCountByFactory[row.factoryId] || 0 }}</template></el-table-column>
            </template>

            <template v-else-if="activeMenu === 'line'">
              <el-table-column prop="lineName" label="产线名称" min-width="160" />
              <el-table-column prop="lineCode" label="产线编码" width="130"><template #default="{ row }"><code>{{ row.lineCode || '-' }}</code></template></el-table-column>
              <el-table-column label="所属工厂" min-width="140"><template #default="{ row }">{{ factoryMap[row.factoryId] || '-' }}</template></el-table-column>
              <el-table-column label="工位数" width="90" align="center"><template #default="{ row }">{{ stationCountByLine[row.lineId] || row.stationCount || 0 }}</template></el-table-column>
              <el-table-column label="相机数" width="90" align="center"><template #default="{ row }">{{ cameraCountByLine[row.lineId] || 0 }}</template></el-table-column>
              <el-table-column prop="description" label="描述" min-width="160" show-overflow-tooltip />
            </template>

            <template v-else-if="activeMenu === 'workstation'">
              <el-table-column label="工位名称" min-width="170"><template #default="{ row }">{{ row.stationName }}</template></el-table-column>
              <el-table-column prop="stationCode" label="工位编码" width="130"><template #default="{ row }"><code>{{ row.stationCode || '-' }}</code></template></el-table-column>
              <el-table-column label="所属产线" min-width="160"><template #default="{ row }">{{ lineMap[row.lineId] || '-' }}</template></el-table-column>
              <el-table-column label="相机数" width="90" align="center"><template #default="{ row }">{{ cameraCountByStation[row.workstationId] || 0 }}</template></el-table-column>
              <el-table-column prop="description" label="描述" min-width="180" show-overflow-tooltip />
            </template>

            <template v-else-if="activeMenu === 'camera'">
              <el-table-column prop="cameraName" label="相机名称" min-width="140" />
              <el-table-column prop="cameraCode" label="设备编号" width="130"><template #default="{ row }"><code>{{ row.cameraCode || row.deviceId || '-' }}</code></template></el-table-column>
              <el-table-column label="所属工厂" min-width="140"><template #default="{ row }">{{ factoryNameByCamera(row) }}</template></el-table-column>
              <el-table-column label="所属产线" min-width="150"><template #default="{ row }">{{ lineMap[row.lineId] || '-' }}</template></el-table-column>
              <el-table-column label="工位名称" min-width="150"><template #default="{ row }">{{ stationMap[row.workstationId] || row.stationName || '-' }}</template></el-table-column>
            </template>

            <template v-else>
              <el-table-column prop="username" label="用户名" min-width="130" />
              <el-table-column prop="realName" label="姓名" min-width="120" />
              <el-table-column label="角色" width="110" align="center"><template #default="{ row }"><StatusBadge :text="row.role" /></template></el-table-column>
              <el-table-column prop="phone" label="手机号" width="140" />
              <el-table-column prop="lastLogin" label="最后登录" width="170" />
            </template>

            <el-table-column label="操作" width="160" fixed="right" align="center">
              <template #default="{ row }">
                <div class="action-group">
                  <el-button size="small" class="action-btn edit" @click="editRow(activeMenu, row)">编辑</el-button>
                  <el-button size="small" class="action-btn del" @click="deleteRow(activeMenu, row)">删除</el-button>
                </div>
              </template>
            </el-table-column>
          </el-table>

          <div class="pager-row">
            <span>当前第 {{ currentPage }} 页 / 共 {{ Math.max(1, Math.ceil(currentTotal / pageSize)) }} 页</span>
            <BasePagination
              v-model:current-page="pages[activeMenu]"
              v-model:page-size="pageSize"
              :page-sizes="pageSizeOptions"
              :total="currentTotal"
              small
              @size-change="handlePageSizeChange"
              @current-change="loadCurrentPage"
            />
          </div>
        </div>
      </section>
    </div>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="520px" :close-on-click-modal="false">
      <el-form :model="formData" label-width="94px" label-position="left">
        <template v-if="dialogType === 'factory'">
          <el-form-item label="工厂名称"><el-input v-model="formData.name" placeholder="请输入工厂名称" /></el-form-item>
          <el-form-item label="工厂编码"><el-input v-model="formData.code" placeholder="请输入工厂编码" /></el-form-item>
          <el-form-item label="地址"><el-input v-model="formData.address" placeholder="请输入地址" /></el-form-item>
        </template>

        <template v-else-if="dialogType === 'line'">
          <el-form-item label="所属工厂"><el-select v-model="formData.factoryId" style="width:100%" :disabled="Boolean(parentLocked.factory)"><el-option v-for="f in factoryList" :key="f.factoryId" :label="f.factoryName" :value="f.factoryId" /></el-select></el-form-item>
          <el-form-item label="产线名称"><el-input v-model="formData.name" placeholder="请输入产线名称" /></el-form-item>
          <el-form-item label="产线编码"><el-input v-model="formData.code" placeholder="请输入产线编码" /></el-form-item>
          <el-form-item label="描述"><el-input v-model="formData.description" type="textarea" :rows="2" placeholder="请输入描述" /></el-form-item>
        </template>

        <template v-else-if="dialogType === 'workstation'">
          <el-form-item label="所属产线"><el-select v-model="formData.lineId" style="width:100%" :disabled="Boolean(parentLocked.line)"><el-option v-for="l in lineList" :key="l.lineId" :label="l.lineName" :value="l.lineId" /></el-select></el-form-item>
          <el-form-item label="工位名称"><el-input v-model="formData.name" placeholder="请输入工位名称" /></el-form-item>
          <el-form-item label="工位编码"><el-input v-model="formData.code" placeholder="请输入工位编码" /></el-form-item>
          <el-form-item label="描述"><el-input v-model="formData.description" type="textarea" :rows="2" placeholder="请输入描述" /></el-form-item>
        </template>

        <template v-else-if="dialogType === 'camera'">
          <el-form-item label="所属产线"><el-select v-model="formData.lineId" style="width:100%" disabled><el-option v-for="l in lineList" :key="l.lineId" :label="l.lineName" :value="l.lineId" /></el-select></el-form-item>
          <el-form-item label="工位"><el-select v-model="formData.workstationId" style="width:100%" :disabled="Boolean(parentLocked.workstation)"><el-option v-for="s in stationOptionsForCamera" :key="s.workstationId" :label="s.stationName" :value="s.workstationId" /></el-select></el-form-item>
          <el-form-item label="相机名称"><el-input v-model="formData.name" placeholder="请输入相机名称" /></el-form-item>
          <el-form-item label="设备编号"><el-input v-model="formData.code" placeholder="请输入设备编号" /></el-form-item>
        </template>

        <template v-else>
          <el-form-item label="用户名"><el-input v-model="formData.name" placeholder="请输入用户名" /></el-form-item>
          <el-form-item label="姓名"><el-input v-model="formData.realName" placeholder="请输入姓名" /></el-form-item>
          <el-form-item label="密码"><el-input v-model="formData.password" type="password" placeholder="新增必填，编辑可不填" /></el-form-item>
          <el-form-item label="角色"><el-select v-model="formData.role" style="width:100%"><el-option label="管理员" :value="1" /><el-option label="普通用户" :value="0" /></el-select></el-form-item>
          <el-form-item label="手机号"><el-input v-model="formData.phone" placeholder="请输入手机号" /></el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>

    <ConfigImportDialog
      v-model=importDialogVisible
      v-model:import-mode=importMode
      :file-list=importFileList
      :result=importResult
      :loading=importLoading
      @download-template=downloadImportTemplate
      @file-change=handleImportFileChange
      @file-remove=handleImportFileRemove
      @file-exceed=handleImportFileExceed
      @submit=submitConfigImport
    />
  </div>
</template>

<script setup>
import { computed, defineComponent, h, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import request from '@/utils/request'
import { getCascadeAll } from '@/api/stats'
import { getCameraList } from '@/api/camera'
import { ElMessage, ElMessageBox } from '@/utils/message'
import OperationLog from './OperationLog.vue'
import BasePagination from '@/components/common/BasePagination.vue'
import { DEFAULT_PAGE_SIZE_OPTIONS } from '@/shared/constants/pagination'
import ConfigImportDialog from './config/ConfigImportDialog.vue'
import { useConfigRiskConfirm } from './config/useConfigRiskConfirm'

const router = useRouter()
const route = useRoute()
const menuKeys = ['factory', 'line', 'workstation', 'camera', 'user', 'operationLog']
const configMenuKeys = ['factory', 'line', 'workstation', 'camera', 'user']
const activeMenu = ref(menuKeys.includes(route.query.tab) ? route.query.tab : 'factory')
const selectedTreeNode = ref({ key: 'root', type: 'root', id: null, label: '全部配置' })
const configTreeRef = ref(null)
const dialogVisible = ref(false)
const dialogType = ref('')
const formData = ref({})
const editingId = ref(null)
const parentLocked = ref({})
const importDialogVisible = ref(false)
const importLoading = ref(false)
const importMode = ref('INCREMENTAL')
const importFile = ref(null)
const importFileList = ref([])
const importResult = ref(null)
const pageSizeOptions = DEFAULT_PAGE_SIZE_OPTIONS
const pageSize = ref(10)
const pages = ref({ factory: 1, line: 1, workstation: 1, camera: 1, user: 1, operationLog: 1 })
const listLoading = ref(false)
const remoteRows = ref([])
const remoteTotal = ref(0)
const treeProps = { children: 'children', label: 'label' }
const expandConfigTree = async (expanded) => {
  await nextTick()
  const tree = configTreeRef.value
  const storeNodes = tree?.store?.nodesMap || {}
  Object.keys(storeNodes).forEach(key => { storeNodes[key].expanded = expanded })
}

const allNavItems = [
  { key: 'factory', icon: '🏭', label: '配置总览', shortLabel: '工厂' },
  { key: 'line', icon: '🔧', label: '产线管理', shortLabel: '产线' },
  { key: 'workstation', icon: '📍', label: '工位管理', shortLabel: '工位' },
  { key: 'camera', icon: '📷', label: '相机管理', shortLabel: '相机' },
  { key: 'user', icon: '👥', label: '用户管理', shortLabel: '用户' },
  { key: 'operationLog', icon: '🧾', label: '操作记录', shortLabel: '操作记录' },
]
const navItems = allNavItems.filter(n => ['user', 'operationLog'].includes(n.key))
const currentNav = computed(() => allNavItems.find(n => n.key === activeMenu.value) || allNavItems[0])
const typeShortLabel = (type) => (allNavItems.find(n => n.key === type)?.shortLabel || '配置')

const factoryList = ref([])
const lineList = ref([])
const stationList = ref([])
const cameraList = ref([])
const userList = ref([])
const sameId = (a, b) => String(a ?? '') === String(b ?? '')

const factoryMap = computed(() => Object.fromEntries(factoryList.value.map(f => [f.factoryId, f.factoryName])))
const lineMap = computed(() => Object.fromEntries(lineList.value.map(l => [l.lineId, l.lineName])))
const stationMap = computed(() => Object.fromEntries(stationList.value.map(s => [s.workstationId, s.stationName])))
const lineCountByFactory = computed(() => lineList.value.reduce((acc, l) => {
  if (l.factoryId != null) acc[l.factoryId] = (acc[l.factoryId] || 0) + 1
  return acc
}, {}))
const stationCountByLine = computed(() => stationList.value.reduce((acc, s) => {
  if (s.lineId != null) acc[s.lineId] = (acc[s.lineId] || 0) + 1
  return acc
}, {}))
const stationCountByFactory = computed(() => stationList.value.reduce((acc, s) => {
  const line = lineList.value.find(l => sameId(l.lineId, s.lineId))
  if (line?.factoryId != null) acc[line.factoryId] = (acc[line.factoryId] || 0) + 1
  return acc
}, {}))
const cameraCountByStation = computed(() => cameraList.value.reduce((acc, c) => {
  const id = c.workstationId
  if (id != null) acc[id] = (acc[id] || 0) + 1
  return acc
}, {}))
const cameraCountByLine = computed(() => cameraList.value.reduce((acc, c) => {
  if (c.lineId != null) acc[c.lineId] = (acc[c.lineId] || 0) + 1
  return acc
}, {}))
const cameraCountByFactory = computed(() => cameraList.value.reduce((acc, c) => {
  const line = lineList.value.find(l => sameId(l.lineId, c.lineId))
  if (line?.factoryId != null) acc[line.factoryId] = (acc[line.factoryId] || 0) + 1
  return acc
}, {}))
const linesWithoutStations = computed(() => lineList.value.filter(l => !(stationCountByLine.value[l.lineId] > 0)).length)
const stationsWithoutCameras = computed(() => stationList.value.filter(s => !(cameraCountByStation.value[s.workstationId] > 0)).length)
const camerasWithoutStation = computed(() => cameraList.value.filter(c => !c.workstationId).length)

const isConfigContext = computed(() => configMenuKeys.includes(activeMenu.value))
const stationOptionsForCamera = computed(() => {
  const lineId = formData.value.lineId
  return lineId ? stationList.value.filter(s => sameId(s.lineId, lineId)) : stationList.value
})
const factoryNameByCamera = (row) => {
  if (row.factoryId && factoryMap.value[row.factoryId]) return factoryMap.value[row.factoryId]
  const line = lineList.value.find(l => sameId(l.lineId, row.lineId))
  return line ? (factoryMap.value[line.factoryId] || '-') : '-'
}
const treeIcon = (type) => ({ root: '总', factory: '厂', line: '线', workstation: '位', camera: '机' }[type] || '•')
const treeTypeLabel = (type) => ({ root: '全部范围', factory: '工厂', line: '产线', workstation: '工位', camera: '相机' }[type] || '配置项')
const configTreeData = computed(() => [{
  key: 'root',
  type: 'root',
  id: null,
  label: '全部配置',
  count: factoryList.value.length,
  children: factoryList.value.map(factory => ({
    key: `factory-${factory.factoryId}`,
    type: 'factory',
    id: factory.factoryId,
    label: factory.factoryName,
    raw: factory,
    count: lineCountByFactory.value[factory.factoryId] || 0,
    children: lineList.value.filter(line => sameId(line.factoryId, factory.factoryId)).map(line => ({
      key: `line-${line.lineId}`,
      type: 'line',
      id: line.lineId,
      label: line.lineName,
      raw: line,
      count: stationCountByLine.value[line.lineId] || 0,
      children: stationList.value.filter(station => sameId(station.lineId, line.lineId)).map(station => ({
        key: `station-${station.workstationId}`,
        type: 'workstation',
        id: station.workstationId,
        label: station.stationName,
        raw: station,
        count: cameraCountByStation.value[station.workstationId] || 0,
        children: cameraList.value.filter(camera => sameId(camera.workstationId, station.workstationId)).map(camera => ({
          key: `camera-${camera.id || camera.cameraId}`,
          type: 'camera',
          id: camera.id || camera.cameraId,
          label: camera.cameraName,
          raw: camera
        }))
      }))
    }))
  }))
}])

const contextTitle = computed(() => {
  if (selectedTreeNode.value.type === 'root') return '配置总览'
  return `${selectedTreeNode.value.label} · ${currentNav.value.label}`
})
const contextDesc = computed(() => {
  const type = selectedTreeNode.value.type
  if (type === 'root') return '按工厂、产线、工位、相机逐级配置，支持分页维护、导入导出和依赖风险确认。'
  if (type === 'factory') return '当前展示所选工厂下的产线，可新增或维护产线配置。'
  if (type === 'line') return '当前展示所选产线下的工位，可新增或维护工位配置。'
  if (type === 'workstation') return '当前展示所选工位下的相机，可新增或维护相机绑定。'
  if (type === 'camera') return '当前定位到单台相机，可编辑相机名称、编号和工位绑定。'
  return currentNav.value.label
})
const addTargetType = computed(() => {
  if (activeMenu.value === 'user') return 'user'
  if (activeMenu.value === 'operationLog') return ''
  const type = selectedTreeNode.value.type
  if (type === 'root') return 'factory'
  if (type === 'factory') return 'line'
  if (type === 'line') return 'workstation'
  if (type === 'workstation') return 'camera'
  return ''
})

const listByType = computed(() => ({ factory: factoryList.value, line: lineList.value, workstation: stationList.value, camera: cameraList.value, user: userList.value }))
const currentRows = computed(() => configMenuKeys.includes(activeMenu.value) ? remoteRows.value : (listByType.value[activeMenu.value] || []))
const currentTotal = computed(() => configMenuKeys.includes(activeMenu.value) ? remoteTotal.value : currentRows.value.length)
const currentPage = computed(() => pages.value[activeMenu.value] || 1)
const pagedRows = computed(() => configMenuKeys.includes(activeMenu.value) ? currentRows.value : currentRows.value.slice((currentPage.value - 1) * pageSize.value, currentPage.value * pageSize.value))
const tableIndex = (index) => (currentPage.value - 1) * pageSize.value + index + 1
const switchMenu = (key) => {
  activeMenu.value = key
  selectedTreeNode.value = { key: 'root', type: 'root', id: null, label: '全部配置' }
  parentLocked.value = {}
  router.replace({ path: '/system/config', query: { tab: key } })
  if (configMenuKeys.includes(key)) loadCurrentPage()
}
const handleTreeSelect = (data) => {
  selectedTreeNode.value = data || { key: 'root', type: 'root', id: null, label: '全部配置' }
  parentLocked.value = {}
  const type = selectedTreeNode.value.type
  if (type === 'root') activeMenu.value = 'factory'
  else if (type === 'factory') {
    activeMenu.value = 'line'
    parentLocked.value = { factory: true }
  } else if (type === 'line') {
    activeMenu.value = 'workstation'
    parentLocked.value = { line: true }
  } else if (type === 'workstation') {
    activeMenu.value = 'camera'
    parentLocked.value = { workstation: true, line: true }
  } else if (type === 'camera') {
    activeMenu.value = 'camera'
    parentLocked.value = { workstation: true, line: true }
  }
  pages.value[activeMenu.value] = 1
  router.replace({ path: '/system/config', query: { tab: activeMenu.value } })
  if (configMenuKeys.includes(activeMenu.value)) loadCurrentPage()
}
watch(() => route.query.tab, (tab) => {
  if (menuKeys.includes(tab) && tab !== activeMenu.value) {
    activeMenu.value = tab
    if (configMenuKeys.includes(tab)) loadCurrentPage()
  }
})
watch(currentTotal, () => {
  const max = Math.max(1, Math.ceil(currentTotal.value / pageSize.value))
  if (pages.value[activeMenu.value] > max) pages.value[activeMenu.value] = max
})
watch(pageSize, () => { pages.value[activeMenu.value] = 1 })

const StatusBadge = defineComponent({
  props: { text: { type: String, default: '-' } },
  setup(props) { return () => h('span', { class: ['status-badge', statusClass(props.text)] }, props.text || '-') }
})
const statusClass = (text = '') => {
  if (['启用', '运行', '管理员'].includes(text)) return 'ok'
  if (['待机', '普通用户'].includes(text)) return 'warn'
  if (['故障'].includes(text)) return 'danger'
  if (['停用', '停止', '离线'].includes(text)) return 'off'
  return 'neutral'
}
const cameraStatusText = (status) => {
  const s = String(status || '').toUpperCase()
  if (s === 'RUNNING' || s === 'ONLINE' || s === '1') return '运行'
  if (s === 'STOPPED' || s === '0') return '停止'
  if (s === 'FAULT' || s === '2') return '故障'
  if (s === 'STANDBY' || s === 'PAUSED' || s === '3') return '待机'
  if (s === 'OFFLINE' || s === '9') return '离线'
  return '待机'
}
const formatPercent = (value) => value == null ? '-' : Number(value).toFixed(1) + '%'
const formatTemp = (value) => value == null ? '-' : Number(value).toFixed(1) + '℃'
const toTimeText = (value) => Array.isArray(value)
  ? value[0] + '-' + String(value[1]).padStart(2, '0') + '-' + String(value[2]).padStart(2, '0') + ' ' + String(value[3] || 0).padStart(2, '0') + ':' + String(value[4] || 0).padStart(2, '0') + ':' + String(value[5] || 0).padStart(2, '0')
  : (value || '-')

const notifyCascadeRefresh = () => window.dispatchEvent(new CustomEvent('config-cascade-refresh'))

const openImportDialog = () => {
  importDialogVisible.value = true
  importMode.value = 'INCREMENTAL'
  importFile.value = null
  importFileList.value = []
  importResult.value = null
}

const downloadImportTemplate = async () => {
  try {
    const res = await request.get('/config-import/template', { responseType: 'blob' })
    const blob = new Blob([res.data], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' })
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = '配置整体导入模板.xlsx'
    document.body.appendChild(link)
    link.click()
    link.remove()
    window.URL.revokeObjectURL(url)
  } catch (e) {
    ElMessage.error('模板下载失败：' + (e.response?.data?.message || e.message))
  }
}

const handleImportFileChange = (file) => {
  importFile.value = file.raw
  importFileList.value = [file]
  importResult.value = null
}
const handleImportFileRemove = () => {
  importFile.value = null
  importFileList.value = []
}
const handleImportFileExceed = (files) => {
  const file = files?.[0]
  if (!file) return
  importFile.value = file
  importFileList.value = [{ name: file.name, raw: file }]
  importResult.value = null
}

const submitConfigImport = async () => {
  if (!importFile.value) {
    ElMessage.warning('请先选择需要导入的 Excel 文件')
    return
  }
  try {
    await confirmImportRisk(importMode.value)
    importLoading.value = true
    const form = new FormData()
    form.append('file', importFile.value)
    const res = await request.post('/config-import/import', form, {
      params: { mode: importMode.value },
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    const payload = res.data || {}
    importResult.value = payload.data || payload
    if (payload.code && payload.code !== 200) {
      ElMessage.warning(payload.message || '导入完成，但存在失败行')
      return
    }
    await fetchAll()
    notifyCascadeRefresh()
    ElMessage.success(payload.message || '导入成功')
  } catch (e) {
    if (e !== 'cancel') {
      const payload = e.response?.data || {}
      importResult.value = payload.data || importResult.value
      ElMessage.error('导入失败：' + (payload.message || e.message || e))
    }
  } finally {
    importLoading.value = false
  }
}

const fetchAll = async () => {
  const res = await getCascadeAll()
  const d = res.data?.data || res.data || {}
  const tree = d.factories || []
  factoryList.value = (tree.length ? tree : (d.flattenedFactories || [])).map(f => ({ factoryId: f.factoryId, factoryName: f.factoryName, factoryCode: f.factoryCode, address: f.address || '', status: f.status || '启用' }))

  const lines = []
  const stations = []
  tree.forEach(f => {
    ;(f.lines || []).forEach(l => {
      lines.push({ lineId: l.lineId, lineName: l.lineName, lineCode: l.lineCode, factoryId: f.factoryId, stationCount: (l.stations || []).length })
      ;(l.stations || []).forEach(s => {
        stations.push({ workstationId: s.workstationId || s.stationId, stationId: s.stationId || s.workstationId, stationName: s.stationName, stationCode: s.stationCode, lineId: l.lineId, description: s.description || '' })
      })
    })
  })
  lineList.value = lines
  stationList.value = stations

  const camRes = await getCameraList({ page: 1, size: 999 })
  const cams = camRes.data?.data?.records || camRes.data?.records || camRes.data || []
  cameraList.value = cams.map(c => ({ ...c, id: c.id || c.cameraId, cameraId: c.cameraId || c.id, cameraName: c.cameraName || c.cameraCode || '', cameraCode: c.cameraCode || c.deviceId || '', lineId: c.lineId, workstationId: c.workstationId, stationName: stationMap.value[c.workstationId] || c.stationName || '-' }))

  try {
    const userRes = await request.get('/user', { params: { page: 1, size: 999 } })
    const users = userRes.data?.data?.records || userRes.data?.data || userRes.data || []
    userList.value = Array.isArray(users) ? users.map(u => ({ userId: u.userId || u.id, username: u.username, realName: u.realName || u.name || u.username, role: Number(u.role) === 1 || u.role === 'ADMIN' ? '管理员' : '普通用户', phone: u.phone || '', status: u.isDisabled ? '停用' : '启用', lastLogin: toTimeText(u.lastLoginAt) })) : []
  } catch (e) { userList.value = [] }

  if (configMenuKeys.includes(activeMenu.value)) await loadCurrentPage()
}

const unwrapPage = (res) => {
  const d = res.data?.data || res.data || {}
  return {
    records: Array.isArray(d.records) ? d.records : (Array.isArray(d) ? d : []),
    total: d.total ?? (Array.isArray(d) ? d.length : 0)
  }
}

const normalizeRowByType = (type, row) => {
  if (type === 'workstation') {
    return { ...row, workstationId: row.workstationId || row.stationId, stationId: row.stationId || row.workstationId }
  }
  if (type === 'camera') {
    return { ...row, id: row.id || row.cameraId, cameraId: row.cameraId || row.id, stationName: stationMap.value[row.workstationId] || row.stationName || '-' }
  }
  if (type === 'user') {
    return {
      userId: row.userId || row.id,
      username: row.username,
      realName: row.realName || row.name || row.username,
      role: Number(row.role) === 1 || row.role === 'ADMIN' ? '管理员' : '普通用户',
      phone: row.phone || '',
      status: row.isDisabled ? '停用' : '启用',
      lastLogin: toTimeText(row.lastLoginAt)
    }
  }
  return row
}

const appendContextParams = (params) => {
  const node = selectedTreeNode.value || {}
  if (activeMenu.value === 'line' && node.type === 'factory') params.factoryId = node.id
  if (activeMenu.value === 'workstation' && node.type === 'line') params.lineId = node.id
  if (activeMenu.value === 'camera') {
    if (node.type === 'factory') params.factoryId = node.id
    if (node.type === 'line') params.lineId = node.id
    if (node.type === 'workstation') params.stationId = node.id
  }
  if (activeMenu.value === 'user' && node.type === 'factory') params.factoryId = node.id
  return params
}

const loadCurrentPage = async () => {
  if (!configMenuKeys.includes(activeMenu.value)) return
  listLoading.value = true
  try {
    const params = appendContextParams({ page: currentPage.value, size: pageSize.value })
    let res
    if (activeMenu.value === 'camera') res = await getCameraList(params)
    else res = await request.get('/' + apiPath(activeMenu.value), { params })
    const pageData = unwrapPage(res)
    remoteRows.value = pageData.records.map(r => normalizeRowByType(activeMenu.value, r))
    remoteTotal.value = pageData.total
  } catch (e) {
    remoteRows.value = []
    remoteTotal.value = 0
    ElMessage.error('加载配置数据失败：' + (e.response?.data?.message || e.message))
  } finally {
    listLoading.value = false
  }
}

const handlePageSizeChange = () => {
  pages.value[activeMenu.value] = 1
  loadCurrentPage()
}

const apiPath = (type) => ({ factory: 'factories', line: 'lines', workstation: 'workstations', camera: 'cameras', user: 'user' }[type])
const { confirmDeleteRisk, confirmUpdateRisk, confirmImportRisk } = useConfigRiskConfirm({
  request,
  messageBox: ElMessageBox,
  typeShortLabel,
  finalConfigMenuKeys: [],
  apiPath
})
const openDialog = (type) => {
  dialogType.value = type
  editingId.value = null
  const base = { role: 0 }
  const node = selectedTreeNode.value || {}
  parentLocked.value = {}
  if (type === 'line' && node.type === 'factory') {
    base.factoryId = node.id
    parentLocked.value = { factory: true }
  } else if (type === 'workstation' && node.type === 'line') {
    base.lineId = node.id
    parentLocked.value = { line: true }
  } else if (type === 'camera' && node.type === 'workstation') {
    base.workstationId = node.id
    base.lineId = node.raw?.lineId
    parentLocked.value = { workstation: true, line: true }
  }
  formData.value = base
  dialogVisible.value = true
}
const editRow = (type, row) => {
  dialogType.value = type
  dialogVisible.value = true
  parentLocked.value = {}
  if (type === 'factory') { editingId.value = row.factoryId; formData.value = { name: row.factoryName, code: row.factoryCode, address: row.address, status: row.status || '启用' } }
  else if (type === 'line') { editingId.value = row.lineId; formData.value = { name: row.lineName, code: row.lineCode, factoryId: row.factoryId, stationCount: row.stationCount } }
  else if (type === 'workstation') { editingId.value = row.workstationId || row.stationId; formData.value = { name: row.stationName, code: row.stationCode, lineId: row.lineId, description: row.description } }
  else if (type === 'camera') { editingId.value = row.id || row.cameraId; formData.value = { name: row.cameraName, code: row.cameraCode, lineId: row.lineId, workstationId: row.workstationId, status: row.status || 'RUNNING' } }
  else { editingId.value = row.userId; formData.value = { name: row.username, realName: row.realName, role: row.role === '管理员' ? 1 : 0, phone: row.phone || '', password: '' } }
}
const buildBody = () => {
  if (dialogType.value === 'factory') return { factoryName: formData.value.name, factoryCode: formData.value.code, address: formData.value.address }
  if (dialogType.value === 'line') return { lineName: formData.value.name, lineCode: formData.value.code, factoryId: formData.value.factoryId, stationCount: formData.value.stationCount }
  if (dialogType.value === 'workstation') return { stationName: formData.value.name, stationCode: formData.value.code, lineId: formData.value.lineId, description: formData.value.description }
  if (dialogType.value === 'camera') return { cameraName: formData.value.name, cameraCode: formData.value.code, lineId: formData.value.lineId, workstationId: formData.value.workstationId, status: formData.value.status || 'RUNNING' }
  const body = { username: formData.value.name, realName: formData.value.realName, role: formData.value.role, phone: formData.value.phone || '' }
  if (formData.value.password) body.passwordHash = formData.value.password
  return body
}
const handleSave = async () => {
  const path = apiPath(dialogType.value)
  const isEdit = editingId.value != null
  try {
    if (isEdit) await confirmUpdateRisk(dialogType.value, editingId.value)
    const res = isEdit
      ? await request.put('/' + path + '/' + editingId.value, buildBody())
      : await request.post('/' + path, buildBody())
    assertOk(res)
    dialogVisible.value = false
    await fetchAll()
    notifyCascadeRefresh()
    ElMessage.success('保存成功')
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('保存失败：' + (e.response?.data?.message || e.message || e))
  }
}
const deleteRow = async (type, row) => {
  const id = { factory: row.factoryId, line: row.lineId, workstation: row.workstationId || row.stationId, camera: row.id || row.cameraId, user: row.userId }[type]
  const name = row.factoryName || row.lineName || row.stationName || row.cameraName || row.username
  try {
    await confirmDeleteRisk(type, id, name)
    const res = await request.delete('/' + apiPath(type) + '/' + id)
    assertOk(res)
    await fetchAll()
    notifyCascadeRefresh()
    ElMessage.success('删除成功')
  }
  catch (e) {
    if (e !== 'cancel') ElMessage.error('删除失败：' + (e.response?.data?.message || e.message || e))
  }
}

const assertOk = (res) => {
  const payload = res?.data
  if (payload && payload.code && payload.code !== 200) {
    const err = new Error(payload.message || '操作失败')
    err.response = { data: payload }
    throw err
  }
}

const dialogTitle = computed(() => (editingId.value != null ? '编辑' : '新增') + (currentNav.value.shortLabel || ''))
onMounted(fetchAll)
</script>

<style scoped>
.sys-config { max-width: 1420px; margin: 0 auto; }
.config-layout { display: flex; min-height: calc(100vh - 200px); background: #fff; border-radius: 18px; box-shadow: 0 14px 34px rgba(15,23,42,.08); overflow: hidden; border: 1px solid rgba(226,232,240,.9); }
.config-sidebar { width: 288px; background: linear-gradient(180deg,#f8fbff 0%,#f9fbff 48%,#ffffff 100%); border-right: 1px solid #e5e7eb; padding: 18px 14px; flex-shrink: 0; }
.sidebar-head { display: flex; align-items: center; gap: 12px; padding: 12px 12px 14px; margin-bottom: 12px; border-radius: 16px; background: linear-gradient(135deg,#2563eb 0%,#38bdf8 100%); color: #fff; box-shadow: 0 10px 22px rgba(37,99,235,.22); }
.sidebar-mark { width: 38px; height: 38px; border-radius: 12px; display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0; color: #1d4ed8; background: rgba(255,255,255,.92); font-size: 16px; font-weight: 900; box-shadow: inset 0 0 0 1px rgba(255,255,255,.6); }
.sidebar-title { font-size: 16px; font-weight: 900; line-height: 1.1; letter-spacing: .02em; }
.sidebar-desc { margin-top: 5px; font-size: 12px; color: rgba(255,255,255,.9); line-height: 1.35; }
.hierarchy-guide { margin: 0 2px 12px; padding: 12px; border-radius: 16px; background: #ffffff; border: 1px solid #e6eefb; box-shadow: 0 8px 18px rgba(15,23,42,.04); }
.guide-title { font-size: 12px; font-weight: 800; color: #334155; margin-bottom: 9px; display: flex; align-items: center; justify-content: space-between; }
.guide-title::after { content: '逐级绑定'; color: #2563eb; background: #eff6ff; border: 1px solid #dbeafe; border-radius: 999px; padding: 1px 8px; font-size: 11px; font-weight: 800; }
.guide-flow { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.guide-flow span { display: inline-flex; align-items: center; justify-content: center; min-width: 42px; height: 24px; padding: 0 9px; border-radius: 999px; background: #f1f5f9; color: #0f172a; font-size: 12px; font-weight: 800; border: 1px solid #e2e8f0; }
.guide-flow i { font-style: normal; color: #94a3b8; font-weight: 900; }
.hierarchy-guide p { margin: 9px 0 0; color: #64748b; font-size: 12px; line-height: 1.55; }
.sidebar-nav { padding: 4px 0; display: flex; flex-direction: column; gap: 2px; }
.nav-item { border: none; background: transparent; display: flex; align-items: center; gap: 10px; padding: 12px 20px; cursor: pointer; font-size: 14px; color: #475569; transition: all .15s; border-left: 3px solid transparent; text-align: left; }
.nav-item:hover { background: #eff6ff; color: #2563eb; }
.nav-item.active { background: #eff6ff; color: #2563eb; border-left-color: #2563eb; font-weight: 700; }
.nav-icon { font-size: 17px; width: 22px; text-align: center; }
.config-content { flex: 1; padding: 24px; min-width: 0; }
.content-header { display: flex; align-items: center; gap: 16px; margin-bottom: 20px; padding-bottom: 16px; border-bottom: 1px solid #eef2f7; }
.header-copy { flex: 1; min-width: 0; }
.header-actions { display: flex; align-items: center; gap: 8px; margin-left: auto; white-space: nowrap; }
.content-header h2 { margin: 0 0 4px; font-size: 20px; color: #0f172a; }
.content-header p { margin: 0; font-size: 13px; color: #64748b; }
.back-btn { border: none !important; font-weight: 700; border-radius: 999px; }
.panel-toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.plus { font-weight: 800; margin-right: 2px; }
.toolbar-count { font-size: 13px; color: #475569; font-weight: 700; }
.toolbar-tip { font-size: 12px; color: #94a3b8; }
.cfg-table { font-size: 13px; border-radius: 12px; overflow: hidden; }
.cfg-table :deep(td) { padding: 10px 12px; }
.cfg-table :deep(.el-table__row:hover) { background: #eff6ff !important; }
.cfg-table code { background: #f1f5f9; padding: 2px 7px; border-radius: 5px; font-size: 12px; color: #2563eb; }
.status-badge { display: inline-flex; align-items: center; justify-content: center; min-width: 54px; height: 24px; padding: 0 10px; border-radius: 999px; font-size: 12px; font-weight: 800; }
.status-badge.ok { background: #dcfce7; color: #15803d; }
.status-badge.warn { background: #fef3c7; color: #b45309; }
.status-badge.danger { background: #fee2e2; color: #b91c1c; }
.status-badge.off { background: #f1f5f9; color: #475569; }
.status-badge.neutral { background: #e0f2fe; color: #0369a1; }
.action-group { display: inline-flex; align-items: center; gap: 6px; padding: 2px; border: 1px solid #e5e7eb; border-radius: 999px; background: #f8fafc; }
.action-group :deep(.el-button + .el-button) { margin-left: 0; }
.action-btn { height: 24px; min-height: 24px; padding: 0 11px !important; border: none !important; border-radius: 999px !important; font-size: 12px; font-weight: 700; transition: all .15s; }
.action-btn.edit { color: #2563eb; background: #eff6ff; }
.action-btn.del { color: #dc2626; background: #fff1f2; }
.action-btn.edit:hover { color: #fff; background: #2563eb; }
.action-btn.del:hover { color: #fff; background: #dc2626; }
.pager-row { display: flex; justify-content: space-between; align-items: center; gap: 12px; padding-top: 14px; font-size: 12px; color: #64748b; }
.import-intro { display: flex; justify-content: space-between; gap: 16px; padding: 14px 16px; border: 1px solid #e0f2fe; border-radius: 14px; background: linear-gradient(135deg,#f0f9ff 0%,#ffffff 100%); margin-bottom: 16px; }
.import-intro h3 { margin: 0 0 6px; font-size: 16px; color: #0f172a; }
.import-intro p { margin: 0; color: #64748b; font-size: 13px; line-height: 1.6; }
.import-alert { margin: 8px 0 14px; border-radius: 10px; }
.import-upload { margin-top: 8px; }
.import-upload :deep(.el-upload-dragger) { border-radius: 14px; background: #f8fafc; }
.upload-icon { font-size: 34px; margin: 12px 0 4px; }
.import-result { margin-top: 16px; padding: 14px; border-radius: 14px; border: 1px solid #e5e7eb; background: #fff; }
.result-title { font-size: 14px; font-weight: 800; color: #0f172a; margin-bottom: 10px; }
.result-grid { display: grid; grid-template-columns: repeat(4, minmax(0,1fr)); gap: 10px; margin-bottom: 12px; }
.result-grid div { padding: 10px; border-radius: 12px; background: #f8fafc; border: 1px solid #eef2f7; }
.result-grid span { display: block; font-size: 12px; color: #64748b; margin-bottom: 4px; }
.result-grid strong { color: #0f172a; font-size: 18px; }
.result-grid strong.ok { color: #16a34a; }
.result-grid strong.danger { color: #dc2626; }
.result-list { padding: 10px 12px; border-radius: 12px; margin-top: 10px; font-size: 12px; line-height: 1.6; }
.result-list p { margin: 4px 0 0; }
.result-list.warn { background: #fffbeb; color: #92400e; border: 1px solid #fde68a; }
.result-list.error { background: #fef2f2; color: #b91c1c; border: 1px solid #fecaca; }
@media (max-width: 900px) {
  .config-layout { flex-direction: column; }
  .config-sidebar { width: 100%; border-right: none; border-bottom: 1px solid #e5e7eb; padding: 12px; }
  .sidebar-nav { flex-direction: row; flex-wrap: wrap; }
  .nav-item { border-left: none; border-bottom: 2px solid transparent; padding: 8px 14px; }
  .nav-item.active { border-left: none; border-bottom-color: #2563eb; }
  .config-content { padding: 16px; }
  .content-header { flex-wrap: wrap; align-items: flex-start; }
  .header-actions { width: 100%; justify-content: flex-start; margin-left: 0; }
  .import-intro { flex-direction: column; }
  .result-grid { grid-template-columns: repeat(2, minmax(0,1fr)); }
  .pager-row { flex-direction: column; align-items: flex-start; }
}

.tree-tools { display: flex; gap: 8px; margin: 10px 2px 12px; }
.tree-tools button { flex: 1; border: 1px solid #dbeafe; background: #eff6ff; color: #1d4ed8; border-radius: 999px; padding: 7px 10px; font-size: 12px; font-weight: 800; cursor: pointer; transition: all .15s ease; }
.tree-tools button:hover { border-color: #2563eb; background: #dbeafe; transform: translateY(-1px); box-shadow: 0 6px 14px rgba(37,99,235,.12); }
.config-tree { margin: 0 2px 14px; padding: 8px 6px; border: 1px solid #e8eef7; border-radius: 16px; background: #fff; max-height: calc(100vh - 360px); min-height: 260px; overflow: auto; box-shadow: inset 0 1px 0 rgba(255,255,255,.8); }
.config-tree :deep(.el-tree-node__content) { height: auto; min-height: 36px; border-radius: 12px; margin: 2px 0; padding-right: 6px; transition: background .15s ease, box-shadow .15s ease; }
.config-tree :deep(.el-tree-node__content:hover) { background: #f1f7ff; }
.config-tree :deep(.el-tree-node.is-current > .el-tree-node__content) { background: linear-gradient(90deg,#eaf2ff 0%,#f8fbff 100%); box-shadow: inset 3px 0 0 #2563eb; }
.config-tree :deep(.el-tree-node__expand-icon) { color: #94a3b8; font-size: 13px; }
.config-tree :deep(.el-tree-node__expand-icon.expanded) { color: #2563eb; }
.tree-node { width: 100%; min-width: 0; display: grid; grid-template-columns: 28px minmax(0,1fr) auto; align-items: center; gap: 8px; padding: 4px 4px 4px 0; }
.tree-icon { width: 26px; height: 26px; border-radius: 9px; display: inline-flex; align-items: center; justify-content: center; font-size: 12px; font-weight: 900; color: #2563eb; background: #eff6ff; border: 1px solid #dbeafe; flex-shrink: 0; }
.tree-node-factory .tree-icon { color: #047857; background: #ecfdf5; border-color: #bbf7d0; }
.tree-node-line .tree-icon { color: #7c3aed; background: #f5f3ff; border-color: #ddd6fe; }
.tree-node-workstation .tree-icon { color: #d97706; background: #fffbeb; border-color: #fde68a; }
.tree-node-camera .tree-icon { color: #0891b2; background: #ecfeff; border-color: #bae6fd; }
.tree-text { min-width: 0; display: flex; flex-direction: column; gap: 1px; }
.tree-label { display: block; max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #1e293b; font-size: 13px; font-weight: 750; line-height: 1.25; }
.tree-meta { color: #94a3b8; font-size: 11px; line-height: 1.15; }
.tree-count { min-width: 24px; height: 20px; padding: 0 7px; display: inline-flex; align-items: center; justify-content: center; border-radius: 999px; background: #f8fafc; border: 1px solid #e2e8f0; color: #475569; font-size: 11px; font-weight: 800; }
.config-tree :deep(.el-tree-node.is-current > .el-tree-node__content) .tree-count { background: #2563eb; border-color: #2563eb; color: #fff; }
.sidebar-tools { margin-top: 12px; padding-top: 12px; border-top: 1px dashed #dbe3ef; }
.sidebar-tools .nav-item { margin: 0 2px; border-left: none; border-radius: 12px; padding: 10px 12px; }
.sidebar-tools .nav-item.active { border-left: none; background: #eff6ff; box-shadow: inset 3px 0 0 #2563eb; }
</style>
