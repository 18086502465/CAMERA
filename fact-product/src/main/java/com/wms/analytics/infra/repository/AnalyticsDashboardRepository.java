package com.wms.analytics.infra.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 动态报表配置 Repository（DDD P3 批次④ 收敛 · 配置域）。
 *
 * <p>承接 {@code LineAnalyticsController} 中 {@code cfg_analytics_dashboard} 与
 * {@code cfg_analytics_widget} 两张表的读写 SQL，以及旧版默认看板的升级检测。
 *
 * <p>SQL 均自原 Controller <b>原样搬入</b>，行为不变。
 * 注意：{@code LAST_INSERT_ID()} 必须在同连接的 INSERT 之后调用 ——
 * 本类的方法内部连续执行 INSERT 与取 ID，保证同一连接（Spring 的
 * {@code JdbcTemplate} 在无事务时每次取连接，用 DataSourceUtils 绑定保证同连接）。
 */
@Repository
public class AnalyticsDashboardRepository {

    private final JdbcTemplate jdbcTemplate;

    public AnalyticsDashboardRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ==================== 看板 ====================

    /** 新建看板并返回自增 ID */
    public Long insertDashboard(String name, Long factoryId, Long ownerUserId, String scopeType,
                                int isDefault, String layoutJson) {
        jdbcTemplate.update(
                "INSERT INTO cfg_analytics_dashboard (dashboard_name, factory_id, owner_user_id, scope_type, is_default, layout_json, is_enabled) " +
                        "VALUES (?,?,?,?,?,CAST(? AS JSON),1)",
                name, factoryId, ownerUserId, scopeType, isDefault, layoutJson);
        return lastInsertId();
    }

    /** 复制看板（含组件），返回新看板 ID */
    public Long copyDashboard(Long sourceId, String name, Long factoryId, Long ownerUserId,
                              String scopeType, int isDefault) {
        jdbcTemplate.update(
                "INSERT INTO cfg_analytics_dashboard (dashboard_name, factory_id, owner_user_id, scope_type, is_default, layout_json, is_enabled) " +
                        "SELECT ?, ?, ?, ?, ?, layout_json, 1 FROM cfg_analytics_dashboard WHERE dashboard_id=? AND is_enabled=1",
                name, factoryId, ownerUserId, scopeType, isDefault, sourceId);
        Long newId = lastInsertId();
        jdbcTemplate.update(
                "INSERT INTO cfg_analytics_widget (dashboard_id, widget_name, widget_type, metric_json, dimension_json, filter_json, option_json, layout_json, follow_global_filter, sort_order, is_enabled) " +
                        "SELECT ?, widget_name, widget_type, metric_json, dimension_json, filter_json, option_json, layout_json, follow_global_filter, sort_order, is_enabled " +
                        "FROM cfg_analytics_widget WHERE dashboard_id=? AND is_enabled=1 ORDER BY sort_order, widget_id",
                newId, sourceId);
        return newId;
    }

    /** 更新看板 */
    public int updateDashboard(Long id, String name, Long factoryId, Long ownerUserId,
                               String scopeType, int isDefault, String layoutJson) {
        return jdbcTemplate.update(
                "UPDATE cfg_analytics_dashboard SET dashboard_name=?, factory_id=?, owner_user_id=?, scope_type=?, is_default=?, layout_json=CAST(? AS JSON) WHERE dashboard_id=?",
                name, factoryId, ownerUserId, scopeType, isDefault, layoutJson, id);
    }

    /** 逻辑删除看板及其组件 */
    public void disableDashboard(Long id) {
        jdbcTemplate.update("UPDATE cfg_analytics_dashboard SET is_enabled = 0 WHERE dashboard_id = ?", id);
        jdbcTemplate.update("UPDATE cfg_analytics_widget SET is_enabled = 0 WHERE dashboard_id = ?", id);
    }

    // ==================== 组件 ====================

    /** 新建组件并返回自增 ID */
    public Long insertWidget(Long dashboardId, String widgetName, String widgetType,
                             String metricJson, String dimensionJson, String filterJson,
                             String optionJson, String layoutJson,
                             int followGlobalFilter, int sortOrder) {
        jdbcTemplate.update(
                "INSERT INTO cfg_analytics_widget (dashboard_id, widget_name, widget_type, metric_json, dimension_json, filter_json, option_json, layout_json, follow_global_filter, sort_order, is_enabled) " +
                        "VALUES (?,?,?,CAST(? AS JSON),CAST(? AS JSON),CAST(? AS JSON),CAST(? AS JSON),CAST(? AS JSON),?,?,1)",
                dashboardId, widgetName, widgetType,
                metricJson, dimensionJson, filterJson, optionJson, layoutJson,
                followGlobalFilter, sortOrder);
        return lastInsertId();
    }

