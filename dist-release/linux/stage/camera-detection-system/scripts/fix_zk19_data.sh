#!/bin/bash
#
# ★ 口令从环境变量读取（勿把真实口令写进仓库）：
#   export MYSQL_PASSWORD='<MySQL root 口令>'
#   bash fix_zk19_data.sh
# ============================================================
# ZK19 空产线下线 + 测试残留相机清理（数据修复脚本）
#
# 事实依据（已逐表核对 Nov4 01:01 备份 + 操作日志确认）：
#   1. ZK19 (line_id=4) 属杭州临平一期工厂，含工位 21(CV-1 产品正面胶污检测)
#      / 22(CV-2 产品背面胶污检测)，与同工厂 ZK23 的工位码/名称完全重复。
#   2. ZK19 的两个工位【从未】绑定过任何真实相机：
#      - dim_camera 中 workstation_id=21/22 仅有两行 is_deleted=1 的测试残留；
#      - camera_status 中 workstation_id=21 仅两行 is_active=0 的测试残留；
#      - 所有事实表/聚合表/看板中 line_id=4 或 workstation_id IN (21,22) 的行数均为 0。
#   3. 现场在跑的两台真实相机 DA8119489 / DA4733319 绑定在 ZK23 的工位 23/24，
#      且正在持续心跳与上传图片（勿动）。
#
# 本脚本只做逻辑删除，不做物理删除，可随时恢复。
# ============================================================
set -eu

run_sql() {
  mysql -uroot -p"${MYSQL_PASSWORD:-}" --default-character-set=utf8mb4 -t -D 'wms-new' "$@" 2>&1 | grep -v "Warning"
}

echo "########## 步骤 0：修复前快照（用于回滚核对） ##########"
run_sql -e "
SELECT 'line_before' k, line_id id, line_code code, is_deleted del, is_enabled en FROM dim_production_line WHERE line_id=4
UNION ALL SELECT 'ws_before', workstation_id, station_code, is_deleted, is_enabled FROM dim_workstation WHERE workstation_id IN (21,22)
UNION ALL SELECT 'dimcam_before', camera_id, camera_code, is_deleted, is_enabled FROM dim_camera WHERE workstation_id IN (21,22)
UNION ALL SELECT 'camstatus_before', id, camera_code, is_active, status FROM camera_status WHERE workstation_id IN (21,22);
"

echo ""
echo "########## 步骤 1：物理清理 aaaaab / CCCC1 测试残留相机 ##########"
echo "-- 确认这两台无任何图片/心跳/映射/令牌关联 --"
run_sql -e "
SELECT 'defect_image_file' t, COUNT(*) n FROM defect_image_file WHERE camera_id IN ('aaaaab','CCCC1') OR camera_name IN ('aaa','CCCC')
UNION ALL SELECT 'camera_images', COUNT(*) FROM camera_images WHERE camera_name IN ('aaa','CCCC')
UNION ALL SELECT 'fact_camera_heartbeat', COUNT(*) FROM fact_camera_heartbeat WHERE camera_code IN ('aaaaab','CCCC1')
UNION ALL SELECT 'cfg_camera_defect_mapping', COUNT(*) FROM cfg_camera_defect_mapping WHERE camera_id IN (28,29) OR camera_code IN ('aaaaab','CCCC1')
UNION ALL SELECT 'cfg_camera_token', COUNT(*) FROM cfg_camera_token WHERE camera_id IN (28,29) OR camera_code IN ('aaaaab','CCCC1')
UNION ALL SELECT 'defect_image_box', COUNT(*) FROM defect_image_box WHERE camera_code IN ('aaaaab','CCCC1')
UNION ALL SELECT 'defect_image_batch', COUNT(*) FROM defect_image_batch WHERE camera_code IN ('aaaaab','CCCC1');
"

