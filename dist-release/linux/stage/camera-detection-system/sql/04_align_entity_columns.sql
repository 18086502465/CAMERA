-- ============================================================
-- 04_align_entity_columns.sql  (产线缺陷在线AI检测系统)
-- 补齐实体类所需、但 01/02/03 三段脚本未创建的列。
--
-- 背景：
--   init_schema.sql 与 final_schema_p0_20260608.sql 各自定义了一部分表，
--   两段叠加后仍有 7 张表的列与 Java 实体类不匹配，会导致接口 500
--   （实测报错：Unknown column 'brand_id' in 'field list'）。
--
--   sys_user                 实体映射 passwordHash/avatarUrl/lastLoginAt，
--                             但脚本建的是 password 列 —— 导致无法登录
--   fact_production_30min    缺 brand_id / shift_id / start_time / end_time / yield_rate
--   product_detection_history 缺 9 列
--   fact_anomaly_alert       缺 9 列
--   fact_production_info     缺 production_date / yield_rate
--   camera_images            缺 image_url / file_size / width / height
--
-- 执行顺序：01 -> 02 -> 03 -> 04
-- 执行后应有 34 张表；应用首次启动后运行时自建 4 张，最终 38 张。
-- 本脚本已在 MySQL 8.0.46 上实测零报错。
-- ============================================================

-- ---------- sys_user：补认证所需列，移除冲突列 ----------
-- 注意顺序：必须先把 password 列的哈希值搬到 password_hash，再删 password。
-- 否则种子账号的 BCrypt 哈希会随 password 列一起被丢弃，
-- 表现为「用户存在但 password_hash 为 NULL」，登录永远提示"用户名或密码错误"。
ALTER TABLE `sys_user` ADD COLUMN `password_hash` VARCHAR(255) NULL AFTER `username`;
ALTER TABLE `sys_user` ADD COLUMN `avatar_url` VARCHAR(500) NULL;
ALTER TABLE `sys_user` ADD COLUMN `last_login_at` DATETIME NULL;
UPDATE `sys_user` SET `password_hash` = `password` WHERE `password` IS NOT NULL AND (`password_hash` IS NULL OR `password_hash` = '');
ALTER TABLE `sys_user` DROP COLUMN `password`;

-- ---------- fact_production_30min ----------
ALTER TABLE `fact_production_30min` ADD COLUMN `brand_id` INT NULL;
ALTER TABLE `fact_production_30min` ADD COLUMN `shift_id` INT NULL;
ALTER TABLE `fact_production_30min` ADD COLUMN `start_time` TIME NULL;
ALTER TABLE `fact_production_30min` ADD COLUMN `end_time` TIME NULL;
ALTER TABLE `fact_production_30min` ADD COLUMN `yield_rate` DECIMAL(10,4) NULL;

-- ---------- product_detection_history ----------
ALTER TABLE `product_detection_history` ADD COLUMN `brand_id` INT NULL;
ALTER TABLE `product_detection_history` ADD COLUMN `shift_id` INT NULL;
ALTER TABLE `product_detection_history` ADD COLUMN `detection_time` DATETIME NULL;
ALTER TABLE `product_detection_history` ADD COLUMN `capture_speed` VARCHAR(50) NULL;
ALTER TABLE `product_detection_history` ADD COLUMN `result` VARCHAR(50) NULL;
ALTER TABLE `product_detection_history` ADD COLUMN `defect_code` VARCHAR(100) NULL;
ALTER TABLE `product_detection_history` ADD COLUMN `defect_area` VARCHAR(100) NULL;
ALTER TABLE `product_detection_history` ADD COLUMN `defect_size` VARCHAR(100) NULL;
ALTER TABLE `product_detection_history` ADD COLUMN `confidence` DECIMAL(10,4) NULL;
-- 实体字段 lineId / updateTime 在表中无对应列，缺列会导致写入抛 SQLSyntaxError
ALTER TABLE `product_detection_history` ADD COLUMN `line_id` INT NULL;
ALTER TABLE `product_detection_history` ADD COLUMN `update_time` DATETIME NULL;

-- ---------- fact_anomaly_alert ----------
ALTER TABLE `fact_anomaly_alert` ADD COLUMN `detection_id` BIGINT NULL;
ALTER TABLE `fact_anomaly_alert` ADD COLUMN `alert_type` VARCHAR(50) NULL;
ALTER TABLE `fact_anomaly_alert` ADD COLUMN `alert_level` VARCHAR(20) NULL;
ALTER TABLE `fact_anomaly_alert` ADD COLUMN `alert_title` VARCHAR(200) NULL;
ALTER TABLE `fact_anomaly_alert` ADD COLUMN `alert_message` VARCHAR(500) NULL;
ALTER TABLE `fact_anomaly_alert` ADD COLUMN `is_resolved` TINYINT DEFAULT 0;
ALTER TABLE `fact_anomaly_alert` ADD COLUMN `resolved_by` VARCHAR(100) NULL;
ALTER TABLE `fact_anomaly_alert` ADD COLUMN `resolved_at` DATETIME NULL;
ALTER TABLE `fact_anomaly_alert` ADD COLUMN `resolve_comment` VARCHAR(500) NULL;
-- 实体映射 workstation_id / create_time / update_time，表中均无
ALTER TABLE `fact_anomaly_alert` ADD COLUMN `workstation_id` INT NULL;
ALTER TABLE `fact_anomaly_alert` ADD COLUMN `create_time` DATETIME NULL;
ALTER TABLE `fact_anomaly_alert` ADD COLUMN `update_time` DATETIME NULL;

-- ---------- fact_production_info ----------
ALTER TABLE `fact_production_info` ADD COLUMN `production_date` DATE NULL;
ALTER TABLE `fact_production_info` ADD COLUMN `yield_rate` DECIMAL(10,4) NULL;
-- 实体映射 workstation_id / shift_id / create_time / update_time，
-- 而表里是 station_id 且没有时间列 —— 缺列会导致写入直接抛 SQLSyntaxError
ALTER TABLE `fact_production_info` ADD COLUMN `workstation_id` INT NULL;
ALTER TABLE `fact_production_info` ADD COLUMN `shift_id` INT NULL;
ALTER TABLE `fact_production_info` ADD COLUMN `create_time` DATETIME NULL;
ALTER TABLE `fact_production_info` ADD COLUMN `update_time` DATETIME NULL;

-- ---------- camera_images ----------
ALTER TABLE `camera_images` ADD COLUMN `image_url` VARCHAR(500) NULL;
ALTER TABLE `camera_images` ADD COLUMN `file_size` BIGINT NULL;
ALTER TABLE `camera_images` ADD COLUMN `width` INT NULL;
ALTER TABLE `camera_images` ADD COLUMN `height` INT NULL;
-- 实体有 updateTime 映射到 update_time，此列缺失会导致写入 camera_images 抛
-- "Unknown column 'update_time' in 'field list'"。
-- 该异常被 DefectImageServiceImpl 的 catch 吞掉只打 WARN，表现是「静默写不进去」。
-- 更严重的后果：DataCleanupTask 的 30 天图片清理正是按 camera_images 走的，
-- 该表写不进 → 清理任务永远查不到记录 → 磁盘上的缺陷图片只增不减。
ALTER TABLE `camera_images` ADD COLUMN `update_time` DATETIME NULL;
