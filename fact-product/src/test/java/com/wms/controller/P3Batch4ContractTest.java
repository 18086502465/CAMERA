package com.wms.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wms.analytics.api.LineAnalyticsController;
import com.wms.analytics.infra.repository.AnalyticsAggregationRepository;
import com.wms.analytics.infra.repository.AnalyticsDashboardRepository;
import com.wms.analytics.infra.repository.LineAnalyticsQueryRepository;
import com.wms.analytics.infra.repository.LineAnalyticsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DDD 重构 P3 批次④ 契约测试（产线统计 LineAnalytics）。
 *
 * <p>冻结点（SQL 下沉后最容易改坏的分支）：
 * <ul>
 *   <li>{@code line-summary} 的<b>数据源兜底</b>：聚合表无数据时切到缺陷图片事实表，
 *       并写入 {@code dataSource=DEFECT_IMAGE}；</li>
 *   <li>良率<b>不可计算</b>时（图片通道无良品样本）必须是 {@code yieldRate=null}
 *       且 {@code yieldRateAvailable=false} —— 绝不显示 0%；</li>
 *   <li>结束时间早于开始时间 → 报错；</li>
 *   <li>看板复制 / 组件 CRUD 的参数校验分支。</li>
 * </ul>
 *
 * <p>重构期间这些断言失败 = 行为变化，必须停。
 */
@ExtendWith(MockitoExtension.class)
class P3Batch4ContractTest {

    private final ObjectMapper om = new ObjectMapper();

    @Mock
    private LineAnalyticsRepository lineAnalyticsRepository;
    @Mock
    private LineAnalyticsQueryRepository queryRepository;
    @Mock
    private AnalyticsDashboardRepository dashboardRepository;
    @Mock
    private AnalyticsAggregationRepository aggregationRepository;

    @InjectMocks
    private LineAnalyticsController controller;

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    /** 空的 KPI（聚合表无数据） */
    private Map<String, Object> emptyKpi() {
        Map<String, Object> kpi = new LinkedHashMap<>();
        kpi.put("totalCount", 0);
        kpi.put("okCount", 0);
        kpi.put("ngCount", 0);
        kpi.put("defectCount", 0);
        return kpi;
    }

    /** 空的图片事实表 KPI（图片通道无数据） */
    private Map<String, Object> emptyImageKpi() {
        Map<String, Object> kpi = new LinkedHashMap<>();
        kpi.put("imageCount", 0);
        kpi.put("defectCount", 0);
        kpi.put("highCount", 0);
        kpi.put("mediumCount", 0);
        kpi.put("lowCount", 0);
        kpi.put("cameraCount", 0);
        kpi.put("workstationCount", 0);
        return kpi;
    }

    // ---------------- 元数据 ----------------

