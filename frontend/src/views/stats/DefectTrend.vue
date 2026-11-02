<template>
  <div class="defect-trend">
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
        v-model="selectedLine"
        placeholder="选择产线"
        @change="handleLineChange"
        style="width: 200px"
      >
        <el-option
          v-for="item in lineOptions"
          :key="item.id"
          :label="item.lineName"
          :value="item.id"
        />
      </el-select>

      <el-select
        v-model="selectedWorkstation"
        placeholder="选择工位"
        clearable
        @change="handleFilterChange"
        style="width: 200px"
      >
        <el-option
          v-for="item in workstationOptions"
          :key="item.id"
          :label="item.stationName"
          :value="item.id"
        />
      </el-select>

      <el-select
        v-model="selectedDefect"
        placeholder="选择缺陷类型"
        @change="handleFilterChange"
        style="width: 200px"
      >
        <el-option
          v-for="item in defectOptions"
          :key="item.id"
          :label="item.defectName"
          :value="item.id"
        />
      </el-select>

      <el-button type="primary" @click="handleExport">
        <el-icon><Download /></el-icon>
        导出 Excel
      </el-button>
    </div>

    <!-- 图表区域 -->
    <el-row :gutter="16" class="chart-row">
      <!-- 图表：单缺陷趋势 -->
      <el-col :xs="24" :md="24">
        <div class="chart-card">
          <div class="chart-title">③ 单工位单缺陷变化趋势</div>
          <div ref="chartRef" class="chart-container"></div>
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
        <el-table-column prop="workstationName" label="工位名称" min-width="120" />
        <el-table-column prop="defectName" label="缺陷类型" width="150" align="center" />
        <el-table-column prop="defectCount" label="缺陷数量" width="100" align="right" />
        <el-table-column prop="defectRate" label="缺陷率(%)" width="100" align="right">
          <template #default="{ row }">
            {{ (row.defectRate * 100).toFixed(2) }}
          </template>
        </el-table-column>
        <el-table-column prop="timeBucket" label="时间段" width="150" align="center" />
        <el-table-column label="趋势" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="getTrendType(row.trend)" size="small">
              {{ getTrendText(row.trend) }}
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
import { getDefectTrend, exportStatsExcel } from '@/api/stats'
import { getLineList } from '@/api/line'
import { getWorkstationList } from '@/api/workstation'
import { getDefectTypeList } from '@/api/defect-type'
import * as echarts from 'echarts'
import { Download } from '@element-plus/icons-vue'

const loading = ref(false)
const dateRange = ref([])
const selectedLine = ref('')
const selectedWorkstation = ref('')
const selectedDefect = ref('')
const lineOptions = ref([])
const workstationOptions = ref([])
const defectOptions = ref([])
const tableData = ref([])

const chartRef = ref(null)
let chart = null

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
    const [lineRes, defectRes] = await Promise.all([
      getLineList(),
      getDefectTypeList()
    ])
    lineOptions.value = lineRes.data.data || lineRes.data || []
    defectOptions.value = defectRes.data.data || defectRes.data || []
    
    // 默认选中第一个产线和第一个缺陷类型
    if (lineOptions.value.length > 0) {
      selectedLine.value = lineOptions.value[0].id
      loadWorkstationOptions()
    }
    if (defectOptions.value.length > 0) {
      selectedDefect.value = defectOptions.value[0].id
    }
  } catch (e) {
    ElMessage.error('加载选项失败')
  }
}

const loadWorkstationOptions = async () => {
  if (!selectedLine.value) return
  
  try {
    const res = await getWorkstationList({ lineId: selectedLine.value })
    workstationOptions.value = res.data.data || res.data || []
  } catch (e) {
    ElMessage.error('加载工位选项失败')
  }
}

const handleLineChange = () => {
  selectedWorkstation.value = ''
  loadWorkstationOptions()
  loadData()
}

const loadData = async () => {
  if (!selectedLine.value || !selectedDefect.value) return
  
  loading.value = true
  try {
    const params = {
      lineId: selectedLine.value,
      startDate: dateRange.value[0],
      endDate: dateRange.value[1],
      workstationId: selectedWorkstation.value,
      defectTypeId: selectedDefect.value
    }
    
    const res = await getDefectTrend(params)
    const data = res.data.data || res.data || []
    
    tableData.value = data
    renderChart(data)
  } catch (e) {
    ElMessage.error('加载数据失败')
  } finally {
    loading.value = false
  }
}

const renderChart = (data) => {
  if (!chart) {
    chart = echarts.init(chartRef.value)
  }
    
  const xData = data.map(item => item.timeBucket || item.date)
  const defectData = data.map(item => item.defectCount || 0)
    
  const option = {
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: xData },
    yAxis: { type: 'value', name: '缺陷数量' },
    series: [
      { 
        name: '缺陷数量', 
        type: 'line', 
        smooth: true, 
        data: defectData,
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(22, 119, 255, 0.3)' },
            { offset: 1, color: 'rgba(22, 119, 255, 0.05)' }
          ])
        }
      }
    ]
  }
    
  chart.setOption(option)
}

const getTrendType = (trend) => {
  if (trend > 0) return 'danger'
  if (trend < 0) return 'success'
  return 'info'
}

const getTrendText = (trend) => {
  if (trend > 0) return '上升 ↗'
  if (trend < 0) return '下降 ↘'
  return '平稳 →'
}

const handleFilterChange = () => {
  loadData()
}

const handleExport = async () => {
  try {
    const params = {
      lineId: selectedLine.value,
      startDate: dateRange.value[0],
      endDate: dateRange.value[1],
      workstationId: selectedWorkstation.value,
      defectTypeId: selectedDefect.value
    }
      
    const res = await exportStatsExcel(params)
    const blob = new Blob([res.data], { type: 'application/vnd.ms-excel' })
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `缺陷趋势_${dateRange.value[0]}_${dateRange.value[1]}.xlsx`
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
  if (chart) chart.dispose()
})
</script>

<style scoped>
.defect-trend {
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
  height: 400px;
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
