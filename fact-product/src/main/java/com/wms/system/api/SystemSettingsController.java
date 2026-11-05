package com.wms.system.api;

import com.wms.shared.common.ResultVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统设置中心 API。
 *
 * 说明：运行参数先落到 sys_system_setting；数据保留策略继续复用
 * sys_data_retention_policy，避免重复维护同一类配置。
 */
@RestController
@RequestMapping("/api/v1/system/settings")
public class SystemSettingsController {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void init() {
        ensureSettingTable();
        seedDefaultSettings();
        repairCorruptedDefaultSettings();
        seedDefaultRetentionPolicies();
    }

    @GetMapping
    public ResultVO<Map<String, Object>> getSettings() {
        ensureSettingTable();
        Map<String, Object> data = new HashMap<>();
        List<Map<String, Object>> settings = jdbcTemplate.queryForList(
                "SELECT setting_id settingId, setting_key settingKey, setting_name settingName, setting_group settingGroup, " +
                        "setting_value settingValue, value_type valueType, unit, description, is_editable isEditable, sort_order sortOrder, update_time updateTime " +
                        "FROM sys_system_setting ORDER BY setting_group, sort_order, setting_id");
        List<Map<String, Object>> policies = jdbcTemplate.queryForList(
                "SELECT policy_id policyId, data_type dataType, retention_days retentionDays, action_type actionType, is_enabled isEnabled, update_time updateTime " +
                        "FROM sys_data_retention_policy ORDER BY policy_id");
        data.put("settings", settings);
        data.put("retentionPolicies", policies);
        data.put("groups", buildGroups(settings));
        data.put("summary", buildSummary(settings, policies));
        return ResultVO.success(data);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping
    @SuppressWarnings("unchecked")
    @Transactional
    public ResultVO<Map<String, Object>> saveSettings(@RequestBody Map<String, Object> body) {
        ensureSettingTable();
        if (body == null) {
            return ResultVO.fail(422, "系统设置保存失败：请求体不能为空", null);
        }

        Map<String, String> proposedSettings = loadCurrentSettingValues();
        List<String> validationErrors = new ArrayList<>();

        Object settingsObjForValidate = body.get("settings");
        if (settingsObjForValidate instanceof List) {
            List<?> settings = (List<?>) settingsObjForValidate;
            for (Object item : settings) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> row = (Map<String, Object>) item;
                String key = str(row.get("settingKey"));
                String value = str(row.get("settingValue"));
                if (key == null || key.trim().isEmpty()) continue;
                proposedSettings.put(key.trim(), value == null ? "" : value.trim());
            }
        }

        validateSettings(proposedSettings, validationErrors);
        validateRetentionPolicies(body.get("retentionPolicies"), validationErrors);
        if (!validationErrors.isEmpty()) {
            Map<String, Object> errorData = new HashMap<>();
            errorData.put("errors", validationErrors);
            return ResultVO.fail(422, "系统设置保存失败，请修正参数后重试", errorData);
        }

        int settingUpdated = 0;
        int policyUpdated = 0;

        Object settingsObj = body.get("settings");
        if (settingsObj instanceof List) {
            List<?> settings = (List<?>) settingsObj;
            for (Object item : settings) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> row = (Map<String, Object>) item;
                String key = str(row.get("settingKey"));
                String value = str(row.get("settingValue"));
                if (key == null || key.trim().isEmpty()) continue;
                settingUpdated += jdbcTemplate.update(
                        "UPDATE sys_system_setting SET setting_value=?, update_time=NOW() WHERE setting_key=? AND is_editable=1",
                        value, key);
            }
        }

