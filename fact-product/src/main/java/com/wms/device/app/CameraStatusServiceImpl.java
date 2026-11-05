package com.wms.device.app;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wms.analytics.infra.repository.AnomalyAlertRepository;
import com.wms.device.api.dto.CameraHeartbeatResponseDTO;
import com.wms.device.api.dto.CameraStatusHeartbeatDTO;
import com.wms.device.domain.model.CameraStatus;
import com.wms.device.infra.mapper.CameraStatusMapper;
import com.wms.device.infra.repository.CameraDirectoryRepository;
import com.wms.device.app.ICameraStatusService;
import com.wms.system.infra.repository.SystemSettingsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 相机状态服务实现。
 */
@Service
@Slf4j
public class CameraStatusServiceImpl implements ICameraStatusService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter MILLIS_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final String CONFIG_VERSION = "v1.0.0";

    @Autowired
    private CameraStatusMapper cameraStatusMapper;

    /** DDD P3 批次③：SQL 已下沉到各域 Repository，本类只保留业务编排 */
    @Autowired
    private CameraDirectoryRepository cameraDirectoryRepository;
    @Autowired
    private SystemSettingsRepository systemSettingsRepository;
    @Autowired
    private AnomalyAlertRepository anomalyAlertRepository;

    private final Map<String, CameraStatusCache> statusCache = new ConcurrentHashMap<>();

    private static class CameraStatusCache {
        Integer recognizeCount;
        Integer correctCount;
        Integer abnormalCount;

        CameraStatusCache(Integer recognizeCount, Integer correctCount, Integer abnormalCount) {
            this.recognizeCount = recognizeCount;
            this.correctCount = correctCount;
            this.abnormalCount = abnormalCount;
        }
    }

    @PostConstruct
    public void initSchema() {
        ensureCameraStatusColumns();
    }

    @Override
    public CameraHeartbeatResponseDTO processHeartbeat(CameraStatusHeartbeatDTO dto, String deviceId) {
        try {
            if (dto.getCameraStatus() != null && (dto.getCameraStatus() < 0 || dto.getCameraStatus() > 3)) {
                throw new IllegalArgumentException("无效的相机状态: " + dto.getCameraStatus());
            }

            if (Boolean.TRUE.equals(dto.getResetFlag())) {
                clearCameraCountCache(dto.getCameraName());
            }

            String cameraName = dto.getCameraName();
            CameraStatusCache cache = statusCache.get(cameraName);
            if (cache != null && dto.getRecognizeCount() != null && dto.getRecognizeCount() < cache.recognizeCount) {
                log.info("检测到 {} 计数器重置，old={}, new={}", cameraName, cache.recognizeCount, dto.getRecognizeCount());
            }
            if (dto.getRecognizeCount() != null) {
                statusCache.put(cameraName, new CameraStatusCache(
                        dto.getRecognizeCount(),
                        dto.getCorrectCount() != null ? dto.getCorrectCount() : 0,
                        dto.getAbnormalCount() != null ? dto.getAbnormalCount() : 0
                ));
            }

            saveCameraStatus(dto, deviceId);
            checkAndAlert(dto, deviceId);
            return buildResponse();
        } catch (IllegalArgumentException e) {
            log.warn("参数校验失败: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("处理心跳异常", e);
            throw new RuntimeException("处理心跳失败: " + e.getMessage(), e);
        }
    }

    private void saveCameraStatus(CameraStatusHeartbeatDTO dto, String deviceId) {
        ensureCameraStatusColumns();
        LocalDateTime reportTime = parseReportTime(dto.getTimestamp());

        CameraStatus status = new CameraStatus();
        status.setCameraName(dto.getCameraName());
        status.setCameraCode(deviceId);
        status.setStatus(mapStatusInt(dto.getCameraStatus()));
        status.setProductName(dto.getProductName());
        status.setReportTime(reportTime);
        status.setLastHeartbeatAt(reportTime);
        status.setCpuUsage(dto.getCpuUsage() != null ? BigDecimal.valueOf(dto.getCpuUsage()) : null);
        status.setMemoryUsage(dto.getMemoryUsage() != null ? BigDecimal.valueOf(dto.getMemoryUsage()) : null);
        status.setTemperature(dto.getTemperature() != null ? BigDecimal.valueOf(dto.getTemperature()) : null);
        status.setTotalCount(dto.getRecognizeCount());
        status.setOkCount(dto.getCorrectCount());
        status.setNgCount(dto.getAbnormalCount());
        status.setRecognizeCount(dto.getRecognizeCount());
        status.setCorrectCount(dto.getCorrectCount());
        status.setAbnormalCount(dto.getAbnormalCount());
        status.setErrorMessage(dto.getErrorMessage());
        status.setIsActive(1);
        status.setCreateTime(LocalDateTime.now());
        status.setUpdateTime(LocalDateTime.now());
        if (dto.getRecognizeCount() != null && dto.getRecognizeCount() > 0 && dto.getCorrectCount() != null) {
            status.setYieldRate(BigDecimal.valueOf(dto.getCorrectCount() * 100.0 / dto.getRecognizeCount()).setScale(2, RoundingMode.HALF_UP));
        }

        // ★ 2026-11-04 修正（数据串号隐患）：
        //   原实现**先按 camera_name 匹配、编号兜底**，而 camera_name 在现场并不唯一
        //   （实测 camera_status 里 CV1_Cam1 / CV2_Cam1 各有 2 行，分别属于
        //   杭州临平一期 ZK54 与二期 ZK23 两套工位）。
        //   只要设备上报的名称与配置名一致，LIMIT 1 就会把心跳写进**另一台相机**的行 ——
        //   表现为该相机的心跳时间/计数被另一台设备覆盖，现场极难察觉。
        //
        //   camera_code 上有唯一索引 uk_camera_code，是设备的天然身份标识，
        //   因此改为：**编号优先，名称仅在无编号时兜底**。
        //   这与同文件的 findCameraForAlert（已是 code-first）口径一致。
        CameraStatus existing = null;
        boolean matchedByName = false;

        if (StringUtils.hasText(deviceId)) {
            existing = cameraStatusMapper.selectOne(
                    new LambdaQueryWrapper<CameraStatus>()
                            .eq(CameraStatus::getCameraCode, deviceId)
                            .last("LIMIT 1")
            );
        }

        if (existing == null && StringUtils.hasText(dto.getCameraName())) {
            // 名称兜底：仅当名称唯一时才敢用。命中多行说明配置有重名，
            // 此时无法判断该写哪一台 —— 宁可拒绝也不静默写错行。
            List<CameraStatus> byName = cameraStatusMapper.selectList(
                    new LambdaQueryWrapper<CameraStatus>()
                            .eq(CameraStatus::getCameraName, dto.getCameraName())
            );
            if (byName.size() > 1) {
                log.warn("相机名称 {} 在系统中存在 {} 条配置，无法唯一定位，拒绝写入以避免串号。请补齐设备编号或修正重名。",
                        dto.getCameraName(), byName.size());
                throw new IllegalArgumentException(
                        "相机名称重复，无法确定目标设备: " + dto.getCameraName()
                                + "（请改用设备编号上报，或在配置中修正重名）");
            }
            if (byName.size() == 1) {
                existing = byName.get(0);
                matchedByName = true;
            }
        }

        if (existing != null) {
            status.setId(existing.getId());
            // Heartbeat only updates runtime metrics. Keep the camera code and hierarchy from system configuration.
            status.setCameraCode(StringUtils.hasText(existing.getCameraCode()) ? existing.getCameraCode() : deviceId);
            status.setLineId(existing.getLineId());
            status.setWorkstationId(existing.getWorkstationId());
            if (!matchedByName) {
                status.setCameraName(existing.getCameraName());
            }
            cameraStatusMapper.updateById(status);
        } else {
            throw new IllegalArgumentException("设备未在系统配置中维护: " + deviceId);
        }
    }

    private void checkAndAlert(CameraStatusHeartbeatDTO dto, String deviceId) {
        CameraStatus camera = findCameraForAlert(dto, deviceId);
        Integer lineId = camera != null && camera.getLineId() != null ? camera.getLineId() : 0;
        Integer workstationId = camera != null && camera.getWorkstationId() != null ? camera.getWorkstationId() : 0;
        String cameraCode = camera != null && StringUtils.hasText(camera.getCameraCode()) ? camera.getCameraCode() : deviceId;
        String cameraName = camera != null && StringUtils.hasText(camera.getCameraName()) ? camera.getCameraName() : dto.getCameraName();

        if (dto.getCameraStatus() != null && dto.getCameraStatus() == 2) {
            log.warn("【告警】相机 {} 故障: {}", dto.getCameraName(), dto.getErrorMessage());
            insertAnomalyAlert(lineId, workstationId, "CAMERA_FAULT", "CRITICAL",
                    "相机故障",
                    "相机 " + safe(cameraName) + "(" + safe(cameraCode) + ") 上报故障状态；故障信息：" + safe(dto.getErrorMessage()));
        }
        if (dto.getRecognizeCount() != null && dto.getCorrectCount() != null && dto.getAbnormalCount() != null) {
            int diff = Math.abs(dto.getRecognizeCount() - (dto.getCorrectCount() + dto.getAbnormalCount()));
            if (diff > 1) {
                log.warn("【告警】相机 {} 计数逻辑异常: recognize={}, correct={}, abnormal={}, diff={}",
                        dto.getCameraName(), dto.getRecognizeCount(), dto.getCorrectCount(), dto.getAbnormalCount(), diff);
                insertAnomalyAlert(lineId, workstationId, "COUNT_MISMATCH", "WARNING",
                        "相机计数异常",
                        "相机 " + safe(cameraName) + "(" + safe(cameraCode) + ") 识别总数与 OK/缺陷数不一致，差值：" + diff);
            }
        }
        double cpuWarning = getDecimalSetting("alert.cpuWarning", 90D);
        double memoryWarning = getDecimalSetting("alert.memoryWarning", 90D);
        double temperatureWarning = getDecimalSetting("alert.temperatureWarning", 70D);
        if (dto.getCpuUsage() != null && dto.getCpuUsage() > cpuWarning) {
            log.warn("【预警】相机 {} CPU 使用率过高: {}%", dto.getCameraName(), dto.getCpuUsage());
            insertAnomalyAlert(lineId, workstationId, "CPU_HIGH", "WARNING",
                    "相机CPU过高",
                    "相机 " + safe(cameraName) + "(" + safe(cameraCode) + ") CPU 使用率 " + dto.getCpuUsage() + "%，超过阈值 " + cpuWarning + "%。建议检查算法进程、设备散热和后台任务。");
        }
        if (dto.getMemoryUsage() != null && dto.getMemoryUsage() > memoryWarning) {
            log.warn("【预警】相机 {} 内存使用率过高: {}%", dto.getCameraName(), dto.getMemoryUsage());
            insertAnomalyAlert(lineId, workstationId, "MEMORY_HIGH", "WARNING",
                    "相机内存过高",
                    "相机 " + safe(cameraName) + "(" + safe(cameraCode) + ") 内存使用率 " + dto.getMemoryUsage() + "%，超过阈值 " + memoryWarning + "%。建议检查内存泄漏、缓存堆积和服务重启策略。");
        }
        if (dto.getTemperature() != null && dto.getTemperature() > temperatureWarning) {
            log.warn("【预警】相机 {} 温度过高: {}℃", dto.getCameraName(), dto.getTemperature());
            insertAnomalyAlert(lineId, workstationId, "TEMPERATURE_HIGH", "WARNING",
                    "相机温度过高",
                    "相机 " + safe(cameraName) + "(" + safe(cameraCode) + ") 设备温度 " + dto.getTemperature() + "℃，超过阈值 " + temperatureWarning + "℃。建议检查机柜散热、风扇和环境温度。");
        }
    }

    private CameraStatus findCameraForAlert(CameraStatusHeartbeatDTO dto, String deviceId) {
        try {
            if (StringUtils.hasText(deviceId)) {
                CameraStatus camera = cameraStatusMapper.selectOne(new LambdaQueryWrapper<CameraStatus>()
                        .eq(CameraStatus::getCameraCode, deviceId)
                        .last("LIMIT 1"));
                if (camera != null) return camera;
            }
            if (dto != null && StringUtils.hasText(dto.getCameraName())) {
                return cameraStatusMapper.selectOne(new LambdaQueryWrapper<CameraStatus>()
                        .eq(CameraStatus::getCameraName, dto.getCameraName())
                        .last("LIMIT 1"));
            }
        } catch (Exception e) {
            log.warn("查询相机告警上下文失败: {}", e.getMessage());
        }
        return null;
    }

    private double getDecimalSetting(String key, double defaultValue) {
        try {
            String value = systemSettingsRepository.findSettingValue(key);
            if (StringUtils.hasText(value)) {
                return Double.parseDouble(value.trim());
            }
        } catch (Exception e) {
            log.debug("读取系统设置 {} 失败，使用默认值 {}", key, defaultValue);
        }
        return defaultValue;
    }

    private void insertAnomalyAlert(Integer lineId, Integer workstationId, String alertType, String alertLevel,
                                    String alertTitle, String alertMessage) {
        try {
            anomalyAlertRepository.insertCameraAlert(lineId, workstationId, alertType, alertLevel,
                    alertTitle, alertMessage);
        } catch (DataAccessException e) {
            log.warn("写入相机异常告警失败: type={}, title={}, error={}", alertType, alertTitle, e.getMessage());
        }
    }

    private String safe(String value) {
        return StringUtils.hasText(value) ? value : "-";
    }

    private CameraHeartbeatResponseDTO buildResponse() {
        CameraHeartbeatResponseDTO response = new CameraHeartbeatResponseDTO();
        response.setServerTime(LocalDateTime.now().format(DATE_TIME_FORMATTER));
        response.setNextInterval(5000);
        response.setConfigVersion(CONFIG_VERSION);
        return response;
    }

    private void clearCameraCountCache(String cameraName) {
        statusCache.remove(cameraName);
        log.info("已清除相机 {} 的计数缓存", cameraName);
    }

    private String mapStatusInt(Integer statusCode) {
        if (statusCode == null) return "OFFLINE";
        switch (statusCode) {
            case 0: return "STOPPED";
            case 1: return "RUNNING";
            case 2: return "FAULT";
            case 3: return "STANDBY";
            default: return "OFFLINE";
        }
    }

    private String mapStatusParam(Integer statusCode) {
        if (statusCode == null) return null;
        switch (statusCode) {
            case 0: return "STOPPED";
            case 1: return "RUNNING";
            case 2: return "FAULT";
            case 3: return "STANDBY";
            case 9: return "OFFLINE";
            default: return String.valueOf(statusCode);
        }
    }

    private LocalDateTime parseReportTime(String timestamp) {
        if (!StringUtils.hasText(timestamp)) return LocalDateTime.now();
        try {
            String value = timestamp.trim();
            if (value.contains(".")) return LocalDateTime.parse(value, MILLIS_FORMATTER);
            if (value.contains("T")) return LocalDateTime.parse(value.replace("Z", ""));
            return LocalDateTime.parse(value, DATE_TIME_FORMATTER);
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    @Override
    public IPage<CameraStatus> pageQuery(Integer pageNum, Integer pageSize, String keyword, Integer status, Set<Integer> workstationIds) {
        LambdaQueryWrapper<CameraStatus> wrapper = new LambdaQueryWrapper<>();
        // ★ 2026-11-02 新增：过滤已逻辑删除（is_active=0）的相机。
        //   删除改为逻辑删除后，若不过滤，被删的相机会继续出现在列表里，
        //   但它的映射与令牌已被级联清理 —— 用户会看到一个"半死不活"的条目。
        //   与 dim_camera.is_deleted 保持同一口径。
        wrapper.eq(CameraStatus::getIsActive, 1);
        if (workstationIds != null && !workstationIds.isEmpty()) {
            wrapper.in(CameraStatus::getWorkstationId, workstationIds);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(CameraStatus::getCameraName, keyword).or().like(CameraStatus::getCameraCode, keyword));
        }
        if (status != null) {
            wrapper.eq(CameraStatus::getStatus, mapStatusParam(status));
        }
        wrapper.orderByDesc(CameraStatus::getLastHeartbeatAt).orderByDesc(CameraStatus::getUpdateTime);
        return cameraStatusMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
    }

    @Override
    public Map<String, Object> getStats() {
        List<Map<String, Object>> rows = cameraStatusMapper.countByStatus();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", 0);
        stats.put("running", 0);
        stats.put("stopped", 0);
        stats.put("fault", 0);
        stats.put("standby", 0);
        stats.put("offline", 0);

        for (Map<String, Object> row : rows) {
            String s = String.valueOf(row.get("status"));
            int c = ((Number) row.get("count")).intValue();
            String su = s.toUpperCase();
            if (su.contains("RUN") || su.equals("ONLINE") || su.equals("1")) stats.put("running", ((Integer) stats.get("running")) + c);
            else if (su.contains("STOP") || su.equals("0")) stats.put("stopped", ((Integer) stats.get("stopped")) + c);
            else if (su.contains("FAULT") || su.equals("2")) stats.put("fault", ((Integer) stats.get("fault")) + c);
            else if (su.contains("STANDBY") || su.contains("PAUSED") || su.equals("3")) stats.put("standby", ((Integer) stats.get("standby")) + c);
            else stats.put("offline", ((Integer) stats.get("offline")) + c);
        }

        List<CameraStatus> all = cameraStatusMapper.selectList(null);
        stats.put("total", all.size());
        stats.put("accuracyRate", 0);
        return stats;
    }

    @Override
    public List<Map<String, Object>> getRecognizeStats() {
        return cameraStatusMapper.getRecognizeStats();
    }

    @Override
    public List<CameraStatus> listAll() {
        return cameraStatusMapper.selectList(null);
    }

    private void ensureCameraStatusColumns() {
        ensureColumn("product_name", "ALTER TABLE camera_status ADD COLUMN product_name VARCHAR(100) NULL COMMENT '当前生产产品'");
        ensureColumn("report_time", "ALTER TABLE camera_status ADD COLUMN report_time DATETIME NULL COMMENT '设备上报时间'");
        ensureColumn("recognize_count", "ALTER TABLE camera_status ADD COLUMN recognize_count INT DEFAULT 0 COMMENT '识别总数累计值'");
        ensureColumn("correct_count", "ALTER TABLE camera_status ADD COLUMN correct_count INT DEFAULT 0 COMMENT '识别正确数'");
        ensureColumn("abnormal_count", "ALTER TABLE camera_status ADD COLUMN abnormal_count INT DEFAULT 0 COMMENT '识别异常数'");
        ensureColumn("error_message", "ALTER TABLE camera_status ADD COLUMN error_message VARCHAR(500) NULL COMMENT '故障信息'");
        ensureColumn("last_upload_at", "ALTER TABLE camera_status ADD COLUMN last_upload_at DATETIME NULL COMMENT '最后缺陷图片上传时间'");
    }

    private void ensureColumn(String columnName, String alterSql) {
        // DDD P3 批次③：SQL 下沉到 CameraDirectoryRepository
        // （INFORMATION_SCHEMA 判断 + 失败记 WARN 继续的语义在 Repository 内保留）
        cameraDirectoryRepository.ensureCameraStatusColumn(columnName, alterSql);
    }
}
