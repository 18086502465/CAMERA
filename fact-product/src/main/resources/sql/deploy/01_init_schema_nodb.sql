-- ============================================================
-- 01_init_schema_nodb.sql  (产线缺陷在线AI检测系统)
-- init_schema.sql 的部署版：已剥离原脚本第 7 行的 `USE `wms-new`;`，
-- 避免导入时强制切换到非目标库。请用 mysql 指定目标库执行本文件。
-- 执行顺序：01 -> 02 -> 03 -> 04
-- ============================================================
-- ============================================================
-- 产线相机检测系统 - 数据库初始化 SQL
-- 数据库：MySQL 8.0+, 字符集：utf8mb4
-- 生成时间：2026-05-28
-- ============================================================


-- 删除表（按外键依赖逆序）
DROP TABLE IF EXISTS `fact_production_30min_archive`;
DROP TABLE IF EXISTS `sys_operation_log`;
DROP TABLE IF EXISTS `sys_holiday_config`;
DROP TABLE IF EXISTS `sys_user`;
DROP TABLE IF EXISTS `fact_production_info`;
DROP TABLE IF EXISTS `fact_anomaly_alert`;
DROP TABLE IF EXISTS `product_detection_history`;
DROP TABLE IF EXISTS `fact_production_30min`;
DROP TABLE IF EXISTS `camera_images`;
DROP TABLE IF EXISTS `camera_status`;
DROP TABLE IF EXISTS `dim_time_bucket_30min`;
DROP TABLE IF EXISTS `dim_shift`;
DROP TABLE IF EXISTS `dim_workstation`;
DROP TABLE IF EXISTS `dim_product_brand`;
DROP TABLE IF EXISTS `dim_production_line`;
DROP TABLE IF EXISTS `dim_factory`;
DROP TABLE IF EXISTS `dim_company`;

-- ============================================================
-- 维度表
-- ============================================================

