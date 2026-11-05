package com.wms.analytics.infra.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 日聚合任务 Repository（DDD P3 批次④ 收敛 · 聚合域）。
 *
 * <p>承接 {@code LineAnalyticsController} 中三张表的 SQL：
 * {@code analytics_aggregation_schedule}（定时配置）、
 * {@code analytics_aggregation_job}（任务记录）、
 * {@code agg_quality_day} / {@code agg_defect_type_day}（日聚合结果重建）。
 *
 * <p>SQL 均自原 Controller <b>原样搬入</b>，行为不变。
 *
 * <p><b>建表策略（原样保留，不可回退）</b>：
 * {@code ensure*} 用 INFORMATION_SCHEMA 判断表是否真实存在，
 * <b>只在表缺失时才执行 DDL</b> —— 2026-10-31 实测发现原实现每分钟跑 4 条 DDL，
 * 单次最慢 7.4 秒（MDL 锁等待），改为「表在就不 DDL」后消除。
 * 判断依据必须是 INFORMATION_SCHEMA，**不能用进程内 AtomicBoolean**
 * （标志位只反映「本进程建过」，表被人为删掉后就永远不会再建）。
 */
@Repository
public class AnalyticsAggregationRepository {

    private final JdbcTemplate jdbcTemplate;

    public AnalyticsAggregationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ==================== 表存在性判断 ====================

