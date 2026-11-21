package com.wms.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wms.analytics.api.DailyReportController;
import com.wms.analytics.infra.repository.AnomalyAlertRepository;
import com.wms.analytics.infra.repository.DailyReportRepository;
import com.wms.analytics.infra.task.DailyReportGenerationTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 产线统计日报契约测试（2026-11-17 新增）。
 *
 * <p>冻结点（改坏任何一条 = 口径漂移，必须停）：
 * <ul>
 *   <li><b>无数据 ≠ 0</b>：detectCount=0 的行 {@code defectRateKilo} 必须是 null，
 *       绝不出现「0‰ 缺陷率」；</li>
 *   <li><b>dataSource 语义</b>：历史日 = SNAPSHOT、当天 = REALTIME；</li>
 *   <li>快照缺失（服务上线前的历史日）→ {@code missing=true}，不伪造数字；</li>
 *   <li>未来日期 → 拒绝；start &gt; end → 拒绝；</li>
 *   <li>生成任务成功 → 自动恢复 DAILY_REPORT_FAILED；失败 → 落 HIGH 告警。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DailyReportControllerTest {

    private final ObjectMapper om = new ObjectMapper();

    @Mock
    private DailyReportRepository dailyReportRepository;

    @Mock
    private DailyReportGenerationTask generationTask;

    @Mock
    private AnomalyAlertRepository anomalyAlertRepository;

    private MockMvc controllerMvc() {
        // ★ 2026-11-20：注册全局异常处理器 —— 生产环境它是 @RestControllerAdvice（全局生效），
        //   而 standaloneSetup 不会自动带上 advice → Controller 抛出的 IllegalArgumentException
        //   会直接冒泡成 NestedServletException，测不出真实的 HTTP 400 行为。
        return MockMvcBuilders.standaloneSetup(new DailyReportController(dailyReportRepository, generationTask))
                .setControllerAdvice(new com.wms.shared.web.GlobalExceptionHandler())
                .build();
    }

    /** 厂级行快照（2026-11-16 现场实测口径：1230 张 / 38 框 / 30.9‰） */
    private Map<String, Object> plantRow(LocalDate date, int detect, int defect, double rate) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("reportDate", Date.valueOf(date));
        row.put("lineId", 0);
        row.put("lineName", "全厂");
        row.put("detectCount", detect);
        row.put("defectCount", defect);
        row.put("defectImages", defect);
        row.put("defectRateKilo", BigDecimal.valueOf(rate));
        row.put("cameraTotal", 2);
        row.put("cameraOnline", 2);
        row.put("tempAlarmCount", 2);
        row.put("topDefectTypes", "[{\"type\":\"接头\",\"count\":20},{\"type\":\"小污点\",\"count\":12}]");
        row.put("worstCameraCode", "DA6090850");
        row.put("worstCameraName", "CV2");
        row.put("worstCameraRate", BigDecimal.valueOf(45.5));
        row.put("worstCameraImages", 620);
        row.put("snapshotVersion", 1);
        return row;
    }

    @Test
    @DisplayName("列表：快照行缺 reportDate 键（如 SQL 别名漏写）不得 500，行保留但无日期字段")
    void listToleratesMissingReportDate() throws Exception {
        // 现场事故（2026-11-17）：listRange SQL 漏写 report_date AS reportDate 别名，
        // 快照行全部缺 reportDate → decorate NPE → 列表接口 500。此处锁死防线。
        Map<String, Object> broken = new LinkedHashMap<>();
        broken.put("lineId", 0);
        broken.put("detectCount", 100);
        when(dailyReportRepository.listRange(any(LocalDate.class), any(LocalDate.class), isNull()))
                .thenReturn(Collections.singletonList(broken));
        when(dailyReportRepository.computeDay(any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(Collections.emptyList());

        controllerMvc().perform(get("/api/v1/analytics/daily-reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.rows.length()").value(1));
    }

    @Test
    @DisplayName("列表：历史日读快照、dataSource=SNAPSHOT，环比由前一天快照计算")
    void listSnapshotRows() throws Exception {
        LocalDate d1 = LocalDate.now().minusDays(2);
        LocalDate d2 = LocalDate.now().minusDays(1);
        when(dailyReportRepository.listRange(any(LocalDate.class), any(LocalDate.class), isNull()))
                .thenReturn(Arrays.asList(plantRow(d1, 1200, 30, 25.0), plantRow(d2, 1230, 38, 30.9)));

        // 当天 computeDay 返回空（今天无数据），不应往列表塞 0 行
        when(dailyReportRepository.computeDay(any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(Collections.emptyList());

        controllerMvc().perform(get("/api/v1/analytics/daily-reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.rows.length()").value(2))
                .andExpect(jsonPath("$.data.rows[0].dataSource").value("SNAPSHOT"))
                .andExpect(jsonPath("$.data.rows[0].defectRateKilo").value(30.9))
                // ★ 2026-11-19 评审 P1-4：环比口径由「缺陷率」改为「**缺陷框数**」。
                //   旧口径用 defectRateKilo —— 图片通道「每图必带 1 框」→ 该比率**恒为 1000‰**
                //   → 真实快照上环比**恒 0.0%**，整列无信息量。
                //   新口径：缺陷框数 30 → 38 → (38-30)/30 = 26.67%（反映"缺陷量在变多还是变少"）。
                .andExpect(jsonPath("$.data.rows[0].dayOverDayRate").value(26.67))
                .andExpect(jsonPath("$.data.caliber.rateDenominator").value("检测总数日增（心跳累计计数差分）"));
    }

    @Test
    @DisplayName("列表：无数据显示 NULL 缺陷率，绝不伪造 0‰（口径冻结点）")
    void listZeroDataIsNullNotZero() throws Exception {
        when(dailyReportRepository.listRange(any(LocalDate.class), any(LocalDate.class), isNull()))
                .thenReturn(Collections.singletonList(plantRow(LocalDate.now().minusDays(1), 0, 0, 0.0)));
        when(dailyReportRepository.computeDay(any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(Collections.emptyList());

        String body = controllerMvc().perform(get("/api/v1/analytics/daily-reports"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        Map<?, ?> res = om.readValue(body, Map.class);
        Map<?, ?> row = (Map<?, ?>) ((List<?>) ((Map<?, ?>) res.get("data")).get("rows")).get(0);
        // detectCount=0 → defectRateKilo 必须为 null（诚实降级），dayOverDayRate 也为 null
        assertThat(row.get("defectRateKilo")).isNull();
        assertThat(row.get("dayOverDayRate")).isNull();
        assertThat(row.get("sampleInsufficient")).isEqualTo(true);
    }

    @Test
    @DisplayName("列表：start > end 拒绝（★ 2026-11-20 复测：契约由 200+code500 改为 HTTP 400）")
    void listRejectsInvertedRange() throws Exception {
        // ★ 契约变更说明：
        //   原行为 `ResultVO.fail("开始日期不能晚于结束日期")` → **HTTP 200 + body.code=500**
        //   （HTTP 说"成功"、业务码说"失败"，自相矛盾），而同一语义的 export 端点返回 400
        //   —— **同一语义的两个端点两种结论**，第三方接入无法统一处理。
        //   现统一为 HTTP 400 + 可读消息（项目既有约定：参数错误用 400）。
        controllerMvc().perform(get("/api/v1/analytics/daily-reports")
                        .param("startDate", "2026-11-16").param("endDate", "2026-11-15"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("开始日期不能晚于结束日期")));
        verify(dailyReportRepository, never()).listRange(any(), any(), any());
    }

    @Test
    @DisplayName("详情：快照日拆分厂级行与产线行，TOP 类型 JSON 解析为列表")
    void detailSplitsFactoryAndLines() throws Exception {
        LocalDate day = LocalDate.now().minusDays(1);
        Map<String, Object> line = plantRow(day, 1230, 38, 30.9);
        line.put("lineId", 11);
        line.put("lineName", "ZK23");
        when(dailyReportRepository.listRange(any(LocalDate.class), any(LocalDate.class), isNull()))
                .thenReturn(Arrays.asList(plantRow(day, 1230, 38, 30.9), line));

        controllerMvc().perform(get("/api/v1/analytics/daily-reports/" + day))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dataSource").value("SNAPSHOT"))
                .andExpect(jsonPath("$.data.factory.lineId").value(0))
                .andExpect(jsonPath("$.data.lines.length()").value(1))
                .andExpect(jsonPath("$.data.lines[0].lineName").value("ZK23"))
                .andExpect(jsonPath("$.data.lines[0].topDefectTypeList[0].type").value("接头"))
                .andExpect(jsonPath("$.data.lines[0].topDefectTypeList[0].count").value(20))
                .andExpect(jsonPath("$.data.factory.sampleInsufficient").value(false));
    }

    @Test
    @DisplayName("详情：无快照的历史日 → missing=true，不伪造 0（口径冻结点）")
    void detailMissingSnapshot() throws Exception {
        when(dailyReportRepository.listRange(any(LocalDate.class), any(LocalDate.class), isNull()))
                .thenReturn(Collections.emptyList());

        controllerMvc().perform(get("/api/v1/analytics/daily-reports/2026-01-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.missing").value(true))
                .andExpect(jsonPath("$.data.factory").doesNotExist());
    }

    @Test
    @DisplayName("详情：当天实时计算（REALTIME），未来日期拒绝")
    void detailTodayRealtimeAndFutureRejected() throws Exception {
        when(dailyReportRepository.computeDay(any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(Collections.singletonList(plantRow(LocalDate.now(), 500, 10, 20.0)));

        controllerMvc().perform(get("/api/v1/analytics/daily-reports/" + LocalDate.now()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dataSource").value("REALTIME"))
                .andExpect(jsonPath("$.data.factory.detectCount").value(500));

        // 2026-11-17 起：实时行也查前一天快照计算计数口径日增（counterRateKilo），
        // 所以 listRange 会被 decorate 调用 —— 不再断言 never()。

        // ★ 2026-11-20 复测 D-8：契约由「HTTP 200 + code=500」改为 **HTTP 400**。
        //   原来 HTTP 说"成功"、业务码说"失败"，自相矛盾；
        //   而同一语义其它端点对参数错误返回 400 → 统一为 400 + 可读消息。
        controllerMvc().perform(get("/api/v1/analytics/daily-reports/2099-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("不能查询未来日期")));
    }

    @Test
    @DisplayName("补算：成功回显行数；失败转发为 fail 并提示告警已落")
    void regeneratePaths() throws Exception {
        when(generationTask.generateFor(any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(true);
        when(dailyReportRepository.listRange(any(LocalDate.class), any(LocalDate.class), isNull()))
                .thenReturn(Collections.singletonList(plantRow(LocalDate.now().minusDays(1), 1230, 38, 30.9)));

        controllerMvc().perform(post("/api/v1/analytics/daily-reports/2026-11-16/regenerate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.regenerated").value(true))
                .andExpect(jsonPath("$.data.message").value("补算完成"));

        when(generationTask.generateFor(any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(false);
        controllerMvc().perform(post("/api/v1/analytics/daily-reports/2026-11-15/regenerate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
    }

    @Test
    @DisplayName("补算计数快照语义：历史日期不拍计数（当前计数≠历史日终，差分必错），当天才拍")
    void regenerateCounterStampSemantics() throws Exception {
        when(generationTask.generateFor(any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(true);
        when(dailyReportRepository.listRange(any(LocalDate.class), any(LocalDate.class), isNull()))
                .thenReturn(Collections.singletonList(plantRow(LocalDate.now(), 100, 3, 30.0)));

        // 历史日期 → stampCounters=false
        controllerMvc().perform(post("/api/v1/analytics/daily-reports/2026-11-10/regenerate"))
                .andExpect(status().isOk());
        verify(generationTask).generateFor(eq(LocalDate.parse("2026-11-10")), eq(false));

        // 当天 → stampCounters=true（日界未过，当前计数即今天截至此刻的真值）
        LocalDate today = LocalDate.now();
        controllerMvc().perform(post("/api/v1/analytics/daily-reports/" + today + "/regenerate"))
                .andExpect(status().isOk());
        verify(generationTask).generateFor(eq(today), eq(true));
    }

    @Test
    @DisplayName("生成任务：成功后自动恢复 DAILY_REPORT_FAILED（脚本告警恢复纪律）")
    void taskResolvesAlertOnSuccess() {
        DailyReportGenerationTask task = new DailyReportGenerationTask(
                dailyReportRepository, anomalyAlertRepository, stubTransactionManager());
        when(dailyReportRepository.computeDay(any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(Collections.emptyList());
        when(anomalyAlertRepository.findUnresolvedAlertId(eq(0), eq(0), eq("DAILY_REPORT_FAILED")))
                .thenReturn(5L);

        boolean ok = task.generateFor(LocalDate.now().minusDays(1), true);

        assertThat(ok).isTrue();
        verify(dailyReportRepository).deleteDate(any(LocalDate.class));
        verify(anomalyAlertRepository).resolveCameraAlert(5L, "AUTO_RECOVER");
        verify(anomalyAlertRepository, never()).insertCameraAlert(anyInt(), anyInt(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("生成任务：失败落 HIGH 级 DAILY_REPORT_FAILED 告警")
    void taskRaisesAlertOnFailure() {
        DailyReportGenerationTask task = new DailyReportGenerationTask(
                dailyReportRepository, anomalyAlertRepository, stubTransactionManager());
        when(dailyReportRepository.computeDay(any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenThrow(new RuntimeException("db down"));

        boolean ok = task.generateFor(LocalDate.now().minusDays(1), true);

        assertThat(ok).isFalse();
        ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> level = ArgumentCaptor.forClass(String.class);
        verify(anomalyAlertRepository).insertCameraAlert(anyInt(), anyInt(), type.capture(),
                level.capture(), any(), any());
        assertThat(type.getValue()).isEqualTo("DAILY_REPORT_FAILED");
        assertThat(level.getValue()).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("计数口径：日增差分出真缺陷率；计数器回绕（差分为负）→ 显示待积累（口径冻结点）")
    void counterRateDelta() throws Exception {
        LocalDate today = LocalDate.now();
        Map<String, Object> todayRow = plantRow(today, 100, 100, 1000.0);
        todayRow.put("inspTotalCum", 1100L);
        todayRow.put("ngTotalCum", 15L);
        when(dailyReportRepository.computeDay(any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(Collections.singletonList(todayRow));
        Map<String, Object> prevRow = plantRow(today.minusDays(1), 0, 0, 0.0);
        prevRow.put("inspTotalCum", 1000L);
        prevRow.put("ngTotalCum", 10L);
        when(dailyReportRepository.listRange(any(LocalDate.class), any(LocalDate.class), isNull()))
                .thenReturn(Collections.singletonList(prevRow));

        // ngDelta=5 / inspDelta=100 → 50.0‰
        controllerMvc().perform(get("/api/v1/analytics/daily-reports/" + today))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dataSource").value("REALTIME"))
                .andExpect(jsonPath("$.data.factory.inspDelta").value(100))
                .andExpect(jsonPath("$.data.factory.ngDelta").value(5))
                .andExpect(jsonPath("$.data.factory.counterRateKilo").value(50.0));

        // 计数器被重置：ngCum < prevNg → 不可信，显示待积累而不是负数
        todayRow.put("ngTotalCum", 5L);
        controllerMvc().perform(get("/api/v1/analytics/daily-reports/" + today))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.factory.counterRateKilo").doesNotExist());
    }

    @Test
    @DisplayName("月历：全月每天一格，历史日读快照、计数差分需前一天基线（缺基线则该日无计数率）")
    void calendarFillsEveryDayOfMonth() throws Exception {
        LocalDate d15 = LocalDate.of(2026, 11, 15);
        LocalDate d16 = LocalDate.of(2026, 11, 16);
        Map<String, Object> r15 = plantRow(d15, 0, 0, 0);
        r15.put("defectRateKilo", null);
        r15.put("inspTotalCum", 1000L);
        r15.put("ngTotalCum", 10L);
        Map<String, Object> r16 = plantRow(d16, 1230, 38, 30.9);
        r16.put("inspTotalCum", 2230L);
        r16.put("ngTotalCum", 48L);
        when(dailyReportRepository.listRange(any(LocalDate.class), any(LocalDate.class), any(Integer.class)))
                .thenReturn(Arrays.asList(r16, r15));
        // 今天可能不在 2026-11（跑测试的日期不固定）→ lenient，避免 strict stubbing 报未使用
        org.mockito.Mockito.lenient()
                .when(dailyReportRepository.computeDay(any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(Collections.emptyList());
        when(dailyReportRepository.listRealLines())
                .thenReturn(Collections.singletonList(lineOption(6, "ZK06")));

        controllerMvc().perform(get("/api/v1/analytics/daily-reports/calendar?month=2026-11"))
                .andExpect(status().isOk())
                // 2026-11 共 30 天，一格都不能少（未来日/无数据日也要有格）
                .andExpect(jsonPath("$.data.days.length()").value(30))
                .andExpect(jsonPath("$.data.days[0].date").value("2026-11-01"))
                .andExpect(jsonPath("$.data.days[14].date").value("2026-11-15"))
                .andExpect(jsonPath("$.data.days[14].hasData").value(false))
                .andExpect(jsonPath("$.data.days[15].date").value("2026-11-16"))
                .andExpect(jsonPath("$.data.days[15].hasData").value(true))
                // 计数差分：(2230-1000)=1230 检测增、(48-10)=38 NG 增 → 30.89‰
                .andExpect(jsonPath("$.data.days[15].counterRateKilo").value(30.89))
                // 11-15 前一天（11-14）没有快照基线 → 差分不可用，不得给出数字
                .andExpect(jsonPath("$.data.days[14].counterRateKilo").doesNotExist())
                // 产线胶囊 Tab：全厂永远在首位
                .andExpect(jsonPath("$.data.lines[0].lineId").value(0))
                .andExpect(jsonPath("$.data.lines[0].lineName").value("全厂"))
                .andExpect(jsonPath("$.data.lines[1].lineName").value("ZK06"));
    }

    @Test
    @DisplayName("单日维度：汇总取快照行，idleHours 归入 anomalies；明细缺失时 detailsAvailable=false")
    void breakdownMergesSnapshotKpiAndDimensions() throws Exception {
        LocalDate day = LocalDate.of(2026, 11, 16);
        Map<String, Object> row = plantRow(day, 1230, 38, 30.9);
        row.put("inspTotalCum", 2230L);
        row.put("ngTotalCum", 48L);
        when(dailyReportRepository.listRange(eq(day), eq(day), eq(0)))
                .thenReturn(Collections.singletonList(row));
        when(dailyReportRepository.listRange(eq(day.minusDays(1)), eq(day.minusDays(1)), isNull()))
                .thenReturn(Collections.emptyList());

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("typeBreakdown", Collections.singletonList(
                new LinkedHashMap<String, Object>() {{ put("type", "接头"); put("count", 20); put("share", 52.63); }}));
        details.put("cameraBreakdown", Collections.singletonList(
                new LinkedHashMap<String, Object>() {{ put("cameraCode", "DA6090850"); put("detectCount", 620); }}));
        details.put("hourly", Collections.emptyList());
        details.put("idleHours", Arrays.asList(3, 4));
        details.put("detailsAvailable", true);
        details.put("detailDetectCount", 620);
        // ★ P1-3/G20：生产走 6 参（工位/相机/缺陷类型/班次筛选），测试桩同步到新签名。
        when(dailyReportRepository.computeBreakdownDetails(eq(day), eq(0), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(details);

        controllerMvc().perform(get("/api/v1/analytics/daily-reports/2026-11-16/breakdown"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dataSource").value("SNAPSHOT"))
                .andExpect(jsonPath("$.data.lineName").value("全厂"))
                .andExpect(jsonPath("$.data.kpi.detectCount").value(1230))
                .andExpect(jsonPath("$.data.detailsAvailable").value(true))
                .andExpect(jsonPath("$.data.typeBreakdown[0].type").value("接头"))
                // idleHours 必须落在 anomalies 里，不能留在顶层（顶层是明细字段区）
                .andExpect(jsonPath("$.data.anomalies.idleHours.length()").value(2))
                .andExpect(jsonPath("$.data.idleHours").doesNotExist())
                // ★ P0-4：异常全景字段必须存在（2 参构造无告警仓储 → 空 types + note，不 500）
                .andExpect(jsonPath("$.data.alertTrend").exists())
                .andExpect(jsonPath("$.data.alertTrend.days.length()").value(7))
                // ★ P1-3/G20：无明细筛选时 ignoredFilters 必须为空、无 detailFilterMessage / detailFilterActive
                .andExpect(jsonPath("$.data.ignoredFilters.length()").value(0))
                .andExpect(jsonPath("$.data.detailFilterMessage").doesNotExist())
                .andExpect(jsonPath("$.data.detailFilterActive").doesNotExist());
    }

    @Test
    @DisplayName("P1-3：工位/相机/缺陷类型筛选真正传给明细仓储，且不再上报 ignoredFilters")
    void breakdownDetailFiltersPassThrough() throws Exception {
        LocalDate day = LocalDate.of(2026, 11, 16);
        Map<String, Object> row = plantRow(day, 1230, 38, 30.9);
        when(dailyReportRepository.listRange(eq(day), eq(day), eq(0)))
                .thenReturn(Collections.singletonList(row));
        when(dailyReportRepository.listRange(eq(day.minusDays(1)), eq(day.minusDays(1)), isNull()))
                .thenReturn(Collections.emptyList());
        when(dailyReportRepository.computeBreakdownDetails(eq(day), eq(0), eq(3), eq(7L), eq("接头"), isNull()))
                .thenReturn(new LinkedHashMap<>());

        controllerMvc().perform(get("/api/v1/analytics/daily-reports/2026-11-16/breakdown")
                        .param("workstationId", "3")
                        .param("cameraId", "7")
                        .param("defectType", "接头"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ignoredFilters.length()").value(0))
                .andExpect(jsonPath("$.data.detailFilterMessage").exists());
        verify(dailyReportRepository).computeBreakdownDetails(eq(day), eq(0), eq(3), eq(7L), eq("接头"), isNull());
    }

    @Test
    @DisplayName("G20：班次筛选透传给明细仓储，且响应给出 detailFilterActive/Names 与口径提示")
    void breakdownShiftFilterPassThrough() throws Exception {
        LocalDate day = LocalDate.of(2026, 11, 16);
        when(dailyReportRepository.listRange(eq(day), eq(day), eq(0)))
                .thenReturn(Collections.singletonList(plantRow(day, 1230, 38, 30.9)));
        when(dailyReportRepository.listRange(eq(day.minusDays(1)), eq(day.minusDays(1)), isNull()))
                .thenReturn(Collections.emptyList());
        when(dailyReportRepository.computeBreakdownDetails(eq(day), eq(0), isNull(), isNull(), isNull(), eq(2L)))
                .thenReturn(new LinkedHashMap<>());

        controllerMvc().perform(get("/api/v1/analytics/daily-reports/2026-11-16/breakdown")
                        .param("shiftId", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ignoredFilters.length()").value(0))
                .andExpect(jsonPath("$.data.detailFilterActive").value(true))
                .andExpect(jsonPath("$.data.detailFilterNames[0]").value("班次"))
                .andExpect(jsonPath("$.data.detailFilterMessage").value(
                        org.hamcrest.Matchers.containsString("未随明细筛选变化")))
                // G19：白话口径字段必须随 breakdown 返回
                .andExpect(jsonPath("$.data.caliber.ratePlain").exists())
                .andExpect(jsonPath("$.data.caliber.passPlain").exists());
        verify(dailyReportRepository).computeBreakdownDetails(eq(day), eq(0), isNull(), isNull(), isNull(), eq(2L));
    }

    @Test
    @DisplayName("P1-2：直通率用 ok 累计差分算，且 ok 语义未校准时标注口径（不叫良率）")
    void heartbeatPassRateUsesOkDelta() throws Exception {
        LocalDate day = LocalDate.of(2026, 11, 16);
        Map<String, Object> row = plantRow(day, 1230, 38, 30.9);
        row.put("inspTotalCum", 2230L);
        row.put("ngTotalCum", 48L);
        row.put("okTotalCum", 2182L);
        Map<String, Object> prev = plantRow(day.minusDays(1), 1000, 10, 10.0);
        prev.put("inspTotalCum", 1230L);
        prev.put("ngTotalCum", 10L);
        prev.put("okTotalCum", 1220L);
        when(dailyReportRepository.listRange(eq(day), eq(day), eq(0)))
                .thenReturn(Collections.singletonList(row));
        when(dailyReportRepository.listRange(eq(day.minusDays(1)), eq(day.minusDays(1)), isNull()))
                .thenReturn(Collections.singletonList(prev));
        when(dailyReportRepository.computeBreakdownDetails(eq(day), eq(0), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(new LinkedHashMap<>());

        controllerMvc().perform(get("/api/v1/analytics/daily-reports/2026-11-16/breakdown"))
                .andExpect(status().isOk())
                // okDelta = 2182-1220 = 962；inspDelta = 2230-1230 = 1000 → 96.2%
                .andExpect(jsonPath("$.data.kpi.okDelta").value(962))
                .andExpect(jsonPath("$.data.kpi.heartbeatPassRate").value(96.2))
                .andExpect(jsonPath("$.data.caliber.heartbeatPassNote").exists());
    }

    @Test
    @DisplayName("G20 发版回归：生产构造函数必须带 @Autowired，防止 Spring 退回无参构造启动崩溃")
    void productionConstructorIsAutowired() {
        boolean annotated = false;
        for (java.lang.reflect.Constructor<?> c : DailyReportController.class.getDeclaredConstructors()) {
            if (c.getParameterCount() == 3
                    && c.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class)) {
                annotated = true;
            }
        }
        assertThat(annotated).as("DailyReportController 的 3 参生产构造必须标 @Autowired；"
                + "两个构造都不标时 Spring 会尝试无参构造 → 启动即崩 No default constructor found").isTrue();
    }

    private static Map<String, Object> lineOption(int lineId, String lineName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lineId", lineId);
        m.put("lineName", lineName);
        return m;
    }

    private PlatformTransactionManager stubTransactionManager() {
        PlatformTransactionManager ptm = org.mockito.Mockito.mock(PlatformTransactionManager.class);
        org.mockito.Mockito.when(ptm.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());
        return ptm;
    }
}
