package com.wms.defect.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wms.defect.api.dto.DefectImageUploadMetadataDTO;
import com.wms.defect.api.dto.DefectImageUploadResultDTO;
import com.wms.device.domain.model.CameraImage;
import com.wms.device.domain.model.CameraStatus;
import com.wms.device.infra.mapper.CameraImageMapper;
import com.wms.device.infra.mapper.CameraStatusMapper;
import com.wms.defect.app.IDefectImageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriUtils;

import javax.annotation.PostConstruct;
import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletRequest;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class DefectImageServiceImpl implements IDefectImageService {

    private static final DateTimeFormatter SECOND_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter MILLIS_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Set<String> IMAGE_EXTENSIONS = new HashSet<>(Arrays.asList("jpg", "jpeg", "png", "bmp", "gif", "webp", "svg"));
    private static final Set<String> IMAGEIO_REQUIRED_EXTENSIONS = new HashSet<>(Arrays.asList("jpg", "jpeg", "png", "bmp", "gif"));

    @Value("${defect-image.upload.dir:D:/45344/runtime/uploads/defect-images/}")
    private String defectImageDir;

    @Value("${defect-image.max-batch-images:100}")
    private int maxBatchImages;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CameraImageMapper cameraImageMapper;

    @Autowired
    private CameraStatusMapper cameraStatusMapper;

    /**
     * 表结构自检的时间戳缓存（避免每次请求都跑 DDL 与元数据查询）。
     *
     * @see #ensureSchema()
     */
    private volatile boolean schemaChecked = false;
    private volatile long lastSchemaCheckAt = 0L;
    /** 运行期表结构自检的最小间隔（毫秒）。 */
    private static final long SCHEMA_CHECK_INTERVAL_MS = 5 * 60 * 1000L;

    @PostConstruct
    public void init() {
        // 启动时强制完整检查一次（建表 + 补列 + 补索引）
        schemaChecked = false;
        lastSchemaCheckAt = 0L;
        ensureSchema();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DefectImageUploadResultDTO upload(MultipartFile[] files, String metadataJson, String authorization, String deviceId) {
        ensureSchema();

        if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
            throw new IllegalArgumentException("缺少认证令牌，请在 Header 中传 Authorization: Bearer <token>");
        }
        if (!StringUtils.hasText(deviceId)) {
            throw new IllegalArgumentException("缺少设备ID，请在 Header 中传 X-Device-Id");
        }
        if (files == null || files.length == 0 || Arrays.stream(files).allMatch(MultipartFile::isEmpty)) {
            throw new IllegalArgumentException("files 不能为空，请使用 multipart/form-data 上传图片数组字段 files");
        }
        if (files.length > maxBatchImages) {
            throw new IllegalArgumentException("单批次最多支持 " + maxBatchImages + " 张图片，当前上传 " + files.length + " 张；请拆分为多个批次后重试");
        }
        if (!StringUtils.hasText(metadataJson)) {
            throw new IllegalArgumentException("metadata 不能为空，请在 form-data 中传 JSON 字符串字段 metadata");
        }

        metadataJson = normalizeMetadataJson(metadataJson);
        DefectImageUploadMetadataDTO metadata;
        try {
            metadata = objectMapper.readValue(metadataJson, DefectImageUploadMetadataDTO.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("metadata JSON 解析失败: " + e.getMessage(), e);
        }
        validateMetadata(metadata);

        String cameraId = firstNotBlank(metadata.getCameraId(), deviceId);
        String cameraName = firstNotBlank(metadata.getCameraName(), cameraId);
        LocalDateTime receiveTime = LocalDateTime.now();
        LocalDateTime uploadTime = parseTime(metadata.getUploadTime(), receiveTime);
        String batchNo = cameraId + "-" + uploadTime.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")) + "-" + UUID.randomUUID().toString().substring(0, 8);

        Map<String, MultipartFile> fileMap = Arrays.stream(files)
                .filter(f -> f != null && !f.isEmpty())
                .collect(Collectors.toMap(f -> fileNameKey(f.getOriginalFilename()), f -> f, (a, b) -> a, LinkedHashMap::new));
        validateFileNameMapping(files, fileMap, metadata);

        DefectImageUploadResultDTO result = new DefectImageUploadResultDTO();
        result.setBatchNo(batchNo);
        result.setCameraId(cameraId);
        result.setCameraName(cameraName);
        result.setImageCount(metadata.getImages().size());

        Long batchId = insertBatch(batchNo, cameraId, cameraName, deviceId, uploadTime, metadata.getImages().size(), metadataJson);
        result.setBatchId(batchId);

        int successCount = 0;
        int failCount = 0;
        int defectCount = 0;
        int highCount = 0;
        int mediumCount = 0;
        int lowCount = 0;
        String latestImageUrl = null;
        LocalDateTime latestCaptureTime = null;
        List<String> failureMessages = new ArrayList<>();

        for (DefectImageUploadMetadataDTO.ImageMetadata imageMeta : metadata.getImages()) {
            DefectImageUploadResultDTO.FileResult fileResult = new DefectImageUploadResultDTO.FileResult();
            fileResult.setFileName(imageMeta.getFileName());
            MultipartFile file = fileMap.get(fileNameKey(imageMeta.getFileName()));
            SavedImage saved = null;
            Long imageId = null;

            try {
                if (file == null || file.isEmpty()) {
                    throw new IllegalArgumentException("metadata.images[].fileName 未匹配到上传文件: " + imageMeta.getFileName());
                }
                validateImageFile(file);

                saved = saveFile(file, cameraId, uploadTime);
                LocalDateTime captureTime = parseTime(imageMeta.getCaptureTime(), uploadTime);
                DefectCounts counts = countDefects(imageMeta.getDefects());

                imageId = insertImageFile(batchId, cameraId, cameraName, imageMeta, saved, captureTime, uploadTime, counts);
                insertDefectBoxes(batchId, imageId, imageMeta.getDefects());
                insertLegacyCameraImage(cameraName, saved, captureTime);

                fileResult.setSuccess(true);
                fileResult.setImageId(imageId);
                fileResult.setImageUrl(saved.imageUrl);
                fileResult.setMessage("上传成功");

                successCount++;
                defectCount += counts.total;
                highCount += counts.high;
                mediumCount += counts.medium;
                lowCount += counts.low;
                if (latestCaptureTime == null || captureTime.isAfter(latestCaptureTime)) {
                    latestCaptureTime = captureTime;
                    latestImageUrl = saved.imageUrl;
                }
            } catch (Exception e) {
                log.warn("缺陷图片处理失败: {}", imageMeta.getFileName(), e);
                cleanupFailedImage(batchId, imageId, saved);
                fileResult.setSuccess(false);
                UploadFailure failure = classifyUploadFailure(e);
                fileResult.setErrorCode(failure.code);
                fileResult.setMessage(e.getMessage());
                fileResult.setFailureReason(failure.reason);
                fileResult.setHandlingAdvice(failure.advice);
                failureMessages.add(imageMeta.getFileName() + ": " + e.getMessage());
                failCount++;
            }
            result.getResults().add(fileResult);
        }

        updateBatchSummary(batchId, successCount, failCount, defectCount, highCount, mediumCount, lowCount, failureMessages);
        updateCameraLatestImage(cameraId, cameraName, deviceId, latestImageUrl, receiveTime);

        result.setSuccess(failCount == 0);
        result.setSuccessCount(successCount);
        result.setFailCount(failCount);
        result.setDefectCount(defectCount);
        result.setHighCount(highCount);
        result.setMediumCount(mediumCount);
        result.setLowCount(lowCount);
        return result;
    }

    @Override
    public List<Map<String, Object>> listRecent(Integer limit, String cameraId, String cameraName,
                                                Integer factoryId, Integer lineId, Integer stationId,
                                                String keyword, String severity, String defectType,
                                                LocalDateTime startTime, LocalDateTime endTime) {
        ensureSchema();
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
        if (StringUtils.hasText(cameraId)) {
            sql.append("AND f.camera_id = ? ");
            params.add(cameraId);
        }
        if (StringUtils.hasText(cameraName)) {
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
        if (StringUtils.hasText(keyword)) {
            String like = "%" + keyword.trim() + "%";
            sql.append("AND (f.camera_id LIKE ? OR f.camera_name LIKE ? OR f.file_name LIKE ? OR f.original_file_name LIKE ? ")
                    .append("OR cs.camera_code LIKE ? OR ws.station_name LIKE ? OR ws.station_code LIKE ? OR pl.line_name LIKE ? ")
                    .append("OR EXISTS (SELECT 1 FROM defect_image_box bkw WHERE bkw.image_id = f.image_id AND bkw.defect_type LIKE ?)) ");
            params.add(like); params.add(like); params.add(like); params.add(like);
            params.add(like); params.add(like); params.add(like); params.add(like); params.add(like);
        }
        if (StringUtils.hasText(severity)) {
            sql.append("AND EXISTS (SELECT 1 FROM defect_image_box bs WHERE bs.image_id = f.image_id AND UPPER(bs.severity) = ?) ");
            params.add(severity.trim().toUpperCase());
        }
        if (StringUtils.hasText(defectType)) {
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
            item.put("stationLabel", formatStationLabel(rs.getString("station_code"), rs.getString("station_name")));
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

    @Override
    public List<Map<String, Object>> listCameraSummaries(Integer latestLimit, String cameraId, String cameraName,
                                                         Integer factoryId, Integer lineId, Integer stationId,
                                                         String keyword, String severity, String defectType,
                                                         LocalDateTime startTime, LocalDateTime endTime) {
        ensureSchema();
        int safeLatestLimit = latestLimit == null ? 5 : Math.max(1, Math.min(latestLimit, 20));
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        // ★ 2026-11-04 性能修复：改为**两段式聚合**。
        //   原实现是「先 JOIN camera_status 再 GROUP BY」，
        //   JOIN 要对 7.4 万行逐行执行（即便已消除相关子查询，仍是 8 万次
        //   派生表查找 + 临时表分组），实测 359ms。
        //   现在改成：
        //     第 1 段：只扫 defect_image_file 做聚合 → 结果只有 10 行（按相机）；
        //     第 2 段：拿这 10 行去 JOIN camera_status / dim_workstation / dim_production_line。
        //   JOIN 的行数从 7.4 万降到 10，实测 203ms（再快 43%）。
        //
        //   关联依据：defect_image_file.camera_id 存的是**设备编号**，
        //   与 camera_status.camera_code 一一对应（服务器实测 10/10 组匹配，
        //   0 组落空），因此可直接走 uk_camera_code 唯一索引。
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
        if (StringUtils.hasText(cameraId)) {
            sql.append("AND f.camera_id = ? ");
            params.add(cameraId);
        }
        if (StringUtils.hasText(cameraName)) {
            sql.append("AND f.camera_name = ? ");
            params.add(cameraName);
        }
        appendKeywordFilterRowLevel(sql, params, keyword);
        if (StringUtils.hasText(severity)) {
            sql.append("AND EXISTS (SELECT 1 FROM defect_image_box bs WHERE bs.image_id = f.image_id AND UPPER(bs.severity) = ?) ");
            params.add(severity.trim().toUpperCase(Locale.ROOT));
        }
        if (StringUtils.hasText(defectType)) {
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
        if (StringUtils.hasText(keyword)) {
            String like = "%" + keyword.trim() + "%";
            sql.append("AND (cs.camera_code LIKE ? OR ws.station_name LIKE ? OR ws.station_code LIKE ? OR pl.line_name LIKE ?) ");
            params.add(like); params.add(like); params.add(like); params.add(like);
        }
        sql.append("GROUP BY agg.camera_key, agg.camera_id, agg.camera_name, ")
                .append("agg.image_count, agg.defect_count, agg.high_count, agg.medium_count, agg.low_count, agg.latest_time ")
                .append("ORDER BY latest_time DESC, image_count DESC, camera_id ASC ");

        List<Map<String, Object>> summaries = jdbcTemplate.query(sql.toString(), params.toArray(), (rs, rowNum) -> {
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
            item.put("stationLabel", formatStationLabel(rs.getString("station_code"), rs.getString("station_name")));
            item.put("imageCount", rs.getLong("image_count"));
            item.put("defectCount", rs.getLong("defect_count"));
            item.put("highCount", rs.getLong("high_count"));
            item.put("mediumCount", rs.getLong("medium_count"));
            item.put("lowCount", rs.getLong("low_count"));
            item.put("latestTime", toLocalDateTime(rs.getTimestamp("latest_time")));
            return item;
        });
        // ★ 2026-11-04 性能修复：原实现在 RowMapper 里对**每一行**汇总都调用
        //   listRecent(...) —— 10 个相机就是 10 次额外查询，且每次都带
        //   那个 3 秒的相关子查询 JOIN（N+1 + 慢 JOIN 叠加 = 6.4 秒）。
        //   现在改为：先拿到全部汇总，再**一次性**批量查最近图片。
        Map<String, List<Map<String, Object>>> recentByCamera =
                listRecentForSummaries(summaries, safeLatestLimit, factoryId, lineId, stationId,
                        keyword, severity, defectType, startTime, endTime);
        for (Map<String, Object> item : summaries) {
            String summaryCameraId = item.get("cameraId") == null ? null : String.valueOf(item.get("cameraId"));
            String summaryCameraName = item.get("cameraName") == null ? null : String.valueOf(item.get("cameraName"));
            List<Map<String, Object>> recent = recentByCamera.get(summaryCameraId);
            if (recent == null) {
                recent = recentByCamera.get(summaryCameraName);
            }
            item.put("latestImages", recent == null ? new ArrayList<>() : recent);
        }
        return summaries;
    }

    /**
     * 批量取每个相机的最近 N 张图片（一次查询解决，不再 N+1）。
     *
     * <p>用<b>窗口函数</b> {@code ROW_NUMBER() OVER (PARTITION BY camera_id ORDER BY ...)}
     * 在一次扫描里给每个相机分组编号，再取每组前 N 条。
     * MySQL 8.0 支持窗口函数。
     */
    private Map<String, List<Map<String, Object>>> listRecentForSummaries(
            List<Map<String, Object>> summaries, int safeLatestLimit,
            Integer factoryId, Integer lineId, Integer stationId,
            String keyword, String severity, String defectType,
            LocalDateTime startTime, LocalDateTime endTime) {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        if (summaries == null || summaries.isEmpty() || safeLatestLimit <= 0) {
            return result;
        }
        List<Object> cameraKeys = new ArrayList<>();
        for (Map<String, Object> item : summaries) {
            Object key = item.get("cameraId");
            if (key == null || String.valueOf(key).isEmpty()) {
                key = item.get("cameraName");
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
        // ★ 2026-11-04 性能修复：**先窗口函数分组，再 JOIN**。
        //   原写法把 JOIN 放在窗口函数<b>之前</b>，
        //   导致 8 万行图片<b>全部</b>都要做 camera_status / dim_workstation /
        //   dim_production_line 的 JOIN，然后才被窗口函数裁掉 ——
        //   实测 1253ms，其中绝大部分是白做的 JOIN。
        //   现在改成：内层只扫 defect_image_file 做窗口编号（222ms），
        //   外层只对 <b>50 行</b>（10 相机 × 5 张）做 JOIN —— 实测 662ms，快 47%。
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
        sql.append(") ) r ")
                .append("LEFT JOIN camera_status cs ON cs.camera_code = r.camera_id ")
                .append("LEFT JOIN dim_workstation ws ON ws.workstation_id = cs.workstation_id ")
                .append("LEFT JOIN dim_production_line pl ON pl.line_id = cs.line_id ")
                .append("WHERE r.rn <= ?");
        params.add(safeLatestLimit);
        // 行级过滤（内层只有 f 表）
        appendKeywordFilterRowLevel(sql, params, keyword);
        if (StringUtils.hasText(severity)) {
            sql.append("AND EXISTS (SELECT 1 FROM defect_image_box bs WHERE bs.image_id = f.image_id AND UPPER(bs.severity) = ?) ");
            params.add(severity.trim().toUpperCase(Locale.ROOT));
        }
        if (StringUtils.hasText(defectType)) {
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
            item.put("stationLabel", formatStationLabel(rs.getString("station_code"), rs.getString("station_name")));
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
    private Map<Long, List<Map<String, Object>>> listDefectsByImageIds(List<Map<String, Object>> images) {
        Map<Long, List<Map<String, Object>>> result = new HashMap<>();
        if (images == null || images.isEmpty()) {
            return result;
        }
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> img : images) {
            Object v = img.get("imageId");
            if (v instanceof Number) {
                ids.add(((Number) v).longValue());
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

    @Override
    public Map<String, Object> listHistoryPage(Integer page, Integer size, String cameraId, String cameraName,
                                               Integer factoryId, Integer lineId, Integer stationId,
                                               String keyword, String severity, String defectType,
                                               LocalDateTime startTime, LocalDateTime endTime) {
        ensureSchema();
        int safePage = page == null ? 1 : Math.max(1, page);
        int safeSize = size == null ? 10 : Math.max(1, Math.min(size, 100));
        int offset = (safePage - 1) * safeSize;

        List<Object> countParams = new ArrayList<>();
        StringBuilder countSql = new StringBuilder();
        countSql.append("SELECT COUNT(1) FROM defect_image_file f ")
                .append(cameraStatusJoinSql())
                .append("LEFT JOIN dim_workstation ws ON ws.workstation_id = cs.workstation_id ")
                .append("LEFT JOIN dim_production_line pl ON pl.line_id = cs.line_id ")
                .append("WHERE 1=1 ");
        appendImageFilters(countSql, countParams, cameraId, cameraName, factoryId, lineId, stationId,
                keyword, severity, defectType, startTime, endTime);
        Long total = jdbcTemplate.queryForObject(countSql.toString(), countParams.toArray(), Long.class);

        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        sql.append(imageSelectSql())
                .append("FROM defect_image_file f ")
                .append(cameraStatusJoinSql())
                .append("LEFT JOIN dim_workstation ws ON ws.workstation_id = cs.workstation_id ")
                .append("LEFT JOIN dim_production_line pl ON pl.line_id = cs.line_id ")
                .append("WHERE 1=1 ");
        appendImageFilters(sql, params, cameraId, cameraName, factoryId, lineId, stationId,
                keyword, severity, defectType, startTime, endTime);
        sql.append("ORDER BY f.create_time DESC, f.upload_time DESC, f.image_id DESC LIMIT ? OFFSET ?");
        params.add(safeSize);
        params.add(offset);

        List<Map<String, Object>> records = jdbcTemplate.query(sql.toString(), params.toArray(),
                (rs, rowNum) -> mapImageRow(rs));

        Map<String, Object> pageResult = new LinkedHashMap<>();
        pageResult.put("records", records);
        pageResult.put("total", total == null ? 0L : total);
        pageResult.put("page", safePage);
        pageResult.put("size", safeSize);
        return pageResult;
    }

    @Override
    public ResponseEntity<Resource> loadImage(String relativePath, HttpServletRequest request) {
        try {
            if (!StringUtils.hasText(relativePath)) {
                return ResponseEntity.notFound().build();
            }
            String decoded = UriUtils.decode(relativePath, StandardCharsets.UTF_8);
            Path filePath = resolveImagePath(decoded);
            if (filePath == null) {
                return ResponseEntity.notFound().build();
            }
            if (!isAllowedImagePath(filePath)) {
                return ResponseEntity.badRequest().build();
            }
            Resource resource = new FileSystemResource(filePath);
            if (!resource.exists() || !resource.isReadable()) {
                return ResponseEntity.notFound().build();
            }
            String contentType = Files.probeContentType(filePath);
            if (!StringUtils.hasText(contentType)) {
                contentType = contentTypeByExtension(extension(decoded));
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CACHE_CONTROL, "max-age=3600")
                    .body(resource);
        } catch (Exception e) {
            log.warn("读取缺陷图片失败: {}", relativePath, e);
            return ResponseEntity.notFound().build();
        }
    }

    private Path resolveImagePath(String decoded) {
        try {
            Path root = Paths.get(defectImageDir).toAbsolutePath().normalize();
            Path configuredPath = root.resolve(decoded).normalize();
            if (configuredPath.startsWith(root) && Files.exists(configuredPath) && Files.isReadable(configuredPath)) {
                return configuredPath;
            }

            String imageUrl = "/api/v1/defect-images/file/" + decoded.replace('\\', '/');
            List<String> paths = jdbcTemplate.query(
                    "SELECT image_path FROM defect_image_file WHERE image_url = ? ORDER BY image_id DESC LIMIT 3",
                    new Object[]{imageUrl},
                    (rs, rowNum) -> rs.getString("image_path")
            );
            for (String path : paths) {
                if (!StringUtils.hasText(path)) continue;
                Path dbPath = Paths.get(path).toAbsolutePath().normalize();
                if (Files.exists(dbPath) && Files.isReadable(dbPath)) {
                    return dbPath;
                }
            }
        } catch (Exception e) {
            log.warn("解析缺陷图片路径失败: {}", decoded, e);
        }
        return null;
    }

    private boolean isAllowedImagePath(Path filePath) {
        if (filePath == null) return false;
        String normalized = filePath.toAbsolutePath().normalize().toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.contains("/defect-images/") || normalized.endsWith("/defect-images");
    }

    private void validateMetadata(DefectImageUploadMetadataDTO metadata) {
        if (metadata == null) {
            throw new IllegalArgumentException("metadata 不能为空");
        }
        if (!StringUtils.hasText(metadata.getCameraId())) {
            throw new IllegalArgumentException("metadata.cameraId 不能为空，请填写系统配置中的相机编号，并与请求头 X-Device-Id 保持一致");
        }
        if (metadata.getImages() == null || metadata.getImages().isEmpty()) {
            throw new IllegalArgumentException("metadata.images 不能为空");
        }
        if (metadata.getImages().size() > maxBatchImages) {
            throw new IllegalArgumentException("metadata.images 单批次最多支持 " + maxBatchImages + " 张图片，当前声明 " + metadata.getImages().size() + " 张；请拆分为多个批次后重试");
        }
        for (DefectImageUploadMetadataDTO.ImageMetadata image : metadata.getImages()) {
            if (!StringUtils.hasText(image.getFileName())) {
                throw new IllegalArgumentException("metadata.images[].fileName 不能为空");
            }
            if (image.getDefects() == null) {
                image.setDefects(Collections.emptyList());
            }
        }
    }

    private void validateFileNameMapping(MultipartFile[] files, Map<String, MultipartFile> fileMap,
                                         DefectImageUploadMetadataDTO metadata) {
        Set<String> metadataNames = new LinkedHashSet<>();
        Set<String> duplicateMetadataNames = new LinkedHashSet<>();
        for (DefectImageUploadMetadataDTO.ImageMetadata image : metadata.getImages()) {
            String key = fileNameKey(image.getFileName());
            if (!metadataNames.add(key)) {
                duplicateMetadataNames.add(cleanOriginalFileName(image.getFileName()));
            }
        }
        if (!duplicateMetadataNames.isEmpty()) {
            throw new IllegalArgumentException("metadata.images[].fileName 存在重复文件名: " + String.join(", ", duplicateMetadataNames));
        }

        List<String> uploadNames = Arrays.stream(files)
                .filter(f -> f != null && !f.isEmpty())
                .map(f -> cleanOriginalFileName(f.getOriginalFilename()))
                .collect(Collectors.toList());
        if (uploadNames.size() != fileMap.size()) {
            throw new IllegalArgumentException("multipart files 存在重复文件名，请确保每张图片文件名唯一: " + String.join(", ", uploadNames));
        }

        Set<String> uploadedNames = fileMap.keySet();
        List<String> missing = metadata.getImages().stream()
                .map(DefectImageUploadMetadataDTO.ImageMetadata::getFileName)
                .filter(name -> !uploadedNames.contains(fileNameKey(name)))
                .map(this::cleanOriginalFileName)
                .collect(Collectors.toList());
        List<String> extra = uploadNames.stream()
                .filter(name -> !metadataNames.contains(fileNameKey(name)))
                .collect(Collectors.toList());
        if (!missing.isEmpty() || !extra.isEmpty() || uploadedNames.size() != metadataNames.size()) {
            throw new IllegalArgumentException("上传文件与 metadata.images 必须一一对应；缺少 metadata 文件: "
                    + (missing.isEmpty() ? "无" : String.join(", ", missing))
                    + "；未在 metadata 声明的上传文件: "
                    + (extra.isEmpty() ? "无" : String.join(", ", extra)));
        }
    }

    private void cleanupFailedImage(Long batchId, Long imageId, SavedImage saved) {
        try {
            if (imageId != null) {
                jdbcTemplate.update("DELETE FROM defect_image_box WHERE image_id=?", imageId);
                jdbcTemplate.update("DELETE FROM defect_image_file WHERE image_id=?", imageId);
            } else if (batchId != null && saved != null && StringUtils.hasText(saved.absolutePath)) {
                jdbcTemplate.update("DELETE FROM defect_image_file WHERE batch_id=? AND image_path=?", batchId, saved.absolutePath);
            }
        } catch (Exception dbCleanupError) {
            log.warn("清理失败图片数据库残留失败: {}", dbCleanupError.getMessage());
        }
        try {
            if (saved != null && StringUtils.hasText(saved.absolutePath)) {
                Files.deleteIfExists(Paths.get(saved.absolutePath));
            }
        } catch (Exception fileCleanupError) {
            log.warn("清理失败图片文件残留失败: {}", fileCleanupError.getMessage());
        }
    }

    private void validateImageFile(MultipartFile file) {
        String original = cleanOriginalFileName(file.getOriginalFilename());
        String ext = extension(original);
        if (!IMAGE_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("不支持的图片格式: " + ext);
        }
    }

    private UploadFailure classifyUploadFailure(Exception e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.contains("fileName") || msg.contains("未匹配") || msg.contains("匹配")) {
            return new UploadFailure(
                    "FILE_NAME_MISMATCH",
                    "metadata 文件名与实际上传文件不一致",
                    "请确认 multipart 文件字段 files/file 中的原始文件名，与 metadata.images[].fileName 完全一致，包含扩展名。");
        }
        if (msg.contains("解析") || msg.contains("损坏") || msg.contains("内容")) {
            return new UploadFailure(
                    "IMAGE_CONTENT_INVALID",
                    "图片内容解析失败，可能是空文件、损坏文件或扩展名与内容不一致",
                    "请从设备端重新导出原始图片后上传；如仍失败，请检查传输过程是否截断或被压缩工具破坏。");
        }
        if (msg.contains("格式") || msg.contains("扩展")) {
            return new UploadFailure(
                    "UNSUPPORTED_IMAGE_FORMAT",
                    "图片格式不支持或扩展名缺失",
                    "请上传 jpg/jpeg/png/bmp/webp 等受支持图片，并确保扩展名与真实文件内容一致。");
        }
        if (msg.contains("尺寸") || msg.contains("占位")) {
            return new UploadFailure(
                    "IMAGE_SIZE_INVALID",
                    "图片尺寸异常，疑似无效占位图",
                    "请确认相机采集结果不是 1px/2px 占位图，重新采集后再上传。");
        }
        return new UploadFailure(
                "UPLOAD_FAILED",
                "图片上传处理失败",
                "请检查认证 Header、设备编号、metadata JSON、文件完整性和服务端上传目录权限。");
    }

    private SavedImage saveFile(MultipartFile file, String cameraId, LocalDateTime uploadTime) throws IOException {
        String original = cleanOriginalFileName(file.getOriginalFilename());
        String ext = extension(original);
        String day = uploadTime.format(DAY_FORMATTER);
        String cameraDir = sanitizePathSegment(firstNotBlank(cameraId, "unknown-camera"));
        String savedName = uploadTime.format(DateTimeFormatter.ofPattern("HHmmssSSS")) + "_" + UUID.randomUUID().toString().substring(0, 8) + "." + ext;
        Path relative = Paths.get(day, cameraDir, savedName);
        Path root = Paths.get(defectImageDir).toAbsolutePath().normalize();
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("非法文件路径");
        }
        Files.createDirectories(target.getParent());
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);

        Integer width = null;
        Integer height = null;
        try {
            BufferedImage img = ImageIO.read(target.toFile());
            if (img != null) {
                width = img.getWidth();
                height = img.getHeight();
            }
            if (img == null && IMAGEIO_REQUIRED_EXTENSIONS.contains(ext)) {
                throw new IOException("图片内容解析失败，请确认文件不是损坏图片且扩展名与内容一致: " + original);
            }
            if (width != null && height != null && (width <= 2 || height <= 2)) {
                throw new IOException("图片尺寸过小，疑似无效占位图: " + width + "x" + height);
            }
        } catch (IOException e) {
            Files.deleteIfExists(target);
            throw e;
        } catch (Exception e) {
            Files.deleteIfExists(target);
            throw new IOException("图片内容解析失败: " + original, e);
        }

        SavedImage saved = new SavedImage();
        saved.originalFileName = original;
        saved.fileName = savedName;
        saved.relativePath = relative.toString().replace('\\', '/');
        saved.absolutePath = target.toString();
        saved.imageUrl = "/api/v1/defect-images/file/" + saved.relativePath;
        saved.fileSize = file.getSize();
        saved.width = width;
        saved.height = height;
        return saved;
    }

    private Long insertBatch(String batchNo, String cameraId, String cameraName, String deviceId,
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

    private Long insertImageFile(Long batchId, String cameraId, String cameraName,
                                 DefectImageUploadMetadataDTO.ImageMetadata imageMeta,
                                 SavedImage saved, LocalDateTime captureTime, LocalDateTime uploadTime,
                                 DefectCounts counts) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO defect_image_file(batch_id, camera_id, camera_name, file_name, original_file_name, image_path, image_url, capture_time, upload_time, file_size, width, height, defect_count, high_count, medium_count, low_count, create_time) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW())",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setString(2, cameraId);
            ps.setString(3, cameraName);
            ps.setString(4, imageMeta.getFileName());
            ps.setString(5, saved.originalFileName);
            ps.setString(6, saved.absolutePath);
            ps.setString(7, saved.imageUrl);
            ps.setTimestamp(8, Timestamp.valueOf(captureTime));
            ps.setTimestamp(9, Timestamp.valueOf(uploadTime));
            ps.setLong(10, saved.fileSize == null ? 0L : saved.fileSize);
            if (saved.width == null) ps.setObject(11, null); else ps.setInt(11, saved.width);
            if (saved.height == null) ps.setObject(12, null); else ps.setInt(12, saved.height);
            ps.setInt(13, counts.total);
            ps.setInt(14, counts.high);
            ps.setInt(15, counts.medium);
            ps.setInt(16, counts.low);
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    private void insertDefectBoxes(Long batchId, Long imageId, List<DefectImageUploadMetadataDTO.DefectMetadata> defects) {
        if (defects == null || defects.isEmpty()) return;
        for (DefectImageUploadMetadataDTO.DefectMetadata defect : defects) {
            String severity = normalizeSeverity(defect.getSeverity());
            jdbcTemplate.update(
                    "INSERT INTO defect_image_box(image_id, batch_id, defect_type, severity, severity_level, x, y, width, height, area, create_time) VALUES(?,?,?,?,?,?,?,?,?,?,NOW())",
                    imageId,
                    batchId,
                    firstNotBlank(defect.getType(), "UNKNOWN"),
                    severity,
                    severityLevel(severity),
                    nvl(defect.getX()),
                    nvl(defect.getY()),
                    nvl(defect.getWidth()),
                    nvl(defect.getHeight()),
                    nvl(defect.getArea())
            );
        }
    }

    private void insertLegacyCameraImage(String cameraName, SavedImage saved, LocalDateTime captureTime) {
        try {
            CameraImage image = new CameraImage();
            image.setCameraName(cameraName);
            image.setImagePath(saved.absolutePath);
            image.setImageUrl(saved.imageUrl);
            image.setFileSize(saved.fileSize);
            image.setWidth(saved.width);
            image.setHeight(saved.height);
            image.setCaptureTime(captureTime);
            image.setExpireTime(captureTime.plusDays(30));
            image.setCreateTime(LocalDateTime.now());
            image.setUpdateTime(LocalDateTime.now());
            cameraImageMapper.insert(image);
        } catch (Exception e) {
            // 旧 camera_images 只作兼容缓存，失败不阻断新版缺陷图片主流程。
            log.warn("写入 camera_images 兼容表失败: {}", e.getMessage());
        }
    }

    private void updateBatchSummary(Long batchId, int successCount, int failCount, int defectCount,
                                    int highCount, int mediumCount, int lowCount, List<String> failureMessages) {
        String uploadStatus = failCount == 0 ? "SUCCESS" : (successCount > 0 ? "PARTIAL_FAILED" : "FAILED");
        String errorSummary = failureMessages == null || failureMessages.isEmpty()
                ? null
                : failureMessages.stream().limit(20).collect(Collectors.joining("; "));
        jdbcTemplate.update("UPDATE defect_image_batch SET image_count=?, success_count=?, fail_count=?, defect_count=?, high_count=?, medium_count=?, low_count=?, upload_status=?, error_summary=? WHERE batch_id=?",
                successCount, successCount, failCount, defectCount, highCount, mediumCount, lowCount, uploadStatus, errorSummary, batchId);
    }

    private void updateCameraLatestImage(String cameraId, String cameraName, String deviceId, String latestImageUrl, LocalDateTime uploadTime) {
        if (!StringUtils.hasText(latestImageUrl)) return;
        try {
            // ★ 2026-11-04 修正（命中不确定性）：
            //   原实现是一条无优先级的 OR 链 + LIMIT 1：
            //     camera_name = ? OR camera_code = ? OR camera_code = ?
            //   MySQL 在多条件命中时返回哪一行**没有确定顺序**，
            //   而 camera_name 在现场存在重名（CV1_Cam1 等各 2 行），
            //   一旦同时命中多行，就会写到**随机的那一行** ——
            //   表现为某台相机的最新图片/上传时间被别的相机覆盖。
            //
            //   改为与心跳通道一致的口径：**camera_code 优先，名称仅兜底**。
            CameraStatus existing = null;

            String codeCandidate = firstNotBlank(cameraId, deviceId);
            if (StringUtils.hasText(codeCandidate)) {
                existing = cameraStatusMapper.selectOne(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CameraStatus>()
                                .eq(CameraStatus::getCameraCode, codeCandidate)
                                .last("LIMIT 1")
                );
            }

            if (existing == null && StringUtils.hasText(cameraName)) {
                // 名称兜底：只在名称唯一时使用，避免重名时写到错误的行
                List<CameraStatus> byName = cameraStatusMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CameraStatus>()
                                .eq(CameraStatus::getCameraName, cameraName)
                );
                if (byName.size() == 1) {
                    existing = byName.get(0);
                } else if (byName.size() > 1) {
                    log.warn("相机名称 {} 有 {} 条配置，跳过最新图片更新以避免串号。cameraCode={}",
                            cameraName, byName.size(), codeCandidate);
                }
            }

            if (existing != null) {
                LocalDateTime existingLastUploadAt = existing.getLastUploadAt();
                boolean shouldRefreshLatest = existingLastUploadAt == null
                        || uploadTime == null
                        || !uploadTime.isBefore(existingLastUploadAt);
                if (shouldRefreshLatest) {
                    existing.setLatestImagePath(latestImageUrl);
                    existing.setLastUploadAt(uploadTime);
                }
                existing.setUpdateTime(LocalDateTime.now());
                if (!StringUtils.hasText(existing.getCameraCode())) {
                    existing.setCameraCode(firstNotBlank(cameraId, deviceId));
                }
                cameraStatusMapper.updateById(existing);
            } else {
                log.warn("缺陷图片来自未配置相机，跳过写入相机配置表: cameraId={}, deviceId={}, cameraName={}",
                        cameraId, deviceId, cameraName);
            }
        } catch (Exception e) {
            log.warn("更新相机最新图片失败: {}", e.getMessage());
        }
    }

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
                                    LocalDateTime startTime, LocalDateTime endTime) {
        if (StringUtils.hasText(cameraId)) {
            sql.append("AND f.camera_id = ? ");
            params.add(cameraId);
        }
        if (StringUtils.hasText(cameraName)) {
            sql.append("AND f.camera_name = ? ");
            params.add(cameraName);
        }
        appendDimensionFilters(sql, params, factoryId, lineId, stationId, keyword);
        if (StringUtils.hasText(severity)) {
            sql.append("AND EXISTS (SELECT 1 FROM defect_image_box bs WHERE bs.image_id = f.image_id AND UPPER(bs.severity) = ?) ");
            params.add(severity.trim().toUpperCase(Locale.ROOT));
        }
        if (StringUtils.hasText(defectType)) {
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
        if (!StringUtils.hasText(keyword)) {
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
        if (!StringUtils.hasText(keyword)) {
            return;
        }
        String like = "%" + keyword.trim() + "%";
        sql.append("AND (f.camera_id LIKE ? OR f.camera_name LIKE ? OR f.file_name LIKE ? OR f.original_file_name LIKE ? ")
                .append("OR EXISTS (SELECT 1 FROM defect_image_box bkw WHERE bkw.image_id = f.image_id AND bkw.defect_type LIKE ?)) ");
        params.add(like); params.add(like); params.add(like); params.add(like);
        params.add(like);
    }

    private Map<String, Object> mapImageRow(ResultSet rs) throws SQLException {
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
        item.put("stationLabel", formatStationLabel(rs.getString("station_code"), rs.getString("station_name")));
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

    private List<Map<String, Object>> listDefects(Long imageId) {
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
                }
        );
    }

    private DefectCounts countDefects(List<DefectImageUploadMetadataDTO.DefectMetadata> defects) {
        DefectCounts counts = new DefectCounts();
        if (defects == null) return counts;
        counts.total = defects.size();
        for (DefectImageUploadMetadataDTO.DefectMetadata defect : defects) {
            String severity = normalizeSeverity(defect.getSeverity());
            if ("HIGH".equals(severity)) counts.high++;
            else if ("MEDIUM".equals(severity)) counts.medium++;
            else counts.low++;
        }
        return counts;
    }

    private String normalizeSeverity(String severity) {
        if (!StringUtils.hasText(severity)) return "LOW";
        String s = severity.trim().toUpperCase(Locale.ROOT);
        if ("HIGH".equals(s) || "严重".equals(s) || "高".equals(s)) return "HIGH";
        if ("MEDIUM".equals(s) || "MAJOR".equals(s) || "中".equals(s) || "重大".equals(s)) return "MEDIUM";
        return "LOW";
    }

    private int severityLevel(String severity) {
        if ("HIGH".equals(severity)) return 3;
        if ("MEDIUM".equals(severity)) return 2;
        return 1;
    }

    private LocalDateTime parseTime(String value, LocalDateTime fallback) {
        if (!StringUtils.hasText(value)) return fallback;
        String v = value.trim();
        try {
            if (v.contains("T")) {
                return LocalDateTime.parse(v.replace("Z", ""));
            }
            if (v.contains(".")) {
                return LocalDateTime.parse(v, MILLIS_FORMATTER);
            }
            return LocalDateTime.parse(v, SECOND_FORMATTER);
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }

    private String firstNotBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (StringUtils.hasText(value)) return value.trim();
        }
        return null;
    }

    private String normalizeMetadataJson(String metadataJson) {
        String text = metadataJson == null ? "" : metadataJson.trim();
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1).trim();
        }
        return text;
    }

    private String formatStationLabel(String code, String name) {
        if (StringUtils.hasText(code) && StringUtils.hasText(name)) {
            String c = code.trim();
            String n = name.trim();
            String lowerCode = c.toLowerCase(Locale.ROOT);
            String lowerName = n.toLowerCase(Locale.ROOT);
            if (lowerName.equals(lowerCode)
                    || lowerName.startsWith(lowerCode)
                    || lowerName.startsWith(lowerCode + "-")
                    || lowerName.startsWith(lowerCode + "_")
                    || lowerName.startsWith(lowerCode + "/")) {
                return n;
            }
            return c + " " + n;
        }
        String label = firstNotBlank(name, code);
        return StringUtils.hasText(label) ? label : "未配置工位";
    }

    private String cleanOriginalFileName(String fileName) {
        if (!StringUtils.hasText(fileName)) return "unknown.jpg";
        String normalized = fileName.replace('\\', '/');
        int idx = normalized.lastIndexOf('/');
        return idx >= 0 ? normalized.substring(idx + 1) : normalized;
    }

    private String fileNameKey(String fileName) {
        return cleanOriginalFileName(fileName).trim().toLowerCase(Locale.ROOT);
    }

    private String extension(String fileName) {
        String clean = cleanOriginalFileName(fileName);
        int idx = clean.lastIndexOf('.');
        if (idx < 0 || idx == clean.length() - 1) return "jpg";
        return clean.substring(idx + 1).toLowerCase(Locale.ROOT);
    }

    private String contentTypeByExtension(String ext) {
        if ("png".equals(ext)) return "image/png";
        if ("jpg".equals(ext) || "jpeg".equals(ext)) return "image/jpeg";
        if ("bmp".equals(ext)) return "image/bmp";
        if ("gif".equals(ext)) return "image/gif";
        if ("webp".equals(ext)) return "image/webp";
        if ("svg".equals(ext)) return "image/svg+xml";
        return "application/octet-stream";
    }

    private String sanitizePathSegment(String input) {
        return input == null ? "unknown" : input.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private double nvl(Double value) {
        return value == null ? 0D : value;
    }

    private LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    /**
     * 表结构自检。
     *
     * <p><b>2026-11-04 性能修复</b>：原实现在<b>每次</b>调用
     * {@code listRecent / listCameraSummaries / listHistoryPage / upload} 时都执行
     * <ul>
     *   <li>3 条 {@code CREATE TABLE IF NOT EXISTS}（DDL！）；</li>
     *   <li>5 条 {@code ensureColumn}（查 INFORMATION_SCHEMA）；</li>
     *   <li>6 条 {@code ensureIndex}（查 INFORMATION_SCHEMA）。</li>
     * </ul>
     * 每分钟被前端轮询触发若干次 → 每分钟几十条 DDL 与元数据查询。
     *
     * <p>DDL 会拿 InnoDB 的<b>元数据锁（MDL）</b>，与慢查询叠加时
     * 会把整个表的所有查询排队 —— 实测曾出现单条 7.4 秒的 DDL。
     *
     * <p>修复：改成<b>按需 + 限频</b>：
     * <ul>
     *   <li>应用启动时（{@code @PostConstruct}）已完整执行过一次；</li>
     *   <li>运行期只做<b>只读</b>的存在性检查（INFORMATION_SCHEMA 查询，毫秒级），</li>
     *   <li>检查结果用时间戳缓存，默认 5 分钟内不重复检查。</li>
     * </ul>
     * 表被人为删除时，最多 5 分钟内自愈，且期间不会产生 DDL 风暴。
     */
    private void ensureSchema() {
        // 运行期只做限频的存在性检查；启动时（init()）走完整流程。
        if (schemaChecked && (System.currentTimeMillis() - lastSchemaCheckAt) < SCHEMA_CHECK_INTERVAL_MS) {
            return;
        }
        synchronized (this) {
            if (schemaChecked && (System.currentTimeMillis() - lastSchemaCheckAt) < SCHEMA_CHECK_INTERVAL_MS) {
                return;
            }
            try {
                doEnsureSchema();
                schemaChecked = true;
                lastSchemaCheckAt = System.currentTimeMillis();
            } catch (Exception e) {
                schemaChecked = false;
                log.warn("表结构自检失败: {}", e.getMessage());
            }
        }
    }

    private void doEnsureSchema() {
        createTables();
        ensureColumn("camera_status", "last_upload_at", "ALTER TABLE camera_status ADD COLUMN last_upload_at DATETIME NULL COMMENT '最后缺陷图片上传时间'");
        ensureColumn("defect_image_batch", "declared_image_count", "ALTER TABLE defect_image_batch ADD COLUMN declared_image_count INT DEFAULT 0 COMMENT 'metadata声明图片数'");
        ensureColumn("defect_image_batch", "success_count", "ALTER TABLE defect_image_batch ADD COLUMN success_count INT DEFAULT 0 COMMENT '成功入库图片数'");
        ensureColumn("defect_image_batch", "fail_count", "ALTER TABLE defect_image_batch ADD COLUMN fail_count INT DEFAULT 0 COMMENT '失败图片数'");
        ensureColumn("defect_image_batch", "upload_status", "ALTER TABLE defect_image_batch ADD COLUMN upload_status VARCHAR(30) DEFAULT 'SUCCESS' COMMENT '上传状态'");
        ensureColumn("defect_image_batch", "error_summary", "ALTER TABLE defect_image_batch ADD COLUMN error_summary TEXT NULL COMMENT '失败摘要'");
        ensureIndexes();
    }

    private void createTables() {
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

    private void ensureColumn(String tableName, String columnName, String alterSql) {
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

    private void ensureIndexes() {
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

    private void ensureIndex(String tableName, String indexName, String alterSql) {
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

    private static class SavedImage {
        String originalFileName;
        String fileName;
        String relativePath;
        String absolutePath;
        String imageUrl;
        Long fileSize;
        Integer width;
        Integer height;
    }

    private static class DefectCounts {
        int total;
        int high;
        int medium;
        int low;
    }

    private static class UploadFailure {
        final String code;
        final String reason;
        final String advice;

        UploadFailure(String code, String reason, String advice) {
            this.code = code;
            this.reason = reason;
            this.advice = advice;
        }
    }
}