echo "-- 执行物理清理 --"
run_sql -e "
DELETE FROM cfg_camera_defect_mapping WHERE camera_id IN (28,29) OR camera_code IN ('aaaaab','CCCC1');
DELETE FROM cfg_camera_token WHERE camera_id IN (28,29) OR camera_code IN ('aaaaab','CCCC1');
DELETE FROM dim_camera WHERE camera_id IN (28,29) AND camera_code IN ('aaaaab','CCCC1');
DELETE FROM camera_status WHERE id IN (28,29) AND camera_code IN ('aaaaab','CCCC1');
SELECT ROW_COUNT() AS last_deleted_camera_status;
"

echo ""
echo "########## 步骤 2：逻辑删除 ZK19 产线及其 2 个空工位 ##########"
run_sql -e "
-- 工位先下线（避免产线仍有可见工位）
UPDATE dim_workstation
   SET is_deleted = 1, is_enabled = 0, update_time = NOW()
 WHERE workstation_id IN (21,22) AND IFNULL(is_deleted,0)=0;
SELECT ROW_COUNT() AS ws_deleted;

-- 产线下线
UPDATE dim_production_line
   SET is_deleted = 1, is_enabled = 0, update_time = NOW()
 WHERE line_id = 4 AND IFNULL(is_deleted,0)=0;
SELECT ROW_COUNT() AS line_deleted;
"

echo ""
echo "########## 步骤 3：修复后校验 ##########"
run_sql -e "
SELECT 'line_after' k, line_id id, line_code code, is_deleted del, is_enabled en FROM dim_production_line WHERE line_id=4
UNION ALL SELECT 'ws_after', workstation_id, station_code, is_deleted, is_enabled FROM dim_workstation WHERE workstation_id IN (21,22)
UNION ALL SELECT 'dimcam_after', camera_id, camera_code, is_deleted, is_enabled FROM dim_camera WHERE camera_id IN (28,29)
UNION ALL SELECT 'camstatus_after', id, camera_code, is_active, status FROM camera_status WHERE id IN (28,29);
"

echo "-- 现场真实相机必须完好无损（ZK23 两台 + ZK54 十四台）--"
run_sql -e "
SELECT cs.id, cs.camera_code, cs.camera_name, cs.line_id, cs.workstation_id, cs.is_active,
       ws.station_name, pl.line_code
FROM camera_status cs
LEFT JOIN dim_workstation ws ON ws.workstation_id = cs.workstation_id
LEFT JOIN dim_production_line pl ON pl.line_id = cs.line_id
WHERE cs.is_active = 1 ORDER BY cs.workstation_id;
"

echo "-- 全库相机总数校验（期望 16 活跃 + 0 残留 = 16）--"
run_sql -e "
SELECT 'camera_status_total' k, COUNT(*) n FROM camera_status
UNION ALL SELECT 'camera_status_active', COUNT(*) FROM camera_status WHERE is_active=1
UNION ALL SELECT 'dim_camera_total', COUNT(*) FROM dim_camera
UNION ALL SELECT 'dim_camera_live', COUNT(*) FROM dim_camera WHERE IFNULL(is_deleted,0)=0
UNION ALL SELECT 'dim_workstation_live', COUNT(*) FROM dim_workstation WHERE IFNULL(is_deleted,0)=0
UNION ALL SELECT 'dim_line_live', COUNT(*) FROM dim_production_line WHERE IFNULL(is_deleted,0)=0;
"

echo ""
echo "########## 步骤 4：生成回滚 SQL（存档用） ##########"
cat > /tmp/rollback_zk19.sql <<'SQL'
-- ZK19 下线回滚脚本（如需恢复）
UPDATE dim_workstation SET is_deleted = 0, is_enabled = 1, update_time = NOW() WHERE workstation_id IN (21,22);
UPDATE dim_production_line SET is_deleted = 0, is_enabled = 1, update_time = NOW() WHERE line_id = 4;
-- 注：aaaaab / CCCC1 两台测试相机为物理删除，未保留备份（其本身即测试垃圾，无需恢复）
SQL
cat /tmp/rollback_zk19.sql
echo ""
echo "完成。"
