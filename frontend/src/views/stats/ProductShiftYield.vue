<template>
  <div class="product-shift-yield">
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
      <!-- 图表：产品班次良率对比 -->
      <el-col :xs="24" :md="24">
        <div class="chart-card">
          <div class="chart-title">② 同一产品不同班次良率变化趋势</div>
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
        <el-table-column prop="productName" label="产品品牌" min-width="120" />
        <el-table-column label="白班良率(%)" width="120" align="right">
          <template #default="{ row }">
            {{ (row.dayShiftYield * 100).toFixed(2) }}
          </template>
        </el-table-column>
        <el-table-column label="晚班良率(%)" width="120" align="right">
          <template #default="{ row }">
            {{ (row.nightShiftYield * 100).toFixed(2) }}
          </template>
        </el-table-column>
        <el-table-column label="良率差异" width="120" align="right">
          <template #default="{ row }">
            <span :style="{ color: getYieldDiffColor(row.dayShiftYield, row.nightShiftYield) }">
              {{ (Math.abs(row.dayShiftYield - row.nightShiftYield) * 100).toFixed(2) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="timeBucket" label="时间段" width="150" align="center" />
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { ElMessage } from '@/utils/message.js'
import { getProductShiftYield, exportStatsExcel } from '@/api/stats'
import { getLineList } from '@/api/line'
import { getBrandList } from '@/api/brand'
import * as echarts from 'echarts'
import { Download } from '@element-plus/icons-vue'

const loading = ref(false)
const dateRange = ref([])
const selectedLine = ref('')
const selectedProduct = ref('')
const lineOptions = ref([])
const productOptions = ref([])
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
    const [lineRes, brandRes] = await Promise.all([
      getLineList(),
      getBrandList()
    ])
    lineOptions.value = lineRes.data.data || lineRes.data || []
    productOptions.value = brandRes.data.data || brandRes.data || []
    
    // 默认选中第一个产线
    if (lineOptions.value.length > 0) {
      selectedLine.value = lineOptions.value[0].id
    }
  } catch (e) {
    ElMessage.error('加载选项失败')
  }
}

const loadData = async () => {
  if (!selectedLine.value) return
  
  loading.value = true
  try {
    const params = {
      lineId: selectedLine.value,
      startDate: dateRange.value[0],
      endDate: dateRange.value[1],
      productId: selectedProduct.value
    }
    
    const res = await getProductShiftYield(params)
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
    
  chart.setOption(option)
}

const getYieldDiffColor = (day, night) => {
  const diff = Math.abs(day - night)
  if (diff > 0.1) return '#F56C6C'  // 差异大于10%显示红色
  if (diff > 0.05) return '#E6A23C' // 差异大于5%显示橙色
  return '#67C23A'  // 差异小于5%显示绿色
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
      productId: selectedProduct.value
    }
      
    const res = await exportStatsExcel(params)
    const blob = new Blob([res.data], { type: 'application/vnd.ms-excel' })
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `产品班次良率_${dateRange.value[0]}_${dateRange.value[1]}.xlsx`
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
.product-shift-yield {
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
