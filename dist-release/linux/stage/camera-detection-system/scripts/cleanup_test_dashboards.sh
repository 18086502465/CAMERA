#!/bin/bash
#
# ★ 口令从环境变量读取（勿把真实口令写进仓库）：
#   export MYSQL_PASSWORD='<MySQL root 口令>'
#   bash cleanup_test_dashboards.sh
# ============================================================
# 清理上一轮按钮验证遗留的 6 个空看板模板
#   id 3 「按钮验证临时看板-已改」  is_default=0 is_enabled=0
#   id 4 「按钮验证副本」            is_default=0 is_enabled=0
#   id 5 「按钮验证另存」            is_default=0 is_enabled=1
#   id 6 「按钮验证临时看板-已改」  is_default=0 is_enabled=0
#   id 7 「按钮验证副本」            is_default=0 is_enabled=0
#   id 8 「按钮验证另存」            is_default=0 is_enabled=1
# 这 6 个模板均无任何下属图表（widget_count=0），是排查删除按钮功能时的残留。
# 删除它们不影响默认看板（id 1/2，各 6 个正式图表）。
# ============================================================
set -u
run_sql() {
  mysql -uroot -p"${MYSQL_PASSWORD:-}" --default-character-set=utf8mb4 -t -D 'wms-new' "$@" 2>&1 | grep -v "Warning"
}

echo "########## 1. 删除前确认（仅这 6 个测试模板，其 widget 均为 0）##########"
run_sql -e "
SELECT d.dashboard_id, d.dashboard_name, d.is_default, d.is_enabled,
       (SELECT COUNT(*) FROM cfg_analytics_widget w WHERE w.dashboard_id=d.dashboard_id) AS widget_count
FROM cfg_analytics_dashboard d
WHERE d.dashboard_id IN (3,4,5,6,7,8);
"

echo ""
echo "########## 2. 执行删除 ##########"
run_sql -e "
DELETE FROM cfg_analytics_widget WHERE dashboard_id IN (3,4,5,6,7,8);
DELETE FROM cfg_analytics_dashboard WHERE dashboard_id IN (3,4,5,6,7,8);
SELECT ROW_COUNT() AS deleted_dashboards;
"

echo ""
echo "########## 3. 删除后校验 ##########"
run_sql -e "
SELECT d.dashboard_id, d.dashboard_name, d.factory_id, d.is_default, d.is_enabled,
       (SELECT COUNT(*) FROM cfg_analytics_widget w WHERE w.dashboard_id=d.dashboard_id) AS widget_count
FROM cfg_analytics_dashboard d ORDER BY d.dashboard_id;
"

echo ""
echo "########## 4. 全库 widget 汇总 ##########"
run_sql -e "
SELECT dashboard_id, COUNT(*) cnt, GROUP_CONCAT(widget_name ORDER BY sort_order SEPARATOR ' | ') charts
FROM cfg_analytics_widget GROUP BY dashboard_id;
"