    /** 更新组件 */
    public int updateWidget(Long id, String widgetName, String widgetType,
                             String metricJson, String dimensionJson, String filterJson,
                             String optionJson, String layoutJson,
                             int followGlobalFilter, int sortOrder, int isEnabled) {
        return jdbcTemplate.update(
                "UPDATE cfg_analytics_widget SET widget_name=?, widget_type=?, metric_json=CAST(? AS JSON), dimension_json=CAST(? AS JSON), " +
                        "filter_json=CAST(? AS JSON), option_json=CAST(? AS JSON), layout_json=CAST(? AS JSON), follow_global_filter=?, sort_order=?, is_enabled=? WHERE widget_id=?",
                widgetName, widgetType,
                metricJson, dimensionJson, filterJson, optionJson, layoutJson,
                followGlobalFilter, sortOrder, isEnabled, id);
    }

    /** 逻辑删除组件 */
    public int disableWidget(Long id) {
        return jdbcTemplate.update("UPDATE cfg_analytics_widget SET is_enabled = 0 WHERE widget_id = ?", id);
    }

    /** 调整组件排序与布局 */
    public int reorderWidget(Long widgetId, int sortOrder, String layoutJson) {
        return jdbcTemplate.update("UPDATE cfg_analytics_widget SET sort_order=?, layout_json=CAST(? AS JSON) WHERE widget_id=?",
                sortOrder, layoutJson, widgetId);
    }

    // ==================== 默认看板 ====================

    /** 创建「工厂质量看板（厂长视角）」并返回 ID */
    public Long insertDefaultDashboard(Long factoryId) {
        jdbcTemplate.update(
                "INSERT INTO cfg_analytics_dashboard (dashboard_name, factory_id, scope_type, is_default, layout_json, is_enabled) " +
                        "VALUES (?,?,?,1,CAST(? AS JSON),1)",
                "工厂质量看板（厂长视角）", factoryId, "FACTORY", "{\"cols\":12,\"rowHeight\":120}");
        return lastInsertId();
    }

    /** 写入内置默认组件 */
    public void insertDefaultWidget(Long dashboardId, String name, String type, String metricJson,
                                    String dimensionJson, String layoutJson, int sortOrder) {
        jdbcTemplate.update(
                "INSERT INTO cfg_analytics_widget (dashboard_id, widget_name, widget_type, metric_json, dimension_json, filter_json, option_json, layout_json, follow_global_filter, sort_order, is_enabled) " +
                        "VALUES (?,?,?,CAST(? AS JSON),CAST(? AS JSON),CAST(? AS JSON),CAST(? AS JSON),CAST(? AS JSON),1,?,1)",
                dashboardId, name, type, metricJson, dimensionJson, "{}", "{}", layoutJson, sortOrder);
    }

    /** 查旧版默认看板（用于一次性升级） */
    public List<Map<String, Object>> findLegacyDefaultDashboard(Long factoryId) {
        return jdbcTemplate.queryForList(
                "SELECT dashboard_id AS dashboardId, dashboard_name AS dashboardName FROM cfg_analytics_dashboard " +
                        "WHERE is_enabled=1 AND dashboard_name='默认产线统计看板' " +
                        (factoryId == null ? "" : "AND (factory_id = ? OR factory_id IS NULL) ") +
                        "ORDER BY dashboard_id LIMIT 1",
                factoryId == null ? new Object[]{} : new Object[]{factoryId});
    }

    /** 统计看板中的「非内置组件」数量（>0 说明用户改造过，不升级） */
    public Integer countCustomWidgets(Long dashboardId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM cfg_analytics_widget WHERE dashboard_id=? AND is_enabled=1 " +
                        "AND widget_name NOT IN ('核心质量指标','30分钟质量趋势','缺陷类型排行','异常工位排行','产品缺陷透视表')",
                Integer.class, dashboardId);
    }

    /** 重命名旧看板为厂长视角 */
    public int renameDashboard(Long dashboardId) {
        return jdbcTemplate.update("UPDATE cfg_analytics_dashboard SET dashboard_name='工厂质量看板（厂长视角）' WHERE dashboard_id=?", dashboardId);
    }

    /** 清空看板组件（升级前先删旧组件） */
    public int deleteWidgets(Long dashboardId) {
        return jdbcTemplate.update("DELETE FROM cfg_analytics_widget WHERE dashboard_id=?", dashboardId);
    }

    // ==================== 通用 ====================

    /** 取自增 ID（必须与 INSERT 同连接） */
    private Long lastInsertId() {
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
