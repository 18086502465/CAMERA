package com.wms.system.infra.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统设置中心仓储（DDD P3 批次② 收敛）。
 *
 * <p>SQL 均自 {@code SystemSettingsController} <b>原样搬入</b>，行为不变：
 * <ul>
 *   <li>建表 / 种子 / 修复的调用顺序与异常吞掉语义保持原样
 *       （{@code ALTER TABLE} 与超长默认值修复失败均静默忽略，不阻断主流程）；</li>
 *   <li>查询列别名保持 {@code settingId / settingKey / ...} 驼峰形式不变，
 *       前端响应键结构零变化。</li>
 * </ul>
 *
 * <p>P5 阶段这些 {@code ensure*} 建表语句会统一收敛到 {@code SchemaInitializer}，
 * 本阶段先完成"Controller 内无 JDBC"的目标。
 */
@Repository
public class SystemSettingsRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ==================== 查询 ====================

    /** 系统设置列表（按分组、排序号、ID 升序） */
    public List<Map<String, Object>> listSettings() {
        return jdbcTemplate.queryForList(
                "SELECT setting_id settingId, setting_key settingKey, setting_name settingName, setting_group settingGroup, " +
                        "setting_value settingValue, value_type valueType, unit, description, is_editable isEditable, sort_order sortOrder, update_time updateTime " +
                        "FROM sys_system_setting ORDER BY setting_group, sort_order, setting_id");
    }

    /** 数据保留策略列表（按策略 ID 升序） */
    public List<Map<String, Object>> listRetentionPolicies() {
        return jdbcTemplate.queryForList(
                "SELECT policy_id policyId, data_type dataType, retention_days retentionDays, action_type actionType, is_enabled isEnabled, update_time updateTime " +
                        "FROM sys_data_retention_policy ORDER BY policy_id");
    }

    /** 当前全部设置项的 key → value（保存前的合并基线） */
    public Map<String, String> loadCurrentSettingValues() {
        Map<String, String> values = new HashMap<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT setting_key settingKey, setting_value settingValue FROM sys_system_setting");
        for (Map<String, Object> row : rows) {
            Object key = row.get("settingKey");
            Object value = row.get("settingValue");
            String keyText = key == null ? null : String.valueOf(key);
            if (keyText != null) {
                values.put(keyText, value == null ? null : String.valueOf(value));
            }
        }
        return values;
    }

    /**
     * 读取单个设置项的数值型值（DDD P3 批次③）。
     *
     * <p>供 device 域的相机告警阈值读取使用。
     *
     * @return 设置值；无值 / 非法值 / 查询失败时返回 {@code null}（由调用方决定默认值）
     */
    public String findSettingValue(String key) {
        return jdbcTemplate.queryForObject(
                "SELECT setting_value FROM sys_system_setting WHERE setting_key=? LIMIT 1",
                new Object[]{key},
                String.class);
    }

    // ==================== 写入 ====================

    /** 更新单个设置项的值（仅可编辑项生效） */
    public int updateSettingValue(String value, String key) {
        return jdbcTemplate.update(
                "UPDATE sys_system_setting SET setting_value=?, update_time=NOW() WHERE setting_key=? AND is_editable=1",
                value, key);
    }

    /** 更新数据保留策略 */
    public int updateRetentionPolicy(Integer days, String action, Integer enabled, String dataType) {
        return jdbcTemplate.update(
                "UPDATE sys_data_retention_policy SET retention_days=?, action_type=?, is_enabled=?, update_time=NOW() WHERE data_type=?",
                days, action, enabled, dataType);
    }

    // ==================== 建表与种子数据 ====================

    /** 确保系统设置表存在（并把 setting_value 放宽为 TEXT） */
    public void ensureSettingTable() {
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

    /** 写入内置默认设置项（已存在则跳过） */
    public void seedDefaultSettings() {
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

    /** 修复历史乱码 / 超长的内置默认值 */
    public void repairCorruptedDefaultSettings() {
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

    /** 确保数据保留策略表存在并写入内置策略 */
    public void seedDefaultRetentionPolicies() {
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
}
