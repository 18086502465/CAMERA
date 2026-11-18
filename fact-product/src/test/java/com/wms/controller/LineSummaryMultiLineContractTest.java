package com.wms.controller;

import com.wms.analytics.api.LineAnalyticsController;
import com.wms.analytics.infra.repository.AnalyticsAggregationRepository;
import com.wms.analytics.infra.repository.AnalyticsDashboardRepository;
import com.wms.analytics.infra.repository.LineAnalyticsQueryRepository;
import com.wms.analytics.infra.repository.LineAnalyticsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 多产线筛选契约测试（2026-11-17）。
 *
 * <p>背景：顶部级联多选产线时，前端同时传 {@code lineId=lineIds[0]}（单值兜底）
 * 与 {@code lineIds=11,10}。后端初版实现「lineId 非空就跳过 IN」→
 * 多选退化为第一条（现场实测：勾 ZK09+ZK06 只出 ZK09，用户以为数据丢了）。
 * 这里锁死语义：lineId 与 lineIds 取<b>并集</b>，WHERE 必须是 {@code line_id IN}。
 */
@ExtendWith(MockitoExtension.class)
class LineSummaryMultiLineContractTest {

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

    /** 图片通道 KPI 桩：返回 1 张（>0 即走 DEFECT_IMAGE 主分支） */
    private void stubImageKpi() {
        Map<String, Object> kpi = new LinkedHashMap<>();
        kpi.put("imageCount", 1L);
        kpi.put("defectCount", 1L);
        kpi.put("highCount", 1L);
        kpi.put("mediumCount", 0L);
        kpi.put("lowCount", 0L);
        kpi.put("cameraCount", 1L);
        kpi.put("workstationCount", 1L);
        when(queryRepository.queryDefectImageKpi(anyString(), anyString(), any())).thenReturn(kpi);
        when(queryRepository.queryOpenMappingExceptions()).thenReturn(new ArrayList<>());
    }

    /** 抓取传给 KPI 查询的 WHERE 片段与参数 */
    private CapturedWhere captureKpiWhere() {
        ArgumentCaptor<String> where = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<Object>> params = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(queryRepository).queryDefectImageKpi(anyString(), where.capture(), params.capture());
        return new CapturedWhere(where.getValue(), params.getValue());
    }

    private static class CapturedWhere {
        final String where;
        final List<Object> params;
        CapturedWhere(String where, List<Object> params) { this.where = where; this.params = params; }
    }

