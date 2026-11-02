<template>
  <div class="line-analytics">
    <section class="hero-card">
      <div>
        <p class="eyebrow">LINE ANALYTICS</p>
        <h2>产线统计</h2>
        <p>支持动态图表模板、图表新增/编辑/删除、拖拽排序、尺寸调整、独立筛选、导出和日聚合。</p>
      </div>
      <div class="hero-actions">
        <el-select v-model="currentDashboardId" filterable placeholder="看板模板" style="width:220px" @change="loadDashboardById">
          <el-option v-for="d in dashboardList" :key="d.dashboardId" :label="d.dashboardName" :value="d.dashboardId" />
        </el-select>
        <el-button plain @click="openDashboardDialog()">新建模板</el-button>
        <el-button plain :disabled="!dashboard" @click="openDashboardDialog(dashboard)">编辑模板</el-button>
        <el-button plain :disabled="!dashboard" @click="openDashboardCopyDialog('copy')">复制模板</el-button>
        <el-button plain :disabled="!dashboard" @click="openDashboardCopyDialog('saveAs')">另存为</el-button>
        <el-button type="warning" plain :disabled="!visibleWidgets.length" @click="openPresentation()">看板大屏</el-button>
        <el-button type="primary" :loading="loading" @click="loadData">刷新报表</el-button>
      </div>
    </section>

    <section class="filter-card">
      <el-date-picker v-model="timeRange" type="datetimerange" range-separator="至" start-placeholder="开始时间" end-placeholder="结束时间" value-format="YYYY-MM-DD HH:mm:ss" style="width:380px" @change="loadData" />
      <el-select v-model="filters.productId" clearable filterable placeholder="全部产品" style="width:180px" @change="loadData">
        <el-option v-for="p in metadata.products" :key="p.productId" :label="p.productName" :value="p.productId" />
      </el-select>
      <el-select v-model="filters.shiftId" clearable placeholder="全部班次" style="width:140px" @change="loadData">
        <el-option v-for="s in metadata.shifts" :key="s.shiftId" :label="s.shiftName" :value="s.shiftId" />
      </el-select>
      <el-select v-model="filters.cameraId" clearable filterable placeholder="全部相机" style="width:220px" @change="loadData">
        <el-option v-for="c in filteredCameras" :key="c.cameraId" :label="cameraOptionLabel(c)" :value="c.cameraId" />
      </el-select>
      <el-select v-model="filters.defectTypeId" clearable filterable placeholder="全部缺陷类型" style="width:220px" @change="loadData">
        <el-option v-for="d in filteredDefectTypes" :key="d.defectTypeId" :label="d.defectName" :value="d.defectTypeId" />
      </el-select>
      <el-select v-model="filters.severity" clearable placeholder="全部严重度" style="width:150px" @change="loadData">
        <el-option label="HIGH" value="HIGH" />
        <el-option label="MEDIUM" value="MEDIUM" />
        <el-option label="LOW" value="LOW" />
      </el-select>
      <el-button plain @click="resetFilters">重置筛选</el-button>
      <el-tag :type="summary.aggregationLevel === 'DAY' ? 'warning' : 'info'">{{ summary.aggregationLevel === 'DAY' ? '日聚合' : '30分钟聚合' }}</el-tag>
    </section>

    <section class="kpi-grid" v-loading="loading">
      <div class="kpi-card"><span>检测总数</span><strong>{{ number(summary.kpi.totalCount) }}</strong><small>增量批次累计</small></div>
      <div class="kpi-card ok"><span>OK数</span><strong>{{ number(summary.kpi.okCount) }}</strong><small>良品数量</small></div>
      <div class="kpi-card ng"><span>NG数</span><strong>{{ number(summary.kpi.ngCount) }}</strong><small>异常数量</small></div>
      <div class="kpi-card yield"><span>良率</span><strong>{{ percent(summary.kpi.yieldRate) }}</strong><small>OK / 检测总数</small></div>
      <div class="kpi-card warn"><span>缺陷数</span><strong>{{ number(summary.kpi.defectCount) }}</strong><small>映射后的缺陷计数</small></div>
    </section>

    <section class="ops-card aggregation-card">
      <div class="aggregation-copy">
        <div class="ops-title-row">
          <h3>性能加速：日聚合</h3>
          <el-tag :type="summary.aggregationLevel === 'DAY' ? 'warning' : 'info'">
            当前：{{ summary.aggregationLevel === 'DAY' ? '日聚合查询' : '30分钟聚合查询' }}
          </el-tag>
        </div>
        <p>
          日聚合会把检测明细按天预汇总，大时间范围报表可直接读取汇总表，避免现场看板查询变慢。
          建议夜间自动执行；如补算历史数据，可手动执行当前筛选时间范围。
        </p>
        <div v-if="aggregationJobs.length" class="job-list">
          <span v-for="job in aggregationJobs.slice(0,3)" :key="job.jobId" class="job-chip">
            <b>#{{ job.jobId }}</b>
            <em :class="'status-'+String(job.status||'').toLowerCase()">{{ job.status || '-' }}</em>
            {{ aggregationJobText(job) }}
          </span>
        </div>
        <div class="schedule-line">
          夜间自动执行：
          <b>{{ aggregationSchedule.enabled ? '已开启' : '已关闭' }}</b>
          <span>{{ aggregationSchedule.scheduleTime || '02:00' }} 执行，补算最近 {{ aggregationSchedule.lookbackDays || 7 }} 天</span>
        </div>
      </div>
      <div class="ops-actions">
        <el-button type="primary" plain :loading="aggregationRunning" @click="runAggregation">执行当前时间范围日聚合</el-button>
        <el-button plain @click="openAggregationTaskDialog">任务列表 / 定时配置</el-button>
        <el-tag v-if="lastAggregationJob" :type="jobTagType(lastAggregationJob.status)">{{ lastAggregationJob.status }} · {{ lastAggregationJob.message || '-' }}</el-tag>
      </div>
    </section>

    <section class="builder-layout">
      <aside class="widget-palette">
        <h3>图表组件库</h3>
        <p class="panel-tip">点击添加；画布中可拖拽排序，右下角可拖拽调整尺寸。</p>
        <button v-for="w in widgetTypes" :key="w.code" class="widget-btn" @click="openWidgetDialog(w)">
          <b>{{ w.name }}</b><span>{{ w.description }}</span>
        </button>
      </aside>

      <main class="canvas">
        <div class="canvas-head">
          <div><h3>{{ dashboard?.dashboardName || '动态报表画布' }}</h3><p>配置保存后会刷新模板、报表数据并定位到刚保存的图表。</p></div>
          <div class="canvas-actions">
            <el-tag type="success">P1 动态配置</el-tag>
            <el-button size="small" type="warning" plain :disabled="!visibleWidgets.length" @click="openPresentation()">大屏展示</el-button>
            <el-button size="small" type="primary" plain @click="openWidgetDialog()">新增图表</el-button>
            <el-button size="small" plain :disabled="!dashboard" @click="openDashboardCopyDialog('copy')">复制模板</el-button>
            <el-button size="small" plain :disabled="!dashboard" @click="openDashboardCopyDialog('saveAs')">另存为</el-button>
            <el-button size="small" plain @click="refreshDashboard">刷新模板</el-button>
            <el-button size="small" type="danger" plain :disabled="!dashboard || Number(dashboard.isDefault) === 1" @click="removeDashboard(dashboard)">删除模板</el-button>
          </div>
        </div>

        <div v-if="visibleWidgets.length" class="chart-grid" v-loading="dashboardLoading">
          <article
            v-for="w in visibleWidgets"
            :key="w.widgetId"
            class="chart-card"
            :class="widgetCardClass(w)"
            :style="widgetCardStyle(w)"
            draggable="true"
            @dragstart="handleDragStart(w)"
            @dragover.prevent
            @drop.prevent="handleDrop(w)"
            @click="selectedWidget=w"
          >
            <div class="card-head">
              <div><h4>{{ w.widgetName }}</h4><span>{{ widgetSubtitle(w) }}</span></div>
              <div class="card-actions" @click.stop>
                <el-button size="small" type="primary" plain @click="editWidget(w)">编辑</el-button>
                <el-button size="small" type="danger" plain @click="removeWidget(w)">删除</el-button>
                <el-dropdown trigger="click" @command="cmd => handleWidgetCommand(cmd, w)">
                  <el-button size="small" plain>更多</el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item command="present">最大化展示</el-dropdown-item>
                      <el-dropdown-item command="exportImage">导出图片</el-dropdown-item>
                      <el-dropdown-item command="exportExcel">导出Excel</el-dropdown-item>
                      <el-dropdown-item divided command="narrow">缩小宽度</el-dropdown-item>
                      <el-dropdown-item command="wide">放大宽度</el-dropdown-item>
                      <el-dropdown-item command="higher">增加高度</el-dropdown-item>
                      <el-dropdown-item command="lower">降低高度</el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </div>
            </div>

            <div v-if="widgetMetric(w)==='quality'" class="quality-mini-grid">
              <div><span>检测总数</span><b>{{ number(summaryFor(w).kpi.totalCount) }}</b></div>
              <div><span>OK数</span><b class="ok">{{ number(summaryFor(w).kpi.okCount) }}</b></div>
              <div><span>NG数</span><b class="ng">{{ number(summaryFor(w).kpi.ngCount) }}</b></div>
              <div><span>良率</span><b class="yield">{{ percent(summaryFor(w).kpi.yieldRate) }}</b></div>
            </div>
            <div v-else-if="isChartWidget(w)" class="echart-wrap">
              <div v-if="hasChartData(w)" :ref="el=>setChartRef(w.widgetId,el)" class="echart-box"></div>
              <EmptyState v-else title="暂无图表数据" text="当前筛选条件下没有可绘制的数据。" />
            </div>
            <div v-else-if="widgetMetric(w)==='defectPivot'" class="table-wrap">
              <el-table :data="pivotRowsFor(w)" size="small" border>
                <el-table-column prop="productName" label="产品" min-width="120" fixed="left" />
                <el-table-column v-for="c in pivotColumnsFor(w)" :key="c.defectTypeId" :prop="String(c.defectTypeId)" :label="c.defectName" min-width="130" align="center">
                  <template #default="{ row }">{{ number(row[c.defectTypeId] || 0) }}</template>
                </el-table-column>
              </el-table>
            </div>
            <div v-else-if="widgetMetric(w)==='mappingExceptions'" class="table-wrap">
              <el-table :data="summaryFor(w).mappingExceptions" size="small" border>
                <el-table-column prop="createTime" label="时间" width="170" />
                <el-table-column prop="cameraCode" label="相机编号" width="120" />
                <el-table-column prop="productName" label="产品" width="110" />
                <el-table-column prop="sourceCode" label="上报编码" width="100" />
                <el-table-column prop="exceptionType" label="异常类型" width="140" />
                <el-table-column prop="exceptionMessage" label="失败原因" min-width="220" show-overflow-tooltip />
              </el-table>
            </div>
            <EmptyState v-else title="暂不支持的指标" :text="'指标 ' + widgetMetric(w) + ' 暂未接入展示组件。'" />
            <span class="resize-handle" title="拖拽调整图表尺寸" @mousedown.stop.prevent="beginResize(w,$event)"></span>
          </article>
        </div>
        <EmptyState v-else title="当前看板暂无图表" text="请从左侧组件库添加图表，或点击新增图表。">
          <el-button type="primary" @click="openWidgetDialog()">新增图表</el-button>
        </EmptyState>
      </main>

      <aside class="config-panel">
        <div class="panel-title-row">
          <h3>当前图表</h3>
          <el-tag size="small" type="info">配置说明</el-tag>
        </div>
        <p class="panel-tip">
          用于解释当前选中图表的数据口径、筛选方式和布局尺寸，并提供编辑、删除、最大化等快捷操作。
        </p>
        <div v-if="selectedWidget" class="selected-widget-card">
          <div class="selected-main">
            <strong>{{ selectedWidget.widgetName }}</strong>
            <span>{{ widgetSubtitle(selectedWidget) }}</span>
          </div>
          <div class="meta-list">
            <p><label>图表类型</label><b>{{ widgetTypeText(selectedWidget) }}</b></p>
            <p><label>统计指标</label><b>{{ metricLabel(widgetMetric(selectedWidget)) }}</b></p>
            <p><label>布局尺寸</label><b>{{ widgetLayout(selectedWidget).w || 6 }}栅格 × {{ widgetLayout(selectedWidget).h || 2 }}行</b></p>
            <p><label>筛选口径</label><b>{{ Number(selectedWidget.followGlobalFilter ?? 1) === 1 ? '跟随顶部全局筛选' : independentFilterText(selectedWidget) }}</b></p>
          </div>
          <div class="quick-actions">
            <el-button size="small" type="primary" plain @click="editWidget(selectedWidget)">编辑配置</el-button>
            <el-button size="small" type="warning" plain @click="openPresentation(selectedWidget)">最大化</el-button>
            <el-button size="small" type="danger" plain @click="removeWidget(selectedWidget)">删除</el-button>
          </div>
        </div>
        <EmptyState v-else title="未选择图表" text="点击画布中的任一图表，可在这里查看配置和快捷操作。" />
        <div class="config-box dimension-box">
          <label>新增图表可选维度</label>
          <p>新增或编辑图表时，可围绕时间、产线、工位、相机、产品、班次、缺陷类型等维度组合分析。</p>
          <el-tag v-for="d in dimensions" :key="d.code" class="dim-tag">{{ d.name }}</el-tag>
        </div>
      </aside>
    </section>

    <el-dialog v-model="widgetDialogVisible" class="analytics-config-dialog" :title="editingWidgetId ? '编辑图表' : '新增图表'" width="920px" destroy-on-close @opened="renderPreviewChart" @closed="disposePreviewChart">
      <div class="widget-guide">
        <div class="guide-step active"><b>1</b><span>选择图表</span><small>选择展示形态</small></div>
        <div class="guide-step active"><b>2</b><span>选择指标</span><small>确定统计口径</small></div>
        <div class="guide-step active"><b>3</b><span>选择维度</span><small>决定分析方向</small></div>
        <div class="guide-step active"><b>4</b><span>预览保存</span><small>确认后生效</small></div>
      </div>
      <div class="wizard-presets">
        <div class="preset-copy"><strong>推荐配置</strong><span>按现场常用问题快速生成图表，减少误配。</span></div>
        <button v-for="preset in widgetPresets" :key="preset.key" type="button" class="preset-btn" @click="applyWidgetPreset(preset)">
          <b>{{ preset.name }}</b><small>{{ preset.description }}</small>
        </button>
      </div>
      <el-form :model="widgetForm" label-width="110px">
        <el-form-item label="图表名称" required><el-input v-model="widgetForm.widgetName" placeholder="请输入图表名称" /></el-form-item>
        <div class="form-row">
          <el-form-item label="图表类型" class="row-item"><el-select v-model="widgetForm.widgetType" style="width:100%" @change="onWidgetTypeChange"><el-option v-for="w in widgetTypes" :key="w.code" :label="w.name" :value="w.code" /></el-select></el-form-item>
          <el-form-item label="统计指标" class="row-item"><el-select v-model="widgetForm.metric" style="width:100%" @change="onMetricChange"><el-option v-for="m in metricOptions" :key="m.value" :label="m.label" :value="m.value" /></el-select></el-form-item>
        </div>
        <div class="metric-hint">
          <strong>{{ metricLabel(widgetForm.metric) }}</strong>
          <span>{{ metricDescription(widgetForm.metric) }}</span>
        </div>
        <el-form-item label="分析维度">
          <el-checkbox-group v-model="widgetForm.dimensions" class="dimension-checks">
            <el-checkbox-button v-for="d in dimensions" :key="d.code" :label="d.code">
              {{ d.name }}
            </el-checkbox-button>
          </el-checkbox-group>
          <p class="form-help">维度用于说明图表统计的拆分方式；例如按“产品 + 缺陷类型”生成透视表，按“时间”生成趋势。</p>
        </el-form-item>
        <div class="form-row">
          <el-form-item label="宽度" class="row-item"><el-select v-model="widgetForm.width"><el-option label="半屏（6栅格）" :value="6" /><el-option label="整行（12栅格）" :value="12" /></el-select></el-form-item>
          <el-form-item label="高度" class="row-item"><el-select v-model="widgetForm.height"><el-option label="小（2行）" :value="2" /><el-option label="中（3行）" :value="3" /><el-option label="大（4行）" :value="4" /></el-select></el-form-item>
        </div>
        <div class="form-row">
          <el-form-item label="排序" class="row-item"><el-input-number v-model="widgetForm.sortOrder" :min="0" :step="1" style="width:100%" /></el-form-item>
          <el-form-item label="跟随筛选" class="row-item"><el-switch v-model="widgetForm.followGlobalFilter" active-text="跟随顶部" inactive-text="独立筛选" /></el-form-item>
        </div>
        <div v-if="!widgetForm.followGlobalFilter" class="independent-box">
          <div class="form-row">
            <el-form-item label="独立产品" class="row-item"><el-select v-model="widgetForm.filterProductId" clearable filterable placeholder="全部产品"><el-option v-for="p in metadata.products" :key="p.productId" :label="p.productName" :value="p.productId" /></el-select></el-form-item>
            <el-form-item label="独立相机" class="row-item"><el-select v-model="widgetForm.filterCameraId" clearable filterable placeholder="全部相机"><el-option v-for="c in metadata.cameras" :key="c.cameraId" :label="cameraOptionLabel(c)" :value="c.cameraId" /></el-select></el-form-item>
          </div>
          <div class="form-row">
            <el-form-item label="独立缺陷" class="row-item"><el-select v-model="widgetForm.filterDefectTypeId" clearable filterable placeholder="全部缺陷类型"><el-option v-for="d in metadata.defectTypes" :key="d.defectTypeId" :label="d.defectName" :value="d.defectTypeId" /></el-select></el-form-item>
            <el-form-item label="独立严重度" class="row-item"><el-select v-model="widgetForm.filterSeverity" clearable placeholder="全部严重度"><el-option label="HIGH" value="HIGH" /><el-option label="MEDIUM" value="MEDIUM" /><el-option label="LOW" value="LOW" /></el-select></el-form-item>
          </div>
        </div>
      </el-form>
      <div class="preview-panel">
        <div class="preview-head"><strong>实时预览</strong><span>预览使用当前全局筛选数据，保存后按图表筛选配置生效。</span></div>
        <div v-if="widgetMetric(previewWidget)==='quality'" class="quality-mini-grid preview-quality">
          <div><span>检测总数</span><b>{{ number(summary.kpi.totalCount) }}</b></div>
          <div><span>OK数</span><b class="ok">{{ number(summary.kpi.okCount) }}</b></div>
          <div><span>NG数</span><b class="ng">{{ number(summary.kpi.ngCount) }}</b></div>
          <div><span>良率</span><b class="yield">{{ percent(summary.kpi.yieldRate) }}</b></div>
        </div>
        <div v-else-if="isChartWidget(previewWidget)" class="preview-chart-wrap"><div v-if="hasChartData(previewWidget)" ref="previewChartRef" class="preview-chart"></div><EmptyState v-else title="暂无预览数据" text="当前筛选条件下没有可绘制的数据。" /></div>
        <div v-else-if="widgetMetric(previewWidget)==='defectPivot'" class="table-wrap preview-table">
          <el-table :data="pivotRowsFor(previewWidget).slice(0,3)" size="small" border><el-table-column prop="productName" label="产品" min-width="120" fixed="left" /><el-table-column v-for="c in pivotColumnsFor(previewWidget).slice(0,6)" :key="c.defectTypeId" :prop="String(c.defectTypeId)" :label="c.defectName" min-width="120" align="center"><template #default="{ row }">{{ number(row[c.defectTypeId] || 0) }}</template></el-table-column></el-table>
        </div>
        <EmptyState v-else title="该类型暂无图形预览" text="保存后可在看板中查看完整数据。" />
      </div>
      <template #footer>
        <el-button :disabled="savingWidget" @click="widgetDialogVisible=false">取消</el-button>
        <button class="primary-save-btn" type="button" :disabled="savingWidget" @click.prevent.stop="requestSaveWidget">
          保存
        </button>
      </template>
    </el-dialog>

    <el-dialog v-model="widgetSaveConfirmVisible" class="analytics-config-dialog save-confirm-dialog" :title="pendingWidgetSave?.isEditing ? '确认保存图表修改' : '确认新增图表'" width="520px" append-to-body destroy-on-close>
      <div class="save-confirm-panel">
        <div class="save-confirm-icon">!</div>
        <div>
          <h3>{{ pendingWidgetSave?.isEditing ? '保存后将立即刷新当前看板' : '新增后将立即刷新当前看板' }}</h3>
          <p>
            图表名称：<b>{{ pendingWidgetSave?.widgetName || '-' }}</b>
          </p>
          <p>
            图表类型：<b>{{ widgetTypeText(pendingWidgetSave?.payload || {}) }}</b>
            ；统计指标：<b>{{ metricLabel(pendingWidgetSave?.payload?.metricJson?.metric) }}</b>
          </p>
          <p class="risk-tip">确认后会写入图表配置，并重新加载看板模板、图表数据和图表渲染结果。</p>
        </div>
      </div>
      <template #footer>
        <el-button :disabled="savingWidget" @click="widgetSaveConfirmVisible=false">取消</el-button>
        <el-button type="primary" :loading="savingWidget" @click="confirmSaveWidget">确认保存并刷新</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="widgetDeleteConfirmVisible" class="analytics-config-dialog save-confirm-dialog" title="确认删除图表" width="520px" append-to-body destroy-on-close>
      <div class="save-confirm-panel delete-confirm-panel">
        <div class="save-confirm-icon danger">!</div>
        <div>
          <h3>删除后将立即刷新当前看板</h3>
          <p>
            图表名称：<b>{{ pendingWidgetDelete?.widgetName || '-' }}</b>
          </p>
          <p>
            图表类型：<b>{{ widgetTypeText(pendingWidgetDelete || {}) }}</b>
            ；统计指标：<b>{{ metricLabel(widgetMetric(pendingWidgetDelete || {})) }}</b>
          </p>
          <p class="risk-tip danger">确认后该图表会从当前看板移除，历史检测数据不受影响；如误删，需要重新新增图表。</p>
        </div>
      </div>
      <template #footer>
        <el-button :disabled="deletingWidget" @click="widgetDeleteConfirmVisible=false">取消</el-button>
        <el-button type="danger" :loading="deletingWidget" @click="confirmDeleteWidget">确认删除并刷新</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dashboardDialogVisible" class="analytics-config-dialog" :title="editingDashboardId ? '编辑看板模板' : '新建看板模板'" width="520px" destroy-on-close>
      <el-form :model="dashboardForm" label-width="100px">
        <el-form-item label="模板名称" required><el-input v-model="dashboardForm.dashboardName" placeholder="例如：杭州工厂质量日报" /></el-form-item>
        <el-form-item label="模板范围"><el-select v-model="dashboardForm.scopeType" style="width:100%"><el-option label="工厂模板" value="FACTORY" /><el-option label="个人模板" value="USER" /><el-option label="全局模板" value="GLOBAL" /></el-select></el-form-item>
        <el-form-item label="默认模板"><el-switch v-model="dashboardForm.isDefault" active-text="是" inactive-text="否" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dashboardDialogVisible=false">取消</el-button><el-button type="primary" :loading="savingDashboard" @click="saveDashboard">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="dashboardCopyDialogVisible" class="analytics-config-dialog" :title="dashboardCopyMode === 'saveAs' ? '另存为看板模板' : '复制看板模板'" width="520px" destroy-on-close>
      <el-form :model="dashboardCopyForm" label-width="100px">
        <el-form-item label="模板名称" required><el-input v-model="dashboardCopyForm.dashboardName" placeholder="请输入新模板名称" /></el-form-item>
        <el-form-item label="模板范围"><el-select v-model="dashboardCopyForm.scopeType" style="width:100%"><el-option label="工厂模板" value="FACTORY" /><el-option label="个人模板" value="USER" /><el-option label="全局模板" value="GLOBAL" /></el-select></el-form-item>
        <el-form-item label="设为默认"><el-switch v-model="dashboardCopyForm.isDefault" active-text="是" inactive-text="否" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dashboardCopyDialogVisible=false">取消</el-button><el-button type="primary" :loading="savingDashboard" @click="saveDashboardCopy">保存副本</el-button></template>
    </el-dialog>

    <el-dialog v-model="aggregationTaskDialogVisible" class="analytics-config-dialog aggregation-dialog" title="日聚合任务与夜间配置" width="980px" destroy-on-close @opened="loadAggregationJobPage">
      <section class="schedule-config-card" v-loading="aggregationScheduleLoading">
        <div class="schedule-title">
          <div><h3>夜间自动执行配置</h3><p>开启后，系统每天到达设定时间会自动补算最近 N 天日聚合，用于大范围报表加速。</p></div>
          <el-switch v-model="aggregationSchedule.enabled" active-text="开启" inactive-text="关闭" />
        </div>
        <div class="form-row">
          <el-form-item label="执行时间" class="row-item"><el-time-picker v-model="aggregationSchedule.scheduleTime" format="HH:mm" value-format="HH:mm" placeholder="选择时间" style="width:100%" /></el-form-item>
          <el-form-item label="补算天数" class="row-item"><el-input-number v-model="aggregationSchedule.lookbackDays" :min="1" :max="365" style="width:100%" /></el-form-item>
        </div>
        <div class="schedule-footer">
          <span>最近执行：{{ aggregationSchedule.lastRunDate || '暂无' }}；最近任务：{{ aggregationSchedule.lastJobId ? '#'+aggregationSchedule.lastJobId : '暂无' }}</span>
          <el-button type="primary" :loading="aggregationScheduleLoading" @click="saveAggregationSchedule">保存定时配置</el-button>
        </div>
      </section>
      <section class="job-table-card">
        <div class="job-table-head">
          <div><h3>聚合任务列表</h3><p>失败任务可展开查看错误详情，并一键重试原时间范围。</p></div>
          <div class="job-toolbar">
            <el-select v-model="aggregationJobStatus" clearable placeholder="全部状态" style="width:140px" @change="loadAggregationJobPage">
              <el-option label="RUNNING" value="RUNNING" />
              <el-option label="SUCCESS" value="SUCCESS" />
              <el-option label="FAILED" value="FAILED" />
            </el-select>
            <el-button plain :loading="aggregationJobLoading" @click="loadAggregationJobPage">刷新</el-button>
          </div>
        </div>
        <el-table :data="aggregationJobPage.records" border size="small" v-loading="aggregationJobLoading">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="job-detail">
                <p><b>错误详情：</b>{{ row.errorDetail || row.message || '暂无错误详情' }}</p>
                <p><b>触发来源：</b>{{ triggerSourceText(row.triggerSource) }}；<b>重试来源：</b>{{ row.retryFromJobId ? '#'+row.retryFromJobId : '-' }}</p>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="jobId" label="任务ID" width="90" />
          <el-table-column prop="status" label="状态" width="110">
            <template #default="{ row }"><el-tag :type="jobTagType(row.status)">{{ row.status }}</el-tag></template>
          </el-table-column>
          <el-table-column label="日期范围" min-width="180">
            <template #default="{ row }">{{ row.startDate || '-' }} ~ {{ row.endDate || '-' }}</template>
          </el-table-column>
          <el-table-column prop="triggerSource" label="来源" width="110">
            <template #default="{ row }">{{ triggerSourceText(row.triggerSource) }}</template>
          </el-table-column>
          <el-table-column prop="qualityRows" label="质量行数" width="100" />
          <el-table-column prop="defectRows" label="缺陷行数" width="100" />
          <el-table-column prop="message" label="结果说明" min-width="180" show-overflow-tooltip />
          <el-table-column label="操作" width="120" fixed="right">
            <template #default="{ row }"><el-button v-if="row.status === 'FAILED'" size="small" type="primary" plain @click="retryJob(row)">重试</el-button><span v-else class="muted">-</span></template>
          </el-table-column>
        </el-table>
        <div class="pager-row">
          <el-pagination v-model:current-page="aggregationJobPage.page" v-model:page-size="aggregationJobPage.pageSize" :total="aggregationJobPage.total" :page-sizes="[10,20,50,100]" layout="total, sizes, prev, pager, next" @size-change="loadAggregationJobPage" @current-change="loadAggregationJobPage" />
        </div>
      </section>
      <template #footer><el-button @click="aggregationTaskDialogVisible=false">关闭</el-button></template>
    </el-dialog>

    <el-dialog v-model="presentationVisible" fullscreen :show-close="false" class="presentation-dialog" destroy-on-close @opened="renderPresentationCharts" @closed="disposePresentationCharts">
      <div class="presentation-shell">
        <header class="presentation-head">
          <div><p class="eyebrow">DASHBOARD PRESENTATION</p><h2>{{ presentationTitle }}</h2><p>{{ presentationSubtitle }}</p></div>
          <div class="presentation-actions"><el-button type="primary" plain :loading="loading" @click="loadData">刷新数据</el-button><el-button v-if="presentationWidget" plain @click="presentationWidget=null; renderPresentationCharts()">返回整屏</el-button><el-button type="danger" plain @click="presentationVisible=false">退出大屏</el-button></div>
        </header>
        <section class="presentation-kpis"><div><span>检测总数</span><strong>{{ number(summary.kpi.totalCount) }}</strong></div><div><span>OK数</span><strong class="ok">{{ number(summary.kpi.okCount) }}</strong></div><div><span>NG数</span><strong class="ng">{{ number(summary.kpi.ngCount) }}</strong></div><div><span>良率</span><strong class="yield">{{ percent(summary.kpi.yieldRate) }}</strong></div><div><span>缺陷数</span><strong class="warn">{{ number(summary.kpi.defectCount) }}</strong></div></section>
        <section v-if="presentationWidgets.length" class="presentation-grid" :class="{ single: presentationWidgets.length === 1 }">
          <article v-for="w in presentationWidgets" :key="'p-'+w.widgetId" class="presentation-card" :class="widgetCardClass(w)" :style="widgetCardStyle(w)">
            <div class="presentation-card-head"><div><h3>{{ w.widgetName }}</h3><p>{{ widgetSubtitle(w) }}</p></div><el-button v-if="!presentationWidget" size="small" text type="warning" @click="presentationWidget=w; renderPresentationCharts()">单图放大</el-button></div>
            <div v-if="widgetMetric(w)==='quality'" class="presentation-quality-grid"><div><span>检测总数</span><b>{{ number(summaryFor(w).kpi.totalCount) }}</b></div><div><span>OK数</span><b class="ok">{{ number(summaryFor(w).kpi.okCount) }}</b></div><div><span>NG数</span><b class="ng">{{ number(summaryFor(w).kpi.ngCount) }}</b></div><div><span>良率</span><b class="yield">{{ percent(summaryFor(w).kpi.yieldRate) }}</b></div></div>
            <div v-else-if="isChartWidget(w)" class="presentation-chart-wrap"><div v-if="hasChartData(w)" :ref="el=>setPresentationChartRef(w.widgetId,el)" class="presentation-echart"></div><EmptyState v-else title="暂无图表数据" text="当前筛选条件下没有可绘制的数据。" /></div>
            <div v-else-if="widgetMetric(w)==='defectPivot'" class="presentation-table-wrap"><el-table :data="pivotRowsFor(w)" size="small" border height="100%"><el-table-column prop="productName" label="产品" min-width="120" fixed="left" /><el-table-column v-for="c in pivotColumnsFor(w)" :key="c.defectTypeId" :prop="String(c.defectTypeId)" :label="c.defectName" min-width="130" align="center"><template #default="{ row }">{{ number(row[c.defectTypeId] || 0) }}</template></el-table-column></el-table></div>
            <div v-else-if="widgetMetric(w)==='mappingExceptions'" class="presentation-table-wrap"><el-table :data="summaryFor(w).mappingExceptions" size="small" border height="100%"><el-table-column prop="createTime" label="时间" width="170" /><el-table-column prop="cameraCode" label="相机编号" width="120" /><el-table-column prop="productName" label="产品" width="110" /><el-table-column prop="sourceCode" label="上报编码" width="100" /><el-table-column prop="exceptionType" label="异常类型" width="140" /><el-table-column prop="exceptionMessage" label="失败原因" min-width="220" show-overflow-tooltip /></el-table></div>
            <EmptyState v-else title="暂不支持的指标" :text="'指标 ' + widgetMetric(w) + ' 暂未接入展示组件。'" />
          </article>
        </section>
        <EmptyState v-else title="当前看板暂无图表" text="请先在配置态添加图表后再进入大屏展示。" />
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, inject, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { ElMessage, ElMessageBox } from '@/utils/message'
import { getAnalyticsMetadata, getLineAnalyticsSummary, getDefaultDashboard, getDashboards, getDashboard, createDashboard, updateDashboard, deleteDashboard, copyDashboard, createWidget, updateWidget, deleteWidget, reorderWidgets, exportWidgetExcel, runDailyAggregation, getAggregationJobs, getAggregationSchedule, updateAggregationSchedule, queryAggregationJobs, retryAggregationJob } from '@/api/analytics'
import EmptyState from '@/components/EmptyState.vue'

