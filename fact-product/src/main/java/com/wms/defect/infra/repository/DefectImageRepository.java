package com.wms.defect.infra.repository;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import com.wms.defect.app.ArchiveMonthRange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 缺陷图片仓储（DDD P3 批次⑤ 收敛）。
 *
 * <p>承载 {@code defect_image_batch} / {@code defect_image_file} /
 * {@code defect_image_box} 三张表的直接 SQL。
 *
 * <p><b>设计取舍</b>：本类的 SQL 拼装与 RowMapper 与查询逻辑<b>强耦合</b>
 * （同一段 SQL 既要拼 WHERE、又要映射结果集），拆开会让 SQL 散落两处，
 * 反而更难维护。因此这里把「拼 SQL + 映射 + 执行」作为一个整体搬入，
 * Service 侧只保留业务编排（文件落盘、参数校验、批次状态流转）。
 *
 * <p><b>必须原样保留的三处性能修复</b>（2026-11-04 服务器实测，改动会退化到 3 秒慢 SQL）：
 * <ul>
 *   <li>{@code cameraStatusJoinSql()} —— 直接按 {@code camera_code = f.camera_id} 关联，
 *       不再用相关子查询（扫描行数 302 万 → 7.4 万，耗时降低 22 倍）；</li>
 *   <li>{@code listCameraSummaries} —— 两段式聚合，JOIN 只对 10 行汇总做；</li>
 *   <li>{@code listRecentForSummaries} —— 先窗口函数编号再 JOIN，
 *       外层只对 50 行做 JOIN（快 47%）。</li>
 * </ul>
 */
@Repository
public class DefectImageRepository {

    private static final Logger log = LoggerFactory.getLogger(DefectImageRepository.class);

    private final JdbcTemplate jdbcTemplate;

    /** 主表热数据天数（与 DefectImageArchiveTask 共用同一配置，保证路由边界与归档一致） */
    @Value("${app.archive.images.hot-days:31}")
    private int hotDays;

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    public DefectImageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ==================== SQL 片段 ====================

    /** 图片查询的 SELECT 列 */
    private String imageSelectSql() {
        return "SELECT f.image_id, f.batch_id, f.camera_id, f.camera_name, f.file_name, f.original_file_name, " +
                "f.image_path, f.image_url, f.capture_time, f.upload_time, f.create_time, f.file_size, f.width, f.height, " +
                "f.defect_count, f.high_count, f.medium_count, f.low_count, " +
                "cs.line_id, cs.workstation_id, cs.camera_code, ws.station_name, ws.station_code, " +
                "pl.line_name, pl.line_code, pl.factory_id ";
    }

    /**
     * 相机状态表的关联子句。
     *
     * <p><b>2026-11-04 性能修复</b>：原实现是<b>相关子查询</b>：
     * <pre>
     * LEFT JOIN camera_status cs ON cs.id = (
     *   SELECT cs2.id FROM camera_status cs2
     *   WHERE (cs2.camera_code = f.camera_id OR cs2.camera_name = f.camera_name)
     *   ORDER BY CASE WHEN cs2.camera_code = f.camera_id THEN 0 ELSE 1 END, cs2.id DESC LIMIT 1)
     * </pre>
     * MySQL 8 会把这种写法优化成 <b>DEPENDENT SUBQUERY</b>：
     * 对 {@code defect_image_file} 的<b>每一行</b>都重新执行一次
     * {@code camera_status} 的全表扫描 + 临时表 + filesort。
     *
     * <p>实测（服务器 2026-11-04，{@code defect_image_file} 7.4 万行）：
     * <pre>
     * # Rows_examined: 3020590   Query_time: 3.517   ← 现状
     * # Query_time: 0.156                                ← 修复后
     * </pre>
     * 扫描行数从 302 万降到 7.4 万（就是表本身行数），耗时降低 <b>22 倍</b>。
     * 慢查询日志里每分钟一次的 3 秒慢 SQL 就是这条。
     *
     * <p><b>最终方案</b>：直接按 {@code camera_code = f.camera_id} 关联。
     *
     * <p>依据（服务器 2026-11-04 实测）：
     * <ul>
     *   <li>{@code defect_image_file.camera_id} 存的是<b>设备编号</b>，
     *       与 {@code camera_status.camera_code} 一一对应
     *       （10/10 组匹配，0 组落空）；</li>
     *   <li>{@code camera_status.camera_code} 上有唯一索引
     *       {@code uk_camera_code} → JOIN 走 {@code eq_ref}，单次索引查找；</li>
     *   <li>原 {@code OR cs2.camera_name = f.camera_name} 分支实测
     *       <b>0 条命中</b>（图片里的 camera_name 是中文工位名，
     *       与 camera_status.camera_name 的「CV5-Cam1」式命名完全不相交），
     *       属于历史兼容残留，可以安全移除。</li>
     * </ul>
     *
     * <p>语义等价性已用服务器实跑 diff 验证：修复前后按相机分组的
     * {@code cs_id / line_id / workstation_id / image_count / defect_count}
     * <b>完全一致</b>。
     */
    private String cameraStatusJoinSql() {
        return "LEFT JOIN camera_status cs ON cs.camera_code = f.camera_id ";
    }

    /**
     * 追加图片筛选条件（<b>行级</b>部分）。
     *
     * <p>只包含可以直接作用在 {@code defect_image_file f} 上的条件。
     * 需要 JOIN 别名（{@code cs} / {@code ws} / {@code pl}）的条件
     * （工厂、产线、工位、以及 keyword 里的维度字段）由
     * {@link #appendDimensionFilters} 单独追加。
     *
     * <p><b>2026-11-04 拆分原因</b>：{@code listCameraSummaries} 改成两段式聚合后，
     * 聚合子查询里<b>没有</b> {@code cs/ws/pl} 这三个别名，
     * 若把维度过滤写在内层会直接报「Unknown column」。
     */
    private void appendImageFilters(StringBuilder sql, List<Object> params,
                                    String cameraId, String cameraName,
                                    Integer factoryId, Integer lineId, Integer stationId,
                                    String keyword, String severity, String defectType,
                                    LocalDateTime startTime, LocalDateTime endTime,
                                    String boxSource) {
        if (hasText(cameraId)) {
            sql.append("AND f.camera_id = ? ");
            params.add(cameraId);
        }
        if (hasText(cameraName)) {
            sql.append("AND f.camera_name = ? ");
            params.add(cameraName);
        }
        appendDimensionFilters(sql, params, factoryId, lineId, stationId, keyword);
        if (hasText(severity)) {
            // 2026-11-05：boxSource 由查询路由提供（历史范围时指向主表+月表的并集）
            sql.append("AND EXISTS (SELECT 1 FROM ").append(boxSource)
                    .append(" bs WHERE bs.image_id = f.image_id AND UPPER(bs.severity) = ?) ");
            params.add(severity.trim().toUpperCase(Locale.ROOT));
        }
        if (hasText(defectType)) {
            sql.append("AND EXISTS (SELECT 1 FROM ").append(boxSource)
                    .append(" bt WHERE bt.image_id = f.image_id AND bt.defect_type LIKE ?) ");
            params.add("%" + defectType.trim() + "%");
        }
        if (startTime != null) {
            sql.append("AND COALESCE(f.capture_time, f.upload_time, f.create_time) >= ? ");
            params.add(Timestamp.valueOf(startTime));
        }
        if (endTime != null) {
            sql.append("AND COALESCE(f.capture_time, f.upload_time, f.create_time) <= ? ");
            params.add(Timestamp.valueOf(endTime));
        }
    }

