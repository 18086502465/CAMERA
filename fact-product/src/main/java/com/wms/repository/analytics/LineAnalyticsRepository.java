package com.wms.repository.analytics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 产线统计查询 Repository。
 *
 * 当前先承接 LineAnalyticsController 中低风险、无业务分支的元数据 SQL；
 * 后续再逐步迁移趋势、排行、看板配置和聚合任务 SQL。
 */
@Repository
public class LineAnalyticsRepository {

    private final JdbcTemplate jdbcTemplate;

    public LineAnalyticsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Map<String, Object>> listEnabledProducts() {
        return jdbcTemplate.queryForList(
                "SELECT product_id AS productId, product_code AS productCode, product_name AS productName " +
                        "FROM dim_product WHERE is_deleted=0 AND is_enabled=1 ORDER BY sort_order, product_id");
    }

    public List<Map<String, Object>> listEnabledDefectTypes() {
        return jdbcTemplate.queryForList(
                "SELECT defect_type_id AS defectTypeId, product_id AS productId, defect_code AS defectCode, defect_name AS defectName, default_severity AS severity " +
                        "FROM dim_defect_type WHERE is_deleted=0 AND is_enabled=1 ORDER BY sort_order, defect_type_id");
    }

    public List<Map<String, Object>> listEnabledPositions() {
        return jdbcTemplate.queryForList(
                "SELECT position_id AS positionId, position_code AS positionCode, position_name AS positionName " +
                        "FROM dim_defect_position WHERE is_deleted=0 AND is_enabled=1 ORDER BY sort_order, position_id");
    }

    public List<Map<String, Object>> listEnabledCameras() {
        return jdbcTemplate.queryForList(
                "SELECT camera_id AS cameraId, camera_code AS cameraCode, camera_name AS cameraName, factory_id AS factoryId, line_id AS lineId, workstation_id AS workstationId " +
                        "FROM dim_camera WHERE is_deleted=0 AND is_enabled=1 ORDER BY sort_order, camera_id");
    }

    public List<Map<String, Object>> listEnabledShifts() {
        return jdbcTemplate.queryForList(
                "SELECT shift_id AS shiftId, shift_code AS shiftCode, shift_name AS shiftName " +
                        "FROM dim_shift WHERE is_enabled=1 ORDER BY shift_id");
    }

    public List<Map<String, Object>> listDashboards(Long factoryId, Long ownerUserId) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(dashboardSelect() + "FROM cfg_analytics_dashboard WHERE is_enabled = 1 ");
        if (factoryId != null) {
            sql.append("AND (factory_id = ? OR factory_id IS NULL) ");
            params.add(factoryId);
        }
        if (ownerUserId != null) {
            sql.append("AND (owner_user_id = ? OR owner_user_id IS NULL) ");
            params.add(ownerUserId);
        }
        sql.append("ORDER BY is_default DESC, update_time DESC, dashboard_id DESC");
        return jdbcTemplate.queryForList(sql.toString(), params.toArray());
    }

    public Map<String, Object> findDefaultDashboard(Long factoryId) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(dashboardSelect() +
                "FROM cfg_analytics_dashboard WHERE is_enabled = 1 AND is_default = 1 ");
        if (factoryId != null) {
            sql.append("AND (factory_id = ? OR factory_id IS NULL) ");
            params.add(factoryId);
        }
        sql.append("ORDER BY CASE WHEN factory_id IS NULL THEN 1 ELSE 0 END, update_time DESC LIMIT 1");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), params.toArray());
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> findDashboard(Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                dashboardSelect() + "FROM cfg_analytics_dashboard WHERE dashboard_id = ? AND is_enabled = 1 LIMIT 1",
                id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> findWidget(Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                widgetSelect() + "FROM cfg_analytics_widget WHERE widget_id = ? LIMIT 1",
                id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> listWidgets(Long dashboardId) {
        if (dashboardId == null) return new ArrayList<>();
        return jdbcTemplate.queryForList(
                widgetSelect() + "FROM cfg_analytics_widget WHERE dashboard_id = ? AND is_enabled = 1 ORDER BY sort_order ASC, widget_id ASC",
                dashboardId);
    }

    public Map<String, Object> one(String sql, Object... params) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, params);
        return rows.isEmpty() ? new LinkedHashMap<>() : rows.get(0);
    }

    private String dashboardSelect() {
        return "SELECT dashboard_id AS dashboardId, dashboard_name AS dashboardName, factory_id AS factoryId, owner_user_id AS ownerUserId, " +
                "scope_type AS scopeType, is_default AS isDefault, layout_json AS layoutJson, is_enabled AS isEnabled, create_time AS createTime, update_time AS updateTime ";
    }

    private String widgetSelect() {
        return "SELECT widget_id AS widgetId, dashboard_id AS dashboardId, widget_name AS widgetName, widget_type AS widgetType, " +
                "metric_json AS metricJson, dimension_json AS dimensionJson, filter_json AS filterJson, option_json AS optionJson, layout_json AS layoutJson, " +
                "follow_global_filter AS followGlobalFilter, sort_order AS sortOrder, is_enabled AS isEnabled, create_time AS createTime, update_time AS updateTime ";
    }
}