const injectedFilters = inject('filterParams', ref({}))
const loading = ref(false)
const dashboardLoading = ref(false)
const savingWidget = ref(false)
const deletingWidget = ref(false)
const savingDashboard = ref(false)
const aggregationRunning = ref(false)
const dashboard = ref(null)
const dashboardList = ref([])
const currentDashboardId = ref(null)
const widgets = ref([])
const selectedWidget = ref(null)
const widgetDialogVisible = ref(false)
const widgetSaveConfirmVisible = ref(false)
const widgetDeleteConfirmVisible = ref(false)
const dashboardDialogVisible = ref(false)
const dashboardCopyDialogVisible = ref(false)
const editingWidgetId = ref(null)
const pendingWidgetSave = ref(null)
const pendingWidgetDelete = ref(null)
const editingDashboardId = ref(null)
const dashboardCopyMode = ref('copy')
const draggedWidgetId = ref(null)
const resizingState = ref(null)
const presentationVisible = ref(false)
const presentationWidget = ref(null)
const timeRange = ref(defaultRange())
const chartRefs = new Map()
const chartInstances = new Map()
const presentationChartRefs = new Map()
const presentationChartInstances = new Map()
const previewChartRef = ref(null)
const previewChartInstance = ref(null)
const widgetData = reactive({})
const aggregationJobs = ref([])
const aggregationTaskDialogVisible = ref(false)
const aggregationScheduleLoading = ref(false)
const aggregationJobLoading = ref(false)
const aggregationJobStatus = ref('')
const aggregationJobPage = reactive({ page: 1, pageSize: 10, total: 0, records: [] })
const aggregationSchedule = reactive({ enabled: true, scheduleTime: '02:00', lookbackDays: 7, factoryId: null, lineId: null, lastRunDate: null, lastJobId: null })