        Object policiesObj = body.get("retentionPolicies");
        if (policiesObj instanceof List) {
            List<?> policies = (List<?>) policiesObj;
            for (Object item : policies) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> row = (Map<String, Object>) item;
                String dataType = str(row.get("dataType"));
                Integer days = intValue(row.get("retentionDays"));
                String action = str(row.get("actionType"));
                Integer enabled = intValue(row.get("isEnabled"));
                if (dataType == null || dataType.trim().isEmpty() || days == null || days < 1) continue;
                if (action == null || action.trim().isEmpty()) action = "DELETE";
                if (enabled == null) enabled = 1;
                policyUpdated += jdbcTemplate.update(
                        "UPDATE sys_data_retention_policy SET retention_days=?, action_type=?, is_enabled=?, update_time=NOW() WHERE data_type=?",
                        days, action, enabled, dataType);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("settingUpdated", settingUpdated);
        result.put("policyUpdated", policyUpdated);
        return ResultVO.success(result, "系统设置已保存");
    }

    private void ensureSettingTable() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS sys_system_setting (" +
                "setting_id BIGINT PRIMARY KEY AUTO_INCREMENT," +
                "setting_key VARCHAR(100) NOT NULL," +
                "setting_name VARCHAR(100) NOT NULL," +
                "setting_group VARCHAR(50) NOT NULL," +
                "setting_value VARCHAR(500) NOT NULL," +
                "value_type VARCHAR(20) NOT NULL DEFAULT 'STRING'," +
                "unit VARCHAR(20) NULL," +
                "description VARCHAR(500) NULL," +
                "is_editable TINYINT NOT NULL DEFAULT 1," +
                "sort_order INT NOT NULL DEFAULT 0," +
                "update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                "UNIQUE KEY uk_setting_key (setting_key)," +
                "KEY idx_setting_group (setting_group)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统设置中心'");
        try {
            jdbcTemplate.execute("ALTER TABLE sys_system_setting MODIFY setting_value TEXT NOT NULL");
        } catch (Exception ignored) {
            // 兼容已经是 TEXT 的数据库或非 MySQL 测试环境。
        }
    }

    private void seedDefaultSettings() {
        insertSetting("system.name", "系统名称", "BASIC", "产线缺陷在线AI检测系统", "STRING", null, "登录页和主界面展示的系统名称", 1, 10);
        insertSetting("system.siteName", "站点名称", "BASIC", "相机检测系统", "STRING", null, "浏览器标题和部署识别名称", 1, 20);
        insertSetting("heartbeat.onlineSeconds", "在线阈值", "CAMERA", "30", "INTEGER", "秒", "心跳/上传时间小于等于该值视为在线", 1, 10);
        insertSetting("heartbeat.offlineSeconds", "离线阈值", "CAMERA", "120", "INTEGER", "秒", "心跳/上传时间大于该值视为离线，中间状态为延迟", 1, 20);
        insertSetting("defectImage.maxBatchImages", "单批图片上限", "UPLOAD", "100", "INTEGER", "张", "缺陷图片上传接口单批最多允许的图片数量", 1, 10);
        insertSetting("defectImage.maxFileSizeMb", "单图大小上限", "UPLOAD", "10", "INTEGER", "MB", "单张图片建议大小限制，实际上传上限以 application.yml 为准", 1, 20);
        insertSetting("alert.cpuWarning", "CPU 告警阈值", "ALERT", "90", "DECIMAL", "%", "CPU 使用率超过该值产生 WARNING", 1, 10);
        insertSetting("alert.memoryWarning", "内存告警阈值", "ALERT", "90", "DECIMAL", "%", "内存使用率超过该值产生 WARNING", 1, 20);
        insertSetting("alert.temperatureWarning", "温度告警阈值", "ALERT", "70", "DECIMAL", "℃", "设备温度超过该值产生 WARNING", 1, 30);
        insertSetting("analytics.dailyAggregationTime", "日聚合执行时间", "ANALYTICS", "02:00", "STRING", null, "夜间自动日聚合执行时间，正式调度以日聚合配置为准", 1, 10);
    }

    private void insertSetting(String key, String name, String group, String value, String type, String unit, String desc, int editable, int order) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM sys_system_setting WHERE setting_key=?", Integer.class, key);
        if (count != null && count > 0) return;
        jdbcTemplate.update("INSERT INTO sys_system_setting(setting_key,setting_name,setting_group,setting_value,value_type,unit,description,is_editable,sort_order) VALUES(?,?,?,?,?,?,?,?,?)",
                key, name, group, value, type, unit, desc, editable, order);
    }

    private void repairCorruptedDefaultSettings() {
        repairDefaultIfTooLong("system.name", "产线缺陷在线AI检测系统", 100);
        repairDefaultIfTooLong("system.siteName", "相机检测系统", 100);
    }

    private void repairDefaultIfTooLong(String key, String defaultValue, int maxLength) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM sys_system_setting WHERE setting_key=? AND CHAR_LENGTH(setting_value)>?",
                    Integer.class, key, maxLength);
            if (count != null && count > 0) {
                jdbcTemplate.update("UPDATE sys_system_setting SET setting_value=?, update_time=NOW() WHERE setting_key=?",
                        defaultValue, key);
            }
        } catch (Exception ignored) {
            // 修复历史乱码/超长默认值失败不影响主流程，后续保存仍由校验兜底。
        }
    }

    private void seedDefaultRetentionPolicies() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS sys_data_retention_policy (" +
                "policy_id BIGINT PRIMARY KEY AUTO_INCREMENT," +
                "data_type VARCHAR(50) NOT NULL," +
                "retention_days INT NOT NULL," +
                "action_type VARCHAR(50) NOT NULL DEFAULT 'DELETE'," +
                "is_enabled TINYINT NOT NULL DEFAULT 1," +
                "update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                "UNIQUE KEY uk_data_type (data_type)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据保留策略'");
        insertPolicy("DEFECT_IMAGE_FILE", 30, "DELETE");
        insertPolicy("DEFECT_IMAGE_BOX", 90, "ARCHIVE");
        insertPolicy("INSPECTION_DETAIL", 90, "ARCHIVE");
        insertPolicy("AGG_30M", 365, "KEEP");
        insertPolicy("OPERATION_LOG", 180, "DELETE");
    }

    private void insertPolicy(String dataType, int days, String action) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM sys_data_retention_policy WHERE data_type=?", Integer.class, dataType);
        if (count != null && count > 0) return;
        jdbcTemplate.update("INSERT INTO sys_data_retention_policy(data_type,retention_days,action_type,is_enabled) VALUES(?,?,?,1)", dataType, days, action);
    }

    private Map<String, List<Map<String, Object>>> buildGroups(List<Map<String, Object>> settings) {
        Map<String, List<Map<String, Object>>> groups = new HashMap<>();
        for (Map<String, Object> row : settings) {
            String group = str(row.get("settingGroup"));
            if (group == null) group = "OTHER";
            if (!groups.containsKey(group)) groups.put(group, new ArrayList<Map<String, Object>>());
            groups.get(group).add(row);
        }
        return groups;
    }

    private Map<String, Object> buildSummary(List<Map<String, Object>> settings, List<Map<String, Object>> policies) {
        Map<String, Object> summary = new HashMap<>();
        summary.put("settingCount", settings.size());
        summary.put("policyCount", policies.size());
        summary.put("editableCount", countEditable(settings));
        summary.put("enabledPolicyCount", countEnabledPolicies(policies));
        return summary;
    }

    private int countEditable(List<Map<String, Object>> settings) {
        int count = 0;
        for (Map<String, Object> row : settings) {
            Object value = row.get("isEditable");
            if (value instanceof Number && ((Number) value).intValue() == 1) count++;
        }
        return count;
    }

    private int countEnabledPolicies(List<Map<String, Object>> policies) {
        int count = 0;
        for (Map<String, Object> row : policies) {
            Object value = row.get("isEnabled");
            if (value instanceof Number && ((Number) value).intValue() == 1) count++;
        }
        return count;
    }

    private Map<String, String> loadCurrentSettingValues() {
        Map<String, String> values = new HashMap<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT setting_key settingKey, setting_value settingValue FROM sys_system_setting");
        for (Map<String, Object> row : rows) {
            String key = str(row.get("settingKey"));
            String value = str(row.get("settingValue"));
            if (key != null) {
                values.put(key, value);
            }
        }
        return values;
    }

    private void validateSettings(Map<String, String> settings, List<String> errors) {
        Integer onlineSeconds = parseIntegerSetting(settings, "heartbeat.onlineSeconds", "在线阈值", 1, 86400, errors);
        Integer offlineSeconds = parseIntegerSetting(settings, "heartbeat.offlineSeconds", "离线阈值", 1, 86400, errors);
        if (onlineSeconds != null && offlineSeconds != null && onlineSeconds >= offlineSeconds) {
            errors.add("在线阈值必须小于离线阈值，例如 30 秒 < 120 秒。");
        }
        parseIntegerSetting(settings, "defectImage.maxBatchImages", "单批图片上限", 1, 100, errors);
        parseIntegerSetting(settings, "defectImage.maxFileSizeMb", "单图大小上限", 1, 1024, errors);
        parseDecimalSetting(settings, "alert.cpuWarning", "CPU 告警阈值", 0D, 100D, errors);
        parseDecimalSetting(settings, "alert.memoryWarning", "内存告警阈值", 0D, 100D, errors);
        parseDecimalSetting(settings, "alert.temperatureWarning", "温度告警阈值", 0D, 150D, errors);

        String dailyTime = settings.get("analytics.dailyAggregationTime");
        if (dailyTime != null && !dailyTime.trim().isEmpty() && !dailyTime.trim().matches("^([01]\\d|2[0-3]):[0-5]\\d$")) {
            errors.add("日聚合执行时间格式必须为 HH:mm，例如 02:00。");
        }
    }

    @SuppressWarnings("unchecked")
    private void validateRetentionPolicies(Object policiesObj, List<String> errors) {
        if (!(policiesObj instanceof List)) return;
        List<?> policies = (List<?>) policiesObj;
        for (Object item : policies) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> row = (Map<String, Object>) item;
            String dataType = str(row.get("dataType"));
            Integer days = intValue(row.get("retentionDays"));
            String action = str(row.get("actionType"));
            Integer enabled = intValue(row.get("isEnabled"));
            String label = dataType == null || dataType.trim().isEmpty() ? "未命名策略" : dataType.trim();
            if (dataType == null || dataType.trim().isEmpty()) {
                errors.add("数据保留策略的数据类型不能为空。");
            }
            if (days == null || days < 1) {
                errors.add(label + " 的保留天数必须大于等于 1 天。");
            }
            if (action != null && !action.trim().isEmpty()) {
                String normalizedAction = action.trim().toUpperCase();
                if (!"DELETE".equals(normalizedAction) && !"ARCHIVE".equals(normalizedAction) && !"KEEP".equals(normalizedAction)) {
                    errors.add(label + " 的处理动作仅支持 DELETE / ARCHIVE / KEEP。");
                }
            }
            if (enabled != null && enabled != 0 && enabled != 1) {
                errors.add(label + " 的启用状态仅支持 0 或 1。");
            }
        }
    }

    private Integer parseIntegerSetting(Map<String, String> settings, String key, String label, int min, int max, List<String> errors) {
        String value = settings.get(key);
        if (value == null || value.trim().isEmpty()) {
            errors.add(label + "不能为空。");
            return null;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < min || parsed > max) {
                errors.add(label + "必须在 " + min + " 到 " + max + " 之间。");
                return null;
            }
            return parsed;
        } catch (Exception e) {
            errors.add(label + "必须为整数。");
            return null;
        }
    }

    private Double parseDecimalSetting(Map<String, String> settings, String key, String label, double min, double max, List<String> errors) {
        String value = settings.get(key);
        if (value == null || value.trim().isEmpty()) {
            errors.add(label + "不能为空。");
            return null;
        }
        try {
            double parsed = Double.parseDouble(value.trim());
            if (parsed < min || parsed > max) {
                errors.add(label + "必须在 " + min + " 到 " + max + " 之间。");
                return null;
            }
            return parsed;
        } catch (Exception e) {
            errors.add(label + "必须为数字。");
            return null;
        }
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer intValue(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }
}