    @Test
    @DisplayName("metadata: 顶层键结构固定（products/defectTypes/positions/cameras/shifts/widgetTypes/dimensions）")
    void metadataStructure() throws Exception {
        when(lineAnalyticsRepository.listEnabledProducts()).thenReturn(Collections.emptyList());
        when(lineAnalyticsRepository.listEnabledDefectTypes()).thenReturn(Collections.emptyList());
        when(lineAnalyticsRepository.listEnabledPositions()).thenReturn(Collections.emptyList());
        when(lineAnalyticsRepository.listEnabledCameras()).thenReturn(Collections.emptyList());
        when(lineAnalyticsRepository.listEnabledShifts()).thenReturn(Collections.emptyList());

        MockMvc mvc = mvc();
        mvc.perform(get("/api/v1/analytics/metadata"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.products").isArray())
                .andExpect(jsonPath("$.data.defectTypes").isArray())
                .andExpect(jsonPath("$.data.positions").isArray())
                .andExpect(jsonPath("$.data.cameras").isArray())
                .andExpect(jsonPath("$.data.shifts").isArray())
                .andExpect(jsonPath("$.data.widgetTypes").isArray())
                .andExpect(jsonPath("$.data.dimensions").isArray());
    }

    // ---------------- 产线汇总：数据源兜底与良率语义 ----------------

    @Test
    @DisplayName("line-summary: 图片事实表无数据、聚合表有数据 → dataSource=AGGREGATION")
    void lineSummaryAggregationSource() throws Exception {
        // ★ 2026-11-05 起「缺陷图片事实表」优先，聚合表是兜底。
        //   本用例覆盖「图片表无数据」的场景 → 落到聚合表。
        when(queryRepository.queryDefectImageKpi(anyString(), anyString(), any())).thenReturn(emptyImageKpi());
        when(queryRepository.queryDefectImageTrend(anyString(), anyString(), any())).thenReturn(Collections.emptyList());
        when(queryRepository.queryDefectImageRanking(anyString(), anyString(), anyString(), any())).thenReturn(Collections.emptyList());
        when(queryRepository.queryDefectImageStationRanking(anyString(), anyString(), any())).thenReturn(Collections.emptyList());
        when(queryRepository.queryDefectImageCameraRanking(anyString(), anyString(), any())).thenReturn(Collections.emptyList());
        when(queryRepository.queryDefectImagePivot(anyString(), anyString(), anyString(), any())).thenReturn(Collections.emptyList());
        when(queryRepository.queryDefectImageSeverityDistribution(anyString(), anyString(), anyString(), any())).thenReturn(Collections.emptyList());

        Map<String, Object> kpi = emptyKpi();
        kpi.put("totalCount", 100);
        kpi.put("okCount", 90);
        kpi.put("ngCount", 10);
        when(queryRepository.queryKpi(anyString(), anyString(), any())).thenReturn(kpi);
        when(queryRepository.queryTrend(anyString(), anyString(), anyString(), any())).thenReturn(Collections.emptyList());
        when(queryRepository.queryDefectRanking(anyString(), anyString(), any())).thenReturn(Collections.emptyList());
        when(queryRepository.queryStationRanking(anyString(), anyString(), any())).thenReturn(Collections.emptyList());
        when(queryRepository.queryCameraRanking(anyString(), anyString(), any())).thenReturn(Collections.emptyList());
        when(queryRepository.queryDefectPivot(anyString(), anyString(), any())).thenReturn(Collections.emptyList());
        when(queryRepository.queryOpenMappingExceptions()).thenReturn(Collections.emptyList());
        when(queryRepository.querySeverityDistribution(anyString(), anyString(), any())).thenReturn(Collections.emptyList());

        MockMvc mvc = mvc();
        mvc.perform(get("/api/v1/analytics/line-summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.dataSource").value("AGGREGATION"))
                .andExpect(jsonPath("$.data.kpi.totalCount").value(100))
                .andExpect(jsonPath("$.data.kpi.yieldRate").value(90.0))
                .andExpect(jsonPath("$.data.aggregationLevel").exists())
                .andExpect(jsonPath("$.data.plantOverview").exists())
                .andExpect(jsonPath("$.data.defectPareto").isArray())
                .andExpect(jsonPath("$.data.defectTrend").isArray());
    }

    @Test
    @DisplayName("line-summary: 图片表与聚合表都有数据 → 必须用图片表（现场实测的「子集比全集大」回归）")
    void lineSummaryPrefersDefectImageOverAggregation() throws Exception {
        // ★ 2026-11-05 现场 bug 回归：
        //   旧实现「聚合表完全无数据才兜底」→ 聚合表里残留几条**孤儿行**
        //   （产线已删除的历史数据）时，不筛选产线会显示聚合表的 4 条，
        //   而筛选产线却显示图片表的 3041 条 → **子集比全集还大**。
        //   现在图片事实表优先，两者都有数据时必须取图片表。
        Map<String, Object> imgKpi = new LinkedHashMap<>();
        imgKpi.put("imageCount", 3041);
        imgKpi.put("defectCount", 3041);
        imgKpi.put("highCount", 100);
        imgKpi.put("mediumCount", 200);
        imgKpi.put("lowCount", 300);
        imgKpi.put("cameraCount", 2);
        imgKpi.put("workstationCount", 2);
        when(queryRepository.queryDefectImageKpi(anyString(), anyString(), any())).thenReturn(imgKpi);
        when(queryRepository.queryDefectImageTrend(anyString(), anyString(), any())).thenReturn(Collections.emptyList());
        when(queryRepository.queryDefectImageRanking(anyString(), anyString(), anyString(), any())).thenReturn(Collections.emptyList());
        when(queryRepository.queryDefectImageStationRanking(anyString(), anyString(), any())).thenReturn(Collections.emptyList());
        when(queryRepository.queryDefectImageCameraRanking(anyString(), anyString(), any())).thenReturn(Collections.emptyList());
        when(queryRepository.queryDefectImagePivot(anyString(), anyString(), anyString(), any())).thenReturn(Collections.emptyList());
        when(queryRepository.queryDefectImageSeverityDistribution(anyString(), anyString(), anyString(), any())).thenReturn(Collections.emptyList());
        when(queryRepository.queryOpenMappingExceptions()).thenReturn(Collections.emptyList());

        // 聚合表也有数据（孤儿行）—— 但不应被采用。
        // ★ 用 lenient()：图片表优先后这些 stub 不会被调用，
        //   而它们的存在正是本用例要验证的前提（"聚合表也有数据"）。
        Map<String, Object> aggKpi = emptyKpi();
        aggKpi.put("totalCount", 4);
        aggKpi.put("okCount", 0);
        aggKpi.put("ngCount", 4);
        aggKpi.put("defectCount", 4);
        lenient().when(queryRepository.queryKpi(anyString(), anyString(), any())).thenReturn(aggKpi);
        lenient().when(queryRepository.queryTrend(anyString(), anyString(), anyString(), any())).thenReturn(Collections.emptyList());
        lenient().when(queryRepository.queryDefectRanking(anyString(), anyString(), any())).thenReturn(Collections.emptyList());
        lenient().when(queryRepository.queryStationRanking(anyString(), anyString(), any())).thenReturn(Collections.emptyList());
        lenient().when(queryRepository.queryCameraRanking(anyString(), anyString(), any())).thenReturn(Collections.emptyList());
        lenient().when(queryRepository.queryDefectPivot(anyString(), anyString(), any())).thenReturn(Collections.emptyList());
        lenient().when(queryRepository.querySeverityDistribution(anyString(), anyString(), any())).thenReturn(Collections.emptyList());

        MockMvc mvc = mvc();
        mvc.perform(get("/api/v1/analytics/line-summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.dataSource").value("DEFECT_IMAGE"))
                .andExpect(jsonPath("$.data.kpi.totalCount").value(3041))
                .andExpect(jsonPath("$.data.kpi.ngCount").value(3041))
                // 良率不可计算，绝不是 0 或 100
                .andExpect(jsonPath("$.data.kpi.yieldRate").doesNotExist())
                .andExpect(jsonPath("$.data.kpi.yieldRateAvailable").value(false));
    }

    @Test
    @DisplayName("line-summary: 图片事实表有数据 → dataSource=DEFECT_IMAGE，良率为 null 且 yieldRateAvailable=false")
    void lineSummaryDefectImageFallback() throws Exception {
        // ★ 2026-11-05 起图片事实表是**主数据源**（不是兜底）——
        //   现场设备走 defect-images/upload 通道，只写 defect_image_*，不写 agg_*。
        //   聚合表此时根本不会被查询，故不 mock（Mockito 严格模式会报多余 stub）。
        when(queryRepository.queryOpenMappingExceptions()).thenReturn(Collections.emptyList());
        // 图片事实表有数据
        Map<String, Object> imgKpi = new LinkedHashMap<>();
        imgKpi.put("imageCount", 50);
        imgKpi.put("defectCount", 8);
        imgKpi.put("highCount", 3);
        imgKpi.put("mediumCount", 4);
        imgKpi.put("lowCount", 1);
        imgKpi.put("cameraCount", 2);
        imgKpi.put("workstationCount", 1);
        when(queryRepository.queryDefectImageKpi(anyString(), anyString(), any())).thenReturn(imgKpi);
        when(queryRepository.queryDefectImageTrend(anyString(), anyString(), any())).thenReturn(Collections.emptyList());
        when(queryRepository.queryDefectImageRanking(anyString(), anyString(), anyString(), any())).thenReturn(Collections.emptyList());
        when(queryRepository.queryDefectImageStationRanking(anyString(), anyString(), any())).thenReturn(Collections.emptyList());
        when(queryRepository.queryDefectImageCameraRanking(anyString(), anyString(), any())).thenReturn(Collections.emptyList());
        when(queryRepository.queryDefectImagePivot(anyString(), anyString(), anyString(), any())).thenReturn(Collections.emptyList());
        when(queryRepository.queryDefectImageSeverityDistribution(anyString(), anyString(), anyString(), any())).thenReturn(Collections.emptyList());

        MockMvc mvc = mvc();
        mvc.perform(get("/api/v1/analytics/line-summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.dataSource").value("DEFECT_IMAGE"))
                // ★ 图片通道无良品样本 → 良率不可计算，必须是 null，绝不是 0
                .andExpect(jsonPath("$.data.kpi.yieldRate").doesNotExist())
                .andExpect(jsonPath("$.data.kpi.yieldRateAvailable").value(false))
                .andExpect(jsonPath("$.data.kpi.okCount").value(0))
                .andExpect(jsonPath("$.data.kpi.totalCount").value(50))
                .andExpect(jsonPath("$.data.plantOverview.yieldRateAvailable").value(false));
    }

    @Test
    @DisplayName("line-summary: 结束时间早于开始时间 → 抛 IllegalArgumentException 且不查库")
    void lineSummaryInvalidRange() throws Exception {
        MockMvc mvc = mvc();
        // ★ 原有行为：Controller 未捕获该异常（全局异常处理器 P3 不接入，行为保持模式），
        //   故此处断言"抛出"而非某个业务 code —— 语义下沉不应改变这一点。
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.util.NestedServletException.class,
                () -> mvc.perform(get("/api/v1/analytics/line-summary")
                        .param("startTime", "2026-11-10 00:00:00")
                        .param("endTime", "2026-11-01 00:00:00")));

        verify(queryRepository, never()).queryKpi(anyString(), anyString(), any());
    }

    // ---------------- 看板配置 ----------------

    @Test
    @DisplayName("dashboards: 列表返回数组")
    void dashboardsList() throws Exception {
        when(lineAnalyticsRepository.listDashboards(any(), any())).thenReturn(Collections.emptyList());

        MockMvc mvc = mvc();
        mvc.perform(get("/api/v1/analytics/dashboards"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("dashboards: 缺看板名称 → 业务错误且不写库")
    void createDashboardMissingName() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("factoryId", 1);

        MockMvc mvc = mvc();
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.util.NestedServletException.class,
                () -> mvc.perform(post("/api/v1/analytics/dashboards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body))));

        verify(dashboardRepository, never()).insertDashboard(any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("dashboards: 新建成功 → 写库并返回看板详情")
    void createDashboardOk() throws Exception {
        when(dashboardRepository.insertDashboard(any(), any(), any(), any(), anyInt(), any())).thenReturn(7L);
        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("dashboardId", 7L);
        dashboard.put("dashboardName", "ZZTEST看板");
        when(lineAnalyticsRepository.findDashboard(7L)).thenReturn(dashboard);
        when(lineAnalyticsRepository.listWidgets(7L)).thenReturn(Collections.emptyList());

        Map<String, Object> body = new HashMap<>();
        body.put("dashboardName", "ZZTEST看板");
        body.put("factoryId", 1);

        MockMvc mvc = mvc();
        mvc.perform(post("/api/v1/analytics/dashboards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.dashboardId").value(7));
    }

    @Test
    @DisplayName("dashboards/{id}: 看板不存在 → code=500")
    void dashboardNotFound() throws Exception {
        when(lineAnalyticsRepository.findDashboard(999L)).thenReturn(null);

        MockMvc mvc = mvc();
        mvc.perform(get("/api/v1/analytics/dashboards/999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("看板不存在"));
    }

    @Test
    @DisplayName("dashboards/{id}: 删除走逻辑删除（同时停用组件）")
    void deleteDashboard() throws Exception {
        MockMvc mvc = mvc();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/analytics/dashboards/3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("删除成功"));

        verify(dashboardRepository).disableDashboard(3L);
    }

    @Test
    @DisplayName("widgets: 缺看板ID → 业务错误且不写库")
    void createWidgetMissingDashboard() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("widgetName", "ZZTEST图表");

        MockMvc mvc = mvc();
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.util.NestedServletException.class,
                () -> mvc.perform(post("/api/v1/analytics/widgets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body))));

        verify(dashboardRepository, never()).insertWidget(any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt());
    }

    // ---------------- 聚合任务 ----------------

    @Test
    @DisplayName("aggregation/schedule: 返回配置对象")
    void aggregationSchedule() throws Exception {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("enabled", true);
        cfg.put("scheduleTime", "02:00");
        cfg.put("lookbackDays", 7);
        when(aggregationRepository.findAggregationSchedule()).thenReturn(cfg);

        MockMvc mvc = mvc();
        mvc.perform(get("/api/v1/analytics/aggregation/schedule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.scheduleTime").value("02:00"));
    }

    @Test
    @DisplayName("aggregation/jobs: 分页结构固定（records/total/page/pageSize）")
    void aggregationJobsPaging() throws Exception {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("records", Collections.emptyList());
        page.put("total", 0);
        page.put("page", 1);
        page.put("pageSize", 10);
        when(aggregationRepository.queryAggregationJobs(any(), anyInt(), anyInt())).thenReturn(page);

        MockMvc mvc = mvc();
        mvc.perform(get("/api/v1/analytics/aggregation/jobs").param("page", "1").param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(10));
    }

    @Test
    @DisplayName("aggregation/jobs/recent: 返回最近任务数组")
    void recentAggregationJobs() throws Exception {
        when(aggregationRepository.queryRecentAggregationJobs()).thenReturn(Collections.emptyList());

        MockMvc mvc = mvc();
        mvc.perform(get("/api/v1/analytics/aggregation/jobs/recent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("aggregation/jobs/{id}: 任务不存在 → code=500")
    void aggregationJobNotFound() throws Exception {
        when(aggregationRepository.findAggregationJob(999L)).thenReturn(new HashMap<>());

        MockMvc mvc = mvc();
        mvc.perform(get("/api/v1/analytics/aggregation/jobs/999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("聚合任务不存在"));
    }
}