const filters = reactive({ productId: null, shiftId: null, cameraId: null, defectTypeId: null, severity: null })
const widgetForm = reactive({ widgetName: '', widgetType: 'LINE', metric: 'trend', dimensions: [], width: 12, height: 3, sortOrder: 10, followGlobalFilter: true, filterProductId: null, filterCameraId: null, filterDefectTypeId: null, filterSeverity: null })
const dashboardForm = reactive({ dashboardName: '', scopeType: 'FACTORY', isDefault: false })
const dashboardCopyForm = reactive({ dashboardName: '', scopeType: 'FACTORY', isDefault: false })
const metadata = reactive({ products: [], defectTypes: [], positions: [], cameras: [], shifts: [], widgetTypes: [], dimensions: [] })
const summary = reactive(normalizeSummary({}))

const fallbackWidgetTypes = [
  { code: 'KPI', name: '指标卡', description: '展示产量、OK、NG、良率等核心指标' },
  { code: 'LINE', name: '趋势折线', description: '按时间展示产量、NG、良率趋势' },
  { code: 'BAR', name: '柱状图', description: '按产线、工位、相机、产品对比' },
  { code: 'STACK_BAR', name: '堆叠柱状图', description: '按缺陷类型堆叠展示' },
  { code: 'RANK', name: '排行', description: '展示 Top 缺陷/工位/相机' },
  { code: 'PIVOT', name: '透视表', description: '动态生成产品缺陷列' }
]
const fallbackDimensions = [
  { code: 'time', name: '时间' }, { code: 'line', name: '产线' }, { code: 'workstation', name: '工位' }, { code: 'camera', name: '相机' }, { code: 'product', name: '产品' }, { code: 'defectType', name: '缺陷类型' }, { code: 'shift', name: '班次' }
]
const metricOptions = [
  { label: '核心质量指标', value: 'quality' },
  { label: '30分钟质量趋势', value: 'trend' },
  { label: '缺陷类型排行', value: 'defectRanking' },
  { label: '异常工位排行', value: 'stationRanking' },
  { label: '异常相机排行', value: 'cameraRanking' },
  { label: '缺陷严重度堆叠', value: 'stackDefectBySeverity' },
  { label: '产品缺陷透视表', value: 'defectPivot' },
  { label: '映射异常明细', value: 'mappingExceptions' }
]
const widgetPresets = [
  { key: 'qualityTrend', name: '质量趋势', description: '看总量、NG和良率随时间变化', widgetType: 'LINE', metric: 'trend', dimensions: ['time'], width: 12, height: 3 },
  { key: 'defectTop', name: '缺陷排行', description: '快速定位高发缺陷类型', widgetType: 'RANK', metric: 'defectRanking', dimensions: ['defectType'], width: 6, height: 3 },
  { key: 'stationTop', name: '工位异常', description: '定位 NG 最高的工位', widgetType: 'BAR', metric: 'stationRanking', dimensions: ['workstation'], width: 6, height: 3 },
  { key: 'productPivot', name: '产品缺陷矩阵', description: '按产品动态展开缺陷列', widgetType: 'PIVOT', metric: 'defectPivot', dimensions: ['product', 'defectType'], width: 12, height: 4 }
]