-- 1. 公司表
CREATE TABLE `dim_company` (
    `company_id`      INT AUTO_INCREMENT PRIMARY KEY,
    `company_name`    VARCHAR(100) NOT NULL COMMENT '公司名称',
    `company_code`    VARCHAR(50)  NOT NULL COMMENT '公司编码',
    `description`     VARCHAR(500) DEFAULT NULL COMMENT '描述',
    `contact_person`  VARCHAR(50)  DEFAULT NULL COMMENT '联系人',
    `contact_phone`   VARCHAR(20)  DEFAULT NULL COMMENT '联系电话',
    `address`         VARCHAR(200) DEFAULT NULL COMMENT '地址',
    `is_deleted`      INT(1) DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    `create_time`     DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_company_code` (`company_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公司维度表';

-- 2. 工厂表
CREATE TABLE `dim_factory` (
    `factory_id`   INT AUTO_INCREMENT PRIMARY KEY,
    `company_id`   INT          NOT NULL COMMENT '所属公司ID',
    `factory_name` VARCHAR(100) NOT NULL COMMENT '工厂名称',
    `factory_code` VARCHAR(50)  NOT NULL COMMENT '工厂编码',
    `address`      VARCHAR(200) DEFAULT NULL COMMENT '地址',
    `is_deleted`   INT(1) DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    `create_time`  DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_factory_code` (`factory_code`),
    KEY `idx_company_id` (`company_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工厂维度表';

-- 3. 产线表
CREATE TABLE `dim_production_line` (
    `line_id`      INT AUTO_INCREMENT PRIMARY KEY,
    `factory_id`   INT          NOT NULL COMMENT '所属工厂ID',
    `line_name`    VARCHAR(100) NOT NULL COMMENT '产线名称',
    `line_code`    VARCHAR(50)  NOT NULL COMMENT '产线编码',
    `description`  VARCHAR(500) DEFAULT NULL COMMENT '描述',
    `is_deleted`   INT(1) DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    `create_time`  DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_line_code` (`line_code`),
    KEY `idx_factory_id` (`factory_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产线维度表';

-- 4. 产品品牌表
CREATE TABLE `dim_product_brand` (
    `brand_id`   INT AUTO_INCREMENT PRIMARY KEY,
    `brand_code` VARCHAR(50)  NOT NULL COMMENT '品牌编码：QBB, SZWG, BC_SUMMER',
    `brand_name` VARCHAR(100) NOT NULL COMMENT '品牌中文名：亲宝宝、狮子王国等',
    UNIQUE KEY `uk_brand_code` (`brand_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产品品牌维度表';

-- 5. 班次维度表
CREATE TABLE `dim_shift` (
    `shift_id`    INT AUTO_INCREMENT PRIMARY KEY,
    `shift_code`  VARCHAR(20)  NOT NULL COMMENT '班次代码：DAY=白班，NIGHT=晚班',
    `shift_name`  VARCHAR(50)  NOT NULL COMMENT '班次名称',
    `start_time`  TIME NOT NULL COMMENT '开始时间，如 08:00:00',
    `end_time`    TIME NOT NULL COMMENT '结束时间，如 20:00:00',
    UNIQUE KEY `uk_shift_code` (`shift_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='班次维度表';

-- 6. 工位表
CREATE TABLE `dim_workstation` (
    `station_id`   INT AUTO_INCREMENT PRIMARY KEY,
    `line_id`      INT         NOT NULL COMMENT '所属产线ID',
    `station_code` VARCHAR(50) NOT NULL COMMENT '工位编码，如 WS01',
    `station_name` VARCHAR(100) DEFAULT NULL COMMENT '工位名称',
    `camera_code`  VARCHAR(100) DEFAULT NULL COMMENT '绑定的相机编号（唯一）',
    `sort_order`   INT DEFAULT 0 COMMENT '工位顺序',
    UNIQUE KEY `uk_station_code` (`station_code`),
    UNIQUE KEY `uk_camera_code` (`camera_code`),
    KEY `idx_line_id` (`line_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工位维度表';

-- 7. 30分钟时间分桶维度表
CREATE TABLE `dim_time_bucket_30min` (
    `bucket_id`   INT AUTO_INCREMENT PRIMARY KEY,
    `full_date`   DATE      NOT NULL COMMENT '日期部分',
    `hour`        TINYINT   NOT NULL COMMENT '小时（0-23）',
    `minute_group` VARCHAR(10) NOT NULL COMMENT '分钟分组：00表示[xx:00~xx:30)，30表示[xx:30~yy:00)',
    `start_time`  DATETIME  NOT NULL COMMENT '该30分钟段起始时间',
    `end_time`    DATETIME  NOT NULL COMMENT '该30分钟段结束时间',
    KEY `idx_full_date` (`full_date`),
    KEY `idx_start_time` (`start_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='30分钟时间分桶维度表';


-- ============================================================
-- 事实表
-- ============================================================

-- 8. 相机状态表
CREATE TABLE `camera_status` (
    `id`                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    `camera_name`         VARCHAR(100) NOT NULL COMMENT '相机名称/编号',
    `status`              VARCHAR(20)  DEFAULT 'OFFLINE' COMMENT '状态：ONLINE/OFFLINE/WARNING',
    `last_heartbeat`      DATETIME     DEFAULT NULL COMMENT '最后心跳时间',
    `latest_image_path`   VARCHAR(500) DEFAULT NULL COMMENT '最新图片路径',
    `factory_id`          INT          DEFAULT NULL COMMENT '所属工厂ID',
    `production_line_id`  INT          DEFAULT NULL COMMENT '所属产线ID',
    `workstation_id`      INT          DEFAULT NULL COMMENT '所属工位ID',
    `create_time`         DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`         DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_camera_name` (`camera_name`),
    KEY `idx_factory_id` (`factory_id`),
    KEY `idx_line_id` (`production_line_id`),
    KEY `idx_workstation_id` (`workstation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='相机状态表';

-- 9. 相机图片表
CREATE TABLE `camera_images` (
    `id`                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    `camera_name`        VARCHAR(100) NOT NULL COMMENT '相机名称',
    `image_path`         VARCHAR(500) NOT NULL COMMENT '图片存储路径',
    `bucket_date`        DATE      DEFAULT NULL COMMENT '所属日期桶',
    `bucket_index`       INT       DEFAULT NULL COMMENT '所属时间桶序号（0-47）',
    `capture_time`       DATETIME  DEFAULT NULL COMMENT '拍摄时间',
    `upload_time`        DATETIME  DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    `expire_time`        DATETIME  DEFAULT NULL COMMENT '过期时间（30天后）',
    `factory_id`         INT       DEFAULT NULL COMMENT '工厂ID',
    `production_line_id` INT       DEFAULT NULL COMMENT '产线ID',
    `workstation_id`     INT       DEFAULT NULL COMMENT '工位ID',
    `create_time`        DATETIME  DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    KEY `idx_camera_name` (`camera_name`),
    KEY `idx_upload_time` (`upload_time`),
    -- 2026-11-07 v3：capture_time 是两处清理的判据（DataCleanupTask 的 1000 行/批循环、
    -- cleanup-defect-images.sh 的 DELETE），01 原先没给它建索引 —— 没索引就是每批一次全表扫。
    KEY `idx_capture_time` (`capture_time`),
    KEY `idx_bucket` (`bucket_date`, `bucket_index`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='相机图片表';

-- 10. 产品检测历史表
CREATE TABLE `product_detection_history` (
    `id`                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    `start_time`          DATETIME     DEFAULT NULL COMMENT '检测开始时间',
    `end_time`            DATETIME     DEFAULT NULL COMMENT '检测结束时间',
    `detection_result`    INT(1)      DEFAULT NULL COMMENT '检测结果：0-NG，1-OK',
    `defect_type`         VARCHAR(100) DEFAULT NULL COMMENT '缺陷类型编码',
    `image_path`          VARCHAR(500) DEFAULT NULL COMMENT '关联图片路径',
    `camera_name`         VARCHAR(100) DEFAULT NULL COMMENT '相机名称',
    `factory_id`          INT          DEFAULT NULL COMMENT '工厂ID',
    `production_line_id`  INT          DEFAULT NULL COMMENT '产线ID',
    `workstation_id`      INT          DEFAULT NULL COMMENT '工位ID',
    `create_time`         DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    KEY `idx_camera_name` (`camera_name`),
    KEY `idx_start_time` (`start_time`),
    KEY `idx_workstation_id` (`workstation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产品检测历史表';

-- 11. 30分钟产量事实表
CREATE TABLE `fact_production_30min` (
    `id`                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    `workstation_id`     INT       DEFAULT NULL COMMENT '工位ID',
    `bucket_date`        DATE      DEFAULT NULL COMMENT '日期桶',
    `bucket_index`       INT       DEFAULT NULL COMMENT '时间桶序号（0-47）',
    `ok_count`           INT       DEFAULT 0 COMMENT 'OK数量',
    `ng_count`           INT       DEFAULT 0 COMMENT 'NG数量',
    `total_count`        INT       DEFAULT 0 COMMENT '总产量',
    `station_id`         INT       DEFAULT NULL COMMENT '备用工位ID',
    `line_id`            INT       DEFAULT NULL COMMENT '产线ID',
    `create_time`        DATETIME  DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`        DATETIME  DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_bucket_workstation` (`bucket_date`, `bucket_index`, `workstation_id`),
    KEY `idx_bucket_date` (`bucket_date`),
    KEY `idx_workstation_id` (`workstation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='30分钟产量事实表';

-- 12. 30分钟产量归档表
CREATE TABLE `fact_production_30min_archive` (
    `id`              BIGINT AUTO_INCREMENT PRIMARY KEY,
    `line_id`         INT       DEFAULT NULL COMMENT '产线ID',
    `workstation_id`  INT       DEFAULT NULL COMMENT '工位ID',
    `brand_id`        INT       DEFAULT NULL COMMENT '品牌ID',
    `shift_id`        INT       DEFAULT NULL COMMENT '班次ID',
    `bucket_date`     DATE      DEFAULT NULL COMMENT '日期桶',
    `bucket_index`    INT       DEFAULT NULL COMMENT '时间桶序号',
    `start_time`      VARCHAR(20) DEFAULT NULL COMMENT '开始时间描述',
    `end_time`        VARCHAR(20) DEFAULT NULL COMMENT '结束时间描述',
    `total_count`     INT        DEFAULT 0 COMMENT '总产量',
    `ok_count`        INT        DEFAULT 0 COMMENT 'OK数量',
    `ng_count`        INT        DEFAULT 0 COMMENT 'NG数量',
    `yield_rate`      DOUBLE     DEFAULT NULL COMMENT '良率',
    `archive_time`    DATETIME   DEFAULT CURRENT_TIMESTAMP COMMENT '归档时间',
    `create_time`     DATETIME   DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME   DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    KEY `idx_bucket_date` (`bucket_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='30分钟产量归档表';

-- 13. 实时生产数据表
CREATE TABLE `fact_production_info` (
    `id`           BIGINT AUTO_INCREMENT PRIMARY KEY,
    `factory_id`   INT          DEFAULT NULL COMMENT '工厂ID',
    `line_id`      INT          DEFAULT NULL COMMENT '产线ID',
    `station_id`   INT          DEFAULT NULL COMMENT '工位ID',
    `brand_id`     INT          DEFAULT NULL COMMENT '品牌ID',
    `total_count`  INT          DEFAULT 0 COMMENT '总产量',
    `ok_count`     INT          DEFAULT 0 COMMENT 'OK数量',
    `ng_count`     INT          DEFAULT 0 COMMENT 'NG数量',
    `jietou`       INT          DEFAULT 0 COMMENT '材料接头',
    `neizhuang_dijiao_1to3` INT DEFAULT 0 COMMENT '内装滴胶',
    `start_at`     DATETIME     DEFAULT NULL COMMENT '开始时间',
    `end_at`       DATETIME     DEFAULT NULL COMMENT '结束时间',
    KEY `idx_line_id` (`line_id`),
    KEY `idx_start_at` (`start_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='实时生产数据表';

-- 14. 异常告警事实表
CREATE TABLE `fact_anomaly_alert` (
    `alert_id`         BIGINT AUTO_INCREMENT PRIMARY KEY,
    `line_id`         INT          DEFAULT NULL COMMENT '关联产线ID',
    `station_id`      INT          DEFAULT NULL COMMENT '关联工位ID（可为空）',
    `detect_time`     DATETIME     NOT NULL COMMENT '检测到异常的时间',
    `severity`        VARCHAR(20)  DEFAULT 'MEDIUM' COMMENT '告警严重度：HIGH/MEDIUM/LOW',
    `message`         VARCHAR(500) NOT NULL COMMENT '告警内容',
    `suggested_action` VARCHAR(500) DEFAULT NULL COMMENT '建议处理措施',
    `is_active`       TINYINT(1)  DEFAULT 1 COMMENT '是否有效（未过期）',
    `expire_time`     DATETIME     DEFAULT NULL COMMENT '过期时间，自动失效',
    KEY `idx_detect_time` (`detect_time`),
    KEY `idx_line_id` (`line_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='异常告警事实表';


-- ============================================================
-- 系统表
-- ============================================================

-- 15. 用户表
CREATE TABLE `sys_user` (
    `user_id`      BIGINT AUTO_INCREMENT PRIMARY KEY,
    `username`     VARCHAR(50)  NOT NULL COMMENT '登录用户名',
    `password`     VARCHAR(100) NOT NULL COMMENT 'BCrypt加密密码',
    `real_name`    VARCHAR(50)  DEFAULT NULL COMMENT '真实姓名',
    `role`         INT(1)      NOT NULL DEFAULT 0 COMMENT '角色：0-普通用户，1-管理员',
    `factory_id`   INT          DEFAULT NULL COMMENT '限定工厂范围（工人）',
    `is_disabled`  INT(1)      DEFAULT 0 COMMENT '是否禁用：0-正常，1-禁用',
    `create_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户表';

-- 16. 节假日配置表
CREATE TABLE `sys_holiday_config` (
    `config_id`    INT AUTO_INCREMENT PRIMARY KEY,
    `config_date`  DATE      NOT NULL COMMENT '日期',
    `day_type`     INT(1)   NOT NULL DEFAULT 0 COMMENT '0-工作日，1-休息日',
    `description`  VARCHAR(100) DEFAULT NULL COMMENT '说明（如：劳动节、调休）',
    `create_time`  DATETIME  DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME  DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_config_date` (`config_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='节假日配置表';

-- 17. 操作日志表
CREATE TABLE `sys_operation_log` (
    `log_id`           BIGINT AUTO_INCREMENT PRIMARY KEY,
    `user_id`          INT          DEFAULT NULL COMMENT '用户ID',
    `user_name`        VARCHAR(50)  DEFAULT NULL COMMENT '用户名',
    `operation_type`   VARCHAR(20)  DEFAULT NULL COMMENT '操作类型：LOGIN/CREATE/UPDATE/DELETE/EXPORT/QUERY',
    `module_name`      VARCHAR(50)  DEFAULT NULL COMMENT '模块名称',
    `operation_desc`   VARCHAR(500) DEFAULT NULL COMMENT '操作描述',
    `request_method`   VARCHAR(10)  DEFAULT NULL COMMENT '请求方法：GET/POST/PUT/DELETE',
    `request_uri`      VARCHAR(200) DEFAULT NULL COMMENT '请求URI',
    `request_params`   TEXT         DEFAULT NULL COMMENT '请求参数',
    `ip_address`       VARCHAR(50)  DEFAULT NULL COMMENT 'IP地址',
    `operation_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    `execution_time`   INT          DEFAULT NULL COMMENT '执行耗时(ms)',
    `operation_result` INT(1)      DEFAULT 1 COMMENT '0-失败，1-成功',
    `error_message`    VARCHAR(500) DEFAULT NULL COMMENT '错误信息',
    KEY `idx_user_id` (`user_id`),
    KEY `idx_operation_time` (`operation_time`),
    KEY `idx_module_name` (`module_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作日志表';


-- ============================================================
-- 初始化数据
-- ============================================================

-- 班次数据（早八晚八）
INSERT INTO `dim_shift` (`shift_code`, `shift_name`, `start_time`, `end_time`) VALUES
('DAY',   '白班', '08:00:00', '20:00:00'),
('NIGHT', '晚班', '20:00:00', '08:00:00');

-- 管理员账号（密码：123456，BCrypt加密）
-- BCrypt hash for "123456" (已实测 matches=true): $2a$10$yFm9KX1BAMStwgNDD8pQA.Qze3.R.kzzg81qqZR5WlBt5RdsGvRYq
INSERT INTO `sys_user` (`username`, `password`, `real_name`, `role`, `factory_id`, `is_disabled`) VALUES
('admin', '$2a$10$yFm9KX1BAMStwgNDD8pQA.Qze3.R.kzzg81qqZR5WlBt5RdsGvRYq', '系统管理员', 1, NULL, 0);

-- 公司数据
INSERT INTO `dim_company` (`company_name`, `company_code`, `description`, `contact_person`, `contact_phone`, `address`) VALUES
('深大科技有限公司', 'SENDA', '新能源尿不湿产线制造企业', '张总', '13800000000', '广东省深圳市');

-- 工厂数据
INSERT INTO `dim_factory` (`company_id`, `factory_name`, `factory_code`, `address`) VALUES
(1, '深圳工厂', 'SZ-001', '广东省深圳市');

-- 产线数据
INSERT INTO `dim_production_line` (`factory_id`, `line_name`, `line_code`, `description`) VALUES
(1, '产线A', 'LINE-A', '尿不湿生产产线A'),
(1, '产线B', 'LINE-B', '尿不湿生产产线B');

-- 产品品牌数据
INSERT INTO `dim_product_brand` (`brand_code`, `brand_name`) VALUES
('QBB',      '亲宝宝'),
('SZWG',     '狮子王国'),
('BC_SUMMER','海滩夏日');

-- 工位数据（每产线30个工位，共60个）
-- 产线A 工位
INSERT INTO `dim_workstation` (`line_id`, `station_code`, `station_name`, `camera_code`, `sort_order`) VALUES
(1, 'WS-A01', '产线A-工位01', 'CAM-A01', 1),
(1, 'WS-A02', '产线A-工位02', 'CAM-A02', 2),
(1, 'WS-A03', '产线A-工位03', 'CAM-A03', 3),
(1, 'WS-A04', '产线A-工位04', 'CAM-A04', 4),
(1, 'WS-A05', '产线A-工位05', 'CAM-A05', 5),
(1, 'WS-A06', '产线A-工位06', 'CAM-A06', 6),
(1, 'WS-A07', '产线A-工位07', 'CAM-A07', 7),
(1, 'WS-A08', '产线A-工位08', 'CAM-A08', 8),
(1, 'WS-A09', '产线A-工位09', 'CAM-A09', 9),
(1, 'WS-A10', '产线A-工位10', 'CAM-A10', 10),
(1, 'WS-A11', '产线A-工位11', 'CAM-A11', 11),
(1, 'WS-A12', '产线A-工位12', 'CAM-A12', 12),
(1, 'WS-A13', '产线A-工位13', 'CAM-A13', 13),
(1, 'WS-A14', '产线A-工位14', 'CAM-A14', 14),
(1, 'WS-A15', '产线A-工位15', 'CAM-A15', 15),
(1, 'WS-A16', '产线A-工位16', 'CAM-A16', 16),
(1, 'WS-A17', '产线A-工位17', 'CAM-A17', 17),
(1, 'WS-A18', '产线A-工位18', 'CAM-A18', 18),
(1, 'WS-A19', '产线A-工位19', 'CAM-A19', 19),
(1, 'WS-A20', '产线A-工位20', 'CAM-A20', 20),
(1, 'WS-A21', '产线A-工位21', 'CAM-A21', 21),
(1, 'WS-A22', '产线A-工位22', 'CAM-A22', 22),
(1, 'WS-A23', '产线A-工位23', 'CAM-A23', 23),
(1, 'WS-A24', '产线A-工位24', 'CAM-A24', 24),
(1, 'WS-A25', '产线A-工位25', 'CAM-A25', 25),
(1, 'WS-A26', '产线A-工位26', 'CAM-A26', 26),
(1, 'WS-A27', '产线A-工位27', 'CAM-A27', 27),
(1, 'WS-A28', '产线A-工位28', 'CAM-A28', 28),
(1, 'WS-A29', '产线A-工位29', 'CAM-A29', 29),
(1, 'WS-A30', '产线A-工位30', 'CAM-A30', 30);

-- 产线B 工位
INSERT INTO `dim_workstation` (`line_id`, `station_code`, `station_name`, `camera_code`, `sort_order`) VALUES
(2, 'WS-B01', '产线B-工位01', 'CAM-B01', 1),
(2, 'WS-B02', '产线B-工位02', 'CAM-B02', 2),
(2, 'WS-B03', '产线B-工位03', 'CAM-B03', 3),
(2, 'WS-B04', '产线B-工位04', 'CAM-B04', 4),
(2, 'WS-B05', '产线B-工位05', 'CAM-B05', 5),
(2, 'WS-B06', '产线B-工位06', 'CAM-B06', 6),
(2, 'WS-B07', '产线B-工位07', 'CAM-B07', 7),
(2, 'WS-B08', '产线B-工位08', 'CAM-B08', 8),
(2, 'WS-B09', '产线B-工位09', 'CAM-B09', 9),
(2, 'WS-B10', '产线B-工位10', 'CAM-B10', 10),
(2, 'WS-B11', '产线B-工位11', 'CAM-B11', 11),
(2, 'WS-B12', '产线B-工位12', 'CAM-B12', 12),
(2, 'WS-B13', '产线B-工位13', 'CAM-B13', 13),
(2, 'WS-B14', '产线B-工位14', 'CAM-B14', 14),
(2, 'WS-B15', '产线B-工位15', 'CAM-B15', 15),
(2, 'WS-B16', '产线B-工位16', 'CAM-B16', 16),
(2, 'WS-B17', '产线B-工位17', 'CAM-B17', 17),
(2, 'WS-B18', '产线B-工位18', 'CAM-B18', 18),
(2, 'WS-B19', '产线B-工位19', 'CAM-B19', 19),
(2, 'WS-B20', '产线B-工位20', 'CAM-B20', 20),
(2, 'WS-B21', '产线B-工位21', 'CAM-B21', 21),
(2, 'WS-B22', '产线B-工位22', 'CAM-B22', 22),
(2, 'WS-B23', '产线B-工位23', 'CAM-B23', 23),
(2, 'WS-B24', '产线B-工位24', 'CAM-B24', 24),
(2, 'WS-B25', '产线B-工位25', 'CAM-B25', 25),
(2, 'WS-B26', '产线B-工位26', 'CAM-B26', 26),
(2, 'WS-B27', '产线B-工位27', 'CAM-B27', 27),
(2, 'WS-B28', '产线B-工位28', 'CAM-B28', 28),
(2, 'WS-B29', '产线B-工位29', 'CAM-B29', 29),
(2, 'WS-B30', '产线B-工位30', 'CAM-B30', 30);

-- 2026年节假日配置（工作日=0，休息日=1）
-- 元旦：2026-01-01~01-03
INSERT INTO `sys_holiday_config` (`config_date`, `day_type`, `description`) VALUES
('2026-01-01', 1, '元旦'),
('2026-01-02', 1, '元旦'),
('2026-01-03', 1, '元旦');

-- 春节：2026-02-17~02-23（除夕到初六）
INSERT INTO `sys_holiday_config` (`config_date`, `day_type`, `description`) VALUES
('2026-02-17', 1, '春节除夕'),
('2026-02-18', 1, '春节初一'),
('2026-02-19', 1, '春节初二'),
('2026-02-20', 1, '春节初三'),
('2026-02-21', 1, '春节初四'),
('2026-02-22', 1, '春节初五'),
('2026-02-23', 1, '春节初六');

-- 清明：2026-04-04~04-06
INSERT INTO `sys_holiday_config` (`config_date`, `day_type`, `description`) VALUES
('2026-04-04', 1, '清明节'),
('2026-04-05', 1, '清明节'),
('2026-04-06', 1, '清明节');

-- 劳动节：2026-05-01~05-05
INSERT INTO `sys_holiday_config` (`config_date`, `day_type`, `description`) VALUES
('2026-05-01', 1, '劳动节'),
('2026-05-02', 1, '劳动节'),
('2026-05-03', 1, '劳动节'),
('2026-05-04', 1, '劳动节'),
('2026-05-05', 1, '劳动节');

-- 端午：2026-06-25~06-27
INSERT INTO `sys_holiday_config` (`config_date`, `day_type`, `description`) VALUES
('2026-06-25', 1, '端午节'),
('2026-06-26', 1, '端午节'),
('2026-06-27', 1, '端午节');

-- 中秋+国庆：2026-10-01~10-07
INSERT INTO `sys_holiday_config` (`config_date`, `day_type`, `description`) VALUES
('2026-10-01', 1, '国庆节'),
('2026-10-02', 1, '国庆节'),
('2026-10-03', 1, '国庆节'),
('2026-10-04', 1, '国庆节/中秋节'),
('2026-10-05', 1, '国庆节'),
('2026-10-06', 1, '国庆节'),
('2026-10-07', 1, '国庆节');


-- ============================================================
-- 生成 2026年全年 30分钟时间分桶数据
-- ============================================================
-- 调用存储过程或在应用启动时通过 InitTimeBucketRunner 初始化
-- 此处提供手动插入2026-01-01示例（实际应用通过Java初始化）
-- 2026年每天48个桶，全年约17568条记录，由应用启动时自动生成

COMMIT;
