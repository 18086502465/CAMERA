package com.wms.system.api;

import com.wms.shared.common.ResultVO;
import com.wms.system.infra.repository.SystemSettingsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统设置中心 API。
 *
 * 说明：运行参数先落到 sys_system_setting；数据保留策略继续复用
 * sys_data_retention_policy，避免重复维护同一类配置。
 *
 * <p>DDD P3 批次②：所有 SQL 已下沉到 {@link SystemSettingsRepository}，
 * 本类只保留参数校验、分组/摘要组装与响应封装。
 */
@RestController
@RequestMapping("/api/v1/system/settings")
public class SystemSettingsController {

    @Autowired
    private SystemSettingsRepository settingsRepository;

    // DDD P5：启动期建表 + 种子数据已由 com.wms.init.SchemaInitializer 统一调度
    // （原样搬运，语义不变）。请求路径的 ensureSettingTable() 幂等守卫保留。

    @GetMapping
    public ResultVO<Map<String, Object>> getSettings() {
        settingsRepository.ensureSettingTable();
        List<Map<String, Object>> settings = settingsRepository.listSettings();
        List<Map<String, Object>> policies = settingsRepository.listRetentionPolicies();
        Map<String, Object> data = new HashMap<>();
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
        settingsRepository.ensureSettingTable();
        if (body == null) {
            return ResultVO.fail(422, "系统设置保存失败：请求体不能为空", null);
        }

        Map<String, String> proposedSettings = settingsRepository.loadCurrentSettingValues();
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
                settingUpdated += settingsRepository.updateSettingValue(value, key);
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
                policyUpdated += settingsRepository.updateRetentionPolicy(days, action, enabled, dataType);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("settingUpdated", settingUpdated);
        result.put("policyUpdated", policyUpdated);
        return ResultVO.success(result, "系统设置已保存");
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