const widgetTypes = computed(() => metadata.widgetTypes.length ? metadata.widgetTypes : fallbackWidgetTypes)
const dimensions = computed(() => metadata.dimensions.length ? metadata.dimensions : fallbackDimensions)
const filteredCameras = computed(() => {
  const f = injectedFilters.value || {}
  const lineId = f.lineIds?.[0]
  const workstationId = f.stationIds?.[0]
  return metadata.cameras.filter(c => (!lineId || String(c.lineId) === String(lineId)) && (!workstationId || String(c.workstationId) === String(workstationId)))
})
const filteredDefectTypes = computed(() => metadata.defectTypes.filter(d => !filters.productId || !d.productId || String(d.productId) === String(filters.productId)))
const visibleWidgets = computed(() => widgets.value.filter(w => Number(w.isEnabled ?? 1) === 1).slice().sort(widgetSort))
const presentationWidgets = computed(() => presentationWidget.value ? [presentationWidget.value] : visibleWidgets.value)
const presentationTitle = computed(() => presentationWidget.value ? presentationWidget.value.widgetName : (dashboard.value?.dashboardName || '默认产线统计看板'))
const presentationSubtitle = computed(() => presentationWidget.value ? widgetSubtitle(presentationWidget.value) : '只展示当前看板已配置图表，适合会议复盘、现场巡检和大屏投放。')
const lastAggregationJob = computed(() => aggregationJobs.value[0] || null)
const previewWidget = computed(() => ({ widgetId: '__preview', widgetName: widgetForm.widgetName || '预览图表', widgetType: widgetForm.widgetType, metricJson: { metric: widgetForm.metric }, layoutJson: { w: widgetForm.width, h: widgetForm.height }, followGlobalFilter: 1, sortOrder: widgetForm.sortOrder, isEnabled: 1 }))

watch(() => injectedFilters.value, async () => { currentDashboardId.value = null; await loadDashboards(); await loadDashboard(); await loadData() }, { deep: true })
watch([visibleWidgets, () => summary.trend, () => summary.defectRanking, () => summary.stationRanking, () => summary.cameraRanking, () => summary.defectPivot], () => renderAllCharts(), { deep: true })
watch([() => widgetDialogVisible.value, () => widgetForm.widgetType, () => widgetForm.metric, () => widgetForm.width, () => widgetForm.height, () => summary.trend, () => summary.defectRanking, () => summary.stationRanking, () => summary.cameraRanking], () => renderPreviewChart(), { deep: true })

onMounted(async () => { await loadMetadata(); await loadDashboards(); await loadDashboard(); await loadData(); await loadAggregationJobs(); await loadAggregationSchedule(); window.addEventListener('resize', resizeCharts) })
onBeforeUnmount(() => { window.removeEventListener('resize', resizeCharts); window.removeEventListener('mousemove', onResizeMove); window.removeEventListener('mouseup', endResize); chartInstances.forEach(i => i.dispose()); chartInstances.clear(); disposePresentationCharts(); disposePreviewChart() })

async function loadMetadata(){ try{ const data=(await getAnalyticsMetadata()).data?.data||{}; Object.assign(metadata,{products:data.products||[],defectTypes:data.defectTypes||[],positions:data.positions||[],cameras:data.cameras||[],shifts:data.shifts||[],widgetTypes:data.widgetTypes||[],dimensions:data.dimensions||[]}) }catch(e){ ElMessage.error('加载统计元数据失败：'+errorMessage(e)) } }
async function loadDashboards(){ const f=injectedFilters.value||{}; dashboardList.value=(await getDashboards({factoryId:f.factoryId||undefined})).data?.data||[] }
async function loadDashboard(){ dashboardLoading.value=true; try{ const f=injectedFilters.value||{}; const res=currentDashboardId.value?await getDashboard(currentDashboardId.value):await getDefaultDashboard({factoryId:f.factoryId||undefined}); const data=res.data?.data||{}; dashboard.value=data; currentDashboardId.value=data.dashboardId||null; widgets.value=data.widgets||[]; selectedWidget.value=widgets.value[0]||null; await nextTick(); await loadWidgetData(); renderAllCharts() }catch(e){ widgets.value=[]; ElMessage.error('加载动态看板失败：'+errorMessage(e)) }finally{ dashboardLoading.value=false } }
async function loadDashboardById(id){ currentDashboardId.value=id; await loadDashboard() }
async function refreshDashboard(){ await loadDashboards(); await loadDashboard() }
async function loadData(){ loading.value=true; try{ const data=(await getLineAnalyticsSummary(globalParams())).data?.data||{}; Object.assign(summary,normalizeSummary(data)); await loadWidgetData(); await nextTick(); renderAllCharts() }catch(e){ Object.assign(summary,normalizeSummary({})); ElMessage.error('加载产线统计失败：'+errorMessage(e)) }finally{ loading.value=false } }
async function loadWidgetData(){ const independent=visibleWidgets.value.filter(w=>Number(w.followGlobalFilter??1)!==1); const alive=new Set(independent.map(w=>String(w.widgetId))); Object.keys(widgetData).forEach(k=>{if(!alive.has(k)) delete widgetData[k]}); await Promise.all(independent.map(async w=>{ try{ widgetData[w.widgetId]=normalizeSummary((await getLineAnalyticsSummary(widgetParams(w))).data?.data||{}) }catch{ widgetData[w.widgetId]=normalizeSummary({}) } })) }
function normalizeSummary(data){ return {aggregationLevel:data.aggregationLevel||'30M',aggregationMessage:data.aggregationMessage||'',aggregationThresholdDays:data.aggregationThresholdDays||3,kpi:data.kpi||{},trend:data.trend||[],defectRanking:data.defectRanking||[],stationRanking:data.stationRanking||[],cameraRanking:data.cameraRanking||[],defectPivot:data.defectPivot||[],mappingExceptions:data.mappingExceptions||[]} }
function globalParams(){ const f=injectedFilters.value||{}; return {startTime:timeRange.value?.[0],endTime:timeRange.value?.[1],factoryId:f.factoryId||undefined,lineId:f.lineIds?.[0],workstationId:f.stationIds?.[0],productId:filters.productId||undefined,shiftId:filters.shiftId||undefined,cameraId:filters.cameraId||undefined,defectTypeId:filters.defectTypeId||undefined,severity:filters.severity||undefined} }
function widgetParams(w){ const f=parseJsonField(w?.filterJson,{}); return {startTime:timeRange.value?.[0],endTime:timeRange.value?.[1],factoryId:f.factoryId||undefined,lineId:f.lineId||undefined,workstationId:f.workstationId||undefined,productId:f.productId||undefined,shiftId:f.shiftId||undefined,cameraId:f.cameraId||undefined,defectTypeId:f.defectTypeId||undefined,severity:f.severity||undefined} }