    /**
     * 追加维度筛选条件（需要 {@code cs / ws / pl} 别名）。
     *
     * <p>用于<b>单段查询</b>（JOIN 之后过滤）。
     */
    private void appendDimensionFilters(StringBuilder sql, List<Object> params,
                                        Integer factoryId, Integer lineId, Integer stationId,
                                        String keyword) {
        if (factoryId != null) {
            sql.append("AND pl.factory_id = ? ");
            params.add(factoryId);
        }
        if (lineId != null) {
            sql.append("AND cs.line_id = ? ");
            params.add(lineId);
        }
        if (stationId != null) {
            sql.append("AND cs.workstation_id = ? ");
            params.add(stationId);
        }
        appendKeywordFilter(sql, params, keyword);
    }

    /**
     * 追加 keyword 模糊搜索条件。
     *
     * <p>拆成两个版本：
     * <ul>
     *   <li>{@link #appendKeywordFilter} —— 需要 {@code cs/ws/pl} 别名的完整版；</li>
     *   <li>{@link #appendKeywordFilterRowLevel} —— 只用 {@code f} 表字段的精简版，
     *       用于两段式聚合的<b>内层</b>子查询（那里没有维度别名）。</li>
     * </ul>
     */
    private void appendKeywordFilter(StringBuilder sql, List<Object> params, String keyword) {
        if (!hasText(keyword)) {
            return;
        }
        String like = "%" + keyword.trim() + "%";
        sql.append("AND (f.camera_id LIKE ? OR f.camera_name LIKE ? OR f.file_name LIKE ? OR f.original_file_name LIKE ? ")
                .append("OR cs.camera_code LIKE ? OR ws.station_name LIKE ? OR ws.station_code LIKE ? OR pl.line_name LIKE ? ")
                .append("OR EXISTS (SELECT 1 FROM defect_image_box bkw WHERE bkw.image_id = f.image_id AND bkw.defect_type LIKE ?)) ");
        params.add(like); params.add(like); params.add(like); params.add(like);
        params.add(like); params.add(like); params.add(like); params.add(like); params.add(like);
    }

    /** keyword 的行级版本（只查 {@code f} 表字段 + 缺陷框）。 */
    private void appendKeywordFilterRowLevel(StringBuilder sql, List<Object> params, String keyword) {
        if (!hasText(keyword)) {
            return;
        }
        String like = "%" + keyword.trim() + "%";
        sql.append("AND (f.camera_id LIKE ? OR f.camera_name LIKE ? OR f.file_name LIKE ? OR f.original_file_name LIKE ? ")
                .append("OR EXISTS (SELECT 1 FROM defect_image_box bkw WHERE bkw.image_id = f.image_id AND bkw.defect_type LIKE ?)) ");
        params.add(like); params.add(like); params.add(like); params.add(like);
        params.add(like);
    }

    // ==================== 查询 ====================

