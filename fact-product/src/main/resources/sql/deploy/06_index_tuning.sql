-- ============================================================
-- 06_index_tuning.sql  (产线缺陷在线AI检测系统)
-- 索引补丁：幂等，可重复执行。
--
-- 背景（2026-11-07 v3 评审）：
--   camera_images.capture_time 是**两处清理任务**的判据，但 01 建表时只给了
--   upload_time / bucket_date 建索引：
--     · DataCleanupTask.cleanupExpiredImages —— 1000 行/批循环，
--       WHERE capture_time <= ? LIMIT 1000，没索引就是每批一次全表扫
--     · cleanup-defect-images.sh —— DELETE FROM camera_images WHERE capture_time < ?
--   现场 1.2 万行时无感，量级上去后是 O(N²)。
--
-- 为什么单独一段而不是并进 04：
--   04_align_entity_columns.sql 是**一次性**脚本（直接 ALTER ADD COLUMN，
--   重复执行会 1060 Duplicate column）。索引补丁需要能反复跑，
--   所以用 information_schema 判存在 + 动态 DDL。
--
-- 执行：mysql -u... wms-new < 06_index_tuning.sql
-- ============================================================

DROP PROCEDURE IF EXISTS cds_add_index_if_missing;

DELIMITER $$
CREATE PROCEDURE cds_add_index_if_missing(
    IN p_table VARCHAR(64),
    IN p_index VARCHAR(64),
    IN p_cols  VARCHAR(255)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name   = p_table
          AND index_name   = p_index
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD INDEX `', p_index, '` (', p_cols, ')');
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
        SELECT CONCAT('已创建索引 ', p_table, '.', p_index) AS result;
    ELSE
        SELECT CONCAT('索引已存在，跳过 ', p_table, '.', p_index) AS result;
    END IF;
END$$
DELIMITER ;

CALL cds_add_index_if_missing('camera_images', 'idx_capture_time', '`capture_time`');

DROP PROCEDURE IF EXISTS cds_add_index_if_missing;
