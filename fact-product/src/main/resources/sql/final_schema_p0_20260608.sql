-- 产线缺陷在线AI检测系统 P0 最终模型初始化脚本
-- 日期：2026-06-08
-- 说明：P0 不迁移旧数据。执行前请先备份完整数据库：
--   mysqldump -uroot -p wms-new > backup-before-final-p0.sql
-- 本脚本用于全新库或已确认可重置的测试库；旧表按最终模型重建/兼容保留。

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- 解除旧 sys_user -> dim_factory 外键，避免重建工厂维度表时类型/约束冲突。
SET @fk_name := (
  SELECT CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND REFERENCED_TABLE_NAME = 'dim_factory'
  LIMIT 1
);
SET @drop_fk_sql := IF(@fk_name IS NULL, 'DO 0', CONCAT('ALTER TABLE sys_user DROP FOREIGN KEY ', @fk_name));
PREPARE drop_fk_stmt FROM @drop_fk_sql;
EXECUTE drop_fk_stmt;
DEALLOCATE PREPARE drop_fk_stmt;

-- 解除旧统计/检测表到旧维度表的外键；P0 最终模型不迁移旧数据，旧事实表可备份后废弃。
SET @fk_name := (SELECT CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fact_production_30min' AND CONSTRAINT_NAME = 'fk_30min_line' LIMIT 1);
SET @drop_fk_sql := IF(@fk_name IS NULL, 'DO 0', CONCAT('ALTER TABLE fact_production_30min DROP FOREIGN KEY ', @fk_name));
PREPARE drop_fk_stmt FROM @drop_fk_sql; EXECUTE drop_fk_stmt; DEALLOCATE PREPARE drop_fk_stmt;
SET @fk_name := (SELECT CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fact_production_30min' AND CONSTRAINT_NAME = 'fk_30min_shift' LIMIT 1);
SET @drop_fk_sql := IF(@fk_name IS NULL, 'DO 0', CONCAT('ALTER TABLE fact_production_30min DROP FOREIGN KEY ', @fk_name));
PREPARE drop_fk_stmt FROM @drop_fk_sql; EXECUTE drop_fk_stmt; DEALLOCATE PREPARE drop_fk_stmt;
SET @fk_name := (SELECT CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fact_production_30min' AND CONSTRAINT_NAME = 'fk_30min_station' LIMIT 1);
SET @drop_fk_sql := IF(@fk_name IS NULL, 'DO 0', CONCAT('ALTER TABLE fact_production_30min DROP FOREIGN KEY ', @fk_name));
PREPARE drop_fk_stmt FROM @drop_fk_sql; EXECUTE drop_fk_stmt; DEALLOCATE PREPARE drop_fk_stmt;
SET @fk_name := (SELECT CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'product_detection_history' AND CONSTRAINT_NAME = 'fk_detection_line' LIMIT 1);
SET @drop_fk_sql := IF(@fk_name IS NULL, 'DO 0', CONCAT('ALTER TABLE product_detection_history DROP FOREIGN KEY ', @fk_name));
PREPARE drop_fk_stmt FROM @drop_fk_sql; EXECUTE drop_fk_stmt; DEALLOCATE PREPARE drop_fk_stmt;
SET @fk_name := (SELECT CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'product_detection_history' AND CONSTRAINT_NAME = 'fk_detection_shift' LIMIT 1);
SET @drop_fk_sql := IF(@fk_name IS NULL, 'DO 0', CONCAT('ALTER TABLE product_detection_history DROP FOREIGN KEY ', @fk_name));
PREPARE drop_fk_stmt FROM @drop_fk_sql; EXECUTE drop_fk_stmt; DEALLOCATE PREPARE drop_fk_stmt;
SET @fk_name := (SELECT CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'product_detection_history' AND CONSTRAINT_NAME = 'fk_detection_station' LIMIT 1);
SET @drop_fk_sql := IF(@fk_name IS NULL, 'DO 0', CONCAT('ALTER TABLE product_detection_history DROP FOREIGN KEY ', @fk_name));
PREPARE drop_fk_stmt FROM @drop_fk_sql; EXECUTE drop_fk_stmt; DEALLOCATE PREPARE drop_fk_stmt;

DROP TABLE IF EXISTS agg_defect_type_30m;
DROP TABLE IF EXISTS agg_quality_30m;
DROP TABLE IF EXISTS fact_inspection_mapping_exception;
DROP TABLE IF EXISTS fact_inspection_defect_count;
DROP TABLE IF EXISTS fact_inspection_batch;
DROP TABLE IF EXISTS fact_camera_status_current;
DROP TABLE IF EXISTS fact_camera_heartbeat;
DROP TABLE IF EXISTS cfg_camera_defect_mapping;
DROP TABLE IF EXISTS cfg_camera_token;
DROP TABLE IF EXISTS cfg_analytics_widget;
DROP TABLE IF EXISTS cfg_analytics_dashboard;
DROP TABLE IF EXISTS dim_camera;
DROP TABLE IF EXISTS dim_product;
DROP TABLE IF EXISTS dim_defect_position;
DROP TABLE IF EXISTS dim_defect_type;
DROP TABLE IF EXISTS sys_data_retention_policy;

-- 兼容旧页面的基础维度表也重建；字段保留当前代码使用的 station_code/station_name/company_id。
DROP TABLE IF EXISTS camera_status;
DROP TABLE IF EXISTS dim_workstation;
DROP TABLE IF EXISTS dim_production_line;
DROP TABLE IF EXISTS dim_factory;
DROP TABLE IF EXISTS dim_shift;

CREATE TABLE dim_factory (
  factory_id INT PRIMARY KEY AUTO_INCREMENT,
  company_id INT NULL,
  factory_code VARCHAR(50) NOT NULL,
  factory_name VARCHAR(100) NOT NULL,
  address VARCHAR(255) NULL,
  sort_order INT NOT NULL DEFAULT 0,
  is_enabled TINYINT NOT NULL DEFAULT 1,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_factory_code (factory_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工厂维度表';

CREATE TABLE dim_production_line (
  line_id INT PRIMARY KEY AUTO_INCREMENT,
  factory_id INT NOT NULL,
  line_code VARCHAR(50) NOT NULL,
  line_name VARCHAR(100) NOT NULL,
  description VARCHAR(500) NULL,
  sort_order INT NOT NULL DEFAULT 0,
  is_enabled TINYINT NOT NULL DEFAULT 1,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_factory_line_code (factory_id, line_code),
  KEY idx_factory (factory_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产线维度表';

CREATE TABLE dim_workstation (
  workstation_id INT PRIMARY KEY AUTO_INCREMENT,
  factory_id INT NULL,
  line_id INT NOT NULL,
  station_code VARCHAR(50) NOT NULL,
  station_name VARCHAR(100) NOT NULL,
  description VARCHAR(500) NULL,
  camera_id BIGINT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  is_enabled TINYINT NOT NULL DEFAULT 1,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_line_station_code (line_id, station_code),
  KEY idx_factory_line (factory_id, line_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工位维度表';

CREATE TABLE dim_camera (
  camera_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  factory_id INT NOT NULL,
  line_id INT NOT NULL,
  workstation_id INT NOT NULL,
  camera_code VARCHAR(50) NOT NULL,
  camera_name VARCHAR(100) NOT NULL,
  device_id VARCHAR(100) NULL,
  ip_address VARCHAR(64) NULL,
  vendor VARCHAR(100) NULL,
  model VARCHAR(100) NULL,
  install_position VARCHAR(100) NULL,
  description VARCHAR(500) NULL,
  sort_order INT NOT NULL DEFAULT 0,
  is_enabled TINYINT NOT NULL DEFAULT 1,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_camera_code (camera_code),
  UNIQUE KEY uk_device_id (device_id),
  KEY idx_scope (factory_id, line_id, workstation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='相机维度表';

-- 兼容旧相机状态页面的当前状态表；最终事实状态以 fact_camera_status_current 为准。
CREATE TABLE camera_status (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  camera_name VARCHAR(100) NOT NULL,
  camera_code VARCHAR(50) NOT NULL,
  line_id INT NULL,
  workstation_id INT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
  cpu_usage DECIMAL(5,2) NULL,
  memory_usage DECIMAL(5,2) NULL,
  temperature DECIMAL(5,2) NULL,
  ok_count BIGINT NOT NULL DEFAULT 0,
  ng_count BIGINT NOT NULL DEFAULT 0,
  total_count BIGINT NOT NULL DEFAULT 0,
  yield_rate DECIMAL(10,4) NULL,
  latest_image_path VARCHAR(500) NULL,
  last_heartbeat_at DATETIME NULL,
  last_upload_at DATETIME NULL,
  product_name VARCHAR(100) NULL,
  report_time DATETIME NULL,
  recognize_count BIGINT NOT NULL DEFAULT 0,
  correct_count BIGINT NOT NULL DEFAULT 0,
  abnormal_count BIGINT NOT NULL DEFAULT 0,
  error_message VARCHAR(500) NULL,
  is_active TINYINT NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_camera_code (camera_code),
  KEY idx_station (workstation_id),
  KEY idx_line (line_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='旧页面兼容相机状态表';

CREATE TABLE dim_product (
  product_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  product_code VARCHAR(50) NOT NULL,
  product_name VARCHAR(100) NOT NULL,
  product_spec VARCHAR(100) NULL,
  brand_name VARCHAR(100) NULL,
  description VARCHAR(500) NULL,
  sort_order INT NOT NULL DEFAULT 0,
  is_enabled TINYINT NOT NULL DEFAULT 1,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_product_code (product_code),
  UNIQUE KEY uk_product_name (product_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产品维度表';

CREATE TABLE dim_shift (
  shift_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  shift_code VARCHAR(50) NOT NULL,
  shift_name VARCHAR(100) NOT NULL,
  start_time TIME NOT NULL,
  end_time TIME NOT NULL,
  cross_day TINYINT NOT NULL DEFAULT 0,
  is_enabled TINYINT NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_shift_code (shift_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='班次维度表';

CREATE TABLE dim_defect_position (
  position_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  position_code VARCHAR(50) NOT NULL,
  position_name VARCHAR(100) NOT NULL,
  description VARCHAR(500) NULL,
  sort_order INT NOT NULL DEFAULT 0,
  is_enabled TINYINT NOT NULL DEFAULT 1,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_position_code (position_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='缺陷位置维度表';

CREATE TABLE dim_defect_type (
  defect_type_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  product_id BIGINT NULL,
  defect_code VARCHAR(50) NOT NULL,
  defect_name VARCHAR(100) NOT NULL,
  defect_category VARCHAR(50) NULL,
  default_position_id BIGINT NULL,
  default_position_code VARCHAR(50) NULL,
  default_position_name VARCHAR(100) NULL,
  default_severity VARCHAR(20) NOT NULL DEFAULT 'MEDIUM',
  is_ng TINYINT NOT NULL DEFAULT 1,
  description VARCHAR(500) NULL,
  sort_order INT NOT NULL DEFAULT 0,
  is_enabled TINYINT NOT NULL DEFAULT 1,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_product_defect_code (product_id, defect_code),
  KEY idx_product (product_id),
  KEY idx_position (default_position_id),
  KEY idx_severity (default_severity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='缺陷类型维度表';

CREATE TABLE cfg_camera_token (
  token_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  camera_id BIGINT NOT NULL,
  camera_code VARCHAR(50) NOT NULL,
  token_hash VARCHAR(255) NOT NULL,
  token_name VARCHAR(100) NULL,
  expire_time DATETIME NULL,
  is_enabled TINYINT NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_camera (camera_id),
  KEY idx_enabled_expire (is_enabled, expire_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='相机设备上报Token配置表';

CREATE TABLE cfg_camera_defect_mapping (
  mapping_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  factory_id INT NOT NULL,
  line_id INT NOT NULL,
  workstation_id INT NOT NULL,
  camera_id BIGINT NOT NULL,
  camera_code VARCHAR(50) NOT NULL,
  product_id BIGINT NULL,
  product_name VARCHAR(100) NULL,
  source_code VARCHAR(50) NOT NULL,
  defect_type_id BIGINT NOT NULL,
  defect_code VARCHAR(50) NOT NULL,
  defect_name VARCHAR(100) NOT NULL,
  position_id BIGINT NULL,
  position_code VARCHAR(50) NULL,
  position_name VARCHAR(100) NULL,
  severity VARCHAR(20) NOT NULL DEFAULT 'MEDIUM',
  is_ng TINYINT NOT NULL DEFAULT 1,
  is_enabled TINYINT NOT NULL DEFAULT 1,
  effective_start DATETIME NOT NULL,
  effective_end DATETIME NULL,
  create_by BIGINT NULL,
  update_by BIGINT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_camera_product_source_start (camera_id, product_id, source_code, effective_start),
  KEY idx_camera_product_source_time (camera_id, product_id, source_code, effective_start, effective_end),
  KEY idx_scope (factory_id, line_id, workstation_id, camera_id, product_id),
  KEY idx_enabled (is_enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='相机上报编码与缺陷类型映射配置表';

CREATE TABLE fact_camera_heartbeat (
  heartbeat_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  camera_id BIGINT NOT NULL,
  camera_code VARCHAR(50) NOT NULL,
  product_id BIGINT NULL,
  product_name VARCHAR(100) NULL,
  report_time DATETIME NOT NULL,
  camera_status INT NOT NULL COMMENT '0停止 1运行 2故障 3待机',
  recognize_count BIGINT NOT NULL DEFAULT 0,
  correct_count BIGINT NOT NULL DEFAULT 0,
  abnormal_count BIGINT NOT NULL DEFAULT 0,
  cpu_usage DECIMAL(5,2) NULL,
  memory_usage DECIMAL(5,2) NULL,
  temperature DECIMAL(5,2) NULL,
  error_message VARCHAR(500) NULL,
  raw_json JSON NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_camera_time (camera_id, report_time),
  KEY idx_status_time (camera_status, report_time),
  KEY idx_product_time (product_id, report_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='相机心跳上报事实表';

CREATE TABLE fact_camera_status_current (
  camera_id BIGINT PRIMARY KEY,
  camera_code VARCHAR(50) NOT NULL,
  product_id BIGINT NULL,
  product_name VARCHAR(100) NULL,
  run_status INT NOT NULL DEFAULT 9 COMMENT '0停止 1运行 2故障 3待机 9离线',
  heartbeat_state VARCHAR(20) NOT NULL DEFAULT 'OFFLINE',
  upload_state VARCHAR(20) NOT NULL DEFAULT 'NONE',
  last_heartbeat_time DATETIME NULL,
  last_upload_time DATETIME NULL,
  recognize_count BIGINT NOT NULL DEFAULT 0,
  correct_count BIGINT NOT NULL DEFAULT 0,
  abnormal_count BIGINT NOT NULL DEFAULT 0,
  cpu_usage DECIMAL(5,2) NULL,
  memory_usage DECIMAL(5,2) NULL,
  temperature DECIMAL(5,2) NULL,
  error_message VARCHAR(500) NULL,
  latest_image_url VARCHAR(500) NULL,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_run_status (run_status),
  KEY idx_heartbeat_state (heartbeat_state),
  KEY idx_upload_state (upload_state),
  KEY idx_last_heartbeat (last_heartbeat_time),
  KEY idx_last_upload (last_upload_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='相机当前状态快照表';

CREATE TABLE fact_inspection_batch (
  inspection_batch_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  batch_id VARCHAR(100) NOT NULL,
  request_id VARCHAR(100) NULL,
  schema_version VARCHAR(20) NOT NULL DEFAULT '2.0',
  camera_id BIGINT NOT NULL,
  camera_code VARCHAR(50) NOT NULL,
  camera_name VARCHAR(100) NULL,
  product_id BIGINT NOT NULL,
  product_name VARCHAR(100) NOT NULL,
  factory_id INT NOT NULL,
  line_id INT NOT NULL,
  workstation_id INT NOT NULL,
  shift_id BIGINT NOT NULL DEFAULT 0,
  report_time DATETIME NOT NULL,
  start_time DATETIME NOT NULL,
  end_time DATETIME NOT NULL,
  counter_mode VARCHAR(20) NOT NULL DEFAULT 'INCREMENTAL',
  total_count BIGINT NOT NULL DEFAULT 0,
  ok_count BIGINT NOT NULL DEFAULT 0,
  ng_count BIGINT NOT NULL DEFAULT 0,
  mapped_defect_count BIGINT NOT NULL DEFAULT 0,
  unmapped_defect_count BIGINT NOT NULL DEFAULT 0,
  status VARCHAR(20) NOT NULL DEFAULT 'ACCEPTED',
  error_message VARCHAR(500) NULL,
  raw_json JSON NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_camera_batch (camera_id, batch_id),
  KEY idx_report_scope (report_time, factory_id, line_id, workstation_id, camera_id),
  KEY idx_product_shift_time (product_id, shift_id, report_time),
  KEY idx_status_time (status, report_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='检测批次上报事实表';

CREATE TABLE fact_inspection_defect_count (
  defect_count_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  inspection_batch_id BIGINT NOT NULL,
  batch_id VARCHAR(100) NOT NULL,
  camera_id BIGINT NOT NULL,
  camera_code VARCHAR(50) NOT NULL,
  product_id BIGINT NOT NULL,
  product_name VARCHAR(100) NOT NULL,
  factory_id INT NOT NULL,
  line_id INT NOT NULL,
  workstation_id INT NOT NULL,
  shift_id BIGINT NOT NULL DEFAULT 0,
  report_time DATETIME NOT NULL,
  time_bucket_10m DATETIME NOT NULL,
  time_bucket_30m DATETIME NOT NULL,
  source_code VARCHAR(50) NOT NULL,
  mapping_id BIGINT NOT NULL,
  defect_type_id BIGINT NOT NULL,
  defect_code VARCHAR(50) NOT NULL,
  defect_name VARCHAR(100) NOT NULL,
  position_id BIGINT NULL,
  position_code VARCHAR(50) NULL,
  position_name VARCHAR(100) NULL,
  severity VARCHAR(20) NOT NULL DEFAULT 'MEDIUM',
  defect_count BIGINT NOT NULL DEFAULT 0,
  is_ng TINYINT NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_batch (inspection_batch_id),
  KEY idx_time_line_defect (time_bucket_30m, line_id, defect_type_id),
  KEY idx_time_station_defect (time_bucket_30m, workstation_id, defect_type_id),
  KEY idx_time_camera_defect (time_bucket_30m, camera_id, defect_type_id),
  KEY idx_product_defect_time (product_id, defect_type_id, report_time),
  KEY idx_filter_combo (factory_id, line_id, workstation_id, camera_id, product_id, shift_id, report_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='检测缺陷计数事实表';

CREATE TABLE fact_inspection_mapping_exception (
  exception_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  batch_id VARCHAR(100) NOT NULL,
  request_id VARCHAR(100) NULL,
  camera_id BIGINT NULL,
  camera_code VARCHAR(50) NOT NULL,
  product_id BIGINT NULL,
  product_name VARCHAR(100) NULL,
  source_code VARCHAR(50) NULL,
  exception_type VARCHAR(50) NOT NULL,
  exception_message VARCHAR(500) NOT NULL,
  suggestion VARCHAR(500) NULL,
  factory_id INT NULL,
  line_id INT NULL,
  workstation_id INT NULL,
  report_time DATETIME NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
  handled_by BIGINT NULL,
  handled_time DATETIME NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_camera_product_source_status (camera_id, product_id, source_code, status),
  KEY idx_scope_time (factory_id, line_id, workstation_id, report_time),
  KEY idx_type_status (exception_type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='检测上报映射异常事实表';

CREATE TABLE agg_quality_30m (
  agg_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  time_bucket DATETIME NOT NULL,
  factory_id INT NOT NULL,
  line_id INT NOT NULL,
  workstation_id INT NOT NULL,
  camera_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  shift_id BIGINT NOT NULL DEFAULT 0,
  total_count BIGINT NOT NULL DEFAULT 0,
  ok_count BIGINT NOT NULL DEFAULT 0,
  ng_count BIGINT NOT NULL DEFAULT 0,
  defect_count BIGINT NOT NULL DEFAULT 0,
  yield_rate DECIMAL(10,6) NULL,
  ng_rate DECIMAL(10,6) NULL,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_quality_scope (time_bucket, factory_id, line_id, workstation_id, camera_id, product_id, shift_id),
  KEY idx_factory_time (factory_id, time_bucket),
  KEY idx_line_time (line_id, time_bucket),
  KEY idx_station_time (workstation_id, time_bucket),
  KEY idx_camera_time (camera_id, time_bucket),
  KEY idx_product_time (product_id, time_bucket)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='质量30分钟聚合表';

CREATE TABLE agg_defect_type_30m (
  agg_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  time_bucket DATETIME NOT NULL,
  factory_id INT NOT NULL,
  line_id INT NOT NULL,
  workstation_id INT NOT NULL,
  camera_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  shift_id BIGINT NOT NULL DEFAULT 0,
  defect_type_id BIGINT NOT NULL,
  defect_code VARCHAR(50) NOT NULL,
  defect_name VARCHAR(100) NOT NULL,
  position_id BIGINT NULL,
  position_code VARCHAR(50) NULL,
  severity VARCHAR(20) NULL,
  defect_count BIGINT NOT NULL DEFAULT 0,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_defect_scope (time_bucket, factory_id, line_id, workstation_id, camera_id, product_id, shift_id, defect_type_id),
  KEY idx_time_defect (time_bucket, defect_type_id),
  KEY idx_product_defect_time (product_id, defect_type_id, time_bucket),
  KEY idx_line_defect_time (line_id, defect_type_id, time_bucket),
  KEY idx_station_defect_time (workstation_id, defect_type_id, time_bucket),
  KEY idx_camera_defect_time (camera_id, defect_type_id, time_bucket),
  KEY idx_position_time (position_id, time_bucket),
  KEY idx_severity_time (severity, time_bucket)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='缺陷类型30分钟聚合表';

CREATE TABLE cfg_analytics_dashboard (
  dashboard_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  dashboard_name VARCHAR(100) NOT NULL,
  factory_id INT NULL,
  owner_user_id BIGINT NULL,
  scope_type VARCHAR(20) NOT NULL DEFAULT 'FACTORY',
  is_default TINYINT NOT NULL DEFAULT 0,
  layout_json JSON NULL,
  is_enabled TINYINT NOT NULL DEFAULT 1,
  create_by BIGINT NULL,
  update_by BIGINT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_scope (scope_type, factory_id, owner_user_id),
  KEY idx_default (is_default)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='动态统计看板配置表';

CREATE TABLE cfg_analytics_widget (
  widget_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  dashboard_id BIGINT NOT NULL,
  widget_name VARCHAR(100) NOT NULL,
  widget_type VARCHAR(30) NOT NULL,
  metric_json JSON NOT NULL,
  dimension_json JSON NOT NULL,
  filter_json JSON NULL,
  option_json JSON NULL,
  layout_json JSON NOT NULL,
  follow_global_filter TINYINT NOT NULL DEFAULT 1,
  sort_order INT NOT NULL DEFAULT 0,
  is_enabled TINYINT NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_dashboard (dashboard_id),
  KEY idx_type (widget_type),
  KEY idx_enabled (is_enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='动态统计图表组件配置表';

CREATE TABLE sys_data_retention_policy (
  policy_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  data_type VARCHAR(50) NOT NULL,
  retention_days INT NOT NULL,
  action_type VARCHAR(50) NOT NULL DEFAULT 'DELETE',
  is_enabled TINYINT NOT NULL DEFAULT 1,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_data_type (data_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据保留策略配置表';

-- 杭州工厂 P0 验收测试数据
INSERT INTO dim_factory(factory_id, factory_code, factory_name, address, sort_order) VALUES
(1, 'HZ-FAC-01', '杭州工厂', '浙江省杭州市', 1);

INSERT INTO dim_production_line(line_id, factory_id, line_code, line_name, description, sort_order) VALUES
(1, 1, 'HZ-L01', '杭州1号产线', 'P0验收主线', 1),
(2, 1, 'HZ-L02', '杭州2号产线', 'P0验收辅线', 2);

INSERT INTO dim_workstation(workstation_id, factory_id, line_id, station_code, station_name, description, sort_order) VALUES
(1, 1, 1, 'HZ-L01-WS01', '上料检测工位', '上料外观检测', 1),
(2, 1, 1, 'HZ-L01-WS02', '涂布检测工位', '涂布缺陷检测', 2),
(3, 1, 1, 'HZ-L01-WS03', '复合检测工位', '复合缺陷检测', 3),
(4, 1, 1, 'HZ-L01-WS04', '包装检测工位', '包装前检测', 4),
(5, 1, 2, 'HZ-L02-WS01', '二线涂布工位', '二线涂布检测', 1),
(6, 1, 2, 'HZ-L02-WS02', '二线复合工位', '二线复合检测', 2);

INSERT INTO dim_camera(camera_id, factory_id, line_id, workstation_id, camera_code, camera_name, device_id, install_position, sort_order) VALUES
(1, 1, 1, 1, 'CAM-001', '杭州1线-上料相机A', 'DEV-CAM-001', '上料左侧', 1),
(2, 1, 1, 1, 'CAM-002', '杭州1线-上料相机B', 'DEV-CAM-002', '上料右侧', 2),
(3, 1, 1, 2, 'CAM-003', '杭州1线-涂布相机A', 'DEV-CAM-003', '涂布入口', 3),
(4, 1, 1, 2, 'CAM-004', '杭州1线-涂布相机B', 'DEV-CAM-004', '涂布出口', 4),
(5, 1, 1, 3, 'CAM-005', '杭州1线-复合相机A', 'DEV-CAM-005', '复合入口', 5),
(6, 1, 1, 4, 'CAM-006', '杭州1线-包装相机A', 'DEV-CAM-006', '包装前', 6),
(7, 1, 2, 5, 'CAM-007', '杭州2线-涂布相机A', 'DEV-CAM-007', '二线涂布', 7),
(8, 1, 2, 6, 'CAM-008', '杭州2线-复合相机A', 'DEV-CAM-008', '二线复合', 8);

INSERT INTO camera_status(camera_name, camera_code, line_id, workstation_id, status, product_name, is_active)
SELECT camera_name, camera_code, line_id, workstation_id, 'RUNNING', '产品A', 1 FROM dim_camera;

INSERT INTO dim_product(product_id, product_code, product_name, product_spec, brand_name, sort_order) VALUES
(1, 'PROD-A', '产品A', '杭州验收规格A', '默认品牌', 1),
(2, 'PROD-B', '产品B', '杭州验收规格B', '默认品牌', 2);

INSERT INTO dim_shift(shift_id, shift_code, shift_name, start_time, end_time, cross_day) VALUES
(1, 'DAY', '白班', '08:00:00', '20:00:00', 0),
(2, 'NIGHT', '夜班', '20:00:00', '08:00:00', 1);

INSERT INTO dim_defect_position(position_id, position_code, position_name, sort_order) VALUES
(1, 'INNER', '内装区域', 1),
(2, 'FRONT', '前腰腹区域', 2),
(3, 'REAR', '后腰腹区域', 3),
(4, 'COMPOSITE', '复合区域', 4),
(5, 'EDGE', '边缘区域', 5);

INSERT INTO dim_defect_type(defect_type_id, product_id, defect_code, defect_name, defect_category, default_position_id, default_position_code, default_position_name, default_severity, sort_order) VALUES
(1, 1, 'P0_INNER_GLUE_SMALL', '内装胶滴-小', '胶滴', 1, 'INNER', '内装区域', 'LOW', 0),
(2, 1, 'P1_INNER_GLUE_LARGE', '内装胶滴-大', '胶滴', 1, 'INNER', '内装区域', 'HIGH', 1),
(3, 1, 'P2_INNER_DIRT_SMALL', '内装异物-小', '异物', 1, 'INNER', '内装区域', 'LOW', 2),
(4, 1, 'P3_INNER_DIRT_LARGE', '内装异物-大', '异物', 1, 'INNER', '内装区域', 'HIGH', 3),
(5, 1, 'P4_FRONT_GLUE_SMALL', '前腰腹胶滴-小', '胶滴', 2, 'FRONT', '前腰腹区域', 'LOW', 4),
(6, 1, 'P5_FRONT_GLUE_LARGE', '前腰腹胶滴-大', '胶滴', 2, 'FRONT', '前腰腹区域', 'HIGH', 5),
(7, 1, 'P6_FRONT_DIRT_SMALL', '前腰腹异物-小', '异物', 2, 'FRONT', '前腰腹区域', 'LOW', 6),
(8, 1, 'P7_FRONT_DIRT_LARGE', '前腰腹异物-大', '异物', 2, 'FRONT', '前腰腹区域', 'HIGH', 7),
(9, 1, 'P8_REAR_GLUE_SMALL', '后腰腹胶滴-小', '胶滴', 3, 'REAR', '后腰腹区域', 'LOW', 8),
(10, 1, 'P9_REAR_GLUE_LARGE', '后腰腹胶滴-大', '胶滴', 3, 'REAR', '后腰腹区域', 'HIGH', 9),
(11, 1, 'P10_REAR_DIRT_SMALL', '后腰腹异物-小', '异物', 3, 'REAR', '后腰腹区域', 'LOW', 10),
(12, 1, 'P11_REAR_DIRT_LARGE', '后腰腹异物-大', '异物', 3, 'REAR', '后腰腹区域', 'HIGH', 11),
(13, 1, 'P12_COMPOSITE_GLUE', '复合区域胶滴', '胶滴', 4, 'COMPOSITE', '复合区域', 'MEDIUM', 12),
(14, 1, 'P13_COMPOSITE_DIRT', '复合区域异物', '异物', 4, 'COMPOSITE', '复合区域', 'MEDIUM', 13),
(15, 1, 'P14_EDGE_DAMAGE', '边缘破损', '破损', 5, 'EDGE', '边缘区域', 'HIGH', 14);

INSERT INTO cfg_camera_token(camera_id, camera_code, token_hash, token_name)
SELECT camera_id, camera_code, 'dev-token', CONCAT(camera_code, '调试Token') FROM dim_camera;

INSERT INTO cfg_camera_defect_mapping(factory_id, line_id, workstation_id, camera_id, camera_code, product_id, product_name,
  source_code, defect_type_id, defect_code, defect_name, position_id, position_code, position_name, severity, is_ng, effective_start)
SELECT c.factory_id, c.line_id, c.workstation_id, c.camera_id, c.camera_code, p.product_id, p.product_name,
       CONCAT('p', dt.sort_order), dt.defect_type_id, dt.defect_code, dt.defect_name,
       dt.default_position_id, dt.default_position_code, dt.default_position_name, dt.default_severity, dt.is_ng, '2026-01-01 00:00:00'
FROM dim_camera c
JOIN dim_product p ON p.product_id = 1
JOIN dim_defect_type dt ON dt.product_id = p.product_id;

INSERT INTO fact_camera_status_current(camera_id, camera_code, product_id, product_name, run_status, heartbeat_state, upload_state)
SELECT camera_id, camera_code, 1, '产品A', 9, 'OFFLINE', 'NONE' FROM dim_camera;

INSERT INTO sys_data_retention_policy(data_type, retention_days, action_type) VALUES
('DEFECT_IMAGE_FILE', 30, 'DELETE'),
('DEFECT_IMAGE_BOX', 90, 'ARCHIVE'),
('INSPECTION_DETAIL', 90, 'ARCHIVE'),
('AGG_30M', 365, 'KEEP'),
('OPERATION_LOG', 180, 'DELETE');


-- 恢复 sys_user 到 dim_factory 的外键（若 sys_user 中存在新工厂表不存在的 factory_id，请先清理后再执行）。
SET @has_user_factory_fk := (
  SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND CONSTRAINT_NAME = 'fk_user_factory'
);
SET @add_fk_sql := IF(@has_user_factory_fk > 0, 'DO 0', 'ALTER TABLE sys_user ADD CONSTRAINT fk_user_factory FOREIGN KEY (factory_id) REFERENCES dim_factory(factory_id)');
PREPARE add_fk_stmt FROM @add_fk_sql;
EXECUTE add_fk_stmt;
DEALLOCATE PREPARE add_fk_stmt;

SET FOREIGN_KEY_CHECKS = 1;