package com.wms.defect.infra.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 缺陷图片月度归档仓储（2026-11-05 新增，配合 DefectImageArchiveTask）。
 *
 * <p><b>数据布局</b>：</p>
 * <ul>
 *   <li>主表 {@code defect_image_file/_box/_batch} 只保留近
 *       {@code app.archive.images.hot-days}（默认 31）天 —— 页面默认查询恒定毫秒级；</li>
 *   <li>更早数据按月移入 {@code defect_image_file_hist_YYYYMM} 等同构月表
 *       （{@code CREATE TABLE … LIKE} 复制全部索引），磁盘图片仍保留至
 *       {@code retain-days}（默认 730 天 = 2 年）；</li>
 *   <li>月表按 image_id / batch_id 主键搬移，<b>INSERT…SELECT 分批</b>（每批由调用方控制），
 *       不采用 {@code fact_production_30min_archive} 的逐行 Java INSERT 写法 ——
 *       图片表是 10 万行/天量级，逐行会在归档窗口拖死数据库。</li>
 * </ul>
 *
 * <p><b>表名安全</b>：所有动态表名都必须通过 {@link #SAFE_TABLE} 白名单校验
 * （{@code defect_image_(file|box|batch)_hist_\d{6}}），杜绝拼接注入与误 DROP。</p>
 */
@Repository
public class DefectImageArchiveRepository {

    /** 动态表名白名单（file/box/batch + 6 位年月） */
    private static final Pattern SAFE_TABLE =
            Pattern.compile("^defect_image_(file|box|batch)_hist_\\d{6}$");

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    /** 显式列清单 —— 不用 SELECT *，防主表后续补列时与月表列序错位 */
    public static final String FILE_COLS =
            "image_id, batch_id, camera_id, camera_name, file_name, original_file_name, "
                    + "image_path, image_url, capture_time, upload_time, file_size, width, height, "
                    + "defect_count, high_count, medium_count, low_count, create_time";

    public static final String BOX_COLS =
            "box_id, image_id, batch_id, defect_type, severity, severity_level, "
                    + "x, y, width, height, area, create_time";

    public static final String BATCH_COLS =
            "batch_id, batch_no, camera_id, camera_name, device_id, upload_time, "
                    + "declared_image_count, image_count, success_count, fail_count, "
                    + "defect_count, high_count, medium_count, low_count, "
                    + "upload_status, error_summary, metadata_json, create_time";

    /** 归档判定用的时间表达式（与主表查询口径一致：拍摄 → 上传 → 入库依次兜底） */
    public static final String TS_EXPR = "COALESCE(capture_time, upload_time, create_time)";

    private final JdbcTemplate jdbcTemplate;

    public DefectImageArchiveRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ==================== 表名与建表 ====================

    /** 月表名（kind = file / box / batch），非法入参直接抛错 */
    public static String monthTable(String kind, String month) {
        String table = "defect_image_" + kind + "_hist_" + month;
        if (!SAFE_TABLE.matcher(table).matches()) {
            throw new IllegalArgumentException("非法归档表名: " + table);
        }
        return table;
    }

    /** 确保某月三张同构表存在（CREATE TABLE … LIKE 复制结构与索引，幂等） */
    public void ensureMonthTables(String month) {
        for (String kind : new String[]{"file", "box", "batch"}) {
            String target = monthTable(kind, month);
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + target
                    + " LIKE defect_image_" + kind);
        }
    }

    // ==================== 待归档数据 ====================

    /**
     * 取一批待归档图片（按 hotDays 之前的 {@code TS_EXPR} 判定），返回
     * {@code image_id} 与归属月份 {@code yyyyMM}。
     */
    public List<Map<String, Object>> selectExpiredImages(int limit, int hotDays) {
        String sql = "SELECT image_id, DATE_FORMAT(" + TS_EXPR + ", '%Y%m') AS month_id, "
                + TS_EXPR + " AS ts FROM defect_image_file "
                + "WHERE " + TS_EXPR + " < NOW() - INTERVAL ? DAY "
                + "ORDER BY image_id LIMIT ?";
        return jdbcTemplate.query(sql, new Object[]{hotDays, limit}, (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("imageId", rs.getLong("image_id"));
            row.put("month", rs.getString("month_id"));
            return row;
        });
    }

    /** 取一批待归档批次（无子图、上传时间早于阈值） */
    public List<Map<String, Object>> selectExpiredBatches(int limit, int hotDays) {
        String sql = "SELECT b.batch_id, DATE_FORMAT(b.upload_time, '%Y%m') AS month_id "
                + "FROM defect_image_batch b "
                + "WHERE b.upload_time < NOW() - INTERVAL ? DAY "
                + "AND NOT EXISTS (SELECT 1 FROM defect_image_file f WHERE f.batch_id = b.batch_id) "
                + "ORDER BY b.batch_id LIMIT ?";
        return jdbcTemplate.query(sql, new Object[]{hotDays, limit}, (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("batchId", rs.getLong("batch_id"));
            row.put("month", rs.getString("month_id"));
            return row;
        });
    }

    // ==================== 搬移（INSERT…SELECT + DELETE，调用方按批事务） ====================

    /** 本批涉及的缺陷框 → 月表 */
    public int moveBoxRows(String month, List<Long> imageIds) {
        if (imageIds.isEmpty()) {
            return 0;
        }
        return jdbcTemplate.update(
                "INSERT INTO " + monthTable("box", month) + " (" + BOX_COLS + ") "
                        + "SELECT " + BOX_COLS + " FROM defect_image_box WHERE image_id IN (" + placeholders(imageIds) + ")",
                imageIds.toArray());
    }

    /** 本批图片 → 月表 */
    public int moveFileRows(String month, List<Long> imageIds) {
        if (imageIds.isEmpty()) {
            return 0;
        }
        return jdbcTemplate.update(
                "INSERT INTO " + monthTable("file", month) + " (" + FILE_COLS + ") "
                        + "SELECT " + FILE_COLS + " FROM defect_image_file WHERE image_id IN (" + placeholders(imageIds) + ")",
                imageIds.toArray());
    }

    /** 本批批次 → 月表 */
    public int moveBatchRows(String month, List<Long> batchIds) {
        if (batchIds.isEmpty()) {
            return 0;
        }
        return jdbcTemplate.update(
                "INSERT INTO " + monthTable("batch", month) + " (" + BATCH_COLS + ") "
                        + "SELECT " + BATCH_COLS + " FROM defect_image_batch WHERE batch_id IN (" + placeholders(batchIds) + ")",
                batchIds.toArray());
    }

    /** 删除主表中已搬移的缺陷框（必须在 moveBoxRows 之后） */
    public int deleteBoxRows(List<Long> imageIds) {
        if (imageIds.isEmpty()) {
            return 0;
        }
        return jdbcTemplate.update(
                "DELETE FROM defect_image_box WHERE image_id IN (" + placeholders(imageIds) + ")",
                imageIds.toArray());
    }

    /** 删除主表中已搬移的图片 */
    public int deleteFileRows(List<Long> imageIds) {
        if (imageIds.isEmpty()) {
            return 0;
        }
        return jdbcTemplate.update(
                "DELETE FROM defect_image_file WHERE image_id IN (" + placeholders(imageIds) + ")",
                imageIds.toArray());
    }

    /** 删除主表中已搬移的批次 */
    public int deleteBatchRows(List<Long> batchIds) {
        if (batchIds.isEmpty()) {
            return 0;
        }
        return jdbcTemplate.update(
                "DELETE FROM defect_image_batch WHERE batch_id IN (" + placeholders(batchIds) + ")",
                batchIds.toArray());
    }

    // ==================== 状态查询 ====================

    /** 主表当前行数（健康度日志用） */
    public long countHotFiles() {
        Long n = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM defect_image_file", Long.class);
        return n == null ? 0L : n;
    }

    /** 主表最早时间（归档滞后告警用），无数据返回 null */
    public String oldestHotTime() {
        List<String> rows = jdbcTemplate.query(
                "SELECT DATE_FORMAT(MIN(" + TS_EXPR + "), '%Y-%m-%d %H:%i:%s') FROM defect_image_file",
                (rs, i) -> rs.getString(1));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 已存在的月表月份（kind = file / box / batch），升序 */
    public List<String> listExistingMonths(String kind) {
        String prefix = "defect_image_" + kind + "_hist_";
        return jdbcTemplate.query(
                "SELECT TABLE_NAME FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME LIKE ? ORDER BY TABLE_NAME",
                new Object[]{prefix + "%"},
                (rs, i) -> rs.getString(1).substring(prefix.length()));
    }

    /** 早于保留期的月表（用于 DROP），返回完整表名 */
    public List<String> listExpiredMonthTables(String kind, int retainDays) {
        String cutoffMonth = monthOf(LocalDate.now().minusDays(retainDays));
        List<String> result = new ArrayList<>();
        for (String month : listExistingMonths(kind)) {
            if (month.compareTo(cutoffMonth) < 0) {
                result.add(monthTable(kind, month));
            }
        }
        return result;
    }

    /** DROP 月表（白名单校验后执行） */
    public void dropMonthTable(String table) {
        if (!SAFE_TABLE.matcher(table).matches()) {
            throw new IllegalArgumentException("拒绝 DROP 非归档表: " + table);
        }
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + table);
    }

    // ==================== 工具 ====================

    public static String monthOf(LocalDate date) {
        return date.format(MONTH_FMT);
    }

    public static String monthOf(LocalDateTime time) {
        return time.toLocalDate().format(MONTH_FMT);
    }

    /** IN 占位符（调用方保证非空） */
    private static String placeholders(List<?> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            sb.append(i == 0 ? "?" : ",?");
        }
        return sb.toString();
    }

    /** 无数据的月份列表（避免调用方 NPE） */
    public List<String> safeMonths(List<String> months) {
        return months == null ? Collections.emptyList() : months;
    }
}
