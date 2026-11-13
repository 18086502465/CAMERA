<template>
  <div class="sys-config">
    <!-- 旧路由（/system/companies、/factories、/lines、/users、/brands…共 9 个）都是
         redirect 到本页的桩，用户点旧书签会"页面莫名变了"却没任何说明。
         vue-router 的 route.redirectedFrom 只在本次重定向导航时存在，刷新即消失，正合适。 -->
    <el-alert
      v-if="redirectedFrom"
      class="merge-notice"
      type="info"
      show-icon
      :title="`「${redirectedFrom}」已并入系统配置`"
      description="该独立页面已合并到当前配置中心：工厂 / 产线 / 工位 / 相机 / 用户在此维护。请更新旧书签为 /system/config。"
      @close="redirectedFrom = ''"
    />
    <div class="config-layout" :class="{ resizing: isResizing }">
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
                <!-- title 属性：即使名字被截断，鼠标悬停也能看到完整名称 -->
                <span class="tree-label" :title="data.label">{{ data.label }}</span>
                <span class="tree-meta">{{ treeTypeLabel(data.type) }}</span>
              </span>
              <!--
                ★ 2026-11-04：徽标数字必须说明「数的是什么」。
                  原实现工厂/产线/工位三种层级都只显示一个裸数字，
                  而数字含义各不相同（产线数 / 工位数 / 相机数），
                  导致现场把「产线徽标=2（2 个工位）」误读成「下面有 2 个相机」。
                  现在统一显示为「2 工位」「2 相机」，鼠标悬停给出完整说明。
              -->
              <span v-if="data.count != null" class="tree-count" :title="treeCountTitle(data)">
                {{ data.count }}<i class="tree-count-unit">{{ treeCountUnit(data) }}</i>
              </span>
            </span>
          </template>
        </el-tree>
        <!-- 侧边栏宽度拖拽把手（2026-11-04 新增） -->
        <div
          class="sidebar-resizer"
          :class="{ dragging: isResizing }"
          role="separator"
          aria-orientation="vertical"
          :aria-valuenow="Math.round(sidebarWidth)"
          aria-valuemin="SIDEBAR_MIN_WIDTH"
          aria-valuemax="SIDEBAR_MAX_WIDTH"
          tabindex="0"
          title="拖动调整层级栏宽度（也可使用方向键）"
          @mousedown="startResize"
          @touchstart.prevent="startResize"
          @dblclick="resetSidebarWidth"
          @keydown="onResizeKeydown"
        ></div>
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
            <div class="summary-group">
              <span class="summary-group-title">配置规模</span>
              <div class="summary-cards">
                <div class="summary-card"><span>工厂</span><strong>{{ factoryList.length }}</strong></div>
                <div class="summary-card"><span>产线</span><strong>{{ lineList.length }}</strong></div>
                <div class="summary-card"><span>工位</span><strong>{{ stationList.length }}</strong></div>
                <div class="summary-card"><span>相机</span><strong>{{ cameraList.length }}</strong></div>
              </div>
            </div>
            <div class="summary-group">
              <span class="summary-group-title">完整性检查</span>
              <div class="summary-cards">
                <div class="summary-card warn" :class="{ 'is-clear': linesWithoutStations === 0 }">
                  <span>有产线无工位</span><strong>{{ linesWithoutStations }}</strong>
                </div>
                <div class="summary-card warn" :class="{ 'is-clear': stationsWithoutCameras === 0 }">
                  <span>有工位无相机</span><strong>{{ stationsWithoutCameras }}</strong>
                </div>
                <div class="summary-card danger" :class="{ 'is-clear': camerasWithoutStation === 0 }">
                  <span>相机未绑定工位</span><strong>{{ camerasWithoutStation }}</strong>
                </div>
              </div>
            </div>
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
              <el-table-column label="相机类型" width="150" align="center">
                <template #default="{ row }">
                  <el-tag :type="row.cameraType === 'REAL' ? 'success' : 'info'" size="small">
                    {{ row.cameraType === 'REAL' ? '真实产线' : '测试相机' }}
                  </el-tag>
                </template>
              </el-table-column>
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

            <el-table-column label="操作" width="180" fixed="right" align="center">
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
          <el-form-item label="相机类型">
            <el-select v-model="formData.cameraType" style="width:100%">
              <el-option label="测试相机（数据参与定时清理）" value="TEST" />
              <el-option label="真实产线相机（数据不参与清理）" value="REAL" />
            </el-select>
            <div class="form-tip">真实产线相机的图片与数据不会被每小时清理任务删除；测试相机会被清理。</div>
          </el-form-item>
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
import { computed, defineComponent, h, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
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
// 从旧路由重定向进来时提示「已合并」，直接访问 /system/config 时为 null（不打扰）
const redirectedFrom = ref(route.redirectedFrom ? route.redirectedFrom.fullPath : '')
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

/* ------------------------------------------------------------------
 * 侧边栏宽度拖拽（2026-11-04 新增）
 *
 * 需求：配置层级窗口希望能拖动，让工位名称完整可见。
 * 实现：
 *   - 用一个绝对定位的「把手」贴在侧边栏右边缘；
 *   - mousedown/touchstart 开始拖动，mousemove/touchmove 实时改宽度；
 *   - 宽度写入 CSS 变量 --sidebar-width，由 .config-sidebar 使用；
 *   - 宽度持久化到 localStorage，刷新后保持；
 *   - 双击把手恢复默认宽度；方向键 ±20px 微调（无障碍）。
 * ------------------------------------------------------------------ */
const SIDEBAR_MIN_WIDTH = 220
const SIDEBAR_MAX_WIDTH = 620
const SIDEBAR_DEFAULT_WIDTH = 288
const SIDEBAR_WIDTH_KEY = 'camera-config-sidebar-width'

const sidebarWidth = ref(SIDEBAR_DEFAULT_WIDTH)
const isResizing = ref(false)
let resizeStartX = 0
let resizeStartWidth = 0

const applySidebarWidth = (width) => {
  const w = Math.min(SIDEBAR_MAX_WIDTH, Math.max(SIDEBAR_MIN_WIDTH, Math.round(width)))
  sidebarWidth.value = w
  document.documentElement.style.setProperty('--sidebar-width', w + 'px')
  try {
    localStorage.setItem(SIDEBAR_WIDTH_KEY, String(w))
  } catch (e) { /* 隐私模式下 localStorage 不可用，忽略 */ }
}

const restoreSidebarWidth = () => {
  let saved = 0
  try { saved = parseInt(localStorage.getItem(SIDEBAR_WIDTH_KEY) || '0', 10) } catch (e) { saved = 0 }
  applySidebarWidth(saved > 0 ? saved : SIDEBAR_DEFAULT_WIDTH)
}

const startResize = (event) => {
  isResizing.value = true
  resizeStartX = (event.touches && event.touches[0] ? event.touches[0].clientX : event.clientX)
  resizeStartWidth = sidebarWidth.value
  document.body.style.userSelect = 'none'
  document.body.style.cursor = 'col-resize'
  window.addEventListener('mousemove', onResizeMove)
  window.addEventListener('mouseup', stopResize)
  window.addEventListener('touchmove', onResizeMove, { passive: false })
  window.addEventListener('touchend', stopResize)
  event.preventDefault()
}

const onResizeMove = (event) => {
  if (!isResizing.value) return
  const clientX = (event.touches && event.touches[0] ? event.touches[0].clientX : event.clientX)
  applySidebarWidth(resizeStartWidth + (clientX - resizeStartX))
  if (event.cancelable && event.touches) event.preventDefault()
}

const stopResize = () => {
  if (!isResizing.value) return
  isResizing.value = false
  document.body.style.userSelect = ''
  document.body.style.cursor = ''
  window.removeEventListener('mousemove', onResizeMove)
  window.removeEventListener('mouseup', stopResize)
  window.removeEventListener('touchmove', onResizeMove)
  window.removeEventListener('touchend', stopResize)
}

const resetSidebarWidth = () => {
  applySidebarWidth(SIDEBAR_DEFAULT_WIDTH)
}

const onResizeKeydown = (event) => {
  if (event.key === 'ArrowLeft') { applySidebarWidth(sidebarWidth.value - 20); event.preventDefault() }
  else if (event.key === 'ArrowRight') { applySidebarWidth(sidebarWidth.value + 20); event.preventDefault() }
  else if (event.key === 'Home') { resetSidebarWidth(); event.preventDefault() }
}

onMounted(() => {
  restoreSidebarWidth()
  window.addEventListener('beforeunload', stopResize)
})

// 组件卸载时清理全局监听，避免内存泄漏
onBeforeUnmount(() => {
  stopResize()
  window.removeEventListener('beforeunload', stopResize)
})

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
/**
 * 工位 → 相机数。
 *
 * ★ 2026-11-04 修正口径：
 *   相机与工位的**绑定关系真实来源是 dim_camera**（后端 /api/cascade/all 已按它统计并下发
 *   station.cameraCount），而 camera_status 只是**设备上报的状态快照**。
 *   原实现只按 camera_status 统计，导致：
 *     ① 新绑定但尚未上报心跳的相机不计数 → 工位显示「0 台相机」，与实际配置不符；
 *     ② 页面「相机数」列与「工位相机绑定」实际状态对不上。
 *   现在优先用后端下发的绑定数；后端未提供时（老接口）才退化为 camera_status 统计。
 */
const cameraCountByStation = computed(() => {
  const acc = {}
  // 1) 绑定真源（dim_camera）
  stationList.value.forEach(s => {
    if (s.boundCameraCount != null) {
      acc[s.workstationId] = s.boundCameraCount
    }
  })
  // 2) 后端未下发绑定数时，退化为 camera_status 快照统计
  const missing = stationList.value.filter(s => s.boundCameraCount == null)
  if (missing.length) {
    cameraList.value.forEach(c => {
      const id = c.workstationId
      if (id == null) return
      if (!missing.some(s => sameId(s.workstationId, id))) return
      acc[id] = (acc[id] || 0) + 1
    })
  }
  return acc
})
const cameraCountByLine = computed(() => {
  const acc = {}
  cameraList.value.forEach(c => {
    if (c.lineId != null) acc[c.lineId] = (acc[c.lineId] || 0) + 1
  })
  return acc
})
const cameraCountByFactory = computed(() => {
  const acc = {}
  cameraList.value.forEach(c => {
    const line = lineList.value.find(l => sameId(l.lineId, c.lineId))
    if (line?.factoryId != null) acc[line.factoryId] = (acc[line.factoryId] || 0) + 1
  })
  return acc
})
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
/**
 * 徽标数字的单位与完整说明。
 *
 * ★ 2026-11-04：三种层级徽标的数字含义完全不同，必须显式标注，
 *   否则「产线徽标=2」会被误读成「这条产线下有 2 个相机」。
 */
const treeCountUnit = (data) => {
  if (data.type === 'root') return '厂'
  if (data.type === 'factory') return '线'
  if (data.type === 'line') return '位'
  if (data.type === 'workstation') return '机'
  return ''
}
const treeCountTitle = (data) => {
  if (data.type === 'root') return `共 ${data.count} 个工厂`
  if (data.type === 'factory') return `该工厂下共 ${data.count} 条产线`
  if (data.type === 'line') return `该产线下共 ${data.count} 个工位`
  if (data.type === 'workstation') return `该工位下共 ${data.count} 台相机`
  return String(data.count)
}
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
        count: cameraCountByStation.value[station.workstationId] || 0
        // ★ 2026-11-04：层级树**只到工位层级**，不再往下挂相机节点。
        //   原实现在工位下继续展开相机节点，但点击相机节点时右侧列表
        //   并没有按该相机过滤（appendContextParams 对 camera 类型不传参），
        //   表现为「选了一台相机，右侧却列出全部 16 台相机」，语义自相矛盾。
        //   现在层级 = 工厂 → 产线 → 工位，选中工位即展示该工位下的相机。
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
  }
  // ★ 2026-11-04：层级树已只到工位层级，这里不再处理 camera 类型节点。
  //   保留分支是为了兼容「旧的相机节点」被外部数据（如 URL 状态恢复）带进来的情况，
  //   此时回退到工位层级展示，避免出现「选了一台相机却列出全部相机」。
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
        stations.push({
          workstationId: s.workstationId || s.stationId,
          stationId: s.stationId || s.workstationId,
          stationName: s.stationName,
          stationCode: s.stationCode,
          lineId: l.lineId,
          description: s.description || '',
          // ★ 2026-11-04：后端按 dim_camera（绑定关系真实来源）返回的相机数。
          //   缺失时置 null，前端会退化为按 camera_status 统计。
          boundCameraCount: typeof s.cameraCount === 'number' ? s.cameraCount : null
        })
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

