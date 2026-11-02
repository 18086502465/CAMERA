<template>
  <div class="stats-overview">
    <!-- 筛选栏 -->
    <div class="filter-bar">
      <el-date-picker
        v-model="dateRange"
        type="daterange"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        value-format="YYYY-MM-DD"
        @change="handleFilterChange"
        style="width: 280px"
      />
      
      <el-select
        v-model="selectedFactory"
        placeholder="选择工厂"
        clearable
        @change="handleFilterChange"
        style="width: 180px"
      >
        <el-option
          v-for="item in factoryOptions"
          :key="item.id"
          :label="item.factoryName"
          :value="item.id"
        />
      </el-select>

      <el-select
        v-model="selectedLine"
        placeholder="选择产线"
        clearable
        @change="handleFilterChange"
        style="width: 180px"
      >
        <el-option
          v-for="item in lineOptions"
          :key="item.id"
          :label="item.lineName"
          :value="item.id"
        />
      </el-select>

      <el-button type="primary" @click="handleExport">
        <el-icon><Download /></el-icon>
        导出报表
      </el-button>
    </div>

    <!-- 统计卡片 -->
    <el-row :gutter="16" class="stats-cards">
      <el-col :xs="12" :sm="6">
        <div class="stat-card stat-card--primary">
          <div class="stat-icon">📊</div>
          <div class="stat-content">
            <div class="stat-value">{{ stats.totalOutput || 0 }}</div>
            <div class="stat-label">总产里</div>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6">
        <div class="stat-card stat-card--success">
          <div class="stat-icon">✅</div>
          <div class="stat-content">
            <div class="stat-value">{{ (stats.avgYield * 100).toFixed(2) || 0 }}%</div>
            <div class="stat-label">平均良率</div>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6">
        <div class="stat-card stat-card--warning">
          <div class="stat-icon">⚠️</div>
          <div class="stat-content">
            <div class="stat-value">{{ stats.totalDefects || 0 }}</div>
            <div class="stat-label">总缺陷数</div>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6">
        <div class="stat-card stat-card--danger">
          <div class="stat-icon">🔴</div>
          <div class="stat-content">
            <div class="stat-value">{{ stats.offlineCameras || 0 }}</div>
            <div class="stat-label">离线相机</div>
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- 图表区域 -->
    <el-row :gutter="16" class="chart-row">
      <!-- 产量趋势图 -->
      <el-col :xs="24" :md="12">
        <div class="chart-card">
          <div class="chart-title">产量趋势</div>
          <div ref="outputChartRef" class="chart-container"></div>
        </div>
      </el-col>

      <!-- 良率趋势图 -->
      <el-col :xs="24" :md="12">
        <div class="chart-card">
          <div class="chart-title">良率趋势</div>
          <div ref="yieldChartRef" class="chart-container"></div>
        </div>
      </el-col>

      <!-- 缺陷分布图 -->
      <el-col :xs="24" :md="12">
        <div class="chart-card">
          <div class="chart-title">缺陷类型分布</div>
          <div ref="defectChartRef" class="chart-container"></div>
        </div>
      </el-col>

      <!-- 产线对比图 -->
      <el-col :xs="24" :md="12">
        <div class="chart-card">
          <div class="chart-title">产线良率对比</div>
          <div ref="lineCompareRef" class="chart-container"></div>
        </div>
      </el-col>
    </el-row>

    <!-- 详细数据表格 -->
    <div class="table-card">
      <div class="table-title">详细数据</div>
      <el-table
        :data="tableData"
        v-loading="loading"
        border
        stripe
        empty-text="暂无数据"
        style="width: 100%"
      >
        <el-table-column prop="lineName" label="产线名称" min-width="120" />
        <el-table-column prop="totalOutput" label="总产量" width="100" align="right" />
        <el-table-column prop="goodOutput" label="良品数" width="100" align="right" />
        <el-table-column prop="defectOutput" label="缺陷数" width="100" align="right" />
        <el-table-column label="良率" width="100" align="right">
          <template #default="{ row }">
            {{ (row.yield * 100).toFixed(2) }}%
          </template>
        </el-table-column>
        <el-table-column prop="avgSpeed" label="平均速度" width="100" align="right" />
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === '正常' ? 'success' : 'danger'" size="small">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { ElMessage } from '@/utils/message.js'
import { getStatsOverview, exportStatsExcel } from '@/api/stats'
import { getFactoryList } from '@/api/factory'
import { getLineList } from '@/api/line'
import * as echarts from 'echarts'
import { Download } from '@element-plus/icons-vue'

const loading = ref(false)
const dateRange = ref([])
const selectedFactory = ref('')
const selectedLine = ref('')
const factoryOptions = ref([])
const lineOptions = ref([])
const tableData = ref([])

const stats = ref({
  totalOutput: 0,
  avgYield: 0,
  totalDefects: 0,
  offlineCameras: 0
})

const outputChartRef = ref(null)
const yieldChartRef = ref(null)
const defectChartRef = ref(null)
const lineCompareRef = ref(null)

let outputChart = null
let yieldChart = null
let defectChart = null
let lineCompareChart = null

// 定时刷新定时器
let refreshTimer = null

const initDateRange = () => {
  const now = new Date()
  const sevenDaysAgo = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000)
  dateRange.value = [
    sevenDaysAgo.toISOString().split('T')[0],
    now.toISOString().split('T')[0]
  ]
}

