<template>
  <div class="line-dashboard">
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
        v-model="selectedShift"
        placeholder="选择班次"
        clearable
        @change="handleFilterChange"
        style="width: 150px"
      >
        <el-option label="白班（早八-晚八）" value="day" />
        <el-option label="晚班（晚八-早八）" value="night" />
      </el-select>

      <el-select
        v-model="selectedLine"
        placeholder="选择产线"
        @change="handleFilterChange"
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
        v-model="selectedProduct"
        placeholder="选择产品品牌"
        clearable
        @change="handleFilterChange"
        style="width: 180px"
      >
        <el-option
          v-for="item in productOptions"
          :key="item.id"
          :label="item.brandName"
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
      <!-- 图表1：单工位产量/良率趋势 -->
      <el-col :xs="24" :md="24">
        <div class="chart-card">
          <div class="chart-title">① 单工位产量、良率变化趋势</div>
          <div ref="chart1Ref" class="chart-container"></div>
        </div>
      </el-col>

      <!-- 图表2：产品班次良率对比 -->
      <el-col :xs="24" :md="12">
        <div class="chart-card">
          <div class="chart-title">② 同一产品不同班次良率变化趋势</div>
          <div ref="chart2Ref" class="chart-container"></div>
        </div>
      </el-col>

      <!-- 图表3：单缺陷趋势 -->
      <el-col :xs="24" :md="12">
        <div class="chart-card">
          <div class="chart-title">③ 单工位单缺陷变化趋势</div>
          <div class="defect-filter">
            <el-select
              v-model="selectedDefect"
              placeholder="选择缺陷类型"
              @change="loadChart3"
              style="width: 100%"
            >
              <el-option
                v-for="item in defectOptions"
                :key="item.id"
                :label="item.defectName"
                :value="item.id"
              />
            </el-select>
          </div>
          <div ref="chart3Ref" class="chart-container"></div>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { ElMessage } from '@/utils/message.js'
import { getLineDashboard, exportStatsExcel } from '@/api/stats'
import { getLineList } from '@/api/line'
import { getBrandList } from '@/api/brand'
import { getDefectTypeList } from '@/api/defect-type'
import * as echarts from 'echarts'
import { Download } from '@element-plus/icons-vue'

const loading = ref(false)
const dateRange = ref([])
const selectedShift = ref('')
const selectedLine = ref('')
const selectedProduct = ref('')
const selectedDefect = ref('')

const lineOptions = ref([])
const productOptions = ref([])
const defectOptions = ref([])

const chart1Ref = ref(null)
const chart2Ref = ref(null)
const chart3Ref = ref(null)

let chart1 = null
let chart2 = null
let chart3 = null

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
    const [lineRes, brandRes, defectRes] = await Promise.all([
      getLineList(),
      getBrandList(),
      getDefectTypeList()
    ])
    lineOptions.value = lineRes.data.data || lineRes.data || []
    productOptions.value = brandRes.data.data || brandRes.data || []
    defectOptions.value = defectRes.data.data || defectRes.data || []
    
    // 默认选中第一个产线
    if (lineOptions.value.length > 0) {
      selectedLine.value = lineOptions.value[0].id
    }
  } catch (e) {
    ElMessage.error('加载选项失败')
  }
}

const loadAllCharts = async () => {
  if (!selectedLine.value) return
  
  loading.value = true
  try {
    const params = {
      lineId: selectedLine.value,
      startDate: dateRange.value[0],
      endDate: dateRange.value[1],
      shift: selectedShift.value,
      productId: selectedProduct.value
    }
    
    const res = await getLineDashboard(params)
    const data = res.data.data || res.data
    
    renderChart1(data.workstationTrend || [])
    renderChart2(data.productShiftYield || [])
    if (selectedDefect.value) {
      renderChart3(data.defectTrend || [])
    }
  } catch (e) {
    ElMessage.error('加载图表数据失败')
  } finally {
    loading.value = false
  }
}