/**
 * 按左侧层级树当前选中节点追加筛选参数。
 *
 * <p>层级语义（2026-11-04 明确）：
 * <ul>
 *   <li>选「工厂」→ 右侧列出该工厂下的产线（factoryId）；</li>
 *   <li>选「产线」→ 右侧列出该产线下的工位（lineId）；</li>
 *   <li>选「工位」→ 右侧列出该工位下的相机（stationId）。</li>
 * </ul>
 *
 * <p><b>修复点</b>：原实现的 camera 分支是三个独立 {@code if}，
 * 当选中节点类型不被识别时（如旧的相机节点），<b>一个过滤参数都不传</b>，
 * 后端就返回<b>全部</b>相机 —— 表现为「点了一台相机，右侧却列出所有相机」。
 * 现在改为 {@code else if} 链，每一层都有确定的过滤参数。
 */
const appendContextParams = (params) => {
  const node = selectedTreeNode.value || {}
  const type = node.type
  if (activeMenu.value === 'line' && type === 'factory') params.factoryId = node.id
  if (activeMenu.value === 'workstation' && type === 'line') params.lineId = node.id
  if (activeMenu.value === 'camera') {
    if (type === 'factory') params.factoryId = node.id
    else if (type === 'line') params.lineId = node.id
    else if (type === 'workstation') params.stationId = node.id
  }
  if (activeMenu.value === 'user' && type === 'factory') params.factoryId = node.id
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
  // 新建相机时默认「测试相机」（2026-11-05）：与存量现状一致（现场 16 台中 14 台为测试相机）。
  // 用户可显式改成「真实产线相机」——真实相机的数据不会被每小时清理任务删除。
  if (type === 'camera') base.cameraType = 'TEST'
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
  else if (type === 'camera') { editingId.value = row.id || row.cameraId; formData.value = { name: row.cameraName, code: row.cameraCode, lineId: row.lineId, workstationId: row.workstationId, status: row.status || 'RUNNING', cameraType: row.cameraType === 'REAL' ? 'REAL' : 'TEST' } }
  else { editingId.value = row.userId; formData.value = { name: row.username, realName: row.realName, role: row.role === '管理员' ? 1 : 0, phone: row.phone || '', password: '' } }
}
const buildBody = () => {
  if (dialogType.value === 'factory') return { factoryName: formData.value.name, factoryCode: formData.value.code, address: formData.value.address }
  if (dialogType.value === 'line') return { lineName: formData.value.name, lineCode: formData.value.code, factoryId: formData.value.factoryId, stationCount: formData.value.stationCount }
  if (dialogType.value === 'workstation') return { stationName: formData.value.name, stationCode: formData.value.code, lineId: formData.value.lineId, description: formData.value.description }
  if (dialogType.value === 'camera') {
    const body = { cameraName: formData.value.name, cameraCode: formData.value.code, lineId: formData.value.lineId, workstationId: formData.value.workstationId, status: formData.value.status || 'RUNNING' }
    // 相机类型（2026-11-05）：REAL=真实产线相机（数据不参与定时清理）/ TEST=测试相机。
    // 编辑时若用户没改，保持后端原值（后端在缺省时也会保留原值）。
    if (formData.value.cameraType) body.cameraType = formData.value.cameraType
    return body
  }
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
  // ★ 必须按类型取名称。原实现用 `row.factoryName || row.lineName || row.stationName || row.cameraName`，
  //   而相机行里**同时有** lineName（所属产线）和 cameraName（相机名），
  //   lineName 在前 → 相机删除弹窗显示的竟是产线名「ZK54」，用户完全看不懂在删什么。
  const name = {
    factory: row.factoryName,
    line: row.lineName,
    workstation: row.stationName,
    camera: row.cameraName,
    user: row.realName || row.username
  }[type] || '-'
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
/* 表单字段下方的灰色提示（相机类型说明等） */
.form-tip { margin-top: 4px; font-size: 12px; line-height: 1.5; color: var(--color-text-secondary); }
.sys-config { max-width: 1420px; margin: 0 auto; }
.merge-notice { margin-bottom: 12px; border-radius: 12px; }
.config-layout { display: flex; min-height: calc(100vh - 200px); background: var(--color-bg-card); border-radius: 18px; box-shadow: 0 14px 34px rgba(15,23,42,.08); overflow: hidden; border: 1px solid rgba(226,232,240,.9); position: relative; }
/* 宽度由 JS 拖拽写入 CSS 变量 --sidebar-width（默认值 288px） */
.config-sidebar { width: var(--sidebar-width, 288px); background: linear-gradient(180deg,#f8fbff 0%,#f9fbff 48%,#ffffff 100%); border-right: 1px solid var(--color-border); padding: 18px 14px; flex-shrink: 0; position: relative; transition: width .12s ease; }
/* 拖拽把手：贴在侧边栏右边缘。
   ⚠️ 必须用 `right: -4px` 而不是 `margin-left:-4px` ——
   后者会把元素推到侧栏盒子之外，被父级 overflow:hidden 裁掉，导致拖不动。 */
.sidebar-resizer { position: absolute; top: 0; bottom: 0; right: -4px; width: 8px; cursor: col-resize; z-index: 30; background: transparent; transition: background .15s ease; }
.sidebar-resizer:hover, .sidebar-resizer:focus-visible { background: rgba(37,99,235,.28); outline: none; }
.sidebar-resizer.dragging { background: rgba(37,99,235,.42); }
/* 拖拽时禁用宽度过渡，宽度跟手无延迟 */
/* 拖拽时禁用宽度过渡，宽度跟手无延迟（用 class 而非 :has()，兼容性更好） */
.config-layout.resizing .config-sidebar { transition: none; }
.sidebar-head { display: flex; align-items: center; gap: 12px; padding: 12px 12px 14px; margin-bottom: 12px; border-radius: 16px; background: linear-gradient(135deg,var(--color-primary) 0%,#38bdf8 100%); color: #fff; box-shadow: 0 10px 22px rgba(37,99,235,.22); }
.sidebar-mark { width: 38px; height: 38px; border-radius: 12px; display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0; color: var(--color-primary-active); background: rgba(255,255,255,.92); font-size: 16px; font-weight: 900; box-shadow: inset 0 0 0 1px rgba(255,255,255,.6); }
.sidebar-title { font-size: 16px; font-weight: 900; line-height: 1.1; letter-spacing: .02em; }
.sidebar-desc { margin-top: 5px; font-size: 12px; color: rgba(255,255,255,.9); line-height: 1.35; }
.hierarchy-guide { margin: 0 2px 12px; padding: 12px; border-radius: 16px; background: var(--color-bg-card); border: 1px solid #e6eefb; box-shadow: 0 8px 18px rgba(15,23,42,.04); }
.guide-title { font-size: 12px; font-weight: 800; color: var(--color-text-regular); margin-bottom: 9px; display: flex; align-items: center; justify-content: space-between; }
.guide-title::after { content: '逐级绑定'; color: var(--color-primary); background: var(--color-primary-bg); border: 1px solid #dbeafe; border-radius: 999px; padding: 1px 8px; font-size: 11px; font-weight: 800; }
.guide-flow { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.guide-flow span { display: inline-flex; align-items: center; justify-content: center; min-width: 42px; height: 24px; padding: 0 9px; border-radius: 999px; background: var(--color-bg-subtle); color: var(--color-text); font-size: 12px; font-weight: 800; border: 1px solid var(--color-border-strong); }
.guide-flow i { font-style: normal; color: var(--color-text-placeholder); font-weight: 900; }
.hierarchy-guide p { margin: 9px 0 0; color: var(--color-text-secondary); font-size: 12px; line-height: 1.55; }
.sidebar-nav { padding: 4px 0; display: flex; flex-direction: column; gap: 2px; }
.nav-item { border: none; background: transparent; display: flex; align-items: center; gap: 10px; padding: 12px 20px; cursor: pointer; font-size: 14px; color: #475569; transition: all .15s; border-left: 3px solid transparent; text-align: left; }
.nav-item:hover { background: var(--color-primary-bg); color: var(--color-primary); }
.nav-item.active { background: var(--color-primary-bg); color: var(--color-primary); border-left-color: var(--color-primary); font-weight: 700; }
.nav-icon { font-size: 17px; width: 22px; text-align: center; }
.config-content { flex: 1; padding: 24px; min-width: 0; }
.content-header { display: flex; align-items: center; gap: 16px; margin-bottom: 20px; padding-bottom: 16px; border-bottom: 1px solid #eef2f7; }
.header-copy { flex: 1; min-width: 0; }
.header-actions { display: flex; align-items: center; gap: 8px; margin-left: auto; white-space: nowrap; }
.content-header h2 { margin: 0 0 4px; font-size: 20px; color: var(--color-text); }
.content-header p { margin: 0; font-size: 13px; color: var(--color-text-secondary); }
.back-btn { border: none !important; font-weight: 700; border-radius: 999px; }
.panel-toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.plus { font-weight: 800; margin-right: 2px; }
.toolbar-count { font-size: 13px; color: #475569; font-weight: 700; }
.toolbar-tip { font-size: 12px; color: var(--color-text-placeholder); }
.cfg-table { font-size: 13px; border-radius: 12px; overflow: hidden; }
.cfg-table :deep(td) { padding: 10px 12px; }
.cfg-table :deep(.el-table__row:hover) { background: var(--color-primary-bg) !important; }
.cfg-table code { background: var(--color-bg-subtle); padding: 2px 7px; border-radius: 5px; font-size: 12px; color: var(--color-primary); }
.status-badge { display: inline-flex; align-items: center; justify-content: center; min-width: 54px; height: 24px; padding: 0 10px; border-radius: 999px; font-size: 12px; font-weight: 800; }
.status-badge.ok { background: var(--color-success-bg); color: #15803d; }
.status-badge.warn { background: var(--color-warning-bg); color: #b45309; }
.status-badge.danger { background: var(--color-error-bg); color: #b91c1c; }
.status-badge.off { background: var(--color-bg-subtle); color: #475569; }
.status-badge.neutral { background: #e0f2fe; color: #0369a1; }
/* 操作列按钮组
   ★ 2026-11-02 修正「部分行只剩删除按钮、编辑按钮消失」：
     1) width:160 太窄，两个按钮 + gap + padding 实际超过列宽，
        Element Plus 的 fixed="right" 固定列在列宽不足时会把按钮挤出可视区；
     2) 行高被长文本撑成两行时，固定列的渲染层与主体行错位，
        按钮会"掉"到别的行去 —— 表现为某些行只剩一个按钮。
     解决办法：列宽放宽、按钮组 nowrap 且宽度固定、禁止单元格换行。
*/
.action-group { display: inline-flex; align-items: center; justify-content: center; gap: 6px; padding: 2px; border: 1px solid var(--color-border); border-radius: 999px; background: var(--color-bg-page); white-space: nowrap; width: 128px; }
.action-group :deep(.el-button + .el-button) { margin-left: 0; }
.action-btn { height: 24px; min-height: 24px; width: 52px; margin: 0 !important; padding: 0 !important; border: none !important; border-radius: 999px !important; font-size: 12px; font-weight: 700; transition: all .15s; }
/* 操作列单元格：禁止换行，避免行高变化导致固定列错位 */
.cfg-table :deep(.el-table__fixed-right .cell),
.cfg-table :deep(.el-table-fixed-column--right .cell) { white-space: nowrap; overflow: visible; }
.action-btn.edit { color: var(--color-primary); background: var(--color-primary-bg); }
.action-btn.del { color: var(--color-error); background: #fff1f2; }
.action-btn.edit:hover { color: #fff; background: var(--color-primary); }
.action-btn.del:hover { color: #fff; background: var(--color-error); }
.pager-row { display: flex; justify-content: space-between; align-items: center; gap: 12px; padding-top: 14px; font-size: 12px; color: var(--color-text-secondary); }
.import-intro { display: flex; justify-content: space-between; gap: 16px; padding: 14px 16px; border: 1px solid #e0f2fe; border-radius: 14px; background: linear-gradient(135deg,#f0f9ff 0%,#ffffff 100%); margin-bottom: 16px; }
.import-intro h3 { margin: 0 0 6px; font-size: 16px; color: var(--color-text); }
.import-intro p { margin: 0; color: var(--color-text-secondary); font-size: 13px; line-height: 1.6; }
.import-alert { margin: 8px 0 14px; border-radius: 10px; }
.import-upload { margin-top: 8px; }
.import-upload :deep(.el-upload-dragger) { border-radius: 14px; background: var(--color-bg-page); }
.upload-icon { font-size: 34px; margin: 12px 0 4px; }
.import-result { margin-top: 16px; padding: 14px; border-radius: 14px; border: 1px solid var(--color-border); background: var(--color-bg-card); }

/* ===================================================================
 * 配置摘要区（delivery-summary）
 *
 * ★ 2026-11-04 修复：本区块的模板写的是 .delivery-summary / .summary-card，
 *   但组件自身的 <style> 里**从未定义**这两个类名 ——
 *   于是退化成浏览器默认的行内流布局，表现为截图里的「裸文本一行行堆叠」：
 *     标签与数字挤在一起、没有卡片、没有间距、数字与文字同字号同颜色。
 *   现在补齐完整样式，并与同目录 OperationLog.vue 的 .summary-card 保持一致：
 *     圆角 16px、1px 浅边框、白色底、极轻阴影、数字 24px 加粗。
 *
 * 设计要点：
 *   1. 拆成「配置规模」与「完整性检查」两个语义组，用组标题+分隔线区隔，
 *      避免 7 张卡片平铺时用户分不清哪几个是规模、哪几个是问题；
 *   2. 告警类卡片数值归零时降级为中性绿并显示「正常」语义色，
 *      避免「0 个问题」仍显示成刺眼的橙/红（现场常态是 0，不该天天报警）；
 *   3. 规模类卡片用蓝色系主色，告警类用橙/红，与左侧树的层级配色呼应；
 *   4. 卡片等宽平铺（repeat(auto-fit, minmax)），窄屏自动折行，不出现挤压。
 * =================================================================== */
.delivery-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: stretch;
  gap: 18px 24px;
  padding: 14px 16px;
  margin-bottom: 14px;
  border: 1px solid var(--color-border);
  border-radius: 16px;
  background: linear-gradient(135deg, var(--color-bg-page) 0%, #ffffff 100%);
  box-shadow: 0 1px 4px rgba(15, 23, 42, .04);
}
.summary-group { display: flex; flex-direction: column; gap: 8px; min-width: 0; flex: 0 0 auto; }
.summary-group + .summary-group { padding-left: 24px; border-left: 1px dashed var(--color-border-strong); }
.summary-group-title {
  color: var(--color-text-placeholder);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: .08em;
  line-height: 1;
  padding-left: 2px;
}
/* ★ 卡片横向流动。
   注意：不能用 repeat(auto-fit, minmax(84px,1fr)) ——
   .summary-group 是 flex item，宽度按内容收缩，
   auto-fit 在收缩容器里会塌陷成单列（实测卡片竖着排，占满竖向空间）。
   改用 grid-auto-flow: column 让卡片始终横向展开，列宽由 minmax 兜底。 */
.summary-cards { display: grid; grid-auto-flow: column; grid-auto-columns: minmax(84px, 1fr); gap: 10px; }

.summary-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 84px;
  padding: 10px 14px;
  border: 1px solid var(--color-border);
  border-radius: 12px;
  background: var(--color-bg-card);
  box-shadow: 0 1px 3px rgba(15, 23, 42, .05);
  transition: transform .15s ease, box-shadow .15s ease, border-color .15s ease;
}
.summary-card:hover { transform: translateY(-1px); box-shadow: 0 6px 14px rgba(15, 23, 42, .08); border-color: #dbe3ef; }
.summary-card span { color: var(--color-text-secondary); font-size: 12px; line-height: 1.2; white-space: nowrap; }
.summary-card strong { color: var(--color-primary-active); font-size: 22px; font-weight: 800; line-height: 1.1; font-variant-numeric: tabular-nums; }

/* 告警语义：橙（缺配置）/ 红（绑定异常） */
.summary-card.warn { border-color: #fde68a; background: #fffbeb; }
.summary-card.warn strong { color: var(--color-warning-dark); }
.summary-card.warn:hover { border-color: #fbbf24; }
.summary-card.danger { border-color: #fecaca; background: #fff1f2; }
.summary-card.danger strong { color: var(--color-error); }
.summary-card.danger:hover { border-color: #f87171; }

/* 数值为 0 时回到中性态：现场常态是 0，不应长期显示告警色 */
.summary-card.is-clear { border-color: #d1fae5; background: #ecfdf5; }
.summary-card.is-clear strong { color: #059669; }
.summary-card.is-clear:hover { border-color: #6ee7b7; }
.result-title { font-size: 14px; font-weight: 800; color: var(--color-text); margin-bottom: 10px; }
.result-grid { display: grid; grid-template-columns: repeat(4, minmax(0,1fr)); gap: 10px; margin-bottom: 12px; }
.result-grid div { padding: 10px; border-radius: 12px; background: var(--color-bg-page); border: 1px solid #eef2f7; }
.result-grid span { display: block; font-size: 12px; color: var(--color-text-secondary); margin-bottom: 4px; }
.result-grid strong { color: var(--color-text); font-size: 18px; }
.result-grid strong.ok { color: var(--color-success); }
.result-grid strong.danger { color: var(--color-error); }
.result-list { padding: 10px 12px; border-radius: 12px; margin-top: 10px; font-size: 12px; line-height: 1.6; }
.result-list p { margin: 4px 0 0; }
.result-list.warn { background: #fffbeb; color: #92400e; border: 1px solid #fde68a; }
.result-list.error { background: #fef2f2; color: #b91c1c; border: 1px solid #fecaca; }
@media (max-width: 900px) {
  .config-layout { flex-direction: column; }
  .config-sidebar { width: 100%; border-right: none; border-bottom: 1px solid var(--color-border); padding: 12px; }
  .sidebar-resizer { display: none; }
  .sidebar-nav { flex-direction: row; flex-wrap: wrap; }
  .nav-item { border-left: none; border-bottom: 2px solid transparent; padding: 8px 14px; }
  .nav-item.active { border-left: none; border-bottom-color: var(--color-primary); }
  .config-content { padding: 16px; }
  .content-header { flex-wrap: wrap; align-items: flex-start; }
  .header-actions { width: 100%; justify-content: flex-start; margin-left: 0; }
  .import-intro { flex-direction: column; }
  .result-grid { grid-template-columns: repeat(2, minmax(0,1fr)); }
  .pager-row { flex-direction: column; align-items: flex-start; }
  /* 摘要区窄屏：两个语义组改为上下堆叠，去掉左侧竖线 */
  .delivery-summary { flex-direction: column; gap: 14px; }
  .summary-group + .summary-group { padding-left: 0; border-left: none; padding-top: 14px; border-top: 1px dashed var(--color-border-strong); }
}

@media (max-width: 560px) {
  .summary-cards { grid-auto-flow: row; grid-template-columns: repeat(2, minmax(0, 1fr)); grid-auto-columns: auto; }
  .summary-card { padding: 9px 12px; }
  .summary-card strong { font-size: 20px; }
}

.tree-tools { display: flex; gap: 8px; margin: 10px 2px 12px; }
.tree-tools button { flex: 1; border: 1px solid #dbeafe; background: var(--color-primary-bg); color: var(--color-primary-active); border-radius: 999px; padding: 7px 10px; font-size: 12px; font-weight: 800; cursor: pointer; transition: all .15s ease; }
.tree-tools button:hover { border-color: var(--color-primary); background: #dbeafe; transform: translateY(-1px); box-shadow: 0 6px 14px rgba(37,99,235,.12); }
.config-tree { margin: 0 2px 14px; padding: 8px 6px; border: 1px solid #e8eef7; border-radius: 16px; background: var(--color-bg-card); max-height: calc(100vh - 360px); min-height: 260px; overflow: auto; box-shadow: inset 0 1px 0 rgba(255,255,255,.8); }
.config-tree :deep(.el-tree-node__content) { height: auto; min-height: 36px; border-radius: 12px; margin: 2px 0; padding-right: 6px; transition: background .15s ease, box-shadow .15s ease; }
.config-tree :deep(.el-tree-node__content:hover) { background: #f1f7ff; }
.config-tree :deep(.el-tree-node.is-current > .el-tree-node__content) { background: linear-gradient(90deg,#eaf2ff 0%,#f8fbff 100%); box-shadow: inset 3px 0 0 var(--color-primary); }
.config-tree :deep(.el-tree-node__expand-icon) { color: var(--color-text-placeholder); font-size: 13px; }
.config-tree :deep(.el-tree-node__expand-icon.expanded) { color: var(--color-primary); }
.tree-node { width: 100%; min-width: 0; display: grid; grid-template-columns: 28px minmax(0,1fr) auto; align-items: center; gap: 8px; padding: 4px 4px 4px 0; }
.tree-icon { width: 26px; height: 26px; border-radius: 9px; display: inline-flex; align-items: center; justify-content: center; font-size: 12px; font-weight: 900; color: var(--color-primary); background: var(--color-primary-bg); border: 1px solid #dbeafe; flex-shrink: 0; }
.tree-node-factory .tree-icon { color: #047857; background: #ecfdf5; border-color: #bbf7d0; }
.tree-node-line .tree-icon { color: #7c3aed; background: #f5f3ff; border-color: #ddd6fe; }
.tree-node-workstation .tree-icon { color: var(--color-warning-dark); background: #fffbeb; border-color: #fde68a; }
.tree-node-camera .tree-icon { color: #0891b2; background: #ecfeff; border-color: #bae6fd; }
.tree-text { min-width: 0; display: flex; flex-direction: column; gap: 1px; }
/* ★ 2026-11-04：改为「完整显示」而非省略号截断。
   工位名较长（如「产品正面胶污检测」），固定 288px 侧栏下会被截成「产品正面…」。
   现在允许换行完整展示；配合侧栏拖拽调宽，绝大多数情况一行即可放下。 */
.tree-label { display: block; max-width: 100%; color: #1e293b; font-size: 13px; font-weight: 750; line-height: 1.3; white-space: normal; word-break: break-word; overflow-wrap: anywhere; }
.tree-meta { color: var(--color-text-placeholder); font-size: 11px; line-height: 1.15; }
.tree-count { min-width: 24px; height: 20px; padding: 0 7px; display: inline-flex; align-items: center; justify-content: center; gap: 1px; border-radius: 999px; background: var(--color-bg-page); border: 1px solid var(--color-border-strong); color: #475569; font-size: 11px; font-weight: 800; }
/* ★ 2026-11-04：徽标单位（厂/线/位/机），消除「数字含义不明」导致的误读 */
.tree-count-unit { font-style: normal; font-size: 10px; font-weight: 700; opacity: 0.75; }
.config-tree :deep(.el-tree-node.is-current > .el-tree-node__content) .tree-count { background: var(--color-primary); border-color: var(--color-primary); color: #fff; }
.sidebar-tools { margin-top: 12px; padding-top: 12px; border-top: 1px dashed #dbe3ef; }
.sidebar-tools .nav-item { margin: 0 2px; border-left: none; border-radius: 12px; padding: 10px 12px; }
.sidebar-tools .nav-item.active { border-left: none; background: var(--color-primary-bg); box-shadow: inset 3px 0 0 var(--color-primary); }
</style>
