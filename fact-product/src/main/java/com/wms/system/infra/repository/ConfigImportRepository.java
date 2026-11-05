package com.wms.system.infra.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 配置导入 / 导出仓储（DDD P3 批次② 收敛）。
 *
 * <p>SQL 均自 {@code ConfigImportController} <b>原样搬入</b>，行为不变。
 *
 * <p><b>与 {@code MasterDataRepository} 的重复说明</b>：本类的
 * 「产线/工位变更后联动相机与映射」三条 SQL 与 masterdata 域的
 * {@code syncCameraFactoryByLine / remapDefectMappingByLine /
 * touchCameraStatusOnWorkstationMove / remapDefectMappingByStation} 同源。
 * 本阶段按「SQL 原样搬运、零优化」原则<b>各自保留</b>，避免跨域改动引入行为风险；
 * P4 抽 {@code CameraDirectory} 跨域接口时统一收敛。
 */
@Repository
public class ConfigImportRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ==================== 导出 ====================

    /** 导出整体配置（相机 + 工位 + 产线 + 工厂） */
    public List<Map<String, Object>> queryExportRows() {
        return jdbcTemplate.queryForList(
                "SELECT f.factory_code factoryCode, f.factory_name factoryName, f.address factoryAddress, " +
                        "l.line_code lineCode, l.line_name lineName, " +
                        "w.station_code stationCode, w.station_name stationName, " +
                        "c.camera_code cameraCode, c.camera_name cameraName " +
                        "FROM camera_status c " +
                        "LEFT JOIN dim_workstation w ON c.workstation_id = w.workstation_id " +
                        "LEFT JOIN dim_production_line l ON c.line_id = l.line_id " +
                        "LEFT JOIN dim_factory f ON l.factory_id = f.factory_id " +
                        "WHERE COALESCE(c.is_active, 1) = 1 " +
                        "ORDER BY f.factory_code, l.line_code, w.station_code, c.camera_code");
    }

    // ==================== 产线变更后的联动 ====================

    /** 产线变更后刷新相机档案的工厂归属 */
    public int syncCameraFactoryByLine(Integer factoryId, Integer lineId) {
        return jdbcTemplate.update(
                "UPDATE dim_camera SET factory_id=? WHERE line_id=? AND IFNULL(is_deleted,0)=0",
                factoryId, lineId);
    }

    /** 产线变更后联动相机缺陷映射的工厂/产线/工位/编码 */
    public int remapDefectMappingByLine(Integer lineId) {
        return jdbcTemplate.update(
                "UPDATE cfg_camera_defect_mapping m JOIN dim_camera c ON m.camera_id=c.camera_id " +
                        "SET m.factory_id=c.factory_id, m.line_id=c.line_id, m.workstation_id=c.workstation_id, m.camera_code=c.camera_code " +
                        "WHERE c.line_id=? AND IFNULL(c.is_deleted,0)=0",
                lineId);
    }

    // ==================== 工位变更后的联动 ====================

    /** 工位变更后刷新相机状态快照的产线 */
    public int touchCameraStatusOnWorkstationMove(Integer lineId, Integer stationId) {
        return jdbcTemplate.update("UPDATE camera_status SET line_id=?, update_time=NOW() WHERE workstation_id=?",
                lineId, stationId);
    }

    /** 工位变更后刷新相机档案的工厂/产线/工位归属 */
    public int syncCameraLocation(Integer factoryId, Integer lineId, Integer stationId) {
        return jdbcTemplate.update(
                "UPDATE dim_camera SET factory_id=?, line_id=?, workstation_id=? WHERE workstation_id=? AND IFNULL(is_deleted,0)=0",
                factoryId, lineId, stationId, stationId);
    }

    /** 工位变更后联动相机缺陷映射的工厂/产线/工位/编码 */
    public int remapDefectMappingByStation(Integer stationId) {
        return jdbcTemplate.update(
                "UPDATE cfg_camera_defect_mapping m JOIN dim_camera c ON m.camera_id=c.camera_id " +
                        "SET m.factory_id=c.factory_id, m.line_id=c.line_id, m.workstation_id=c.workstation_id, m.camera_code=c.camera_code " +
                        "WHERE c.workstation_id=? AND IFNULL(c.is_deleted,0)=0",
                stationId);
    }

    // ==================== 相机档案同步 ====================

    /** 写入 / 更新相机档案（按 camera_id 主键 upsert） */
    public int upsertDimCamera(Long cameraId, Integer factoryId, Integer lineId, Integer workstationId,
                               String cameraCode, String cameraName) {
        return jdbcTemplate.update(
                "INSERT INTO dim_camera(camera_id, factory_id, line_id, workstation_id, camera_code, camera_name, device_id, is_enabled, is_deleted) " +
                        "VALUES (?,?,?,?,?,?,?,1,0) " +
                        "ON DUPLICATE KEY UPDATE factory_id=VALUES(factory_id), line_id=VALUES(line_id), workstation_id=VALUES(workstation_id), " +
                        "camera_code=VALUES(camera_code), camera_name=VALUES(camera_name), device_id=VALUES(device_id), is_enabled=1, is_deleted=0",
                cameraId, factoryId, lineId, workstationId, cameraCode, cameraName, cameraCode);
    }

    /** 相机档案变更后同步映射表的工厂/产线/工位/编码 */
    public int syncMappingLocation(Integer factoryId, Integer lineId, Integer workstationId, String cameraCode, Long cameraId) {
        return jdbcTemplate.update(
                "UPDATE cfg_camera_defect_mapping SET factory_id=?, line_id=?, workstation_id=?, camera_code=? WHERE camera_id=?",
                factoryId, lineId, workstationId, cameraCode, cameraId);
    }
}
