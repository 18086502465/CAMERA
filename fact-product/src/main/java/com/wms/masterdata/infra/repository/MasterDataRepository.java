package com.wms.masterdata.infra.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 主数据域的跨表依赖检查 / 联动更新 SQL（DDD P3 批次① 收敛）。
 *
 * <p>SQL 均自 DimFactoryController / DimProductionLineController /
 * DimWorkstationController / CascadeController <b>原样搬入</b>（2026-11-04 现场修正
 * 的语义注释见各调用方），行为不变。P4 阶段相机相关查询将迁往 device 域接口。
 */
@Repository
public class MasterDataRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ==================== 工厂 ====================

    /** 工厂下属产线数（排除已删除） */
    public int countLinesByFactory(Integer factoryId) {
        return count("SELECT COUNT(*) FROM dim_production_line WHERE factory_id = ? AND IFNULL(is_deleted,0)=0", factoryId);
    }

    /** 归属该工厂的用户数 */
    public int countUsersByFactory(Integer factoryId) {
        return count("SELECT COUNT(*) FROM sys_user WHERE factory_id = ?", factoryId);
    }

    /** 工厂下属工位数（经产线 JOIN，双侧过滤 is_deleted） */
    public int countStationsByFactory(Integer factoryId) {
        return count("SELECT COUNT(*) FROM dim_workstation ws JOIN dim_production_line pl ON ws.line_id = pl.line_id "
                + "WHERE pl.factory_id = ? AND IFNULL(pl.is_deleted,0)=0 AND IFNULL(ws.is_deleted,0)=0", factoryId);
    }

    /** 工厂下属相机数（dim_camera ← dim_workstation ← dim_production_line JOIN） */
    public int countCamerasByFactory(Integer factoryId) {
        return count("SELECT COUNT(*) FROM dim_camera dc JOIN dim_workstation ws ON dc.workstation_id = ws.workstation_id "
                + "JOIN dim_production_line pl ON ws.line_id = pl.line_id WHERE pl.factory_id = ? AND IFNULL(dc.is_deleted,0)=0", factoryId);
    }

    /** 工厂逻辑删除 */
    public int softDeleteFactory(Integer factoryId) {
        return jdbcTemplate.update("UPDATE dim_factory SET is_deleted = 1 WHERE factory_id = ?", factoryId);
    }

    // ==================== 产线 ====================

    public int countStationsByLine(Integer lineId) {
        return count("SELECT COUNT(*) FROM dim_workstation WHERE line_id = ? AND IFNULL(is_deleted,0)=0", lineId);
    }

    public int countCamerasByLine(Integer lineId) {
        return count("SELECT COUNT(*) FROM dim_camera WHERE line_id = ? AND IFNULL(is_deleted,0)=0", lineId);
    }

    public int countProductionByLine(Integer lineId) {
        return count("SELECT COUNT(*) FROM fact_production_30min WHERE line_id = ?", lineId);
    }

    public int countDetectionByLine(Integer lineId) {
        return count("SELECT COUNT(*) FROM product_detection_history WHERE line_id = ?", lineId);
    }

    public int softDeleteLine(Integer lineId) {
        return jdbcTemplate.update("UPDATE dim_production_line SET is_deleted = 1 WHERE line_id = ?", lineId);
    }

    /** 产线换工厂后，联动相机档案的 factory_id */
    public int syncCameraFactoryByLine(Integer newFactoryId, Integer lineId) {
        return jdbcTemplate.update("UPDATE dim_camera SET factory_id=? WHERE line_id=? AND IFNULL(is_deleted,0)=0",
                newFactoryId, lineId);
    }

    /** 产线换工厂后，联动缺陷检测配置映射（经相机 JOIN） */
    public int remapDefectMappingByLine(Integer lineId) {
        return jdbcTemplate.update(
                "UPDATE cfg_camera_defect_mapping m JOIN dim_camera c ON m.camera_id=c.camera_id " +
                        "SET m.factory_id=c.factory_id, m.line_id=c.line_id, m.workstation_id=c.workstation_id, m.camera_code=c.camera_code " +
                        "WHERE c.line_id=? AND IFNULL(c.is_deleted,0)=0",
                lineId);
    }

    // ==================== 工位 ====================

    public int countCamerasByStation(Integer stationId) {
        return count("SELECT COUNT(*) FROM dim_camera WHERE workstation_id = ? AND IFNULL(is_deleted,0)=0", stationId);
    }

    public int countProductionByStation(Integer stationId) {
        return count("SELECT COUNT(*) FROM fact_production_30min WHERE workstation_id = ?", stationId);
    }

    public int countDetectionByStation(Integer stationId) {
        return count("SELECT COUNT(*) FROM product_detection_history WHERE workstation_id = ?", stationId);
    }

    /** 工位换产线后，刷新状态快照行的 line_id */
    public int touchCameraStatusOnWorkstationMove(Integer lineId, Integer stationId) {
        return jdbcTemplate.update("UPDATE camera_status SET line_id=?, update_time=NOW() WHERE workstation_id=?",
                lineId, stationId);
    }

    /** 工位换产线后，联动相机档案的 line_id/factory_id */
    public int syncCameraLocation(Integer lineId, Integer stationId) {
        return jdbcTemplate.update(
                "UPDATE dim_camera dc JOIN dim_production_line l ON l.line_id=? " +
                        "SET dc.line_id=?, dc.factory_id=l.factory_id, dc.workstation_id=? " +
                        "WHERE dc.workstation_id=? AND IFNULL(dc.is_deleted,0)=0",
                lineId, lineId, stationId, stationId);
    }

    /** 工位换产线后，联动缺陷检测配置映射（经相机 JOIN） */
    public int remapDefectMappingByStation(Integer stationId) {
        return jdbcTemplate.update(
                "UPDATE cfg_camera_defect_mapping m JOIN dim_camera c ON m.camera_id=c.camera_id " +
                        "SET m.factory_id=c.factory_id, m.line_id=c.line_id, m.workstation_id=c.workstation_id, m.camera_code=c.camera_code " +
                        "WHERE c.workstation_id=? AND IFNULL(c.is_deleted,0)=0",
                stationId);
    }

    /** 删除工位前停用其绑定相机（不改 workstation_id —— 该列 NOT NULL，见 2026-11-04 修正） */
    public int disableCamerasByStation(Integer stationId) {
        return jdbcTemplate.update("UPDATE dim_camera SET is_enabled = 0, update_time = NOW() "
                + "WHERE workstation_id = ? AND IFNULL(is_deleted,0)=0", stationId);
    }

    // ==================== 级联树 ====================

    /**
     * 各工位「有效绑定相机数」（单条 GROUP BY，避免 N+1）。
     * 统计失败返回空 Map，由调用方退化为前端按 camera_status 统计 —— 原语义保留。
     */
    public Map<Integer, Integer> countCamerasGroupByStation() {
        Map<Integer, Integer> result = new HashMap<>();
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT workstation_id, COUNT(*) AS cnt FROM dim_camera "
                    + "WHERE IFNULL(is_deleted,0)=0 AND workstation_id IS NOT NULL GROUP BY workstation_id");
            for (Map<String, Object> row : rows) {
                Object wsId = row.get("workstation_id");
                Object cnt = row.get("cnt");
                if (wsId instanceof Number && cnt instanceof Number) {
                    result.put(((Number) wsId).intValue(), ((Number) cnt).intValue());
                }
            }
        } catch (Exception e) {
            return new HashMap<>();
        }
        return result;
    }

    /**
     * 依赖计数兜底：查询异常时返回 0（与原 Controller 内私有 count() 语义一致 ——
     * 依赖检查失败不应阻断删除流程）。
     */
    private int count(String sql, Object... args) {
        try {
            Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
            return value == null ? 0 : value;
        } catch (Exception e) {
            return 0;
        }
    }
}
