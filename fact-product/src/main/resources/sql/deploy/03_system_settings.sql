-- 系统设置中心初始化脚本
-- 日期：2026-06-11
-- 用途：新增 sys_system_setting，并补齐 sys_data_retention_policy 默认策略。

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS sys_system_setting (
  setting_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  setting_key VARCHAR(100) NOT NULL,
  setting_name VARCHAR(100) NOT NULL,
  setting_group VARCHAR(50) NOT NULL,
  setting_value VARCHAR(500) NOT NULL,
  value_type VARCHAR(20) NOT NULL DEFAULT 'STRING',
  unit VARCHAR(20) NULL,
  description VARCHAR(500) NULL,
  is_editable TINYINT NOT NULL DEFAULT 1,
  sort_order INT NOT NULL DEFAULT 0,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_setting_key (setting_key),
  KEY idx_setting_group (setting_group)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统设置中心';

INSERT INTO sys_system_setting(setting_key,setting_name,setting_group,setting_value,value_type,unit,description,is_editable,sort_order)
SELECT 'system.name','系统名称','BASIC','产线缺陷在线AI检测系统','STRING',NULL,'登录页和主界面展示的系统名称',1,10
WHERE NOT EXISTS (SELECT 1 FROM sys_system_setting WHERE setting_key='system.name');
INSERT INTO sys_system_setting(setting_key,setting_name,setting_group,setting_value,value_type,unit,description,is_editable,sort_order)
SELECT 'system.siteName','站点名称','BASIC','相机检测系统','STRING',NULL,'浏览器标题和部署识别名称',1,20
WHERE NOT EXISTS (SELECT 1 FROM sys_system_setting WHERE setting_key='system.siteName');
INSERT INTO sys_system_setting(setting_key,setting_name,setting_group,setting_value,value_type,unit,description,is_editable,sort_order)
SELECT 'heartbeat.onlineSeconds','在线阈值','CAMERA','30','INTEGER','秒','心跳/上传时间小于等于该值视为在线',1,10
WHERE NOT EXISTS (SELECT 1 FROM sys_system_setting WHERE setting_key='heartbeat.onlineSeconds');
INSERT INTO sys_system_setting(setting_key,setting_name,setting_group,setting_value,value_type,unit,description,is_editable,sort_order)
SELECT 'heartbeat.offlineSeconds','离线阈值','CAMERA','120','INTEGER','秒','心跳/上传时间大于该值视为离线，中间状态为延迟',1,20
WHERE NOT EXISTS (SELECT 1 FROM sys_system_setting WHERE setting_key='heartbeat.offlineSeconds');
INSERT INTO sys_system_setting(setting_key,setting_name,setting_group,setting_value,value_type,unit,description,is_editable,sort_order)
SELECT 'defectImage.maxBatchImages','单批图片上限','UPLOAD','100','INTEGER','张','缺陷图片上传接口单批最多允许的图片数量',1,10
WHERE NOT EXISTS (SELECT 1 FROM sys_system_setting WHERE setting_key='defectImage.maxBatchImages');
INSERT INTO sys_system_setting(setting_key,setting_name,setting_group,setting_value,value_type,unit,description,is_editable,sort_order)
SELECT 'defectImage.maxFileSizeMb','单图大小上限','UPLOAD','10','INTEGER','MB','单张图片建议大小限制，实际上传上限以 application.yml 为准',1,20
WHERE NOT EXISTS (SELECT 1 FROM sys_system_setting WHERE setting_key='defectImage.maxFileSizeMb');
INSERT INTO sys_system_setting(setting_key,setting_name,setting_group,setting_value,value_type,unit,description,is_editable,sort_order)
SELECT 'alert.cpuWarning','CPU 告警阈值','ALERT','90','DECIMAL','%','CPU 使用率超过该值产生 WARNING',1,10
WHERE NOT EXISTS (SELECT 1 FROM sys_system_setting WHERE setting_key='alert.cpuWarning');
INSERT INTO sys_system_setting(setting_key,setting_name,setting_group,setting_value,value_type,unit,description,is_editable,sort_order)
SELECT 'alert.memoryWarning','内存告警阈值','ALERT','90','DECIMAL','%','内存使用率超过该值产生 WARNING',1,20
WHERE NOT EXISTS (SELECT 1 FROM sys_system_setting WHERE setting_key='alert.memoryWarning');
INSERT INTO sys_system_setting(setting_key,setting_name,setting_group,setting_value,value_type,unit,description,is_editable,sort_order)
SELECT 'alert.temperatureWarning','温度告警阈值','ALERT','70','DECIMAL','℃','设备温度超过该值产生 WARNING',1,30
WHERE NOT EXISTS (SELECT 1 FROM sys_system_setting WHERE setting_key='alert.temperatureWarning');
INSERT INTO sys_system_setting(setting_key,setting_name,setting_group,setting_value,value_type,unit,description,is_editable,sort_order)
SELECT 'analytics.dailyAggregationTime','日聚合执行时间','ANALYTICS','02:00','STRING',NULL,'夜间自动日聚合执行时间，正式调度以日聚合配置为准',1,10
WHERE NOT EXISTS (SELECT 1 FROM sys_system_setting WHERE setting_key='analytics.dailyAggregationTime');

CREATE TABLE IF NOT EXISTS sys_data_retention_policy (
  policy_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  data_type VARCHAR(50) NOT NULL,
  retention_days INT NOT NULL,
  action_type VARCHAR(50) NOT NULL DEFAULT 'DELETE',
  is_enabled TINYINT NOT NULL DEFAULT 1,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_data_type (data_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据保留策略';

INSERT INTO sys_data_retention_policy(data_type,retention_days,action_type,is_enabled)
SELECT 'DEFECT_IMAGE_FILE',30,'DELETE',1 WHERE NOT EXISTS (SELECT 1 FROM sys_data_retention_policy WHERE data_type='DEFECT_IMAGE_FILE');
INSERT INTO sys_data_retention_policy(data_type,retention_days,action_type,is_enabled)
SELECT 'DEFECT_IMAGE_BOX',90,'ARCHIVE',1 WHERE NOT EXISTS (SELECT 1 FROM sys_data_retention_policy WHERE data_type='DEFECT_IMAGE_BOX');
INSERT INTO sys_data_retention_policy(data_type,retention_days,action_type,is_enabled)
SELECT 'INSPECTION_DETAIL',90,'ARCHIVE',1 WHERE NOT EXISTS (SELECT 1 FROM sys_data_retention_policy WHERE data_type='INSPECTION_DETAIL');
INSERT INTO sys_data_retention_policy(data_type,retention_days,action_type,is_enabled)
SELECT 'AGG_30M',365,'KEEP',1 WHERE NOT EXISTS (SELECT 1 FROM sys_data_retention_policy WHERE data_type='AGG_30M');
INSERT INTO sys_data_retention_policy(data_type,retention_days,action_type,is_enabled)
SELECT 'OPERATION_LOG',180,'DELETE',1 WHERE NOT EXISTS (SELECT 1 FROM sys_data_retention_policy WHERE data_type='OPERATION_LOG');
