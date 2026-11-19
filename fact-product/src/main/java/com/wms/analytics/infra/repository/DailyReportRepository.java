package com.wms.analytics.infra.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 产线统计日报快照仓储（2026-11-17 新增）。
 *
 * <p>口径（与 {@code docs/archive/2026-11-17-产线统计日报PRD.md} 逐字一致，改口径先改文档）：
 * <ul>
 *   <li><b>只统计 REAL 相机</b>：{@code camera_type='REAL' AND is_deleted=0}，
 *       TEST/未知相机（camera_code 不在 dim_camera 的行）一律不进日报 —— 厂长看到的
 *       数字必须可直接对现场；</li>
 *   <li><b>缺陷率分母 = 检测图片数</b>（H1 拍板），分子 = {@code SUM(f.defect_count)}，
 *       单位「每千张」（D2 拍板）；</li>
 *   <li><b>无数据 ≠ 0</b>：detect_count=0 时缺陷率写 NULL，页面显示「无数据」，
 *       绝不显示 0‰（延续统计口径诚实化原则）；</li>
 *   <li><b>相机排名需要样本下限</b>：当日检测图片数 ≥{@link #MIN_RANK_SAMPLE} 才参与
 *       「最差相机」排名，防止 3 张图 2 张缺陷的假 100%。</li>
 * </ul>
 *
 * <p>每天 00:05 由 {@code DailyReportGenerationTask} 物化前一天（幂等覆盖）；当天数据
 * 不落库，由 {@link #computeDay} 实时计算（响应带 {@code dataSource=REALTIME}）。
 * 快照表不受 31 天图片归档影响 —— 这是选「物化」而不是「实时聚合」的核心收益。
 */
@Repository
public class DailyReportRepository {

    /** 最差相机排名的最低样本（当日检测图片数），不足则该相机不参与排名 */
    public static final int MIN_RANK_SAMPLE = 100;

    /** 口径版本：改口径（分母/过滤/指标定义）时 +1，历史快照保持原版本号不变 */
    public static final int SNAPSHOT_VERSION = 1;

    /** REAL 相机过滤 JOIN：JOIN（非 LEFT）—— camera_code 不在 dim_camera 的孤儿行不是真实产线数据，直接排除 */
    static final String REAL_CAMERA_JOIN =
            " JOIN dim_camera c ON c.camera_code = f.camera_id " +
            "      AND c.camera_type = 'REAL' AND IFNULL(c.is_deleted, 0) = 0 ";

    private final JdbcTemplate jdbcTemplate;

    public DailyReportRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ==================== 表结构 ====================

    /** 启动期建表（幂等，与 07_daily_report.sql 同构；SchemaInitializer 调用） */
    public void ensureTable() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS fact_daily_line_report (" +
                "id                  BIGINT       NOT NULL AUTO_INCREMENT," +
                "report_date         DATE         NOT NULL," +
                "line_id             INT          NOT NULL DEFAULT 0," +
                "line_name           VARCHAR(100) NULL," +
                "detect_count        INT          NOT NULL DEFAULT 0," +
                "defect_count        INT          NOT NULL DEFAULT 0," +
                "defect_images       INT          NOT NULL DEFAULT 0," +
                "defect_rate_kilo    DECIMAL(10,2) NULL," +
                "camera_total        INT          NOT NULL DEFAULT 0," +
                "camera_online       INT          NOT NULL DEFAULT 0," +
                "temp_alarm_count    INT          NOT NULL DEFAULT 0," +
                "top_defect_types    VARCHAR(1024) NULL," +
                "worst_camera_code   VARCHAR(64)  NULL," +
                "worst_camera_name   VARCHAR(100) NULL," +
                "worst_camera_rate   DECIMAL(10,2) NULL," +
                "worst_camera_images INT          NULL," +
                "insp_total_cum      BIGINT       NULL," +
                "ok_total_cum        BIGINT       NULL," +
                "ng_total_cum        BIGINT       NULL," +
                "snapshot_version    INT          NOT NULL DEFAULT 1," +
                "create_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                "update_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                "PRIMARY KEY (id)," +
                "UNIQUE KEY uk_report_date_line (report_date, line_id)," +
                "KEY idx_report_date (report_date)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        ensureColumns();
        ensureFactoryView();
    }

    /**
     * 建「只含厂级汇总行」的视图（2026-11-19 评审 N-07）。
     *
     * <p><b>为什么需要</b>：本表同时存**厂级行（{@code line_id=0}）**与
     * **产线行（{@code line_id>0}）** —— 两套是同一份数据的两种视角，
     * <b>不是可加的两部分</b>。任何直接 {@code SUM} 的下游统计都会翻倍：
     * 实测 11-18 厂级行 {@code detect_count=11377}，而 6 行
     * {@code SUM(detect_count)=22754} = <b>恰好 2 倍</b>。
     *
     * <p>这个视图把"只取厂级"的口径<b>固化</b>下来，避免每个查询方各自记一份
     * "记得加 {@code WHERE line_id=0}" 的口头约定 —— 约定一定会被忘。
     *
     * <p>{@code CREATE OR REPLACE VIEW} 是幂等的，重复启动安全。
     */
    private void ensureFactoryView() {
        try {
            jdbcTemplate.execute("CREATE OR REPLACE VIEW v_daily_line_report_factory AS " +
                    "SELECT * FROM fact_daily_line_report WHERE line_id = 0");
        } catch (Exception e) {
            // 视图创建失败不应阻断应用启动 —— 它只是防「误 SUM 翻倍」的护栏，
            // 不是功能必需（真实数据仍完整存在于表里）。
            org.slf4j.LoggerFactory.getLogger(DailyReportRepository.class)
                    .warn("创建 v_daily_line_report_factory 视图失败（不影响日报功能）: {}", e.getMessage());
        }
    }

    /**
     * 补齐后增列（2026-11-17 计数口径三列）。ensureTable 的 CREATE IF NOT EXISTS 对
     * 已存在的表不生效，列级迁移必须单独做 —— 现场表可能在本版本之前就已建好。
     */
    @SuppressWarnings("unchecked")
    private void ensureColumns() {
        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS " +
                        " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fact_daily_line_report'");
        java.util.Set<String> cols = new java.util.HashSet<>();
        for (Map<String, Object> c : existing) {
            cols.add(String.valueOf(c.get("COLUMN_NAME")).toLowerCase());
        }
        if (!cols.contains("insp_total_cum")) {
            jdbcTemplate.execute("ALTER TABLE fact_daily_line_report " +
                    "ADD COLUMN insp_total_cum BIGINT NULL COMMENT 'REAL 相机 total_count 累计和（生成时点快照）' AFTER worst_camera_images");
        }
        if (!cols.contains("ok_total_cum")) {
            jdbcTemplate.execute("ALTER TABLE fact_daily_line_report " +
                    "ADD COLUMN ok_total_cum BIGINT NULL COMMENT 'REAL 相机 ok_count 累计和' AFTER insp_total_cum");
        }
        if (!cols.contains("ng_total_cum")) {
            jdbcTemplate.execute("ALTER TABLE fact_daily_line_report " +
                    "ADD COLUMN ng_total_cum BIGINT NULL COMMENT 'REAL 相机 ng_count 累计和' AFTER ok_total_cum");
        }
    }

    // ==================== 生成（物化） ====================

    /** 删除某天快照（重跑前调用，幂等覆盖） */
    public int deleteDate(LocalDate date) {
        return jdbcTemplate.update("DELETE FROM fact_daily_line_report WHERE report_date = ?",
                java.sql.Date.valueOf(date));
    }

    /** 批量写入某天快照行（含厂级行 line_id=0） */
    public void insertRows(List<Map<String, Object>> rows, LocalDate date) {
        for (Map<String, Object> row : rows) {
            jdbcTemplate.update(
                    "INSERT INTO fact_daily_line_report(report_date, line_id, line_name, detect_count, defect_count, " +
                            "defect_images, defect_rate_kilo, camera_total, camera_online, temp_alarm_count, " +
                            "top_defect_types, worst_camera_code, worst_camera_name, worst_camera_rate, worst_camera_images, " +
                            "insp_total_cum, ok_total_cum, ng_total_cum, snapshot_version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    java.sql.Date.valueOf(date),
                    intVal(row.get("lineId"), 0),
                    strOrNull(row.get("lineName")),
                    intVal(row.get("detectCount"), 0),
                    intVal(row.get("defectCount"), 0),
                    intVal(row.get("defectImages"), 0),
                    row.get("defectRateKilo"),
                    intVal(row.get("cameraTotal"), 0),
                    intVal(row.get("cameraOnline"), 0),
                    intVal(row.get("tempAlarmCount"), 0),
                    strOrNull(row.get("topDefectTypes")),
                    strOrNull(row.get("worstCameraCode")),
                    strOrNull(row.get("worstCameraName")),
                    row.get("worstCameraRate"),
                    row.get("worstCameraImages") == null ? null : intVal(row.get("worstCameraImages"), 0),
                    longOrNull(row.get("inspTotalCum")),
                    longOrNull(row.get("okTotalCum")),
                    longOrNull(row.get("ngTotalCum")),
                    SNAPSHOT_VERSION);
        }
    }

    // ==================== 查询 ====================

    /** 区间内的厂级日报行（lineId 为空查厂级；传 lineId 查该产线） */
    public List<Map<String, Object>> listRange(LocalDate start, LocalDate end, Integer lineId) {
        StringBuilder sql = new StringBuilder(
                "SELECT report_date AS reportDate, line_id AS lineId, line_name AS lineName, detect_count AS detectCount, " +
                        "defect_count AS defectCount, defect_images AS defectImages, defect_rate_kilo AS defectRateKilo, " +
                        "camera_total AS cameraTotal, camera_online AS cameraOnline, temp_alarm_count AS tempAlarmCount, " +
                        "top_defect_types AS topDefectTypes, worst_camera_code AS worstCameraCode, " +
                        "worst_camera_name AS worstCameraName, worst_camera_rate AS worstCameraRate, " +
                        "worst_camera_images AS worstCameraImages, " +
                        "insp_total_cum AS inspTotalCum, ok_total_cum AS okTotalCum, ng_total_cum AS ngTotalCum, " +
                        "snapshot_version AS snapshotVersion " +
                        "FROM fact_daily_line_report WHERE report_date BETWEEN ? AND ? ");
        List<Object> params = new ArrayList<>();
        params.add(java.sql.Date.valueOf(start));
        params.add(java.sql.Date.valueOf(end));
        if (lineId != null) {
            sql.append(" AND line_id = ? ");
            params.add(lineId);
        }
        sql.append(" ORDER BY report_date DESC, line_id ASC");
        return jdbcTemplate.queryForList(sql.toString(), params.toArray());
    }

    /**
     * 有 REAL 相机在册的产线清单（月历页的产线胶囊 Tab 数据源）。
     *
     * <p>用 dim_camera 而不是 dim_production_line 全量：没挂 REAL 相机的产线
     * 在日报里永远没有数据，列出来只会让用户点进去看到一片空白。
     */
    public List<Map<String, Object>> listRealLines() {
        return jdbcTemplate.queryForList(
                "SELECT l.line_id AS lineId, COALESCE(NULLIF(l.line_name,''), l.line_code) AS lineName " +
                        "FROM dim_production_line l " +
                        " WHERE EXISTS (SELECT 1 FROM dim_camera c WHERE c.line_id = l.line_id " +
                        "   AND c.camera_type = 'REAL' AND IFNULL(c.is_deleted, 0) = 0) " +
                        " ORDER BY l.line_id");
    }

    /**
     * 单日明细维度（缺陷类型 TOP5 / 相机 TOP10 / 24 小时分布）。
     *
     * <p>只从图片事实表算：快照表只固化汇总指标，没存维度明细。因此**历史日超过
     * 31 天归档期后，这里会返回空** —— 调用方必须据此标 {@code detailsAvailable=false}
     * 并说明「明细已归档」，绝不能把空数组当成「当天没有缺陷」。
     *
     * @param lineId null 或 0 表示全厂
     */
    public Map<String, Object> computeBreakdownDetails(LocalDate date, Integer lineId) {
        Timestamp dayStart = Timestamp.valueOf(date.atStartOfDay());
        Timestamp dayEnd = Timestamp.valueOf(date.plusDays(1).atStartOfDay());
        List<Object> params = new ArrayList<>();
        params.add(dayStart);
        params.add(dayEnd);
        String lineWhere = "";
        if (lineId != null && lineId > 0) {
            lineWhere = " AND c.line_id = ? ";
            params.add(lineId);
        }

        Map<String, Object> result = new LinkedHashMap<>();

        long totalBox = 0;
        List<Map<String, Object>> typeRows = jdbcTemplate.queryForList(
                "SELECT b.defect_type AS type, COUNT(1) AS cnt FROM defect_image_box b " +
                        " JOIN defect_image_file f ON f.image_id = b.image_id " + REAL_CAMERA_JOIN +
                        " WHERE f.capture_time >= ? AND f.capture_time < ?" + lineWhere +
                        " GROUP BY b.defect_type ORDER BY cnt DESC LIMIT 5",
                params.toArray());
        for (Map<String, Object> r : typeRows) totalBox += intVal(r.get("cnt"), 0);
        List<Map<String, Object>> types = new ArrayList<>();
        for (Map<String, Object> r : typeRows) {
            long cnt = intVal(r.get("cnt"), 0);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", strOrNull(r.get("type")));
            item.put("count", (int) cnt);
            item.put("share", totalBox > 0 ? round2(cnt * 100.0 / totalBox) : null);
            types.add(item);
        }
        result.put("typeBreakdown", types);

        List<Map<String, Object>> cameraRows = jdbcTemplate.queryForList(
                "SELECT c.camera_code AS cameraCode, c.camera_name AS cameraName, COUNT(1) AS detectCount, " +
                        "COALESCE(SUM(f.defect_count),0) AS defectCount " +
                        "FROM defect_image_file f " + REAL_CAMERA_JOIN +
                        " WHERE f.capture_time >= ? AND f.capture_time < ?" + lineWhere +
                        " GROUP BY c.camera_code, c.camera_name ORDER BY defectCount DESC LIMIT 10",
                params.toArray());
        List<Map<String, Object>> cameras = new ArrayList<>();
        for (Map<String, Object> r : cameraRows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("cameraCode", strOrNull(r.get("cameraCode")));
            item.put("cameraName", strOrNull(r.get("cameraName")));
            item.put("detectCount", intVal(r.get("detectCount"), 0));
            item.put("defectCount", intVal(r.get("defectCount"), 0));
            cameras.add(item);
        }
        result.put("cameraBreakdown", cameras);

        // 24 小时分布（当天只到当前小时，避免右侧一串 0 被当成「夜里停线」）
        Map<Integer, Integer> hourMap = new LinkedHashMap<>();
        jdbcTemplate.queryForList(
                "SELECT HOUR(f.capture_time) AS h, COUNT(1) AS cnt FROM defect_image_file f " +
                        REAL_CAMERA_JOIN +
                        " WHERE f.capture_time >= ? AND f.capture_time < ?" + lineWhere +
                        " GROUP BY h",
                params.toArray())
                .forEach(r -> hourMap.put(intVal(r.get("h"), 0), intVal(r.get("cnt"), 0)));
        boolean today = date.equals(LocalDate.now());
        int lastHour = today ? java.time.LocalTime.now().getHour() : 23;
        List<Map<String, Object>> hours = new ArrayList<>();
        List<Integer> idleHours = new ArrayList<>();
        for (int h = 0; h <= lastHour; h++) {
            int cnt = hourMap.getOrDefault(h, 0);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("hour", h);
            item.put("count", cnt);
            hours.add(item);
            if (cnt == 0) idleHours.add(h);
        }
        result.put("hourly", hours);
        result.put("idleHours", idleHours);

        long detailDetect = 0;
        for (Map<String, Object> c : cameras) detailDetect += intVal(c.get("detectCount"), 0);
        result.put("detailsAvailable", !cameras.isEmpty() || !types.isEmpty());
        result.put("detailDetectCount", (int) detailDetect);
        return result;
    }

    // ==================== 计算（生成与当天实时共用同一套口径） ====================

    /**
     * 计算某天的全部日报行（含厂级行）。
     *
     * <p>行集合 = 「当日有 REAL 检测数据的产线」∪「在册 REAL 相机所属产线」∪ 厂级行 ——
     * 只有相机没有数据的产线也要有一行（相机 0/在线 0、检测 NULL），否则「这条线今天
     * 完全没上报」会被误显示成「这条线不存在」。
     */
    /**
     * 实时计算某天日报行。
     *
     * @param stampCounters 是否拍心跳累计计数快照。仅当调用时点贴近该日期的日界
     *                      （日终任务算昨天 / 实时算今天）才允许 true；
     *                      白天补算历史日期必须传 false，否则累计值属于「现在」
     *                      而不是那个日期的日终，差分必错。
     */
    public List<Map<String, Object>> computeDay(LocalDate date, boolean stampCounters) {
        Timestamp dayStart = Timestamp.valueOf(date.atStartOfDay());
        Timestamp dayEnd = Timestamp.valueOf(date.plusDays(1).atStartOfDay());

        // ① 当日检测量：按产线聚合（分母=图片数、分子=缺陷框数）
        Map<Integer, long[]> detectByLine = new LinkedHashMap<>();
        jdbcTemplate.queryForList(
                "SELECT c.line_id AS lineId, COUNT(1) AS detectCount, COALESCE(SUM(f.defect_count),0) AS defectCount, " +
                        "COALESCE(SUM(CASE WHEN f.defect_count > 0 THEN 1 ELSE 0 END),0) AS defectImages " +
                        "FROM defect_image_file f " + REAL_CAMERA_JOIN +
                        " WHERE f.capture_time >= ? AND f.capture_time < ? GROUP BY c.line_id",
                dayStart, dayEnd)
                .forEach(r -> detectByLine.put(
                        intVal(r.get("lineId"), 0),
                        new long[]{intVal(r.get("detectCount"), 0), intVal(r.get("defectCount"), 0), intVal(r.get("defectImages"), 0)}));

        // ② 在册 REAL 相机数（按产线）——快照口径：相机当天有没有上报都算「在册」
        Map<Integer, Integer> cameraTotalByLine = new LinkedHashMap<>();
        jdbcTemplate.queryForList(
                "SELECT c.line_id AS lineId, COUNT(1) AS cnt FROM dim_camera c " +
                        " WHERE c.camera_type = 'REAL' AND IFNULL(c.is_deleted, 0) = 0 GROUP BY c.line_id")
                .forEach(r -> cameraTotalByLine.put(intVal(r.get("lineId"), 0), intVal(r.get("cnt"), 0)));

        // ③ 当日有上报的 REAL 相机数（按产线）
        Map<Integer, Integer> cameraOnlineByLine = new LinkedHashMap<>();
        jdbcTemplate.queryForList(
                "SELECT c.line_id AS lineId, COUNT(DISTINCT c.camera_id) AS cnt " +
                        "FROM defect_image_file f " + REAL_CAMERA_JOIN +
                        " WHERE f.capture_time >= ? AND f.capture_time < ? GROUP BY c.line_id",
                dayStart, dayEnd)
                .forEach(r -> cameraOnlineByLine.put(intVal(r.get("lineId"), 0), intVal(r.get("cnt"), 0)));

        // ④ 当日温度告警数（按产线；day 内发生过的都算，不看是否已恢复）
        Map<Integer, Integer> tempAlarmByLine = new LinkedHashMap<>();
        jdbcTemplate.queryForList(
                "SELECT IFNULL(line_id,0) AS lineId, COUNT(1) AS cnt FROM fact_anomaly_alert " +
                        " WHERE alert_type = 'TEMPERATURE_HIGH' AND create_time >= ? AND create_time < ? " +
                        " GROUP BY IFNULL(line_id,0)",
                dayStart, dayEnd)
                .forEach(r -> tempAlarmByLine.put(intVal(r.get("lineId"), 0), intVal(r.get("cnt"), 0)));

        // ⑤ 产线名快照
        Map<Integer, String> lineNameById = new LinkedHashMap<>();
        jdbcTemplate.queryForList("SELECT line_id AS lineId, COALESCE(NULLIF(line_name,''), line_code) AS lineName " +
                "FROM dim_production_line")
                .forEach(r -> lineNameById.put(intVal(r.get("lineId"), 0), strOrNull(r.get("lineName"))));

        // ⑤b 心跳累计检测计数快照（REAL 相机求和）。
        //   图片通道只上报缺陷图 → 图片口径缺陷率恒 1000‰（2026-11-17 现场实测）；
        //   真缺陷率只能靠计数日增差分：今天 00:05 固化累计值，明天差分出当天增量。
        //   不过滤 is_active：已删除相机的计数器冻结，排除它反而会让累计和下跌（差分为负）。
        //   stampCounters=false 时整体跳过（补算历史日期的计数真值不可恢复，留 NULL 诚实降级）。
        Map<Integer, long[]> cumByLine = new LinkedHashMap<>();
        if (stampCounters) {
            jdbcTemplate.queryForList(
                    "SELECT c.line_id AS lineId, COALESCE(SUM(s.total_count),0) AS inspTotal, " +
                            "COALESCE(SUM(s.ok_count),0) AS okTotal, COALESCE(SUM(s.ng_count),0) AS ngTotal " +
                            "FROM camera_status s " +
                            " JOIN dim_camera c ON c.camera_code = s.camera_code " +
                            "      AND c.camera_type = 'REAL' AND IFNULL(c.is_deleted, 0) = 0 " +
                            " GROUP BY c.line_id")
                    .forEach(r -> cumByLine.put(intVal(r.get("lineId"), 0),
                            new long[]{intVal(r.get("inspTotal"), 0), intVal(r.get("okTotal"), 0), intVal(r.get("ngTotal"), 0)}));
        }

        // ⑥ 组装产线行（有相机或当日有数据的产线）
        List<Map<String, Object>> rows = new ArrayList<>();
        java.util.TreeSet<Integer> lineIds = new java.util.TreeSet<>();
        lineIds.addAll(cameraTotalByLine.keySet());
        lineIds.addAll(detectByLine.keySet());
        long sumDetect = 0, sumDefect = 0, sumDefectImages = 0, sumCameraTotal = 0, sumCameraOnline = 0, sumTempAlarm = 0;
        long[] sumCum = null;

        for (Integer lineId : lineIds) {
            long[] metrics = detectByLine.getOrDefault(lineId, new long[]{0, 0, 0});
            Map<String, Object> row = baseRow(lineId, lineNameById.get(lineId), metrics,
                    cameraTotalByLine.getOrDefault(lineId, 0), cameraOnlineByLine.getOrDefault(lineId, 0),
                    tempAlarmByLine.getOrDefault(lineId, 0));
            fillScopeDetails(row, date, lineId, metrics);
            putCum(row, cumByLine.get(lineId));
            rows.add(row);

            sumDetect += metrics[0];
            sumDefect += metrics[1];
            sumDefectImages += metrics[2];
            sumCameraTotal += cameraTotalByLine.getOrDefault(lineId, 0);
            sumCameraOnline += cameraOnlineByLine.getOrDefault(lineId, 0);
            sumTempAlarm += tempAlarmByLine.getOrDefault(lineId, 0);
            sumCum = addCum(sumCum, cumByLine.get(lineId));
        }

        // ⑦ 厂级汇总行（line_id=0）——指标取各产线之和，「最差相机」跨全部产线评
        Map<String, Object> plantRow = baseRow(0, "全厂",
                new long[]{sumDetect, sumDefect, sumDefectImages},
                (int) sumCameraTotal, (int) sumCameraOnline, (int) sumTempAlarm);
        fillScopeDetails(plantRow, date, null, new long[]{sumDetect, sumDefect, sumDefectImages});
        putCum(plantRow, sumCum);
        rows.add(plantRow);
        return rows;
    }

    /** 计数累计和聚合缓冲（null 表示该产线没有任何带计数的 REAL 相机） */
    private long[] addCum(long[] acc, long[] add) {
        if (add == null) return acc;
        if (acc == null) return new long[]{add[0], add[1], add[2]};
        return new long[]{acc[0] + add[0], acc[1] + add[1], acc[2] + add[2]};
    }

    private void putCum(Map<String, Object> row, long[] cum) {
        if (cum == null) {
            row.put("inspTotalCum", null);
            row.put("okTotalCum", null);
            row.put("ngTotalCum", null);
        } else {
            row.put("inspTotalCum", cum[0]);
            row.put("okTotalCum", cum[1]);
            row.put("ngTotalCum", cum[2]);
        }
    }

    /** 基础行（指标卡字段 + 缺陷率；无数据显示 NULL 不为 0） */
    private Map<String, Object> baseRow(Integer lineId, String lineName, long[] metrics,
                                        int cameraTotal, int cameraOnline, int tempAlarm) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("lineId", lineId);
        row.put("lineName", lineName);
        row.put("detectCount", (int) metrics[0]);
        row.put("defectCount", (int) metrics[1]);
        row.put("defectImages", (int) metrics[2]);
        row.put("defectRateKilo", metrics[0] > 0
                ? round2(metrics[1] * 1000.0 / metrics[0]) : null);
        row.put("cameraTotal", cameraTotal);
        row.put("cameraOnline", cameraOnline);
        row.put("tempAlarmCount", tempAlarm);
        row.put("snapshotVersion", SNAPSHOT_VERSION);
        return row;
    }

    /** TOP3 缺陷类型 + 最差相机（厂级传 lineId=null 评全厂；样本 ≥ MIN_RANK_SAMPLE 才参与） */
    private void fillScopeDetails(Map<String, Object> row, LocalDate date, Integer lineId, long[] metrics) {
        Timestamp dayStart = Timestamp.valueOf(date.atStartOfDay());
        Timestamp dayEnd = Timestamp.valueOf(date.plusDays(1).atStartOfDay());
        List<Object> params = new ArrayList<>();
        params.add(dayStart);
        params.add(dayEnd);
        String lineWhere = "";
        if (lineId != null) {
            lineWhere = " AND c.line_id = ? ";
            params.add(lineId);
        }

        // TOP3 缺陷类型（按缺陷框数）
        List<Map<String, Object>> topTypes = jdbcTemplate.queryForList(
                "SELECT b.defect_type AS type, COUNT(1) AS cnt FROM defect_image_box b " +
                        " JOIN defect_image_file f ON f.image_id = b.image_id " + REAL_CAMERA_JOIN +
                        " WHERE f.capture_time >= ? AND f.capture_time < ?" + lineWhere +
                        " GROUP BY b.defect_type ORDER BY cnt DESC LIMIT 3",
                params.toArray());
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (Map<String, Object> t : topTypes) {
            if (!first) json.append(',');
            first = false;
            json.append("{\"type\":\"").append(String.valueOf(t.get("type")).replace("\"", "\\\""))
                    .append("\",\"count\":").append(intVal(t.get("cnt"), 0)).append('}');
        }
        json.append(']');
        row.put("topDefectTypes", first ? null : json.toString());

        // 缺陷最多相机：按当日缺陷框数绝对值排名（不用缺陷率——图片通道每张必带缺陷框，
        // 相机级「缺陷率」恒 1000‰ 无区分度；样本下限保留，防个别相机刷量误导）。
        List<Map<String, Object>> worst = jdbcTemplate.queryForList(
                "SELECT c.camera_code AS cameraCode, c.camera_name AS cameraName, COUNT(1) AS detectCount, " +
                        "COALESCE(SUM(f.defect_count),0) AS defectCount " +
                        "FROM defect_image_file f " + REAL_CAMERA_JOIN +
                        " WHERE f.capture_time >= ? AND f.capture_time < ?" + lineWhere +
                        " GROUP BY c.camera_code, c.camera_name " +
                        " HAVING COUNT(1) >= " + MIN_RANK_SAMPLE +
                        " ORDER BY COALESCE(SUM(f.defect_count),0) DESC LIMIT 1",
                params.toArray());
        if (!worst.isEmpty()) {
            Map<String, Object> w = worst.get(0);
            long detect = intVal(w.get("detectCount"), 0);
            long defect = intVal(w.get("defectCount"), 0);
            row.put("worstCameraCode", strOrNull(w.get("cameraCode")));
            row.put("worstCameraName", strOrNull(w.get("cameraName")));
            // 占比口径：该相机缺陷框数占本 scope 当日缺陷框数的百分比（缺陷率恒 1000‰ 无区分度）
            row.put("worstCameraRate", metrics[1] > 0 ? round2(defect * 100.0 / metrics[1]) : null);
            row.put("worstCameraImages", (int) detect);
        } else {
            row.put("worstCameraCode", null);
            row.put("worstCameraName", null);
            row.put("worstCameraRate", null);
            row.put("worstCameraImages", null);
        }
    }

    // ==================== 通用 ====================

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static int intVal(Object value, int fallback) {
        if (value == null) return fallback;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Long longOrNull(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String strOrNull(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }
}
