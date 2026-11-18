-- =====================================================================
-- 07_daily_report.sql —— 产线统计日报快照表（2026-11-17 新增）
-- 幂等补丁：只对存量库追加。
-- ★ 本表目前**未纳入** init-database.sh 的建库序列（该脚本仍停在 01→05）；
--   全新建库时由应用启动期 SchemaInitializer → DailyReportRepository.ensureTable()
--   幂等补建，功能不受影响（不要照抄本注释里的"01→07"）。
--
-- 口径（与 PRD docs/archive/PRD-产线统计日报-20261117.md 一致）：
--   * 只统计 REAL 相机（camera_type='REAL' AND is_deleted=0），TEST 不进日报；
--   * 缺陷率分母 = 检测图片数（H1 拍板），分子 = SUM(defect_image_file.defect_count)，
--     单位为「每千张」（D2 拍板）；
--   * line_id = 0 的行是厂级汇总行，line_id > 0 为产线行；
--   * 每天 00:05 由 DailyReportGenerationTask 物化前一天（快照），重跑幂等覆盖；
--     当天的数据不入库，由查询端点实时计算（响应带 dataSource=REALTIME）。
-- =====================================================================

CREATE TABLE IF NOT EXISTS fact_daily_line_report (
  id                  BIGINT       NOT NULL AUTO_INCREMENT,
  report_date         DATE         NOT NULL                COMMENT '日报日期（物化的那一天）',
  line_id             INT          NOT NULL DEFAULT 0      COMMENT '产线 ID；0=厂级汇总行',
  line_name           VARCHAR(100) NULL                    COMMENT '产线名（生成时快照，产线改名/删除不影响历史）',
  detect_count        INT          NOT NULL DEFAULT 0      COMMENT '检测图片数（REAL 相机，缺陷率分母）',
  defect_count        INT          NOT NULL DEFAULT 0      COMMENT '缺陷框数（SUM(defect_image_file.defect_count)）',
  defect_images       INT          NOT NULL DEFAULT 0      COMMENT '含缺陷的图片张数',
  defect_rate_kilo    DECIMAL(10,2) NULL                   COMMENT '缺陷率（每千张）= defect_count/detect_count*1000；无数据为 NULL 不为 0',
  camera_total        INT          NOT NULL DEFAULT 0      COMMENT '当日在册 REAL 相机数',
  camera_online       INT          NOT NULL DEFAULT 0      COMMENT '当日有上报的 REAL 相机数',
  temp_alarm_count    INT          NOT NULL DEFAULT 0      COMMENT '当日 TEMPERATURE_HIGH 告警条数',
  top_defect_types    VARCHAR(1024) NULL                   COMMENT 'TOP3 缺陷类型 JSON：[{"type":"..","count":n}]',
  worst_camera_code   VARCHAR(64)  NULL                    COMMENT '缺陷率最高的相机（仅当日样本 ≥100 张参与排名）',
  worst_camera_name   VARCHAR(100) NULL,
  worst_camera_rate   DECIMAL(10,2) NULL                   COMMENT '该相机缺陷率（每千张）',
  worst_camera_images INT          NULL                    COMMENT '该相机当日检测图片数（排名依据可追溯）',
  -- 2026-11-17 增补：心跳累计检测计数快照（REAL 相机求和）。
  -- 图片通道只上报缺陷图 → 图片口径缺陷率恒 1000‰（实测）；真缺陷率 = 计数日增差分：
  --   缺陷率(计数) = (ng_cum[D] - ng_cum[D-1]) / (insp_cum[D] - insp_cum[D-1]) × 1000
  -- 首日无前日快照 → NULL；计数器回绕（相机重置）→ 差分为负也按 NULL 处理。
  insp_total_cum      BIGINT       NULL                    COMMENT 'REAL 相机 total_count 累计和（生成时点快照）',
  ok_total_cum        BIGINT       NULL                    COMMENT 'REAL 相机 ok_count 累计和',
  ng_total_cum        BIGINT       NULL                    COMMENT 'REAL 相机 ng_count 累计和',
  snapshot_version    INT          NOT NULL DEFAULT 1      COMMENT '口径版本；口径变更只影响此版本之后的日期',
  create_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_report_date_line (report_date, line_id),
  KEY idx_report_date (report_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产线统计日报快照（每天 00:05 物化前一天）';