const renderChart1 = (data) => {
  if (!chart1) {
    chart1 = echarts.init(chart1Ref.value)
  }
  
  const xData = data.map(item => item.timeBucket || item.workstationName)
  const outputData = data.map(item => item.output || 0)
  const yieldData = data.map(item => (item.yield * 100).toFixed(2))
  
  const option = {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross' }
    },
    legend: { data: ['产量', '良率'] },
    xAxis: {
      type: 'category',
      data: xData
    },
    yAxis: [
      { type: 'value', name: '产量', position: 'left' },
      { type: 'value', name: '良率(%)', position: 'right', max: 100 }
    ],
    series: [
      { name: '产量', type: 'bar', data: outputData, yAxisIndex: 0 },
      { name: '良率', type: 'line', data: yieldData, yAxisIndex: 1 }
    ]
  }
  
  chart1.setOption(option)
}

const renderChart2 = (data) => {
  if (!chart2) {
    chart2 = echarts.init(chart2Ref.value)
  }
  
  const xData = data.map(item => item.productName || item.timeBucket)
  const dayShiftData = data.map(item => (item.dayShiftYield * 100).toFixed(2))
  const nightShiftData = data.map(item => (item.nightShiftYield * 100).toFixed(2))
  
  const option = {
    tooltip: { trigger: 'axis' },
    legend: { data: ['白班良率', '晚班良率'] },
    xAxis: { type: 'category', data: xData },
    yAxis: { type: 'value', name: '良率(%)', max: 100 },
    series: [
      { name: '白班良率', type: 'bar', data: dayShiftData },
      { name: '晚班良率', type: 'bar', data: nightShiftData }
    ]
  }
  
  chart2.setOption(option)
}

const renderChart3 = (data) => {
  if (!chart3) {
    chart3 = echarts.init(chart3Ref.value)
  }
    
  const xData = data.map(item => item.timeBucket || item.date)
  const defectData = data.map(item => item.defectCount || 0)
    
  const option = {
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: xData },
    yAxis: { type: 'value', name: '缺陷数量' },
    series: [
      { name: '缺陷数量', type: 'line', smooth: true, data: defectData }
    ]
  }
    
  chart3.setOption(option)
}

const loadChart3 = () => {
  if (!selectedDefect.value || !selectedLine.value) return
    
  const params = {
    lineId: selectedLine.value,
    startDate: dateRange.value[0],
    endDate: dateRange.value[1],
    shift: selectedShift.value,
    defectTypeId: selectedDefect.value
  }
    
  getLineDashboard(params).then(res => {
    const data = res.data.data || res.data
    renderChart3(data.defectTrend || [])
  }).catch(() => {
    ElMessage.error('加载缺陷趋势失败')
  })
}

const handleFilterChange = () => {
  loadAllCharts()
}

const handleExport = async () => {
  try {
    const params = {
      lineId: selectedLine.value,
      startDate: dateRange.value[0],
      endDate: dateRange.value[1],
      shift: selectedShift.value,
      productId: selectedProduct.value
    }
      
    const res = await exportStatsExcel(params)
    const blob = new Blob([res.data], { type: 'application/vnd.ms-excel' })
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `产线看板数据_${dateRange.value[0]}_${dateRange.value[1]}.xlsx`
    link.click()
    window.URL.revokeObjectURL(url)
    ElMessage.success('导出成功')
  } catch (e) {
    ElMessage.error('导出失败')
  }
}

const startAutoRefresh = () => {
  refreshTimer = setInterval(() => {
    loadAllCharts()
  }, 30000) // 30秒刷新
}

onMounted(() => {
  initDateRange()
  loadOptions().then(() => {
    loadAllCharts()
    startAutoRefresh()
  })
})

onUnmounted(() => {
  if (refreshTimer) {
    clearInterval(refreshTimer)
  }
  if (chart1) chart1.dispose()
  if (chart2) chart2.dispose()
  if (chart3) chart3.dispose()
})
</script>

<style scoped>
.line-dashboard {
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
  background: var(--color-bg-card);
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
  border-bottom: 1px solid var(--color-divider);
}

.chart-container {
  height: 350px;
  width: 100%;
}

.defect-filter {
  margin-bottom: 12px;
}
</style>