    /**
     * 最近图片列表。
     *
     * <p>★ 注意：原实现的 RowMapper <b>不含</b> {@code defects} 字段 ——
     * 缺陷框是查完列表后<b>批量回填</b>的（避免逐图查询的 N+1）。
     * 本方法保持一致，并在内部完成批量回填。
     *
     * @param stationLabel 形参 {@code (stationCode, stationName) -> label}，
     *                      用于生成 {@code stationLabel} 字段（展示逻辑留在 Service）
     */
    public List<Map<String, Object>> listRecentImages(Integer limit, String cameraId, String cameraName,
                                                      Integer factoryId, Integer lineId, Integer stationId,
                                                      String keyword, String severity, String defectType,
                                                      LocalDateTime startTime, LocalDateTime endTime,
                                                      java.util.function.BiFunction<String, String, String> stationLabel) {
        int safeLimit = limit == null ? 1000 : Math.max(1, Math.min(limit, 5000));
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT f.image_id, f.batch_id, f.camera_id, f.camera_name, f.file_name, f.original_file_name, ")
                .append("f.image_path, f.image_url, f.capture_time, f.upload_time, f.create_time, f.file_size, f.width, f.height, ")
                .append("f.defect_count, f.high_count, f.medium_count, f.low_count, ")
                .append("cs.line_id, cs.workstation_id, cs.camera_code, ws.station_name, ws.station_code, ")
                .append("pl.line_name, pl.line_code, pl.factory_id ")
                .append("FROM defect_image_file f ")
                .append(cameraStatusJoinSql())
                .append("LEFT JOIN dim_workstation ws ON ws.workstation_id = cs.workstation_id ")
                .append("LEFT JOIN dim_production_line pl ON pl.line_id = cs.line_id ")
                .append("WHERE 1=1 ");
        if (hasText(cameraId)) {
            sql.append("AND f.camera_id = ? ");
            params.add(cameraId);
        }
        if (hasText(cameraName)) {
            sql.append("AND f.camera_name = ? ");
            params.add(cameraName);
        }
        if (factoryId != null) {
            sql.append("AND pl.factory_id = ? ");
            params.add(factoryId);
        }
        if (lineId != null) {
            sql.append("AND cs.line_id = ? ");
            params.add(lineId);
        }
        if (stationId != null) {
            sql.append("AND cs.workstation_id = ? ");
            params.add(stationId);
        }
        if (hasText(keyword)) {
            String like = "%" + keyword.trim() + "%";
            sql.append("AND (f.camera_id LIKE ? OR f.camera_name LIKE ? OR f.file_name LIKE ? OR f.original_file_name LIKE ? ")
                    .append("OR cs.camera_code LIKE ? OR ws.station_name LIKE ? OR ws.station_code LIKE ? OR pl.line_name LIKE ? ")
                    .append("OR EXISTS (SELECT 1 FROM defect_image_box bkw WHERE bkw.image_id = f.image_id AND bkw.defect_type LIKE ?)) ");
            params.add(like); params.add(like); params.add(like); params.add(like);
            params.add(like); params.add(like); params.add(like); params.add(like); params.add(like);
        }
        if (hasText(severity)) {
            sql.append("AND EXISTS (SELECT 1 FROM defect_image_box bs WHERE bs.image_id = f.image_id AND UPPER(bs.severity) = ?) ");
            params.add(severity.trim().toUpperCase());
        }
        if (hasText(defectType)) {
            sql.append("AND EXISTS (SELECT 1 FROM defect_image_box bt WHERE bt.image_id = f.image_id AND bt.defect_type LIKE ?) ");
            params.add("%" + defectType.trim() + "%");
        }
        if (startTime != null) {
            sql.append("AND COALESCE(f.capture_time, f.upload_time, f.create_time) >= ? ");
            params.add(Timestamp.valueOf(startTime));
        }
        if (endTime != null) {
            sql.append("AND COALESCE(f.capture_time, f.upload_time, f.create_time) <= ? ");
            params.add(Timestamp.valueOf(endTime));
        }
        sql.append("ORDER BY f.create_time DESC, f.upload_time DESC, f.image_id DESC LIMIT ?");
        params.add(safeLimit);

        List<Map<String, Object>> images = jdbcTemplate.query(sql.toString(), params.toArray(), (rs, rowNum) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            long imageId = rs.getLong("image_id");
            item.put("imageId", imageId);
            item.put("batchId", rs.getLong("batch_id"));
            item.put("cameraId", rs.getString("camera_id"));
            item.put("cameraName", rs.getString("camera_name"));
            item.put("cameraCode", rs.getString("camera_code"));
            item.put("factoryId", rs.getObject("factory_id"));
            item.put("lineId", rs.getObject("line_id"));
            item.put("lineName", rs.getString("line_name"));
            item.put("lineCode", rs.getString("line_code"));
            item.put("workstationId", rs.getObject("workstation_id"));
            item.put("stationCode", rs.getString("station_code"));
            item.put("stationName", rs.getString("station_name"));
            item.put("stationLabel", stationLabel.apply(rs.getString("station_code"), rs.getString("station_name")));
            item.put("fileName", rs.getString("file_name"));
            item.put("originalFileName", rs.getString("original_file_name"));
            item.put("imagePath", rs.getString("image_path"));
            item.put("imageUrl", rs.getString("image_url"));
            item.put("captureTime", toLocalDateTime(rs.getTimestamp("capture_time")));
            item.put("uploadTime", toLocalDateTime(rs.getTimestamp("upload_time")));
            item.put("receiveTime", toLocalDateTime(rs.getTimestamp("create_time")));
            item.put("createTime", toLocalDateTime(rs.getTimestamp("create_time")));
            item.put("fileSize", rs.getLong("file_size"));
            item.put("width", rs.getObject("width"));
            item.put("height", rs.getObject("height"));
            item.put("defectCount", rs.getInt("defect_count"));
            item.put("highCount", rs.getInt("high_count"));
            item.put("mediumCount", rs.getInt("medium_count"));
            item.put("lowCount", rs.getInt("low_count"));
            return item;
        });
        // ★ 2026-11-04 性能修复：原实现在 RowMapper 里逐图调用 listDefects(imageId)，
        //   10 张图就是 10 次查询（N+1）。改为批量查一次再回填。
        Map<Long, List<Map<String, Object>>> defectsByImage = listDefectsByImageIds(images);
        for (Map<String, Object> item : images) {
            Object v = item.get("imageId");
            Long imageId = v instanceof Number ? ((Number) v).longValue() : null;
            List<Map<String, Object>> boxes = imageId == null ? null : defectsByImage.get(imageId);
            item.put("defects", boxes == null ? new ArrayList<>() : boxes);
        }
        return images;
    }

    /**
     * 相机汇总列表（两段式聚合）。
     *
     * <p>★ 2026-11-04 性能修复：改为<b>两段式聚合</b>。
     * 原实现是「先 JOIN camera_status 再 GROUP BY」，
     * JOIN 要对 7.4 万行逐行执行（即便已消除相关子查询，仍是 8 万次
     * 派生表查找 + 临时表分组），实测 359ms。
     * 现在改成：
     * <ul>
     *   <li>第 1 段：只扫 defect_image_file 做聚合 → 结果只有 10 行（按相机）；</li>
     *   <li>第 2 段：拿这 10 行去 JOIN camera_status / dim_workstation / dim_production_line。</li>
     * </ul>
     * JOIN 的行数从 7.4 万降到 10，实测 203ms（再快 43%）。
     *
     * <p>关联依据：defect_image_file.camera_id 存的是<b>设备编号</b>，
     * 与 camera_status.camera_code 一一对应（服务器实测 10/10 组匹配，
     * 0 组落空），因此可直接走 uk_camera_code 唯一索引。
     */
    public List<Map<String, Object>> listCameraSummaries(Integer latestLimit, String cameraId, String cameraName,
                                                         Integer factoryId, Integer lineId, Integer stationId,
                                                         String keyword, String severity, String defectType,
                                                         LocalDateTime startTime, LocalDateTime endTime,
                                                         java.util.function.BiFunction<String, String, String> stationLabel) {
        int safeLatestLimit = latestLimit == null ? 5 : Math.max(1, Math.min(latestLimit, 20));
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT agg.camera_key camera_key, agg.camera_id camera_id, ")
                .append("COALESCE(MAX(cs.camera_name), agg.camera_name) camera_name, ")
                .append("MAX(cs.camera_code) camera_code, MAX(cs.status) status, ")
                .append("MAX(cs.line_id) line_id, MAX(cs.workstation_id) workstation_id, ")
                .append("MAX(ws.station_name) station_name, MAX(ws.station_code) station_code, ")
                .append("MAX(pl.line_name) line_name, MAX(pl.line_code) line_code, MAX(pl.factory_id) factory_id, ")
                .append("agg.image_count image_count, agg.defect_count defect_count, agg.high_count high_count, ")
                .append("agg.medium_count medium_count, agg.low_count low_count, agg.latest_time latest_time ")
                .append("FROM (")
                .append("SELECT COALESCE(NULLIF(f.camera_id, ''), f.camera_name) camera_key, ")
                .append("MAX(f.camera_id) camera_id, MAX(f.camera_name) camera_name, ")
                .append("COUNT(1) image_count, SUM(f.defect_count) defect_count, SUM(f.high_count) high_count, ")
                .append("SUM(f.medium_count) medium_count, SUM(f.low_count) low_count, ")
                .append("MAX(COALESCE(f.capture_time, f.upload_time, f.create_time)) latest_time ")
                .append("FROM defect_image_file f ")
                .append("WHERE 1=1 ");
        // 内层子查询只有 f 表 —— 只能用行级条件（相机编号/名称、严重度、缺陷类型、时间范围）
        if (hasText(cameraId)) {
            sql.append("AND f.camera_id = ? ");
            params.add(cameraId);
        }
        if (hasText(cameraName)) {
            sql.append("AND f.camera_name = ? ");
            params.add(cameraName);
        }
        appendKeywordFilterRowLevel(sql, params, keyword);
        if (hasText(severity)) {
            sql.append("AND EXISTS (SELECT 1 FROM defect_image_box bs WHERE bs.image_id = f.image_id AND UPPER(bs.severity) = ?) ");
            params.add(severity.trim().toUpperCase(Locale.ROOT));
        }
        if (hasText(defectType)) {
            sql.append("AND EXISTS (SELECT 1 FROM defect_image_box bt WHERE bt.image_id = f.image_id AND bt.defect_type LIKE ?) ");
            params.add("%" + defectType.trim() + "%");
        }
        if (startTime != null) {
            sql.append("AND COALESCE(f.capture_time, f.upload_time, f.create_time) >= ? ");
            params.add(Timestamp.valueOf(startTime));
        }
        if (endTime != null) {
            sql.append("AND COALESCE(f.capture_time, f.upload_time, f.create_time) <= ? ");
            params.add(Timestamp.valueOf(endTime));
        }
        sql.append("GROUP BY COALESCE(NULLIF(f.camera_id, ''), f.camera_name) ")
                .append(") agg ")
                .append("LEFT JOIN camera_status cs ON cs.camera_code = agg.camera_id ")
                .append("LEFT JOIN dim_workstation ws ON ws.workstation_id = cs.workstation_id ")
                .append("LEFT JOIN dim_production_line pl ON pl.line_id = cs.line_id ")
                .append("WHERE 1=1 ");
        // 外层才有过 cs/ws/pl 别名 —— 维度过滤（工厂/产线/工位）放这里
        if (factoryId != null) {
            sql.append("AND pl.factory_id = ? ");
            params.add(factoryId);
        }
        if (lineId != null) {
            sql.append("AND cs.line_id = ? ");
            params.add(lineId);
        }
        if (stationId != null) {
            sql.append("AND cs.workstation_id = ? ");
            params.add(stationId);
        }
        // keyword 的维度部分（相机编号/工位名/产线名）同样只能在外层过滤
        if (hasText(keyword)) {
            String like = "%" + keyword.trim() + "%";
            sql.append("AND (cs.camera_code LIKE ? OR ws.station_name LIKE ? OR ws.station_code LIKE ? OR pl.line_name LIKE ?) ");
            params.add(like); params.add(like); params.add(like); params.add(like);
        }
        sql.append("GROUP BY agg.camera_key, agg.camera_id, agg.camera_name, ")
                .append("agg.image_count, agg.defect_count, agg.high_count, agg.medium_count, agg.low_count, agg.latest_time ")
                .append("ORDER BY latest_time DESC, image_count DESC, camera_id ASC ");

        return jdbcTemplate.query(sql.toString(), params.toArray(), (rs, rowNum) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            String summaryCameraId = rs.getString("camera_id");
            String summaryCameraName = rs.getString("camera_name");
            item.put("cameraId", summaryCameraId);
            item.put("cameraName", summaryCameraName);
            item.put("cameraCode", rs.getString("camera_code"));
            item.put("status", rs.getString("status"));
            item.put("factoryId", rs.getObject("factory_id"));
            item.put("lineId", rs.getObject("line_id"));
            item.put("lineName", rs.getString("line_name"));
            item.put("lineCode", rs.getString("line_code"));
            item.put("workstationId", rs.getObject("workstation_id"));
            item.put("stationCode", rs.getString("station_code"));
            item.put("stationName", rs.getString("station_name"));
            item.put("stationLabel", stationLabel.apply(rs.getString("station_code"), rs.getString("station_name")));
            item.put("imageCount", rs.getLong("image_count"));
            item.put("defectCount", rs.getLong("defect_count"));
            item.put("highCount", rs.getLong("high_count"));
            item.put("mediumCount", rs.getLong("medium_count"));
            item.put("lowCount", rs.getLong("low_count"));
            item.put("latestTime", toLocalDateTime(rs.getTimestamp("latest_time")));
            return item;
        });
    }

    /**
     * 批量取每个相机的最近 N 张图片（一次查询解决，不再 N+1）。
     *
     * <p>用<b>窗口函数</b> {@code ROW_NUMBER() OVER (PARTITION BY camera_id ORDER BY ...)}
     * 在一次扫描里给每个相机分组编号，再取每组前 N 条。
     * MySQL 8.0 支持窗口函数。
     *
     * <p>★ 2026-11-04 性能修复：<b>先窗口函数分组，再 JOIN</b>。
     * 原写法把 JOIN 放在窗口函数<b>之前</b>，
     * 导致 8 万行图片<b>全部</b>都要做 camera_status / dim_workstation /
     * dim_production_line 的 JOIN，然后才被窗口函数裁掉 ——
     * 实测 1253ms，其中绝大部分是白做的 JOIN。
     * 现在改成：内层只扫 defect_image_file 做窗口编号（222ms），
     * 外层只对 <b>50 行</b>（10 相机 × 5 张）做 JOIN —— 实测 662ms，快 47%。
     */
    public Map<String, List<Map<String, Object>>> listRecentForSummaries(
            List<Map<String, Object>> summaries, int safeLatestLimit,
            Integer factoryId, Integer lineId, Integer stationId,
            String keyword, String severity, String defectType,
            LocalDateTime startTime, LocalDateTime endTime,
            java.util.function.BiFunction<String, String, String> stationLabel) {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        if (summaries == null || summaries.isEmpty() || safeLatestLimit <= 0) {
            return result;
        }
        // ★ 2026-11-05 相机身份改为 **code-first**（详见 utils/camera-identity.js 与
        //   docs/踩坑速查.md §10）：
        //   现场 camera_name **跨产线重名**（ZK54 的 CV1_Cam1/CV2_Cam1 与 ZK23 同名），
        //   若按名称匹配/分组，一台相机会拿到另一台的图片与统计。
        //   规则：只用 camera_id（= camera_code）；仅当它为空、**且**该名称在本次
        //   汇总结果中全局唯一时，才允许用 camera_name 兜底（老数据兼容）。
        Map<String, Integer> nameCounts = new LinkedHashMap<>();
        for (Map<String, Object> item : summaries) {
            String name = item.get("cameraName") == null ? "" : String.valueOf(item.get("cameraName")).trim();
            if (!name.isEmpty()) {
                nameCounts.merge(name, 1, Integer::sum);
            }
        }
        List<Object> cameraKeys = new ArrayList<>();
        for (Map<String, Object> item : summaries) {
            Object key = item.get("cameraId");
            if (key == null || String.valueOf(key).isEmpty()) {
                String name = item.get("cameraName") == null ? "" : String.valueOf(item.get("cameraName")).trim();
                // 名称重名 → 不允许兜底（宁可该相机没有最近图片，也不能拿到别人的）
                if (!name.isEmpty() && nameCounts.getOrDefault(name, 0) == 1) {
                    key = name;
                }
            }
            if (key != null && !String.valueOf(key).isEmpty()) {
                cameraKeys.add(String.valueOf(key));
            }
        }
        if (cameraKeys.isEmpty()) {
            return result;
        }

        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<>();
        sql.append("SELECT r.image_id, r.batch_id, r.camera_id, r.camera_name, r.file_name, r.original_file_name, ")
                .append("r.image_path, r.image_url, r.capture_time, r.upload_time, r.create_time, r.file_size, r.width, r.height, ")
                .append("r.defect_count, r.high_count, r.medium_count, r.low_count, ")
                .append("cs.line_id, cs.workstation_id, cs.camera_code, ws.station_name, ws.station_code, ")
                .append("pl.line_name, pl.line_code, pl.factory_id ")
                .append("FROM (")
                .append("SELECT f.image_id, f.batch_id, f.camera_id, f.camera_name, f.file_name, f.original_file_name, ")
                .append("f.image_path, f.image_url, f.capture_time, f.upload_time, f.create_time, f.file_size, f.width, f.height, ")
                .append("f.defect_count, f.high_count, f.medium_count, f.low_count, ")
                .append("ROW_NUMBER() OVER (PARTITION BY COALESCE(NULLIF(f.camera_id, ''), f.camera_name) ")
                .append("ORDER BY f.create_time DESC, f.upload_time DESC, f.image_id DESC) rn ")
                .append("FROM defect_image_file f ")
                .append("WHERE COALESCE(NULLIF(f.camera_id, ''), f.camera_name) IN (");
        for (int i = 0; i < cameraKeys.size(); i++) {
            sql.append(i == 0 ? "?" : ",?");
            params.add(cameraKeys.get(i));
        }
        // 先闭合相机 IN (...) 的右括号，后续筛选条件才能作为 WHERE 的 AND 子句拼接
        sql.append(") ");
        // BUG-01/BUG-02 修复（2026-11-06）：行级筛选必须放在<b>内层派生表</b>（别名 f）的 WHERE 里、
        // ROW_NUMBER 计算之前：
        //   1. 语法：派生表含窗口函数，MySQL 不做派生表合并，别名 f 在外层不可见——
        //      原写法把筛选拼在外层 WHERE r.rn 之后，任一筛选启用即
        //      Error 1054 "Unknown column 'f.xxx' in 'where clause'"，接口 500；
        //   2. 语义：筛选先于 ROW_NUMBER，口径为「每相机满足筛选条件的最新一张」；
        //      原设计筛选后置会导致「最新图不满足筛选则整台相机消失」（静默少图）。
        // 下推后窗内无匹配图的相机不出现在结果中（与图像记录页"有图才显示"逻辑一致）。
        appendKeywordFilterRowLevel(sql, params, keyword);
        if (hasText(severity)) {
            sql.append("AND EXISTS (SELECT 1 FROM defect_image_box bs WHERE bs.image_id = f.image_id AND UPPER(bs.severity) = ?) ");
            params.add(severity.trim().toUpperCase(Locale.ROOT));
        }
        if (hasText(defectType)) {
            sql.append("AND EXISTS (SELECT 1 FROM defect_image_box bt WHERE bt.image_id = f.image_id AND bt.defect_type LIKE ?) ");
            params.add("%" + defectType.trim() + "%");
        }
        if (startTime != null) {
            sql.append("AND COALESCE(f.capture_time, f.upload_time, f.create_time) >= ? ");
            params.add(Timestamp.valueOf(startTime));
        }
        if (endTime != null) {
            sql.append("AND COALESCE(f.capture_time, f.upload_time, f.create_time) <= ? ");
            params.add(Timestamp.valueOf(endTime));
        }
        // 相机 IN (...) 的右括号已在上方闭合，这里只需闭合内层派生表
        sql.append(") r ")
                .append("LEFT JOIN camera_status cs ON cs.camera_code = r.camera_id ")
                .append("LEFT JOIN dim_workstation ws ON ws.workstation_id = cs.workstation_id ")
                .append("LEFT JOIN dim_production_line pl ON pl.line_id = cs.line_id ")
                .append("WHERE r.rn <= ?");
        params.add(safeLatestLimit);

        List<Map<String, Object>> rows = jdbcTemplate.query(sql.toString(), params.toArray(), (rs, rowNum) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("imageId", rs.getLong("image_id"));
            item.put("batchId", rs.getLong("batch_id"));
            item.put("cameraId", rs.getString("camera_id"));
            item.put("cameraName", rs.getString("camera_name"));
            item.put("cameraCode", rs.getString("camera_code"));
            item.put("factoryId", rs.getObject("factory_id"));
            item.put("lineId", rs.getObject("line_id"));
            item.put("lineName", rs.getString("line_name"));
            item.put("lineCode", rs.getString("line_code"));
            item.put("workstationId", rs.getObject("workstation_id"));
            item.put("stationCode", rs.getString("station_code"));
            item.put("stationName", rs.getString("station_name"));
            item.put("stationLabel", stationLabel.apply(rs.getString("station_code"), rs.getString("station_name")));
            item.put("fileName", rs.getString("file_name"));
            item.put("originalFileName", rs.getString("original_file_name"));
            item.put("imagePath", rs.getString("image_path"));
            item.put("imageUrl", rs.getString("image_url"));
            item.put("captureTime", toLocalDateTime(rs.getTimestamp("capture_time")));
            item.put("uploadTime", toLocalDateTime(rs.getTimestamp("upload_time")));
            item.put("receiveTime", toLocalDateTime(rs.getTimestamp("create_time")));
            item.put("createTime", toLocalDateTime(rs.getTimestamp("create_time")));
            item.put("fileSize", rs.getLong("file_size"));
            item.put("width", rs.getObject("width"));
            item.put("height", rs.getObject("height"));
            item.put("defectCount", rs.getInt("defect_count"));
            item.put("highCount", rs.getInt("high_count"));
            item.put("mediumCount", rs.getInt("medium_count"));
            item.put("lowCount", rs.getInt("low_count"));
            return item;
        });

        // 按相机分组；缺陷框批量补齐（避免逐图查询）
        Map<Long, List<Map<String, Object>>> defectsByImage = listDefectsByImageIds(rows);
        for (Map<String, Object> row : rows) {
            Long imageId = row.get("imageId") == null ? null : ((Number) row.get("imageId")).longValue();
            List<Map<String, Object>> boxes = imageId == null ? null : defectsByImage.get(imageId);
            row.put("defects", boxes == null ? new ArrayList<>() : boxes);
            String key = row.get("cameraId") == null ? null : String.valueOf(row.get("cameraId"));
            if (key == null || key.isEmpty()) {
                key = row.get("cameraName") == null ? null : String.valueOf(row.get("cameraName"));
            }
            if (key == null) continue;
            result.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        return result;
    }

    /** 批量取多张图片的缺陷框，避免 N 次单查（N+1）。 */
    public Map<Long, List<Map<String, Object>>> listDefectsByImageIds(List<Map<String, Object>> images) {
        Map<Long, List<Map<String, Object>>> result = new HashMap<>();
        if (images == null || images.isEmpty()) {
            return result;
        }
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> v : images) {
            Object id = v.get("imageId");
            if (id instanceof Number) {
                ids.add(((Number) id).longValue());
            }
        }
        if (ids.isEmpty()) {
            return result;
        }
        StringBuilder sql = new StringBuilder(
                "SELECT box_id, image_id, defect_type, severity, severity_level, x, y, width, height, area "
                        + "FROM defect_image_box WHERE image_id IN (");
        List<Object> params = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            sql.append(i == 0 ? "?" : ",?");
            params.add(ids.get(i));
        }
        sql.append(") ORDER BY image_id ASC, box_id ASC");
        jdbcTemplate.query(sql.toString(), params.toArray(), rs -> {
            Map<String, Object> defect = new LinkedHashMap<>();
            defect.put("boxId", rs.getLong("box_id"));
            defect.put("type", rs.getString("defect_type"));
            defect.put("severity", rs.getString("severity"));
            defect.put("severityLevel", rs.getInt("severity_level"));
            defect.put("x", rs.getDouble("x"));
            defect.put("y", rs.getDouble("y"));
            defect.put("width", rs.getDouble("width"));
            defect.put("height", rs.getDouble("height"));
            defect.put("area", rs.getDouble("area"));
            long imageId = rs.getLong("image_id");
            result.computeIfAbsent(imageId, k -> new ArrayList<>()).add(defect);
        });
        return result;
    }

    /**
     * 图片历史分页。
     *
     * <p><b>2026-11-05 归档路由</b>：主表只保留近 {@code app.archive.images.hot-days}
     * （默认 31）天，更早数据在 {@code defect_image_file_hist_YYYYMM} 月表。
     * 本方法按查询时间范围自动选表：</p>
     * <ul>
     *   <li>无时间范围 / 范围全在热区 → 只查主表（毫秒级，默认查询路径）；</li>
     *   <li>范围涉及更早 → 主表(仅热区行) UNION ALL 各月月表，再走原有 JOIN/过滤/分页；</li>
     *   <li>月表不存在（该月无数据）→ 跳过，不报错。</li>
     * </ul>
     * <p>返回结构保持不变（records/total/page/size），前端零改造。</p>
     */
    public Map<String, Object> listHistoryPage(Integer page, Integer size, String cameraId, String cameraName,
                                               Integer factoryId, Integer lineId, Integer stationId,
                                               String keyword, String severity, String defectType,
                                               LocalDateTime startTime, LocalDateTime endTime,
                                               java.util.function.BiFunction<String, String, String> stationLabel) {
        int safePage = page == null ? 1 : Math.max(1, page);
        int safeSize = size == null ? 10 : Math.max(1, Math.min(size, 100));
        int offset = (safePage - 1) * safeSize;

        // 表源路由（srcParams 必须在过滤器参数之前绑定）
        List<String> archiveMonths = archiveMonthsFor(startTime, endTime);
        List<Object> srcParams = new ArrayList<>();
        String fileSource = buildFileSource(archiveMonths, srcParams);
        String boxSource = buildBoxSource(archiveMonths);

        List<Object> countParams = new ArrayList<>(srcParams);
        StringBuilder countSql = new StringBuilder();
        countSql.append("SELECT COUNT(1) FROM ").append(fileSource).append("f ")
                .append(cameraStatusJoinSql())
                .append("LEFT JOIN dim_workstation ws ON ws.workstation_id = cs.workstation_id ")
                .append("LEFT JOIN dim_production_line pl ON pl.line_id = cs.line_id ")
                .append("WHERE 1=1 ");
        appendImageFilters(countSql, countParams, cameraId, cameraName, factoryId, lineId, stationId,
                keyword, severity, defectType, startTime, endTime, boxSource);
        Long total = jdbcTemplate.queryForObject(countSql.toString(), countParams.toArray(), Long.class);

        List<Object> params = new ArrayList<>(srcParams);
        StringBuilder sql = new StringBuilder();
        sql.append(imageSelectSql())
                .append("FROM ").append(fileSource).append("f ")
                .append(cameraStatusJoinSql())
                .append("LEFT JOIN dim_workstation ws ON ws.workstation_id = cs.workstation_id ")
                .append("LEFT JOIN dim_production_line pl ON pl.line_id = cs.line_id ")
                .append("WHERE 1=1 ");
        appendImageFilters(sql, params, cameraId, cameraName, factoryId, lineId, stationId,
                keyword, severity, defectType, startTime, endTime, boxSource);
        sql.append("ORDER BY f.create_time DESC, f.upload_time DESC, f.image_id DESC LIMIT ? OFFSET ?");
        params.add(safeSize);
        params.add(offset);

        List<Map<String, Object>> records = jdbcTemplate.query(sql.toString(), params.toArray(),
                (rs, rowNum) -> mapImageRow(rs, stationLabel));

        Map<String, Object> pageResult = new LinkedHashMap<>();
        pageResult.put("records", records);
        pageResult.put("total", total == null ? 0L : total);
        pageResult.put("page", safePage);
        pageResult.put("size", safeSize);
        return pageResult;
    }

    // ==================== 归档路由（2026-11-05） ====================

    /** 主表热数据下界 */
    private LocalDateTime hotCutoff() {
        return LocalDateTime.now().minusDays(hotDays);
    }

    /**
     * 计算本次查询需要并集的归档月份（只返回真实存在的月表）。
     * 区间计算本身在 {@link com.wms.defect.app.ArchiveMonthRange}（纯函数，已单测覆盖）。
     */
    private List<String> archiveMonthsFor(LocalDateTime startTime, LocalDateTime endTime) {
        return filterExistingMonths(ArchiveMonthRange.months(startTime, endTime, hotCutoff()));
    }

    /** 过滤出真实存在的月表月份（缺表跳过，不报错） */
    private List<String> filterExistingMonths(List<String> months) {
        if (months.isEmpty()) {
            return Collections.emptyList();
        }
        String prefix = "defect_image_file_hist_";
        Set<String> existing = new HashSet<>(jdbcTemplate.query(
                "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME LIKE ?",
                new Object[]{prefix + "%"},
                (rs, i) -> rs.getString(1).substring(prefix.length())));
        List<String> result = new ArrayList<>();
        for (String m : months) {
            if (existing.contains(m)) {
                result.add(m);
            }
        }
        return result;
    }

    /** 文件表源：纯主表，或「主表(热区) ∪ 各月表」派生表（参数先于过滤条件绑定） */
    private String buildFileSource(List<String> months, List<Object> params) {
        if (months.isEmpty()) {
            return "defect_image_file ";
        }
        String cols = DefectImageArchiveRepository.FILE_COLS;
        StringBuilder sb = new StringBuilder("(SELECT ").append(cols)
                .append(" FROM defect_image_file WHERE COALESCE(capture_time, upload_time, create_time) >= ? ");
        params.add(Timestamp.valueOf(hotCutoff()));
        for (String m : months) {
            sb.append("UNION ALL SELECT ").append(cols)
                    .append(" FROM ").append(DefectImageArchiveRepository.monthTable("file", m)).append(" ");
        }
        return sb.append(") ").toString();
    }

    /** 缺陷框表源（severity / defectType 过滤用）：纯主表或主表∪月表并集 */
    private String buildBoxSource(List<String> months) {
        if (months.isEmpty()) {
            return "defect_image_box";
        }
        StringBuilder sb = new StringBuilder(
                "(SELECT image_id, defect_type, severity FROM defect_image_box");
        for (String m : months) {
            sb.append(" UNION ALL SELECT image_id, defect_type, severity FROM ")
                    .append(DefectImageArchiveRepository.monthTable("box", m));
        }
        return sb.append(") ").toString();
    }

    /** 单张图片的缺陷框列表 */
    public List<Map<String, Object>> listDefects(Long imageId) {
        return jdbcTemplate.query(
                "SELECT box_id, defect_type, severity, severity_level, x, y, width, height, area FROM defect_image_box WHERE image_id=? ORDER BY box_id ASC",
                new Object[]{imageId},
                (rs, rowNum) -> {
                    Map<String, Object> defect = new LinkedHashMap<>();
                    defect.put("boxId", rs.getLong("box_id"));
                    defect.put("type", rs.getString("defect_type"));
                    defect.put("severity", rs.getString("severity"));
                    defect.put("severityLevel", rs.getInt("severity_level"));
                    defect.put("x", rs.getDouble("x"));
                    defect.put("y", rs.getDouble("y"));
                    defect.put("width", rs.getDouble("width"));
                    defect.put("height", rs.getDouble("height"));
                    defect.put("area", rs.getDouble("area"));
                    return defect;
                });
    }

    /** 按 image_url 反查磁盘路径（最多 3 条） */
    public List<String> listImagePathsByUrl(String imageUrl) {
        return jdbcTemplate.query(
                "SELECT image_path FROM defect_image_file WHERE image_url = ? ORDER BY image_id DESC LIMIT 3",
                new Object[]{imageUrl},
                (rs, rowNum) -> rs.getString("image_path")
        );
    }

    // ==================== 写入 ====================

    /** 清理失败图片的数据库残留 */
    public void cleanupFailedImageRows(Long batchId, Long imageId, String absolutePath) {
        if (imageId != null) {
            jdbcTemplate.update("DELETE FROM defect_image_box WHERE image_id=?", imageId);
            jdbcTemplate.update("DELETE FROM defect_image_file WHERE image_id=?", imageId);
        } else if (batchId != null && hasText(absolutePath)) {
            jdbcTemplate.update("DELETE FROM defect_image_file WHERE batch_id=? AND image_path=?", batchId, absolutePath);
        }
    }

    /** 更新批次汇总 */
    public void updateBatchSummary(Long batchId, int successCount, int failCount, int defectCount,
                                   int highCount, int mediumCount, int lowCount,
                                   String uploadStatus, String errorSummary) {
        jdbcTemplate.update("UPDATE defect_image_batch SET image_count=?, success_count=?, fail_count=?, defect_count=?, high_count=?, medium_count=?, low_count=?, upload_status=?, error_summary=? WHERE batch_id=?",
                successCount, successCount, failCount, defectCount, highCount, mediumCount, lowCount, uploadStatus, errorSummary, batchId);
    }

    // ==================== 插入（用 GeneratedKeyHolder 取自增 ID） ====================

    /** 插入批次记录，返回 batch_id */
    public Long insertBatch(String batchNo, String cameraId, String cameraName, String deviceId,
                            LocalDateTime uploadTime, int declaredImageCount, String metadataJson) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO defect_image_batch(batch_no, camera_id, camera_name, device_id, upload_time, declared_image_count, metadata_json, upload_status, create_time) VALUES(?,?,?,?,?,?,?,?,NOW())",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, batchNo);
            ps.setString(2, cameraId);
            ps.setString(3, cameraName);
            ps.setString(4, deviceId);
            ps.setTimestamp(5, Timestamp.valueOf(uploadTime));
            ps.setInt(6, declaredImageCount);
            ps.setString(7, metadataJson);
            ps.setString(8, "PROCESSING");
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    /** 插入图片记录，返回 image_id */
    public Long insertImageFile(Long batchId, String cameraId, String cameraName,
                                String fileName, String originalFileName, String absolutePath, String imageUrl,
                                LocalDateTime captureTime, LocalDateTime uploadTime,
                                Long fileSize, Integer width, Integer height,
                                int defectTotal, int defectHigh, int defectMedium, int defectLow) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO defect_image_file(batch_id, camera_id, camera_name, file_name, original_file_name, image_path, image_url, capture_time, upload_time, file_size, width, height, defect_count, high_count, medium_count, low_count, create_time) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW())",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setString(2, cameraId);
            ps.setString(3, cameraName);
            ps.setString(4, fileName);
            ps.setString(5, originalFileName);
            ps.setString(6, absolutePath);
            ps.setString(7, imageUrl);
            ps.setTimestamp(8, Timestamp.valueOf(captureTime));
            ps.setTimestamp(9, Timestamp.valueOf(uploadTime));
            ps.setLong(10, fileSize == null ? 0L : fileSize);
            if (width == null) ps.setObject(11, null); else ps.setInt(11, width);
            if (height == null) ps.setObject(12, null); else ps.setInt(12, height);
            ps.setInt(13, defectTotal);
            ps.setInt(14, defectHigh);
            ps.setInt(15, defectMedium);
            ps.setInt(16, defectLow);
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    /** 插入缺陷框 */
    public void insertDefectBox(Long imageId, Long batchId, String defectType, String severity,
                                 int severityLevel, Double x, Double y, Double width, Double height, Double area) {
        jdbcTemplate.update(
                "INSERT INTO defect_image_box(image_id, batch_id, defect_type, severity, severity_level, x, y, width, height, area, create_time) VALUES(?,?,?,?,?,?,?,?,?,?,NOW())",
                imageId,
                batchId,
                defectType,
                severity,
                severityLevel,
                x,
                y,
                width,
                height,
                area
        );
    }

    // ==================== 建表与字段补齐 ====================

    /** 建三张缺陷图片表 */
    public void createTables() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS defect_image_batch (" +
        "batch_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY," +
        "batch_no VARCHAR(100) NOT NULL," +
        "camera_id VARCHAR(100) NULL," +
        "camera_name VARCHAR(100) NOT NULL," +
        "device_id VARCHAR(100) NULL," +
        "upload_time DATETIME NOT NULL," +
        "declared_image_count INT DEFAULT 0," +
        "image_count INT DEFAULT 0," +
        "success_count INT DEFAULT 0," +
        "fail_count INT DEFAULT 0," +
        "defect_count INT DEFAULT 0," +
        "high_count INT DEFAULT 0," +
        "medium_count INT DEFAULT 0," +
        "low_count INT DEFAULT 0," +
        "upload_status VARCHAR(30) DEFAULT 'SUCCESS'," +
        "error_summary TEXT NULL," +
        "metadata_json LONGTEXT NULL," +
        "create_time DATETIME DEFAULT CURRENT_TIMESTAMP," +
        "UNIQUE KEY uk_defect_image_batch_no(batch_no)," +
        "KEY idx_defect_batch_camera(camera_name, upload_time)," +
        "KEY idx_defect_batch_upload(upload_time)," +
        "KEY idx_defect_batch_status_time(upload_status, upload_time)" +
        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='缺陷图片上传批次'");

        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS defect_image_file (" +
        "image_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY," +
        "batch_id BIGINT NOT NULL," +
        "camera_id VARCHAR(100) NULL," +
        "camera_name VARCHAR(100) NOT NULL," +
        "file_name VARCHAR(255) NOT NULL," +
        "original_file_name VARCHAR(255) NULL," +
        "image_path VARCHAR(500) NOT NULL," +
        "image_url VARCHAR(500) NOT NULL," +
        "capture_time DATETIME NOT NULL," +
        "upload_time DATETIME NOT NULL," +
        "file_size BIGINT DEFAULT 0," +
        "width INT NULL," +
        "height INT NULL," +
        "defect_count INT DEFAULT 0," +
        "high_count INT DEFAULT 0," +
        "medium_count INT DEFAULT 0," +
        "low_count INT DEFAULT 0," +
        "create_time DATETIME DEFAULT CURRENT_TIMESTAMP," +
        "KEY idx_defect_file_batch(batch_id)," +
        "KEY idx_defect_file_camera(camera_name, upload_time)," +
        "KEY idx_defect_file_upload(upload_time)," +
        "KEY idx_defect_file_camera_id_time(camera_id, create_time, image_id)," +
        "KEY idx_defect_file_camera_name_time(camera_name, create_time, image_id)," +
        "KEY idx_defect_file_capture_time(capture_time, image_id)" +
        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='缺陷图片文件'");

        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS defect_image_box (" +
        "box_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY," +
        "image_id BIGINT NOT NULL," +
        "batch_id BIGINT NOT NULL," +
        "defect_type VARCHAR(100) NOT NULL," +
        "severity VARCHAR(20) NOT NULL," +
        "severity_level INT NOT NULL," +
        "x DOUBLE DEFAULT 0," +
        "y DOUBLE DEFAULT 0," +
        "width DOUBLE DEFAULT 0," +
        "height DOUBLE DEFAULT 0," +
        "area DOUBLE DEFAULT 0," +
        "create_time DATETIME DEFAULT CURRENT_TIMESTAMP," +
        "KEY idx_defect_box_image(image_id)," +
        "KEY idx_defect_box_batch(batch_id)," +
        "KEY idx_defect_box_severity(severity)," +
        "KEY idx_defect_box_type(defect_type)," +
        "KEY idx_defect_box_severity_image(severity, image_id)" +
        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='缺陷图片缺陷框'");
    }

    /** 检查并新增列（失败记 WARN，由后续 SQL 暴露真实错误） */
    public void ensureColumn(String tableName, String columnName, String alterSql) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class, tableName, columnName);
            if (count == null || count == 0) {
                jdbcTemplate.execute(alterSql);
            }
        } catch (DataAccessException e) {
            log.warn("检查/新增字段失败 {}.{}: {}", tableName, columnName, e.getMessage());
        }
    }

    /** 检查并新增索引（失败记 WARN，由后续 SQL 暴露真实错误） */
    /**
     * DDD P5：完整表结构保证（建表 + 补列 + 补索引），原样搬运自
     * DefectImageServiceImpl.doEnsureSchema（2026-11-05 收敛到 SchemaInitializer 调度，
     * 语义与 SQL 逐字保留）。ensureColumn/ensureIndex 内部自带 INFORMATION_SCHEMA
     * 判断与异常 WARN 兜底（既有语义），幂等可重入。
     */
    public void ensureSchemaFull() {
        createTables();
        ensureColumn("camera_status", "last_upload_at", "ALTER TABLE camera_status ADD COLUMN last_upload_at DATETIME NULL COMMENT '最后缺陷图片上传时间'");
        ensureColumn("defect_image_batch", "declared_image_count", "ALTER TABLE defect_image_batch ADD COLUMN declared_image_count INT DEFAULT 0 COMMENT 'metadata声明图片数'");
        ensureColumn("defect_image_batch", "success_count", "ALTER TABLE defect_image_batch ADD COLUMN success_count INT DEFAULT 0 COMMENT '成功入库图片数'");
        ensureColumn("defect_image_batch", "fail_count", "ALTER TABLE defect_image_batch ADD COLUMN fail_count INT DEFAULT 0 COMMENT '失败图片数'");
        ensureColumn("defect_image_batch", "upload_status", "ALTER TABLE defect_image_batch ADD COLUMN upload_status VARCHAR(30) DEFAULT 'SUCCESS' COMMENT '上传状态'");
        ensureColumn("defect_image_batch", "error_summary", "ALTER TABLE defect_image_batch ADD COLUMN error_summary TEXT NULL COMMENT '失败摘要'");
        ensureIndex("defect_image_file", "idx_defect_file_camera_id_time",
                "ALTER TABLE defect_image_file ADD INDEX idx_defect_file_camera_id_time(camera_id, create_time, image_id)");
        ensureIndex("defect_image_file", "idx_defect_file_camera_name_time",
                "ALTER TABLE defect_image_file ADD INDEX idx_defect_file_camera_name_time(camera_name, create_time, image_id)");
        ensureIndex("defect_image_file", "idx_defect_file_capture_time",
                "ALTER TABLE defect_image_file ADD INDEX idx_defect_file_capture_time(capture_time, image_id)");
        ensureIndex("defect_image_box", "idx_defect_box_type",
                "ALTER TABLE defect_image_box ADD INDEX idx_defect_box_type(defect_type)");
        ensureIndex("defect_image_box", "idx_defect_box_severity_image",
                "ALTER TABLE defect_image_box ADD INDEX idx_defect_box_severity_image(severity, image_id)");
        ensureIndex("defect_image_batch", "idx_defect_batch_status_time",
                "ALTER TABLE defect_image_batch ADD INDEX idx_defect_batch_status_time(upload_status, upload_time)");
    }

    public void ensureIndex(String tableName, String indexName, String alterSql) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                    Integer.class, tableName, indexName);
            if (count == null || count == 0) {
                jdbcTemplate.execute(alterSql);
            }
        } catch (DataAccessException e) {
            log.warn("检查/新增索引失败 {}.{}: {}", tableName, indexName, e.getMessage());
        }
    }

    // ==================== 行映射 ====================

    private Map<String, Object> mapImageRow(ResultSet rs,
                                            java.util.function.BiFunction<String, String, String> stationLabel) throws SQLException {
        Map<String, Object> item = new LinkedHashMap<>();
        long imageId = rs.getLong("image_id");
        item.put("imageId", imageId);
        item.put("batchId", rs.getLong("batch_id"));
        item.put("cameraId", rs.getString("camera_id"));
        item.put("cameraName", rs.getString("camera_name"));
        item.put("cameraCode", rs.getString("camera_code"));
        item.put("factoryId", rs.getObject("factory_id"));
        item.put("lineId", rs.getObject("line_id"));
        item.put("lineName", rs.getString("line_name"));
        item.put("lineCode", rs.getString("line_code"));
        item.put("workstationId", rs.getObject("workstation_id"));
        item.put("stationCode", rs.getString("station_code"));
        item.put("stationName", rs.getString("station_name"));
        item.put("stationLabel", stationLabel.apply(rs.getString("station_code"), rs.getString("station_name")));
        item.put("fileName", rs.getString("file_name"));
        item.put("originalFileName", rs.getString("original_file_name"));
        item.put("imagePath", rs.getString("image_path"));
        item.put("imageUrl", rs.getString("image_url"));
        item.put("captureTime", toLocalDateTime(rs.getTimestamp("capture_time")));
        item.put("uploadTime", toLocalDateTime(rs.getTimestamp("upload_time")));
        item.put("receiveTime", toLocalDateTime(rs.getTimestamp("create_time")));
        item.put("createTime", toLocalDateTime(rs.getTimestamp("create_time")));
        item.put("fileSize", rs.getLong("file_size"));
        item.put("width", rs.getObject("width"));
        item.put("height", rs.getObject("height"));
        item.put("defectCount", rs.getInt("defect_count"));
        item.put("highCount", rs.getInt("high_count"));
        item.put("mediumCount", rs.getInt("medium_count"));
        item.put("lowCount", rs.getInt("low_count"));
        item.put("defects", listDefects(imageId));
        return item;
    }

    // ==================== 通用 ====================

    /** 时间戳转 LocalDateTime（为空返回 null） */
    private LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    private boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