const loadOptions = async () => {
  try {
    const [factoryRes, lineRes] = await Promise.all([
      getFactoryList(),
      getLineList()
    ])
    factoryOptions.value = factoryRes.data.data || factoryRes.data || []
    lineOptions.value = lineRes.data.data || lineRes.data || []
  } catch (e) {
    ElMessage.error('加载选项失败')
  }
}

const loadData = async () => {
  loading.value = true
  try {
    const params = {
      startDate: dateRange.value[0],
      endDate: dateRange.value[1],
      factoryId: selectedFactory.value,
      lineId: selectedLine.value
    }
    
    const res = await getStatsOverview(params)
    const data = res.data.data || res.data
    
    stats.value = data.stats || stats.value
    tableData.value = data.details || []
    
    renderOutputChart(data.outputTrend || [])
    renderYieldChart(data.yieldTrend || [])
    renderDefectChart(data.defectDist || [])
    renderLineCompareChart(data.lineCompare || [])
  } catch (e) {
    ElMessage.error('加载数据失败')
  } finally {
    loading.value = false
  }
}

const renderOutputChart = (data) => {
  if (!outputChart) {
    outputChart = echarts.init(outputChartRef.value)
  }
  
  const option = {
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: data.map(item => item.timeBucket) },
    yAxis: { type: 'value', name: '产量' },
    series: [{ name: '产量', type: 'line', smooth: true, data: data.map(item => item.output) }]
  }
    
  outputChart.setOption(option)
}

const renderYieldChart = (data) => {
  if (!yieldChart) {
    yieldChart = echarts.init(yieldChartRef.value)
  }
    
  const option = {
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: data.map(item => item.timeBucket) },
    yAxis: { type: 'value', name: '良率(%)', max: 100 },
    series: [{ name: '良率', type: 'line', smooth: true, data: data.map(item => (item.yield * 100).toFixed(2)) }]
  }
    
  yieldChart.setOption(option)
}

const renderDefectChart = (data) => {
  if (!defectChart) {
    defectChart = echarts.init(defectChartRef.value)
  }
    
  const option = {
    tooltip: { trigger: 'item' },
    legend: { bottom: 0 },
    series: [{
      name: '缺陷分布',
      type: 'pie',
      radius: ['40%', '70%'],
      data: data.map(item => ({ name: item.defectName, value: item.count }))
    }]
  }
    
  defectChart.setOption(option)
}

const renderLineCompareChart = (data) => {
  if (!lineCompareChart) {
    lineCompareChart = echarts.init(lineCompareRef.value)
  }
    
  const option = {
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: data.map(item => item.lineName) },
    yAxis: { type: 'value', name: '良率(%)', max: 100 },
    series: [{ name: '良率', type: 'bar', data: data.map(item => (item.yield * 100).toFixed(2)) }]
  }
    
  lineCompareChart.setOption(option)
}

const handleFilterChange = () => {
  loadData()
}

const handleExport = async () => {
  try {
    const params = {
      startDate: dateRange.value[0],
      endDate: dateRange.value[1],
      factoryId: selectedFactory.value,
      lineId: selectedLine.value
    }
      
    const res = await exportStatsExcel(params)
    const blob = new Blob([res.data], { type: 'application/vnd.ms-excel' })
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `统计报表_${dateRange.value[0]}_${dateRange.value[1]}.xlsx`
    link.click()
    window.URL.revokeObjectURL(url)
    ElMessage.success('导出成功')
  } catch (e) {
    ElMessage.error('导出失败')
  }
}

const startAutoRefresh = () => {
  refreshTimer = setInterval(() => {
    loadData()
  }, 30000) // 30秒刷新
}

onMounted(() => {
  initDateRange()
  loadOptions().then(() => {
    loadData()
    startAutoRefresh()
  })
})

onUnmounted(() => {
  if (refreshTimer) {
    clearInterval(refreshTimer)
  }
  if (outputChart) outputChart.dispose()
  if (yieldChart) yieldChart.dispose()
  if (defectChart) defectChart.dispose()
  if (lineCompareChart) lineCompareChart.dispose()
})
</script>

<style scoped>
.stats-overview {
  padding: 20px;
  background: #f5f5f5;
  min-height: calc(100vh - 56px);
}

.filter-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
  align-items: center;
}

.stats-cards {
  margin-bottom: 16px;
}

.stat-card {
  background: #fff;
  border-radius: 8px;
  padding: 16px;
  display: flex;
  align-items: center;
  gap: 12px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.05);
  margin-bottom: 12px;
}

.stat-card--primary { border-left: 4px solid #1677ff; }
.stat-card--success { border-left: 4px solid #52c41a; }
.stat-card--warning { border-left: 4px solid #fa8c16; }
.stat-card--danger { border-left: 4px solid #ff4d4f; }

.stat-icon {
  font-size: 32px;
}

.stat-content {
  flex: 1;
}

.stat-value {
  font-size: 24px;
  font-weight: 700;
  color: #1f1f1f;
  line-height: 1.2;
}

.stat-label {
  font-size: 12px;
  color: #8c8c8c;
  margin-top: 4px;
}

.chart-row {
  margin-bottom: 16px;
}

.chart-card {
  background: #fff;
  border-radius: 8px;
  padding: 16px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.05);
  margin-bottom: 16px;
}

.chart-title {
  font-size: 16px;
  font-weight: 600;
  color: #1f1f1f;
  margin-bottom: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid #f0f0f0;
}

.chart-container {
  height: 300px;
  width: 100%;
}

.table-card {
  background: #fff;
  border-radius: 8px;
  padding: 16px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.05);
}

.table-title {
  font-size: 16px;
  font-weight: 600;
  color: #1f1f1f;
  margin-bottom: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid #f0f0f0;
}
</style>