function openWidgetDialog(t){ editingWidgetId.value=null; const type=t?.code||'LINE', metric=defaultMetricByType(type); Object.assign(widgetForm,{widgetName:t?.name||'自定义图表',widgetType:type,metric,dimensions:defaultDimensionsByMetric(metric),width:type==='KPI'?12:6,height:type==='KPI'?2:3,sortOrder:Math.max(0,...widgets.value.map(w=>Number(w.sortOrder||0)))+10,followGlobalFilter:true,filterProductId:null,filterCameraId:null,filterDefectTypeId:null,filterSeverity:null}); widgetDialogVisible.value=true }
function editWidget(w){ selectedWidget.value=w; editingWidgetId.value=w.widgetId; const l=widgetLayout(w), f=parseJsonField(w.filterJson,{}), metric=widgetMetric(w), dims=parseJsonField(w.dimensionJson,defaultDimensionsByMetric(metric)); Object.assign(widgetForm,{widgetName:w.widgetName,widgetType:w.widgetType||'LINE',metric,dimensions:Array.isArray(dims)?dims:defaultDimensionsByMetric(metric),width:Number(l.w||6),height:Number(l.h||3),sortOrder:Number(w.sortOrder||0),followGlobalFilter:Number(w.followGlobalFilter??1)===1,filterProductId:f.productId||null,filterCameraId:f.cameraId||null,filterDefectTypeId:f.defectTypeId||null,filterSeverity:f.severity||null}); widgetDialogVisible.value=true }
function onWidgetTypeChange(type){ widgetForm.metric=defaultMetricByType(type); widgetForm.dimensions=defaultDimensionsByMetric(widgetForm.metric) }
function onMetricChange(metric){ widgetForm.dimensions=defaultDimensionsByMetric(metric) }
function applyWidgetPreset(preset){
  widgetForm.widgetType=preset.widgetType
  widgetForm.metric=preset.metric
  widgetForm.dimensions=[...(preset.dimensions||defaultDimensionsByMetric(preset.metric))]
  widgetForm.width=preset.width||12
  widgetForm.height=preset.height||3
  if(!widgetForm.widgetName) widgetForm.widgetName=preset.name
}
function requestSaveWidget(){
  if(!dashboard.value?.dashboardId) return ElMessage.error('请先加载或创建看板模板')
  if(!String(widgetForm.widgetName||'').trim()) return ElMessage.warning('请填写图表名称')
  const isEditing=!!editingWidgetId.value
  const payload=buildWidgetPayloadFromForm()
  const targetWidgetId=editingWidgetId.value
  pendingWidgetSave.value={ isEditing, payload, targetWidgetId, widgetName: payload.widgetName }
  widgetSaveConfirmVisible.value=true
}
async function confirmSaveWidget(){
  const pending=pendingWidgetSave.value
  if(!pending?.payload) return ElMessage.warning('请先完成图表配置')
  const { isEditing, payload, targetWidgetId } = pending
  try{
    savingWidget.value=true
    const res=isEditing?await updateWidget(targetWidgetId,payload):await createWidget(payload)
    assertSuccess(res)
    const saved=res?.data?.data||{}
    widgetDialogVisible.value=false
    widgetSaveConfirmVisible.value=false
    const savedId=saved.widgetId||targetWidgetId
    await refreshDashboardAfterWidgetSave(savedId)
    pendingWidgetSave.value=null
    ElMessage.success(isEditing?'图表已更新，看板已刷新':'图表已新增，看板已刷新')
  }catch(e){ ElMessage.error('保存图表失败：'+errorMessage(e)) }finally{ savingWidget.value=false }
}
async function refreshDashboardAfterWidgetSave(savedId){
  const activeDashboardId=dashboard.value?.dashboardId||currentDashboardId.value
  if(activeDashboardId) currentDashboardId.value=activeDashboardId
  await loadDashboards()
  await loadDashboard()
  await loadData()
  await nextTick()
  if(savedId){ const current=widgets.value.find(w=>String(w.widgetId)===String(savedId)); if(current) selectedWidget.value=current }
  renderAllCharts()
}
function buildWidgetPayloadFromForm(){ const filterJson=widgetForm.followGlobalFilter?{}:compactObject({productId:widgetForm.filterProductId,cameraId:widgetForm.filterCameraId,defectTypeId:widgetForm.filterDefectTypeId,severity:widgetForm.filterSeverity}); return {dashboardId:dashboard.value.dashboardId,widgetName:widgetForm.widgetName.trim(),widgetType:widgetForm.widgetType,metricJson:{metric:widgetForm.metric},dimensionJson:(widgetForm.dimensions&&widgetForm.dimensions.length)?widgetForm.dimensions:defaultDimensionsByMetric(widgetForm.metric),filterJson,optionJson:{},layoutJson:{w:widgetForm.width,h:widgetForm.height},followGlobalFilter:widgetForm.followGlobalFilter?1:0,sortOrder:widgetForm.sortOrder,isEnabled:1} }
function removeWidget(w){
  if(!w?.widgetId) return ElMessage.warning('请选择要删除的图表')
  selectedWidget.value=w
  pendingWidgetDelete.value={...w}
  widgetDeleteConfirmVisible.value=true
}
async function confirmDeleteWidget(){
  const target=pendingWidgetDelete.value
  if(!target?.widgetId) return ElMessage.warning('请选择要删除的图表')
  deletingWidget.value=true
  try{
    assertSuccess(await deleteWidget(target.widgetId))
    widgetDeleteConfirmVisible.value=false
    pendingWidgetDelete.value=null
    await refreshDashboardAfterWidgetDelete(target.widgetId)
    ElMessage.success('图表已删除，看板已刷新')
  }catch(e){ ElMessage.error('删除图表失败：'+errorMessage(e)) }finally{ deletingWidget.value=false }
}
async function refreshDashboardAfterWidgetDelete(deletedId){
  const activeDashboardId=dashboard.value?.dashboardId||currentDashboardId.value
  if(activeDashboardId) currentDashboardId.value=activeDashboardId
  await loadDashboards()
  await loadDashboard()
  await loadData()
  await nextTick()
  if(deletedId && selectedWidget.value && String(selectedWidget.value.widgetId)===String(deletedId)){
    selectedWidget.value=visibleWidgets.value[0]||widgets.value[0]||null
  }
  renderAllCharts()
}
async function resizeWidget(w,dw,dh){ const l=widgetLayout(w); const next={...l,w:clamp(Number(l.w||6)+dw,6,12),h:clamp(Number(l.h||3)+dh,2,5)}; await updateWidgetConfig(w,{layoutJson:next}); ElMessage.success('尺寸已保存') }
async function updateWidgetConfig(w,patch={}){ assertSuccess(await updateWidget(w.widgetId,widgetPayloadFromWidget(w,patch))); await loadDashboard() }
function widgetPayloadFromWidget(w,patch={}){ return {dashboardId:w.dashboardId,widgetName:patch.widgetName||w.widgetName,widgetType:patch.widgetType||w.widgetType,metricJson:patch.metricJson||parseJsonField(w.metricJson,{}),dimensionJson:patch.dimensionJson||parseJsonField(w.dimensionJson,[]),filterJson:patch.filterJson||parseJsonField(w.filterJson,{}),optionJson:patch.optionJson||parseJsonField(w.optionJson,{}),layoutJson:patch.layoutJson||widgetLayout(w),followGlobalFilter:patch.followGlobalFilter??w.followGlobalFilter??1,sortOrder:patch.sortOrder??w.sortOrder??0,isEnabled:patch.isEnabled??w.isEnabled??1} }
async function handleWidgetCommand(cmd,w){ selectedWidget.value=w; if(cmd==='present') return openPresentation(w); if(cmd==='exportImage') return exportWidgetImage(w); if(cmd==='exportExcel') return downloadWidgetExcel(w); if(cmd==='narrow') return resizeWidget(w,-6,0); if(cmd==='wide') return resizeWidget(w,6,0); if(cmd==='higher') return resizeWidget(w,0,1); if(cmd==='lower') return resizeWidget(w,0,-1) }
function handleDragStart(w){ draggedWidgetId.value=w.widgetId }
async function handleDrop(target){ if(!draggedWidgetId.value||draggedWidgetId.value===target.widgetId) return; const list=visibleWidgets.value.slice(); const from=list.findIndex(w=>w.widgetId===draggedWidgetId.value), to=list.findIndex(w=>w.widgetId===target.widgetId); if(from<0||to<0) return; const moved=list.splice(from,1)[0]; list.splice(to,0,moved); widgets.value=list.map((w,i)=>({...w,sortOrder:(i+1)*10})); draggedWidgetId.value=null; try{ assertSuccess(await reorderWidgets({widgets:widgets.value.map(w=>({widgetId:w.widgetId,sortOrder:w.sortOrder,layoutJson:widgetLayout(w)}))})); ElMessage.success('布局顺序已保存') }catch(e){ ElMessage.error('保存拖拽排序失败：'+errorMessage(e)) } }
function beginResize(w,e){ const l=widgetLayout(w); selectedWidget.value=w; resizingState.value={widgetId:w.widgetId,startX:e.clientX,startY:e.clientY,startW:Number(l.w||6),startH:Number(l.h||3),lastW:Number(l.w||6),lastH:Number(l.h||3)}; window.addEventListener('mousemove', onResizeMove); window.addEventListener('mouseup', endResize) }
function onResizeMove(e){ const s=resizingState.value; if(!s) return; const nextW=clamp(s.startW + (e.clientX-s.startX>=140?6:e.clientX-s.startX<=-140?-6:0),6,12); const nextH=clamp(s.startH + Math.round((e.clientY-s.startY)/110),2,5); s.lastW=nextW; s.lastH=nextH; const idx=widgets.value.findIndex(w=>String(w.widgetId)===String(s.widgetId)); if(idx>=0){ const w=widgets.value[idx]; widgets.value.splice(idx,1,{...w,layoutJson:{...widgetLayout(w),w:nextW,h:nextH}}); nextTick(renderAllCharts) } }
async function endResize(){ const s=resizingState.value; if(!s) return; window.removeEventListener('mousemove', onResizeMove); window.removeEventListener('mouseup', endResize); resizingState.value=null; const w=widgets.value.find(x=>String(x.widgetId)===String(s.widgetId)); if(!w) return; try{ await updateWidgetConfig(w,{layoutJson:{...widgetLayout(w),w:s.lastW,h:s.lastH}}); ElMessage.success('尺寸已保存') }catch(e){ ElMessage.error('保存尺寸失败：'+errorMessage(e)); await loadDashboard() } }

function openDashboardDialog(item){ editingDashboardId.value=item?.dashboardId||null; Object.assign(dashboardForm,{dashboardName:item?.dashboardName||'',scopeType:item?.scopeType||'FACTORY',isDefault:Number(item?.isDefault||0)===1}); dashboardDialogVisible.value=true }
async function saveDashboard(){ if(!String(dashboardForm.dashboardName||'').trim()) return ElMessage.warning('请填写模板名称'); savingDashboard.value=true; try{ const f=injectedFilters.value||{}; const payload={dashboardName:dashboardForm.dashboardName.trim(),factoryId:f.factoryId||null,ownerUserId:null,scopeType:dashboardForm.scopeType,isDefault:dashboardForm.isDefault?1:0,layoutJson:{cols:12,rowHeight:120}}; const res=editingDashboardId.value?await updateDashboard(editingDashboardId.value,payload):await createDashboard(payload); assertSuccess(res); currentDashboardId.value=res.data?.data?.dashboardId||editingDashboardId.value; dashboardDialogVisible.value=false; await loadDashboards(); await loadDashboard(); ElMessage.success('看板模板已保存') }catch(e){ ElMessage.error('保存模板失败：'+errorMessage(e)) }finally{ savingDashboard.value=false } }
function openDashboardCopyDialog(mode='copy'){ if(!dashboard.value?.dashboardId) return ElMessage.warning('请先选择看板模板'); dashboardCopyMode.value=mode; Object.assign(dashboardCopyForm,{dashboardName:(dashboard.value.dashboardName||'看板模板')+(mode==='saveAs'?' 另存':' 副本'),scopeType:dashboard.value.scopeType||'FACTORY',isDefault:false}); dashboardCopyDialogVisible.value=true }
async function saveDashboardCopy(){ if(!dashboard.value?.dashboardId) return; if(!String(dashboardCopyForm.dashboardName||'').trim()) return ElMessage.warning('请填写模板名称'); savingDashboard.value=true; try{ const f=injectedFilters.value||{}; const payload={dashboardName:dashboardCopyForm.dashboardName.trim(),factoryId:f.factoryId||dashboard.value.factoryId||null,ownerUserId:null,scopeType:dashboardCopyForm.scopeType,isDefault:dashboardCopyForm.isDefault?1:0}; const res=await copyDashboard(dashboard.value.dashboardId,payload); assertSuccess(res); currentDashboardId.value=res.data?.data?.dashboardId||null; dashboardCopyDialogVisible.value=false; await loadDashboards(); await loadDashboard(); ElMessage.success(dashboardCopyMode.value==='saveAs'?'模板已另存为':'模板已复制') }catch(e){ ElMessage.error('复制模板失败：'+errorMessage(e)) }finally{ savingDashboard.value=false } }
async function removeDashboard(item){ if(!item?.dashboardId) return; try{ await ElMessageBox.confirm('确认删除看板模板“'+item.dashboardName+'”吗？下属图表配置会同步删除，历史检测数据不受影响。','删除模板',{type:'warning',confirmButtonText:'确认删除',cancelButtonText:'取消'}); assertSuccess(await deleteDashboard(item.dashboardId)); currentDashboardId.value=null; await loadDashboards(); await loadDashboard(); ElMessage.success('模板已删除') }catch(e){ if(e!=='cancel'&&e!=='close') ElMessage.error('删除模板失败：'+errorMessage(e)) } }