    /** 判断表是否真实存在（查 INFORMATION_SCHEMA，不执行任何 DDL） */
    public boolean tableExists(String tableName) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                    Integer.class, tableName);
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 补列（失败静默，由后续 SQL 暴露真实错误） */
    public void addColumnQuiet(String tableName, String columnName, String ddl) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class, tableName, columnName);
            if (count == null || count == 0) {
                jdbcTemplate.execute(ddl);
            }
        } catch (Exception ignored) {
            // 兼容老库字段补齐，失败时由后续 SQL 暴露真实错误。
        }
    }

    // ==================== 定时配置表 ====================

    /** 确保定时配置表存在（表在则不执行 DDL） */
    public void ensureAggregationScheduleTable() {
        if (tableExists("analytics_aggregation_schedule")) {
            return;
        }
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS analytics_aggregation_schedule (" +
                "config_id BIGINT NOT NULL PRIMARY KEY," +
                "enabled TINYINT NOT NULL DEFAULT 1," +
                "schedule_time TIME NOT NULL DEFAULT '02:00:00'," +
                "lookback_days INT NOT NULL DEFAULT 7," +
                "factory_id BIGINT NULL," +
                "line_id BIGINT NULL," +
                "last_run_date DATE NULL," +
                "last_job_id BIGINT NULL," +
                "create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                "update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产线统计日聚合定时配置表'");
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM analytics_aggregation_schedule WHERE config_id=1", Integer.class);
        if (count == null || count == 0) {
            jdbcTemplate.update("INSERT INTO analytics_aggregation_schedule(config_id, enabled, schedule_time, lookback_days) VALUES (1,1,'02:00:00',7)");
        }
    }

    /** 读取定时配置 */
    public Map<String, Object> findAggregationSchedule() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT config_id AS configId, enabled, TIME_FORMAT(schedule_time,'%H:%i') AS scheduleTime, lookback_days AS lookbackDays, " +
                        "factory_id AS factoryId, line_id AS lineId, last_run_date AS lastRunDate, last_job_id AS lastJobId, create_time AS createTime, update_time AS updateTime " +
                        "FROM analytics_aggregation_schedule WHERE config_id=1");
        return rows.isEmpty() ? new LinkedHashMap<>() : rows.get(0);
    }

    /** 更新定时配置 */
    public int updateAggregationSchedule(int enabled, String scheduleTime, int lookbackDays,
                                         Long factoryId, Long lineId) {
        return jdbcTemplate.update(
                "UPDATE analytics_aggregation_schedule SET enabled=?, schedule_time=?, lookback_days=?, factory_id=?, line_id=?, update_time=NOW() WHERE config_id=1",
                enabled, scheduleTime, lookbackDays, factoryId, lineId);
    }

    /** 记录定时任务的最近一次执行 */
    public int updateLastRun(LocalDate today, Long jobId) {
        return jdbcTemplate.update("UPDATE analytics_aggregation_schedule SET last_run_date=?, last_job_id=?, update_time=NOW() WHERE config_id=1",
                Date.valueOf(today), jobId);
    }

    // ==================== 日聚合表 ====================

    /** 确保两张日聚合表存在（表在则不执行 DDL） */
    public void ensureDailyAggregationTables() {
        if (tableExists("agg_quality_day") && tableExists("agg_defect_type_day")) {
            return;
        }
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS agg_quality_day (" +
                "agg_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY," +
                "date_bucket DATE NOT NULL," +
                "factory_id INT NOT NULL," +
                "line_id INT NOT NULL," +
                "workstation_id INT NOT NULL," +
                "camera_id BIGINT NOT NULL," +
                "product_id BIGINT NOT NULL," +
                "shift_id BIGINT NOT NULL DEFAULT 0," +
                "total_count BIGINT NOT NULL DEFAULT 0," +
                "ok_count BIGINT NOT NULL DEFAULT 0," +
                "ng_count BIGINT NOT NULL DEFAULT 0," +
                "defect_count BIGINT NOT NULL DEFAULT 0," +
                "yield_rate DECIMAL(10,6) NULL," +
                "ng_rate DECIMAL(10,6) NULL," +
                "update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                "UNIQUE KEY uk_quality_day_scope(date_bucket,factory_id,line_id,workstation_id,camera_id,product_id,shift_id)," +
                "KEY idx_quality_day_factory(date_bucket,factory_id)," +
                "KEY idx_quality_day_line(date_bucket,line_id)," +
                "KEY idx_quality_day_product(date_bucket,product_id)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='质量日聚合表'");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS agg_defect_type_day (" +
                "agg_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY," +
                "date_bucket DATE NOT NULL," +
                "factory_id INT NOT NULL," +
                "line_id INT NOT NULL," +
                "workstation_id INT NOT NULL," +
                "camera_id BIGINT NOT NULL," +
                "product_id BIGINT NOT NULL," +
                "shift_id BIGINT NOT NULL DEFAULT 0," +
                "defect_type_id BIGINT NOT NULL," +
                "defect_code VARCHAR(50) NOT NULL," +
                "defect_name VARCHAR(100) NOT NULL," +
                "position_id BIGINT NULL," +
                "position_code VARCHAR(50) NULL," +
                "severity VARCHAR(20) NULL," +
                "defect_count BIGINT NOT NULL DEFAULT 0," +
                "update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                "UNIQUE KEY uk_defect_day_scope(date_bucket,factory_id,line_id,workstation_id,camera_id,product_id,shift_id,defect_type_id)," +
                "KEY idx_defect_day_type(date_bucket,defect_type_id)," +
                "KEY idx_defect_day_line(date_bucket,line_id)," +
                "KEY idx_defect_day_product(date_bucket,product_id)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='缺陷类型日聚合表'");
    }

    // ==================== 聚合任务表 ====================

    /** 确保任务表存在（表在则不执行 DDL） */
    public void ensureAggregationJobTable() {
        if (tableExists("analytics_aggregation_job")) {
            return;
        }
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS analytics_aggregation_job (" +
                "job_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY," +
                "job_type VARCHAR(50) NOT NULL," +
                "status VARCHAR(20) NOT NULL," +
                "start_date DATE NULL," +
                "end_date DATE NULL," +
                "factory_id BIGINT NULL," +
                "line_id BIGINT NULL," +
                "quality_rows INT NOT NULL DEFAULT 0," +
                "defect_rows INT NOT NULL DEFAULT 0," +
                "message VARCHAR(500) NULL," +
                "error_detail TEXT NULL," +
                "trigger_source VARCHAR(30) NOT NULL DEFAULT 'MANUAL'," +
                "retry_from_job_id BIGINT NULL," +
                "create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                "start_time DATETIME NULL," +
                "finish_time DATETIME NULL," +
                "KEY idx_job_create_time(create_time)," +
                "KEY idx_job_status(status)," +
                "KEY idx_job_trigger_source(trigger_source)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产线统计聚合任务表'");
        addColumnQuiet("analytics_aggregation_job", "error_detail", "ALTER TABLE analytics_aggregation_job ADD COLUMN error_detail TEXT NULL AFTER message");
        addColumnQuiet("analytics_aggregation_job", "trigger_source", "ALTER TABLE analytics_aggregation_job ADD COLUMN trigger_source VARCHAR(30) NOT NULL DEFAULT 'MANUAL' AFTER error_detail");
        addColumnQuiet("analytics_aggregation_job", "retry_from_job_id", "ALTER TABLE analytics_aggregation_job ADD COLUMN retry_from_job_id BIGINT NULL AFTER trigger_source");
    }

    /** 创建聚合任务，返回任务 ID */
    public Long createAggregationJob(LocalDate startDate, LocalDate endDate, Long factoryId, Long lineId,
                                     String triggerSource, Long retryFromJobId) {
        jdbcTemplate.update(
                "INSERT INTO analytics_aggregation_job(job_type,status,start_date,end_date,factory_id,line_id,message,trigger_source,retry_from_job_id,start_time) " +
                        "VALUES ('DAILY_AGGREGATION','RUNNING',?,?,?,?,?,?,?,NOW())",
                Date.valueOf(startDate), Date.valueOf(endDate), factoryId, lineId, "日聚合任务已提交", triggerSource, retryFromJobId);
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /** 更新任务状态 */
    public int updateAggregationJob(Long jobId, String status, int qualityRows, int defectRows,
                                    String message, String errorDetail) {
        return jdbcTemplate.update("UPDATE analytics_aggregation_job SET status=?, quality_rows=?, defect_rows=?, message=?, error_detail=?, finish_time=NOW() WHERE job_id=?",
                status, qualityRows, defectRows, clip(message, 500), clip(errorDetail, 4000), jobId);
    }

    /** 按 ID 查任务（无结果返回空 Map） */
    public Map<String, Object> findAggregationJob(Long jobId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT job_id AS jobId, job_type AS jobType, status, start_date AS startDate, end_date AS endDate, factory_id AS factoryId, line_id AS lineId, " +
                        "quality_rows AS qualityRows, defect_rows AS defectRows, message, error_detail AS errorDetail, trigger_source AS triggerSource, retry_from_job_id AS retryFromJobId, create_time AS createTime, start_time AS startTime, finish_time AS finishTime " +
                        "FROM analytics_aggregation_job WHERE job_id=?", jobId);
        return rows.isEmpty() ? new LinkedHashMap<>() : rows.get(0);
    }

    /** 任务分页列表 */
    public Map<String, Object> queryAggregationJobs(String status, int page, int size) {
        List<Object> params = new ArrayList<>();
        String where = "";
        if (status != null && !status.trim().isEmpty()) {
            where = " WHERE status = ? ";
            params.add(status.trim().toUpperCase(java.util.Locale.ROOT));
        }
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM analytics_aggregation_job" + where, params.toArray(), Long.class);
        List<Object> listParams = new ArrayList<>(params);
        listParams.add((page - 1) * size);
        listParams.add(size);
        List<Map<String, Object>> records = jdbcTemplate.queryForList(
                "SELECT job_id AS jobId, job_type AS jobType, status, start_date AS startDate, end_date AS endDate, factory_id AS factoryId, line_id AS lineId, " +
                        "quality_rows AS qualityRows, defect_rows AS defectRows, message, error_detail AS errorDetail, trigger_source AS triggerSource, retry_from_job_id AS retryFromJobId, " +
                        "create_time AS createTime, start_time AS startTime, finish_time AS finishTime " +
                        "FROM analytics_aggregation_job " + where + " ORDER BY job_id DESC LIMIT ?, ?",
                listParams.toArray());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", records);
        data.put("total", total == null ? 0 : total);
        data.put("page", page);
        data.put("pageSize", size);
        return data;
    }

    /** 最近 20 条任务 */
    public List<Map<String, Object>> queryRecentAggregationJobs() {
        return jdbcTemplate.queryForList(
                "SELECT job_id AS jobId, job_type AS jobType, status, start_date AS startDate, end_date AS endDate, factory_id AS factoryId, line_id AS lineId, " +
                        "quality_rows AS qualityRows, defect_rows AS defectRows, message, error_detail AS errorDetail, trigger_source AS triggerSource, retry_from_job_id AS retryFromJobId, create_time AS createTime, start_time AS startTime, finish_time AS finishTime " +
                        "FROM analytics_aggregation_job ORDER BY job_id DESC LIMIT 20");
    }

    // ==================== 日聚合重建 ====================

    /** 重建质量日聚合（先删后插） */
    public int rebuildQualityDaily(String deleteWhere, List<Object> deleteParams,
                                   String sourceWhere, List<Object> insertParams) {
        jdbcTemplate.update("DELETE FROM agg_quality_day " + deleteWhere, deleteParams.toArray());
        return jdbcTemplate.update(
                "INSERT INTO agg_quality_day(date_bucket,factory_id,line_id,workstation_id,camera_id,product_id,shift_id,total_count,ok_count,ng_count,defect_count,yield_rate,ng_rate) " +
                        "SELECT DATE(time_bucket), factory_id, line_id, workstation_id, camera_id, product_id, shift_id, " +
                        "SUM(total_count), SUM(ok_count), SUM(ng_count), SUM(defect_count), " +
                        "IF(SUM(total_count)>0, SUM(ok_count)/SUM(total_count), 0), IF(SUM(total_count)>0, SUM(ng_count)/SUM(total_count), 0) " +
                        "FROM agg_quality_30m " + sourceWhere +
                        " GROUP BY DATE(time_bucket), factory_id, line_id, workstation_id, camera_id, product_id, shift_id",
                insertParams.toArray());
    }

    /** 重建缺陷类型日聚合（先删后插） */
    public int rebuildDefectDaily(String deleteWhere, List<Object> deleteParams,
                                  String sourceWhere, List<Object> insertParams) {
        jdbcTemplate.update("DELETE FROM agg_defect_type_day " + deleteWhere, deleteParams.toArray());
        return jdbcTemplate.update(
                "INSERT INTO agg_defect_type_day(date_bucket,factory_id,line_id,workstation_id,camera_id,product_id,shift_id,defect_type_id,defect_code,defect_name,position_id,position_code,severity,defect_count) " +
                        "SELECT DATE(time_bucket), factory_id, line_id, workstation_id, camera_id, product_id, shift_id, defect_type_id, defect_code, defect_name, position_id, position_code, severity, SUM(defect_count) " +
                        "FROM agg_defect_type_30m " + sourceWhere +
                        " GROUP BY DATE(time_bucket), factory_id, line_id, workstation_id, camera_id, product_id, shift_id, defect_type_id, defect_code, defect_name, position_id, position_code, severity",
                insertParams.toArray());
    }

    // ==================== 通用 ====================

    /** 截断字符串（与原 Controller 私有方法同语义） */
    private String clip(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
