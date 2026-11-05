-- ============================================================
-- 05_camera_type_migration.sql  (产线缺陷在线AI检测系统)
-- 相机类型隔离：区分「真实产线相机」与「测试相机」。
--
-- 背景：
--   定时清理任务（每小时 :15，cleanup_zk54_incremental.sh）原先用
--   **脚本内硬编码白名单**（DA8119489 / DA4733319）来判断"哪些相机不能删"。
--   这带来三个问题：
--     ① 加一台真实相机必须改脚本并重跑，改漏即误删真实数据；
--     ② 相机属性无法在系统内表达；
--     ③ 单点失误无第二道防线。
--
--   本脚本给两张相机表加 camera_type 字段，把"是否可清理"变成**数据**而非代码。
--
-- 字段设计：
--   camera_type VARCHAR(20) NOT NULL DEFAULT 'TEST'
--     REAL = 真实产线相机 → 数据一律不参与清理
--     TEST = 测试相机     → 参与清理（无效图片 + 对应库记录）
--
--   为什么两张表都加：camera_status（运行态）与 dim_camera（档案态）
--   在本系统里本就是"双表一致"模式（is_active / is_deleted / is_enabled
--   已在两表各存一份，删除时也是两表一起逻辑删除）。沿用同一模式，
--   避免引入第三种数据归属口径。
--
--   权威源：dim_camera（相机↔工位绑定的真源）。
--   清理脚本取**两表并集**作为保护集合 —— 任一表标记 REAL 即受保护，
--   单表漏更新不会导致误删。
--
-- 幂等性：
--   本脚本可重复执行。① 用 information_schema 判断列是否存在（MySQL 8.0
--   的 ALTER TABLE ADD COLUMN 不支持 IF NOT EXISTS）；② 回填是 UPDATE，
--   重复执行结果一致。
--
-- ★ 执行前必读（现场执行顺序）：
--   1) 先停掉清理任务，避免迁移过程中相机被误判类型而删除：
--        root crontab -e   # 注释 cleanup_zk54_incremental.sh 那行
--   2) 执行本脚本
--   3) 核对第 ④ 步的校验输出：REAL 恰好 2 台，TEST 14 台
--   4) 部署新 JAR（应用启动时会用 ensureColumn 兜底补列）
--   5) 用新版脚本 dry-run 验证清理范围
--   6) 恢复清理任务调度
--
--   单独跑本脚本不会破坏既有数据：只加列 + 改 camera_type，
--   不动任何图片记录、不 DROP 任何表。
-- ============================================================

SET NAMES utf8mb4;

-- ---------- ① camera_status：加 camera_type 列（幂等） ----------
SET @ddl = (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `camera_status` ADD COLUMN `camera_type` VARCHAR(20) NOT NULL DEFAULT ''TEST'' COMMENT ''相机类型：REAL=真实产线相机（不参与清理），TEST=测试相机（参与清理）''',
    'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'camera_status'
    AND COLUMN_NAME = 'camera_type'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- ① dim_camera：加 camera_type 列（幂等） ----------
SET @ddl = (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `dim_camera` ADD COLUMN `camera_type` VARCHAR(20) NOT NULL DEFAULT ''TEST'' COMMENT ''相机类型：REAL=真实产线相机（不参与清理），TEST=测试相机（参与清理）''',
    'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'dim_camera'
    AND COLUMN_NAME = 'camera_type'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- ② 归一化：所有非 REAL/TEST 的值一律置 TEST ----------
-- 覆盖三种情况：列刚加上的历史行（DEFAULT 'TEST' 已生效，此处无操作）、
-- 人工写入的非法值、以及理论上可能存在的 NULL。
UPDATE `camera_status` SET `camera_type` = 'TEST'
 WHERE `camera_type` IS NULL OR `camera_type` NOT IN ('REAL', 'TEST');
UPDATE `dim_camera` SET `camera_type` = 'TEST'
 WHERE `camera_type` IS NULL OR `camera_type` NOT IN ('REAL', 'TEST');

-- ---------- ③ 标记真实产线相机（现场实测：ZK23 两台） ----------
-- 幂等覆盖：重复执行永远得到同一结果。
UPDATE `camera_status` SET `camera_type` = 'REAL'
 WHERE `camera_code` IN ('DA8119489', 'DA4733319');
UPDATE `dim_camera` SET `camera_type` = 'REAL'
 WHERE `camera_code` IN ('DA8119489', 'DA4733319');

-- ---------- ④ 校验（只读，人工核对） ----------
-- 期望：camera_status 与 dim_camera 各 2 行 REAL、其余 TEST。
SELECT 'camera_status' AS tbl, camera_type, COUNT(*) AS cnt
  FROM camera_status GROUP BY camera_type
UNION ALL
SELECT 'dim_camera', camera_type, COUNT(*)
  FROM dim_camera GROUP BY camera_type;

SELECT camera_code, camera_name, camera_type, is_active
  FROM camera_status ORDER BY camera_type, camera_code;

SELECT camera_code, camera_name, camera_type, is_deleted
  FROM dim_camera ORDER BY camera_type, camera_code;