function openPresentation(w=null){ presentationWidget.value=w; presentationVisible.value=true; nextTick(renderPresentationCharts) }
async function runAggregation(){ aggregationRunning.value=true; try{ const f=injectedFilters.value||{}; const res=await runDailyAggregation({startDate:String(timeRange.value?.[0]||'').slice(0,10),endDate:String(timeRange.value?.[1]||'').slice(0,10),factoryId:f.factoryId||undefined,lineId:f.lineIds?.[0]}); assertSuccess(res); aggregationJobs.value=[res.data.data,...aggregationJobs.value]; ElMessage.success('日聚合任务已提交'); window.setTimeout(loadAggregationJobs,1600) }catch(e){ ElMessage.error('提交日聚合失败：'+errorMessage(e)) }finally{ aggregationRunning.value=false } }
async function loadAggregationJobs(){ try{ aggregationJobs.value=(await getAggregationJobs()).data?.data||[] }catch{ aggregationJobs.value=[] } }
async function loadAggregationSchedule(){ aggregationScheduleLoading.value=true; try{ const data=(await getAggregationSchedule()).data?.data||{}; Object.assign(aggregationSchedule,{enabled:!!data.enabled,scheduleTime:data.scheduleTime||'02:00',lookbackDays:Number(data.lookbackDays||7),factoryId:data.factoryId||null,lineId:data.lineId||null,lastRunDate:data.lastRunDate||null,lastJobId:data.lastJobId||null}) }catch(e){ ElMessage.error('加载日聚合定时配置失败：'+errorMessage(e)) }finally{ aggregationScheduleLoading.value=false } }
async function saveAggregationSchedule(){ aggregationScheduleLoading.value=true; try{ const res=await updateAggregationSchedule({enabled:aggregationSchedule.enabled,scheduleTime:aggregationSchedule.scheduleTime||'02:00',lookbackDays:aggregationSchedule.lookbackDays||7,factoryId:aggregationSchedule.factoryId||null,lineId:aggregationSchedule.lineId||null}); assertSuccess(res); const data=res.data?.data||{}; Object.assign(aggregationSchedule,{enabled:!!data.enabled,scheduleTime:data.scheduleTime||'02:00',lookbackDays:Number(data.lookbackDays||7),factoryId:data.factoryId||null,lineId:data.lineId||null,lastRunDate:data.lastRunDate||null,lastJobId:data.lastJobId||null}); ElMessage.success('夜间自动执行配置已保存') }catch(e){ ElMessage.error('保存定时配置失败：'+errorMessage(e)) }finally{ aggregationScheduleLoading.value=false } }
async function openAggregationTaskDialog(){ aggregationTaskDialogVisible.value=true; await loadAggregationSchedule(); await loadAggregationJobPage() }
async function loadAggregationJobPage(){ aggregationJobLoading.value=true; try{ const data=(await queryAggregationJobs({page:aggregationJobPage.page,pageSize:aggregationJobPage.pageSize,status:aggregationJobStatus.value||undefined})).data?.data||{}; aggregationJobPage.records=data.records||[]; aggregationJobPage.total=Number(data.total||0); aggregationJobPage.page=Number(data.page||aggregationJobPage.page); aggregationJobPage.pageSize=Number(data.pageSize||aggregationJobPage.pageSize) }catch(e){ ElMessage.error('加载聚合任务列表失败：'+errorMessage(e)) }finally{ aggregationJobLoading.value=false } }
async function retryJob(row){ try{ await ElMessageBox.confirm('确认重试聚合任务 #'+row.jobId+' 吗？系统将按原日期范围重新提交日聚合。','重试聚合任务',{type:'warning',confirmButtonText:'确认重试',cancelButtonText:'取消'}); assertSuccess(await retryAggregationJob(row.jobId)); ElMessage.success('重试任务已提交'); await loadAggregationJobs(); await loadAggregationJobPage() }catch(e){ if(e!=='cancel'&&e!=='close') ElMessage.error('重试失败：'+errorMessage(e)) } }
async function downloadWidgetExcel(w){ if(!w?.widgetId) return; try{ const params=Number(w.followGlobalFilter??1)===1?globalParams():widgetParams(w); const res=await exportWidgetExcel(w.widgetId,params); const blob=new Blob([res.data],{type:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'}); downloadBlob(blob,(w.widgetName||'图表')+'-'+formatCompactDate(new Date())+'.xlsx'); ElMessage.success('Excel已导出') }catch(e){ ElMessage.error('导出Excel失败：'+errorMessage(e)) } }
function exportWidgetImage(w){ if(!isChartWidget(w)) return ElMessage.warning('该图表类型暂不支持图片导出，请使用Excel导出'); const ins=chartInstances.get(String(w.widgetId)); if(!ins) return ElMessage.warning('图表尚未渲染，请刷新后重试'); const url=ins.getDataURL({type:'png',pixelRatio:2,backgroundColor:'#ffffff'}); downloadUrl(url,(w.widgetName||'图表')+'-'+formatCompactDate(new Date())+'.png'); ElMessage.success('图片已导出') }
function downloadBlob(blob,filename){ const url=URL.createObjectURL(blob); downloadUrl(url,filename,()=>URL.revokeObjectURL(url)) }
function downloadUrl(url,filename,after){ const a=document.createElement('a'); a.href=url; a.download=filename; document.body.appendChild(a); a.click(); document.body.removeChild(a); if(after) window.setTimeout(after,300) }
function formatCompactDate(d){ const pad=n=>String(n).padStart(2,'0'); return d.getFullYear()+pad(d.getMonth()+1)+pad(d.getDate())+'-'+pad(d.getHours())+pad(d.getMinutes())+pad(d.getSeconds()) }

function setChartRef(id,el){ if(el) chartRefs.set(String(id),el); else chartRefs.delete(String(id)) }
function setPresentationChartRef(id,el){ if(el) presentationChartRefs.set(String(id),el); else presentationChartRefs.delete(String(id)) }
function renderAllCharts(){ nextTick(()=>{ visibleWidgets.value.filter(isChartWidget).forEach(renderChart); if(presentationVisible.value) renderPresentationCharts() }) }
function resizeCharts(){ chartInstances.forEach(i=>i.resize()); presentationChartInstances.forEach(i=>i.resize()) }
function renderChart(w){ const el=chartRefs.get(String(w.widgetId)); if(!el||!hasChartData(w)) return; const id=String(w.widgetId); const ins=chartInstances.get(id)||echarts.init(el); chartInstances.set(id,ins); ins.setOption(chartOption(w),true); ins.resize() }
function renderPresentationCharts(){ nextTick(()=>presentationWidgets.value.filter(isChartWidget).forEach(renderPresentationChart)) }
function renderPresentationChart(w){ const el=presentationChartRefs.get(String(w.widgetId)); if(!el||!hasChartData(w)) return; const id=String(w.widgetId); const ins=presentationChartInstances.get(id)||echarts.init(el); presentationChartInstances.set(id,ins); ins.setOption(chartOption(w),true); ins.resize() }
function renderPreviewChart(){ nextTick(()=>{ if(!widgetDialogVisible.value || !isChartWidget(previewWidget.value) || !hasChartData(previewWidget.value) || !previewChartRef.value) return; const ins=previewChartInstance.value || echarts.init(previewChartRef.value); previewChartInstance.value=ins; ins.setOption(chartOption(previewWidget.value),true); ins.resize() }) }
function disposePreviewChart(){ if(previewChartInstance.value){ previewChartInstance.value.dispose(); previewChartInstance.value=null } }
function disposePresentationCharts(){ presentationChartInstances.forEach(i=>i.dispose()); presentationChartInstances.clear(); presentationChartRefs.clear(); presentationWidget.value=null }
function chartOption(w){ const data=summaryFor(w), m=widgetMetric(w); if(m==='trend'){ const rows=data.trend||[]; return baseOption({tooltip:{trigger:'axis'},legend:{top:0,data:['检测总数','NG数','良率']},xAxis:{type:'category',data:rows.map(r=>shortTime(r.timeBucket))},yAxis:[{type:'value'},{type:'value',axisLabel:{formatter:'{value}%'}}],series:[{name:'检测总数',type:'line',smooth:true,data:rows.map(r=>Number(r.totalCount||0))},{name:'NG数',type:'line',smooth:true,data:rows.map(r=>Number(r.ngCount||0))},{name:'良率',type:'line',smooth:true,yAxisIndex:1,data:rows.map(r=>Number(r.yieldRate||0))}]}) } if(m==='defectRanking') return rankingBarOption(data.defectRanking||[],'defectName','defectCount','缺陷数'); if(m==='stationRanking') return rankingBarOption(data.stationRanking||[],'workstationName','ngCount','NG数'); if(m==='cameraRanking') return rankingBarOption(data.cameraRanking||[],'cameraName','ngCount','NG数'); if(m==='stackDefectBySeverity') return stackSeverityOption(data.defectRanking||[]); return {} }
function baseOption(o){ return {grid:{left:40,right:42,top:42,bottom:34},color:['#2563eb','#ef4444','#16a34a','#f59e0b'],...o} }
function rankingBarOption(rows,nameKey,valueKey,seriesName){ const list=rows.slice(0,12).reverse(); return baseOption({tooltip:{trigger:'axis'},xAxis:{type:'value'},yAxis:{type:'category',data:list.map(r=>r[nameKey]||r.cameraCode||'未命名')},series:[{name:seriesName,type:'bar',barWidth:14,data:list.map(r=>Number(r[valueKey]||0))}]}) }
function stackSeverityOption(rows){ const names=Array.from(new Set(rows.slice(0,12).map(r=>r.defectName||'未知缺陷'))); const values=sev=>names.map(n=>rows.filter(r=>(r.defectName||'未知缺陷')===n&&String(r.severity||'').toUpperCase()===sev).reduce((s,r)=>s+Number(r.defectCount||0),0)); return baseOption({tooltip:{trigger:'axis'},legend:{top:0,data:['HIGH','MEDIUM','LOW']},xAxis:{type:'category',data:names},yAxis:{type:'value'},series:[{name:'HIGH',type:'bar',stack:'severity',data:values('HIGH')},{name:'MEDIUM',type:'bar',stack:'severity',data:values('MEDIUM')},{name:'LOW',type:'bar',stack:'severity',data:values('LOW')}]}) }
function summaryFor(w){ if(String(w?.widgetId)==='__preview') return summary; return Number(w?.followGlobalFilter??1)===1?summary:(widgetData[w.widgetId]||normalizeSummary({})) }
function isChartWidget(w){ return ['trend','defectRanking','stationRanking','cameraRanking','stackDefectBySeverity'].includes(widgetMetric(w)) }
function hasChartData(w){ const d=summaryFor(w),m=widgetMetric(w); if(m==='trend') return (d.trend||[]).length>0; if(m==='defectRanking'||m==='stackDefectBySeverity') return (d.defectRanking||[]).length>0; if(m==='stationRanking') return (d.stationRanking||[]).length>0; if(m==='cameraRanking') return (d.cameraRanking||[]).length>0; return false }
function pivotColumnsFor(w){ const d=summaryFor(w); const src=(d.defectPivot||[]).map(r=>({defectTypeId:r.defectTypeId,defectName:r.defectName})); const source=src.length?src:filteredDefectTypes.value; const map=new Map(); source.forEach(x=>map.set(x.defectTypeId,x)); return Array.from(map.values()).slice(0,30) }
function pivotRowsFor(w){ const d=summaryFor(w); const productMap=new Map(metadata.products.map(p=>[p.productId,p.productName])); const rows=new Map(); (d.defectPivot||[]).forEach(item=>{ const pid=item.productId||filters.productId||0; if(!rows.has(pid)) rows.set(pid,{productId:pid,productName:productMap.get(pid)||item.productName||'当前产品'}); rows.get(pid)[item.defectTypeId]=item.defectCount }); if(!rows.size&&filters.productId) rows.set(filters.productId,{productId:filters.productId,productName:productMap.get(filters.productId)||'当前产品'}); return Array.from(rows.values()) }

function resetFilters(){ timeRange.value=defaultRange(); Object.assign(filters,{productId:null,shiftId:null,cameraId:null,defectTypeId:null,severity:null}); loadData() }
function defaultRange(){ const now=new Date(), start=new Date(now.getFullYear(),now.getMonth(),now.getDate(),0,0,0); return [formatDate(start),formatDate(now)] }
function formatDate(d){ const pad=n=>String(n).padStart(2,'0'); return d.getFullYear()+'-'+pad(d.getMonth()+1)+'-'+pad(d.getDate())+' '+pad(d.getHours())+':'+pad(d.getMinutes())+':'+pad(d.getSeconds()) }
function parseJsonField(v,f){ if(v==null||v==='') return f; if(typeof v==='object') return v; try{return JSON.parse(v)}catch{return f} }
function widgetMetric(w){ return parseJsonField(w?.metricJson,{}).metric||defaultMetricByType(w?.widgetType) }
function widgetLayout(w){ return parseJsonField(w?.layoutJson,{w:6,h:3}) }
function widgetCardClass(w){ const l=widgetLayout(w); return {wide:Number(l.w||6)>=12,tall:Number(l.h||3)>=4,selected:selectedWidget.value?.widgetId===w.widgetId} }
function widgetCardStyle(w){ const h=Number(widgetLayout(w).h||3); return {'--chart-height': Math.max(220,h*110)+'px', minHeight: Math.max(260,h*120)+'px'} }
function widgetSubtitle(w){ const typeName=widgetTypes.value.find(t=>t.code===w.widgetType)?.name||w.widgetType||'图表'; return typeName+' · '+metricLabel(widgetMetric(w))+' · '+(Number(w.followGlobalFilter??1)===1?'跟随全局筛选':'独立筛选') }
function widgetTypeText(w){ return widgetTypes.value.find(t=>t.code===w?.widgetType)?.name||w?.widgetType||'图表' }
function metricLabel(m){ return {quality:'核心质量指标',trend:'30分钟质量趋势',defectRanking:'缺陷类型排行',stationRanking:'异常工位排行',cameraRanking:'异常相机排行',stackDefectBySeverity:'缺陷严重度堆叠',defectPivot:'产品缺陷透视表',mappingExceptions:'映射异常明细'}[m]||'自定义指标' }
function metricDescription(m){ return {quality:'适合负责人快速查看检测总数、OK、NG、良率等总体质量表现。',trend:'按时间观察产量、NG数和良率波动，适合班次复盘和异常追溯。',defectRanking:'按缺陷类型统计 Top 问题，适合定位产品质量主要矛盾。',stationRanking:'按工位统计异常数量，适合定位产线瓶颈。',cameraRanking:'按相机统计异常数量，适合排查设备或视角问题。',stackDefectBySeverity:'按严重度堆叠缺陷类型，适合区分高危/中危/低危问题。',defectPivot:'按产品动态生成缺陷列，适合产品维度横向对比。',mappingExceptions:'展示未能完成映射的数据，适合维护配置和接口联调。'}[m]||'用于自定义统计口径，保存后在看板中生效。' }
function defaultMetricByType(t){ return {KPI:'quality',LINE:'trend',BAR:'stationRanking',STACK_BAR:'stackDefectBySeverity',RANK:'defectRanking',PIVOT:'defectPivot'}[t]||'trend' }
function defaultDimensionsByMetric(m){ return {quality:['time'],trend:['time'],defectRanking:['defectType'],stationRanking:['workstation'],cameraRanking:['camera'],stackDefectBySeverity:['defectType','severity'],defectPivot:['product','defectType'],mappingExceptions:['camera','product','defectType']}[m]||[] }
function widgetSort(a,b){ return Number(a.sortOrder||0)-Number(b.sortOrder||0)||Number(a.widgetId||0)-Number(b.widgetId||0) }
function cameraOptionLabel(c){ return (c.cameraName||c.cameraCode||'未命名相机')+(c.cameraCode?'（'+c.cameraCode+'）':'') }
function independentFilterText(w){ const f=parseJsonField(w.filterJson,{}), names=[]; if(f.productId) names.push('产品='+idName(metadata.products,'productId','productName',f.productId)); if(f.cameraId) names.push('相机='+idName(metadata.cameras,'cameraId','cameraName',f.cameraId)); if(f.defectTypeId) names.push('缺陷='+idName(metadata.defectTypes,'defectTypeId','defectName',f.defectTypeId)); if(f.severity) names.push('严重度='+f.severity); return names.length?names.join(' / '):'独立筛选：全部范围' }
function idName(list,idKey,nameKey,id){ return list.find(x=>String(x[idKey])===String(id))?.[nameKey]||id }
function compactObject(obj){ const out={}; Object.keys(obj).forEach(k=>{ if(obj[k]!==null&&obj[k]!==undefined&&obj[k]!=='') out[k]=obj[k] }); return out }
function assertSuccess(res){ const body=res?.data||{}; if(body.code&&Number(body.code)!==200) throw new Error(body.message||'接口返回失败') }
function errorMessage(e){ return e.response?.data?.message||e.message||String(e) }
function clamp(v,min,max){ return Math.max(min,Math.min(max,v)) }
function number(v){ return Number(v||0).toLocaleString() }
function percent(v){ return Number(v||0).toFixed(2)+'%' }
function shortTime(v){ return String(v||'').replace('T',' ').slice(5,16) }
function jobTagType(s){ return {RUNNING:'warning',SUCCESS:'success',FAILED:'danger'}[s]||'info' }
function aggregationJobText(job){ const range=[job.startDate,job.endDate].filter(Boolean).join(' ~ ')||'未设置时间范围'; const rows=[]; if(job.qualityRows!=null) rows.push('质量'+number(job.qualityRows)); if(job.defectRows!=null) rows.push('缺陷'+number(job.defectRows)); return range+(rows.length?' · '+rows.join(' / '):'') }
function triggerSourceText(s){ return {MANUAL:'手动执行',SCHEDULE:'夜间自动',RETRY:'失败重试'}[s]||s||'-' }
</script>

<style scoped>
.line-analytics{display:flex;flex-direction:column;gap:18px}
.hero-card{display:flex;justify-content:space-between;gap:20px;padding:22px 24px;border-radius:18px;background:linear-gradient(135deg,#eff6ff 0%,#fff 58%,#f8fafc 100%);border:1px solid #dbeafe;box-shadow:0 10px 30px rgba(37,99,235,.08)}
.eyebrow{margin:0 0 6px;font-size:12px;letter-spacing:.12em;color:#2563eb;font-weight:900}
.hero-card h2{margin:0 0 8px;font-size:24px;color:#0f172a}.hero-card p{margin:0;color:#64748b;line-height:1.7}
.hero-actions,.filter-card,.ops-actions,.canvas-actions,.card-actions{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.hero-actions{justify-content:flex-end}
.filter-card,.ops-card{display:flex;flex-wrap:wrap;align-items:center;gap:12px;padding:16px;border-radius:16px;background:#fff;border:1px solid #e5e7eb;box-shadow:0 1px 4px rgba(15,23,42,.06)}.ops-card{justify-content:space-between}.ops-card h3{margin:0 0 4px;color:#0f172a;font-size:16px}.ops-card p{margin:0;color:#64748b;font-size:13px}
.aggregation-card{align-items:flex-start;background:linear-gradient(135deg,#ffffff 0%,#f8fbff 58%,#f8fafc 100%);border-color:#dbeafe}.aggregation-copy{max-width:860px}.ops-title-row,.panel-title-row{display:flex;align-items:center;justify-content:space-between;gap:10px;margin-bottom:6px}.job-list{display:flex;flex-wrap:wrap;gap:8px;margin-top:10px}.job-chip{display:inline-flex;align-items:center;gap:6px;padding:6px 9px;border-radius:999px;background:#f1f5f9;border:1px solid #e2e8f0;color:#475569;font-size:12px}.job-chip b{color:#0f172a}.job-chip em{font-style:normal;font-weight:800}.job-chip .status-success{color:#16a34a}.job-chip .status-running{color:#d97706}.job-chip .status-failed{color:#dc2626}.schedule-line{display:flex;flex-wrap:wrap;gap:8px;margin-top:10px;color:#64748b;font-size:12px}.schedule-line b{color:#2563eb}
.kpi-grid{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:12px}.kpi-card{padding:16px;border-radius:16px;background:#fff;border:1px solid #e5e7eb;box-shadow:0 1px 4px rgba(15,23,42,.06)}.kpi-card span{display:block;color:#64748b;font-size:13px;font-weight:700;margin-bottom:8px}.kpi-card strong{display:block;color:#0f172a;font-size:26px;line-height:1}.kpi-card small{display:block;margin-top:9px;color:#94a3b8}.kpi-card.ok strong{color:#16a34a}.kpi-card.ng strong,.kpi-card.warn strong{color:#dc2626}.kpi-card.yield strong{color:#2563eb}
.builder-layout{display:grid;grid-template-columns:220px minmax(0,1fr) 260px;gap:16px;align-items:start}.widget-palette,.config-panel,.canvas{border-radius:18px;background:#fff;border:1px solid #e5e7eb;box-shadow:0 1px 4px rgba(15,23,42,.06)}.widget-palette,.config-panel{padding:16px;position:sticky;top:140px}.canvas{padding:16px;min-width:0}.widget-palette h3,.config-panel h3{margin:0 0 12px;font-size:16px;color:#0f172a}.panel-tip{margin:-4px 0 12px;color:#64748b;font-size:12px;line-height:1.6}
.widget-btn{width:100%;border:1px solid #e5e7eb;background:#f8fafc;border-radius:14px;padding:12px;text-align:left;margin-bottom:10px;cursor:pointer;transition:.15s}.widget-btn:hover{border-color:#93c5fd;background:#eff6ff;transform:translateY(-1px)}.widget-btn b{display:block;color:#0f172a;margin-bottom:4px}.widget-btn span{color:#64748b;font-size:12px;line-height:1.5}
.canvas-head{display:flex;justify-content:space-between;align-items:flex-start;gap:12px;margin-bottom:14px}.canvas-head h3{margin:0 0 4px;color:#0f172a}.canvas-head p{margin:0;color:#64748b;font-size:13px}.chart-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}
.chart-card{position:relative;border:1px solid #e5e7eb;border-radius:16px;padding:14px;background:#fff;min-height:280px;transition:.15s;overflow:hidden;cursor:grab}.chart-card:hover{border-color:#bfdbfe;box-shadow:0 8px 20px rgba(37,99,235,.08)}.chart-card.selected{border-color:#60a5fa;box-shadow:0 0 0 3px rgba(96,165,250,.18)}.chart-card.wide{grid-column:span 2}.chart-card.tall{min-height:380px}
.card-head{display:flex;justify-content:space-between;align-items:flex-start;gap:10px;margin-bottom:12px}.card-head h4{margin:0;color:#0f172a;font-size:15px}.card-head span{display:block;margin-top:4px;color:#94a3b8;font-size:12px}.card-actions{justify-content:flex-end;gap:6px;flex-shrink:0;padding:4px;border-radius:12px;background:#f8fafc;border:1px solid #eef2f7}.card-actions :deep(.el-button){margin-left:0}.echart-wrap,.table-wrap{min-height:220px}.echart-box{width:100%;height:var(--chart-height,260px)}
.quality-mini-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px}.quality-mini-grid div{padding:14px 12px;border-radius:14px;background:#f8fafc;border:1px solid #edf2f7}.quality-mini-grid span{display:block;color:#64748b;font-size:12px;margin-bottom:8px}.quality-mini-grid b{color:#0f172a;font-size:22px}.quality-mini-grid b.ok{color:#16a34a}.quality-mini-grid b.ng{color:#dc2626}.quality-mini-grid b.yield{color:#2563eb}
.resize-handle{position:absolute;right:8px;bottom:8px;width:18px;height:18px;border-radius:6px;cursor:nwse-resize;background:linear-gradient(135deg,transparent 0 45%,#bfdbfe 46% 55%,transparent 56%),linear-gradient(135deg,transparent 0 62%,#60a5fa 63% 72%,transparent 73%);opacity:.8}.selected-widget-card{padding:14px;border-radius:16px;background:linear-gradient(135deg,#eff6ff,#fff);border:1px solid #bfdbfe;margin-bottom:12px}.selected-main strong{display:block;color:#0f172a;font-size:15px;margin-bottom:5px}.selected-main span{display:block;color:#64748b;font-size:12px;line-height:1.5}.meta-list{display:flex;flex-direction:column;gap:8px;margin:12px 0}.meta-list p{display:flex;align-items:flex-start;justify-content:space-between;gap:10px;margin:0;padding:8px 0;border-top:1px dashed #dbeafe}.meta-list label{color:#64748b;font-size:12px;white-space:nowrap}.meta-list b{color:#0f172a;font-size:12px;text-align:right;font-weight:800;line-height:1.5}.quick-actions{display:grid;grid-template-columns:1fr;gap:8px}.quick-actions :deep(.el-button){width:100%;margin-left:0}.config-box{padding:12px;border-radius:14px;background:#f8fafc;border:1px solid #eef2f7;margin-bottom:12px}.config-box label{display:block;color:#64748b;font-size:12px;margin-bottom:6px}.config-box p{margin:6px 0 10px;color:#64748b;font-size:12px;line-height:1.6}.dimension-box{background:#fff}.dim-tag{margin:0 6px 6px 0}.form-row{display:grid;grid-template-columns:1fr 1fr;gap:12px}.row-item{margin-bottom:18px}.independent-box{padding:12px;border-radius:12px;border:1px dashed #bfdbfe;background:#eff6ff;margin-bottom:8px}
.widget-guide{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px;margin-bottom:14px}.guide-step{display:flex;align-items:center;gap:8px;padding:10px;border-radius:14px;background:#f8fafc;border:1px solid #e5e7eb}.guide-step b{display:grid;place-items:center;width:24px;height:24px;border-radius:50%;background:#2563eb;color:#fff;font-size:12px}.guide-step span{display:block;color:#0f172a;font-weight:800;font-size:13px}.guide-step small{display:block;color:#94a3b8;font-size:11px}.metric-hint{display:flex;gap:8px;align-items:flex-start;padding:10px 12px;margin:-4px 0 14px 110px;border-radius:12px;background:#f8fafc;border:1px solid #eef2f7}.metric-hint strong{white-space:nowrap;color:#2563eb}.metric-hint span{color:#64748b;font-size:13px;line-height:1.6}.dimension-checks{display:flex;flex-wrap:wrap;gap:8px}.dimension-checks :deep(.el-checkbox-button__inner){border-radius:999px!important;border:1px solid #dbeafe!important;box-shadow:none!important}.form-help{margin:8px 0 0;color:#94a3b8;font-size:12px;line-height:1.6}.preview-panel{margin-top:4px;padding:14px;border-radius:14px;border:1px solid #dbeafe;background:linear-gradient(135deg,#f8fbff,#ffffff)}.preview-head{display:flex;justify-content:space-between;align-items:center;gap:12px;margin-bottom:12px}.preview-chart{width:100%;height:260px}.preview-table{max-height:260px;overflow:auto}
.primary-save-btn{display:inline-flex;align-items:center;justify-content:center;height:32px;padding:8px 15px;border:1px solid #2563eb;border-radius:4px;background:#2563eb;color:#fff;font-size:14px;line-height:1;cursor:pointer;transition:.15s;margin-left:12px}.primary-save-btn:hover{background:#1d4ed8;border-color:#1d4ed8}.primary-save-btn:disabled{cursor:not-allowed;opacity:.65}.save-confirm-panel{display:flex;gap:14px;padding:4px 2px}.save-confirm-icon{flex:0 0 38px;width:38px;height:38px;border-radius:50%;display:grid;place-items:center;background:#fef3c7;color:#d97706;font-weight:900;font-size:22px}.save-confirm-panel h3{margin:0 0 10px;color:#0f172a;font-size:17px}.save-confirm-panel p{margin:8px 0;color:#475569;line-height:1.7}.save-confirm-panel b{color:#0f172a}.save-confirm-panel .risk-tip{padding:10px 12px;border-radius:12px;background:#eff6ff;border:1px solid #bfdbfe;color:#2563eb;font-size:13px}
.save-confirm-icon.danger{background:#fee2e2;color:#dc2626}.save-confirm-panel .risk-tip.danger{background:#fff1f2;border-color:#fecdd3;color:#be123c}
.schedule-config-card,.job-table-card{padding:14px;border-radius:16px;border:1px solid #e5e7eb;background:#fff;margin-bottom:14px}.schedule-config-card{background:linear-gradient(135deg,#eff6ff,#fff)}.schedule-title,.job-table-head,.schedule-footer{display:flex;align-items:flex-start;justify-content:space-between;gap:14px}.schedule-title h3,.job-table-head h3{margin:0 0 4px;color:#0f172a;font-size:16px}.schedule-title p,.job-table-head p{margin:0;color:#64748b;font-size:13px;line-height:1.6}.schedule-footer{align-items:center;color:#64748b;font-size:13px}.job-toolbar{display:flex;gap:10px;align-items:center}.job-detail{padding:10px 14px;border-radius:12px;background:#f8fafc;color:#475569;line-height:1.7;white-space:pre-wrap}.job-detail p{margin:0 0 6px}.muted{color:#94a3b8}.pager-row{display:flex;justify-content:flex-end;margin-top:12px}
:global(.analytics-config-dialog.el-dialog),:global(.analytics-config-dialog .el-dialog){width:min(920px,calc(100vw - 32px))!important;max-height:92vh;display:flex;flex-direction:column}
:global(.analytics-config-dialog .el-dialog__body){overflow:auto;max-height:calc(92vh - 126px);padding-bottom:14px}
:global(.analytics-config-dialog .el-dialog__footer){position:sticky;bottom:0;z-index:2;background:#fff;border-top:1px solid #eef2f7;padding:12px 20px}
.presentation-dialog :deep(.el-dialog__header){display:none}.presentation-dialog :deep(.el-dialog__body){height:100vh;padding:0;background:#08111f}.presentation-shell{min-height:100vh;padding:22px;background:linear-gradient(135deg,#08111f 0%,#0f172a 54%,#111827 100%);color:#e5e7eb}.presentation-head{display:flex;justify-content:space-between;gap:20px;margin-bottom:16px;padding:16px 18px;border:1px solid rgba(148,163,184,.22);border-radius:20px;background:rgba(15,23,42,.72)}.presentation-head h2{margin:0 0 6px;font-size:26px;color:#fff}.presentation-head p{margin:0;color:#94a3b8}.presentation-actions{display:flex;gap:10px;flex-wrap:wrap}.presentation-kpis{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:12px;margin-bottom:16px}.presentation-kpis div{padding:15px 16px;border-radius:18px;background:rgba(255,255,255,.08);border:1px solid rgba(148,163,184,.18)}.presentation-kpis span{display:block;color:#94a3b8;font-size:12px;margin-bottom:8px}.presentation-kpis strong{font-size:28px;color:#fff}.presentation-kpis .ok{color:#4ade80}.presentation-kpis .ng,.presentation-kpis .warn{color:#fb7185}.presentation-kpis .yield{color:#60a5fa}.presentation-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}.presentation-grid.single{grid-template-columns:1fr}.presentation-card{padding:16px;border-radius:20px;background:rgba(255,255,255,.96);border:1px solid rgba(226,232,240,.9);min-height:360px;overflow:hidden;box-shadow:0 18px 36px rgba(0,0,0,.18)}.presentation-card.wide{grid-column:span 2}.presentation-card-head{display:flex;justify-content:space-between;align-items:flex-start;gap:12px;margin-bottom:10px}.presentation-card-head h3{margin:0;color:#0f172a}.presentation-card-head p{margin:4px 0 0;color:#64748b;font-size:12px}.presentation-echart{width:100%;height:var(--chart-height,330px)}.presentation-table-wrap{height:var(--chart-height,330px);min-height:260px}.presentation-quality-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px}.presentation-quality-grid div{padding:18px;border-radius:16px;background:#f8fafc;border:1px solid #e2e8f0}.presentation-quality-grid span{display:block;color:#64748b;font-size:13px;margin-bottom:10px}.presentation-quality-grid b{font-size:30px;color:#0f172a}.presentation-quality-grid b.ok{color:#16a34a}.presentation-quality-grid b.ng{color:#dc2626}.presentation-quality-grid b.yield{color:#2563eb}
@media(max-width:1280px){.builder-layout{grid-template-columns:1fr}.widget-palette,.config-panel{position:static}.kpi-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.presentation-grid{grid-template-columns:1fr}.presentation-card.wide{grid-column:span 1}}@media(max-width:760px){.hero-card,.canvas-head,.presentation-head,.schedule-title,.job-table-head,.schedule-footer{flex-direction:column}.chart-grid,.form-row,.widget-guide{grid-template-columns:1fr}.metric-hint{margin-left:0}.chart-card.wide{grid-column:span 1}.quality-mini-grid,.presentation-quality-grid{grid-template-columns:1fr}.presentation-kpis{grid-template-columns:repeat(2,minmax(0,1fr))}}

.wizard-presets { display: grid; grid-template-columns: 150px repeat(4, minmax(0, 1fr)); gap: 10px; align-items: stretch; margin: 12px 0 16px; padding: 12px; border: 1px solid #dbeafe; border-radius: 14px; background: #f8fbff; }
.preset-copy { display: flex; flex-direction: column; justify-content: center; gap: 4px; }
.preset-copy strong { color: #1d4ed8; font-size: 14px; }
.preset-copy span { color: #64748b; font-size: 12px; line-height: 1.5; }
.preset-btn { text-align: left; border: 1px solid #bfdbfe; background: #fff; border-radius: 12px; padding: 10px 12px; cursor: pointer; transition: all .2s; }
.preset-btn:hover { border-color: #2563eb; box-shadow: 0 8px 18px rgba(37,99,235,.12); transform: translateY(-1px); }
.preset-btn b { display: block; color: #0f172a; font-size: 13px; margin-bottom: 4px; }
.preset-btn small { color: #64748b; line-height: 1.5; }
@media (max-width: 1100px) { .wizard-presets { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
