#!/bin/bash
#
# ★ 口令从环境变量读取（勿把真实口令写进仓库）：
#   export MYSQL_PASSWORD='<MySQL root 口令>'
#   bash cleanup_test_widgets.sh
# ============================================================
# 清理默认看板中混入的测试图表（上一轮排查按钮时遗留）
#
# 事实（2026-11-04 实测 cfg_analytics_widget）：
#   两个工厂的默认看板「工厂质量看板（厂长视角）」各混入 3 个测试图表：
#     widget 17/18 「按钮验证图表-改」   metric=defectPareto
#     widget 25    「自定义图表」         metric=defectPareto
#   它们与正式图表「缺陷类型帕累托」指标完全重复，
#   在页面上表现为画布里多出 2~3 个一模一样的帕累托图（实测渲染 7 张卡，实际有用 6 张）。
#
# 本脚本只删除这 3 个测试图表（widget_id 17/18/25），不触碰 6 个正式图表。
# ============================================================
set -u
run_sql() {
  mysql -uroot -p"${MYSQL_PASSWORD:-}" --default-character-set=utf8mb4 -t -D 'wms-new' "$@" 2>&1 | grep -v "Warning"
}

echo "########## 1. 删除前确认（这 3 个是测试图表）##########"
run_sql -e "
SELECT w.widget_id, w.widget_name, w.metric_json, d.dashboard_id, d.dashboard_name
FROM cfg_analytics_widget w
JOIN cfg_analytics_dashboard d ON d.dashboard_id = w.dashboard_id
WHERE w.widget_id IN (17,18,25);
"

echo ""
echo "########## 2. 删除测试图表 ##########"
run_sql -e "
DELETE FROM cfg_analytics_widget WHERE widget_id IN (17,18,25);
SELECT ROW_COUNT() AS deleted_widgets;
"

echo ""
echo "########## 3. 删除后校验：默认看板应只剩 6 个正式图表/工厂 ##########"
run_sql -e "
SELECT d.dashboard_id, d.dashboard_name, d.factory_id, d.is_default,
       COUNT(w.widget_id) AS widget_count,
       GROUP_CONCAT(CONCAT(w.widget_name,'[',JSON_UNQUOTE(JSON_EXTRACT(w.metric_json,'\$.metric')),']') ORDER BY w.sort_order SEPARATOR ' | ') AS charts
FROM cfg_analytics_dashboard d
LEFT JOIN cfg_analytics_widget w ON w.dashboard_id = d.dashboard_id
WHERE d.is_default = 1 AND d.is_enabled = 1
GROUP BY d.dashboard_id, d.dashboard_name, d.factory_id, d.is_default
ORDER BY d.factory_id;
"

echo ""
echo "########## 4. 非默认看板（含测试残留）清单，供确认 ##########"
run_sql -e "
SELECT d.dashboard_id, d.dashboard_name, d.is_default, d.is_enabled, COUNT(w.widget_id) AS cnt
FROM cfg_analytics_dashboard d
LEFT JOIN cfg_analytics_widget w ON w.dashboard_id = d.dashboard_id
GROUP BY d.dashboard_id, d.dashboard_name, d.is_default, d.is_enabled
ORDER BY d.dashboard_id;
"