    @Test
    @DisplayName("lineId+lineIds 并集：WHERE 必须是 line_id IN (?,?)，参数按序为 11,10")
    void unionsLineIdAndLineIds() throws Exception {
        stubImageKpi();
        mvc().perform(get("/api/v1/analytics/line-summary")
                        .param("startTime", "2026-11-17 00:00:00")
                        .param("endTime", "2026-11-17 23:59:59")
                        .param("lineId", "11")
                        .param("lineIds", "11,10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        CapturedWhere c = captureKpiWhere();
        assertTrue(c.where.contains("c.line_id IN (?,?)"), "WHERE 应为 IN，实际：" + c.where);
        assertEquals(Arrays.asList(11L, 10L), c.params.subList(2, 4), "IN 参数应为时间之后的 11,10");
    }

    @Test
    @DisplayName("仅 lineIds（POST /query 数组形式）：同样走 IN")
    void lineIdsOnlyViaPostQuery() throws Exception {
        stubImageKpi();
        mvc().perform(post("/api/v1/analytics/query")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"startTime\":\"2026-11-17 00:00:00\",\"endTime\":\"2026-11-17 23:59:59\",\"lineIds\":[11,10,12]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        CapturedWhere c = captureKpiWhere();
        assertTrue(c.where.contains("c.line_id IN (?,?,?)"), "WHERE 应为 IN，实际：" + c.where);
    }

    @Test
    @DisplayName("仅单值 lineId：保持旧语义 line_id = ?")
    void singleLineIdKeepsEquality() throws Exception {
        stubImageKpi();
        mvc().perform(get("/api/v1/analytics/line-summary")
                        .param("startTime", "2026-11-17 00:00:00")
                        .param("endTime", "2026-11-17 23:59:59")
                        .param("lineId", "11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        CapturedWhere c = captureKpiWhere();
        assertTrue(c.where.contains("c.line_id = ?"), "单产线应保持 = 语义，实际：" + c.where);
    }

    @Test
    @DisplayName("非法 lineIds 只忽略非法 token，不 500")
    void garbageLineIdsIgnored() throws Exception {
        stubImageKpi();
        mvc().perform(get("/api/v1/analytics/line-summary")
                        .param("startTime", "2026-11-17 00:00:00")
                        .param("endTime", "2026-11-17 23:59:59")
                        .param("lineIds", "abc,,11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        CapturedWhere c = captureKpiWhere();
        assertTrue(c.where.contains("c.line_id = ?"), "解析后仅剩 11，应为 = 语义");
        assertEquals(11L, c.params.get(2), "= 参数应为 11");
    }

    @Test
    @DisplayName("workstationId+workstationIds 并集：WHERE 必须是 workstation_id IN (?,?)")
    void unionsWorkstationIdAndWorkstationIds() throws Exception {
        stubImageKpi();
        mvc().perform(get("/api/v1/analytics/line-summary")
                        .param("startTime", "2026-11-17 00:00:00")
                        .param("endTime", "2026-11-17 23:59:59")
                        .param("workstationId", "33")
                        .param("workstationIds", "33,34"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        CapturedWhere c = captureKpiWhere();
        assertTrue(c.where.contains("c.workstation_id IN (?,?)"), "WHERE 应为 IN，实际：" + c.where);
        assertEquals(Arrays.asList(33L, 34L), c.params.subList(2, 4), "IN 参数应为时间之后的 33,34");
    }

    @Test
    @DisplayName("POST /query 的 workstationIds 数组同样生效")
    void workstationIdsViaPostQuery() throws Exception {
        stubImageKpi();
        mvc().perform(post("/api/v1/analytics/query")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"startTime\":\"2026-11-17 00:00:00\",\"endTime\":\"2026-11-17 23:59:59\",\"workstationIds\":[7,8,9]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        CapturedWhere c = captureKpiWhere();
        assertTrue(c.where.contains("c.workstation_id IN (?,?,?)"), "WHERE 应为 IN，实际：" + c.where);
    }

    /**
     * 日历块必须用**独立近 30 天窗口**：原先复用主 where，时间范围选「今天」时
     * 日历只剩 1 个数据点（现场实测 points=1），内置「缺陷日历（近 30 天）」名不副实。
     */
    @Test
    @DisplayName("dailyCalendar 用近 30 天窗口（end-29d 起），不跟随当前时间范围")
    void dailyCalendarUsesRolling30DayWindow() throws Exception {
        stubImageKpi();
        ArgumentCaptor<String> where = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object>> params = ArgumentCaptor.forClass(List.class);
        mvc().perform(get("/api/v1/analytics/line-summary")
                        .param("startTime", "2026-11-17 00:00:00")
                        .param("endTime", "2026-11-17 23:59:59"))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(queryRepository)
                .queryDefectImageDailyCalendar(anyString(), where.capture(), params.capture());
        java.sql.Timestamp start = (java.sql.Timestamp) params.getValue().get(0);
        java.sql.Timestamp end = (java.sql.Timestamp) params.getValue().get(1);
        assertEquals(java.sql.Timestamp.valueOf(java.time.LocalDateTime.of(2026, 10, 19, 0, 0, 0)), start,
                "日历起点应为 end-29 天的 00:00，实际：" + start);
        assertEquals(java.sql.Timestamp.valueOf(java.time.LocalDateTime.of(2026, 11, 17, 23, 59, 59)), end,
                "日历终点应等于查询 end，实际：" + end);
    }
}
