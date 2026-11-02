package com.wms.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.wms.entity.CameraStatus;
import com.wms.entity.DimProductionLine;
import com.wms.entity.DimWorkstation;
import com.wms.mapper.CameraStatusMapper;
import com.wms.service.ICameraStatusService;
import com.wms.service.IDimProductionLineService;
import com.wms.service.IDimWorkstationService;
import com.wms.vo.ResultVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/cameras")
public class CameraDashboardController {

    @Autowired private ICameraStatusService cameraStatusService;
    @Autowired private CameraStatusMapper cameraStatusMapper;
    @Autowired private IDimWorkstationService workstationService;
    @Autowired private IDimProductionLineService lineService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @GetMapping
    public ResultVO<IPage<CameraStatus>> pageQuery(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer factoryId,
            @RequestParam(required = false) Integer lineId,
            @RequestParam(required = false) Integer stationId) {

        Set<Integer> wsIds = null;
        if (factoryId != null || lineId != null || stationId != null) {
            List<DimWorkstation> allStations = workstationService.list();
            if (stationId != null) {
                allStations = allStations.stream()
                        .filter(s -> stationId.equals(s.getStationId()))
                        .collect(Collectors.toList());
            }
            if (lineId != null) {
                allStations = allStations.stream()
                        .filter(s -> lineId.equals(s.getLineId()))
                        .collect(Collectors.toList());
            }
            if (factoryId != null) {
                List<DimProductionLine> lines = lineService.list();
                Set<Integer> factoryLineIds = lines.stream()
                        .filter(l -> factoryId.equals(l.getFactoryId()))
                        .map(DimProductionLine::getLineId)
                        .collect(Collectors.toSet());
                allStations = allStations.stream()
                        .filter(s -> factoryLineIds.contains(s.getLineId()))
                        .collect(Collectors.toList());
            }
            wsIds = allStations.stream()
                    .map(DimWorkstation::getStationId)
                    .collect(Collectors.toSet());
            if (wsIds.isEmpty()) {
                return ResultVO.success(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, size, 0));
            }
        }

        IPage<CameraStatus> result = cameraStatusService.pageQuery(page, size, keyword, status, wsIds);

        Map<Integer, DimWorkstation> stationMap = workstationService.list().stream()
                .collect(Collectors.toMap(DimWorkstation::getStationId, w -> w, (a, b) -> a));
        Map<Integer, DimProductionLine> lineMap = lineService.list().stream()
                .collect(Collectors.toMap(DimProductionLine::getLineId, l -> l, (a, b) -> a));
        for (CameraStatus cam : result.getRecords()) {
            if (cam.getWorkstationId() != null) {
                DimWorkstation station = stationMap.get(cam.getWorkstationId());
                if (station != null) {
                    cam.setStationName(station.getStationName());
                    cam.setStationCode(station.getStationCode());
                    cam.setStationLabel(buildStationLabel(station.getStationCode(), station.getStationName()));
                    if (cam.getLineId() == null) {
                        cam.setLineId(station.getLineId());
                    }
                }
            }
            if (cam.getLineId() != null) {
                DimProductionLine line = lineMap.get(cam.getLineId());
                if (line != null) {
                    cam.setLineName(line.getLineName());
                    cam.setLineCode(line.getLineCode());
                    cam.setFactoryId(line.getFactoryId());
                }
            }
        }

        return ResultVO.success(result);
    }

    private String buildStationLabel(String code, String name) {
        boolean hasCode = code != null && !code.trim().isEmpty();
        boolean hasName = name != null && !name.trim().isEmpty();
        if (hasCode && hasName) {
            String c = code.trim();
            String n = name.trim();
            String lowerCode = c.toLowerCase(Locale.ROOT);
            String lowerName = n.toLowerCase(Locale.ROOT);
            if (lowerName.equals(lowerCode)
                    || lowerName.startsWith(lowerCode)
                    || lowerName.startsWith(lowerCode + "-")
                    || lowerName.startsWith(lowerCode + "_")
                    || lowerName.startsWith(lowerCode + "/")) {
                return n;
            }
            return c + " " + n;
        }
        if (hasName) return name.trim();
        if (hasCode) return code.trim();
        return "未配置工位";
    }

    @GetMapping("/stats")
    public ResultVO<Map<String, Object>> getStats() {
        return ResultVO.success(cameraStatusService.getStats());
    }

    @GetMapping("/recognize-stats")
    public ResultVO<List<Map<String, Object>>> getRecognizeStats() {
        return ResultVO.success(cameraStatusService.getRecognizeStats());
    }

    @PostMapping
    @Transactional
    public ResultVO<String> create(@RequestBody CameraStatus camera) {
        if (camera.getCameraName() == null || camera.getCameraName().trim().isEmpty()) {
            return ResultVO.fail("相机名称不能为空");
        }
        if (camera.getCameraCode() == null || camera.getCameraCode().trim().isEmpty()) {
            return ResultVO.fail("设备编号不能为空");
        }
        String relationError = validateAndFillCameraRelation(camera);
        if (relationError != null) {
            return ResultVO.fail(relationError);
        }
        CameraStatus exists = cameraStatusMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CameraStatus>()
                        .eq(CameraStatus::getCameraCode, camera.getCameraCode())
                        .last("LIMIT 1")
        );
        if (exists != null) {
            return ResultVO.fail("设备编号已存在");
        }
        if (count("SELECT COUNT(*) FROM dim_camera WHERE camera_code = ? AND IFNULL(is_deleted,0)=0", camera.getCameraCode()) > 0) {
            return ResultVO.fail("设备编号已存在");
        }
        camera.setId(null);
        if (camera.getStatus() == null || camera.getStatus().trim().isEmpty()) camera.setStatus("RUNNING");
        if (camera.getIsActive() == null) camera.setIsActive(1);
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        camera.setCreateTime(now);
        camera.setUpdateTime(now);
        cameraStatusMapper.insert(camera);
        syncDimCamera(camera);
        return ResultVO.success("创建成功");
    }

    @PutMapping("/{id}")
    @Transactional
    public ResultVO<String> update(@PathVariable Long id, @RequestBody CameraStatus camera) {
        CameraStatus existing = cameraStatusMapper.selectById(id);
        if (existing == null) {
            return ResultVO.fail("相机不存在");
        }
        if (camera.getCameraName() == null || camera.getCameraName().trim().isEmpty()) {
            return ResultVO.fail("相机名称不能为空");
        }
        if (camera.getCameraCode() == null || camera.getCameraCode().trim().isEmpty()) {
            return ResultVO.fail("设备编号不能为空");
        }
        String relationError = validateAndFillCameraRelation(camera);
        if (relationError != null) {
            return ResultVO.fail(relationError);
        }
        if (camera.getCameraCode() != null && !camera.getCameraCode().trim().isEmpty()) {
            CameraStatus codeExists = cameraStatusMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CameraStatus>()
                            .eq(CameraStatus::getCameraCode, camera.getCameraCode())
                            .ne(CameraStatus::getId, id)
                            .last("LIMIT 1")
            );
            if (codeExists != null) return ResultVO.fail("设备编号已存在");
        }
        if (count("SELECT COUNT(*) FROM dim_camera WHERE camera_code = ? AND camera_id <> ? AND IFNULL(is_deleted,0)=0", camera.getCameraCode(), id) > 0) {
            return ResultVO.fail("设备编号已存在");
        }
        camera.setId(id);
        camera.setUpdateTime(java.time.LocalDateTime.now());
        cameraStatusMapper.updateById(camera);
        syncDimCamera(camera);
        return ResultVO.success("更新成功");
    }

    @GetMapping("/{id}/dependencies")
    public ResultVO<Map<String, Object>> dependencies(@PathVariable Long id) {
        CameraStatus existing = cameraStatusMapper.selectById(id);
        if (existing == null) {
            return ResultVO.fail("相机不存在");
        }
        int imageCount = count("SELECT COUNT(*) FROM defect_image_file WHERE camera_id = ? OR camera_name = ?",
                existing.getCameraCode(), existing.getCameraName());
        int legacyImageCount = count("SELECT COUNT(*) FROM camera_image WHERE camera_name = ?",
                existing.getCameraName());
        int defectBoxCount = count("SELECT COUNT(*) FROM defect_image_box b JOIN defect_image_file f ON b.image_id = f.image_id WHERE f.camera_id = ? OR f.camera_name = ?",
                existing.getCameraCode(), existing.getCameraName());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("imageCount", imageCount + legacyImageCount);
        data.put("defectBoxCount", defectBoxCount);
        data.put("riskLevel", (imageCount + legacyImageCount + defectBoxCount) > 0 ? "MEDIUM" : "LOW");
        return ResultVO.success(data);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResultVO<String> delete(@PathVariable Long id) {
        CameraStatus existing = cameraStatusMapper.selectById(id);
        if (existing == null) {
            return ResultVO.fail("相机不存在");
        }
        int imageCount = count("SELECT COUNT(*) FROM defect_image_file WHERE camera_id = ? OR camera_name = ?",
                existing.getCameraCode(), existing.getCameraName());
        int legacyImageCount = count("SELECT COUNT(*) FROM camera_image WHERE camera_name = ?",
                existing.getCameraName());
        if (imageCount + legacyImageCount > 0) {
            return ResultVO.fail("相机存在历史图片/缺陷记录，禁止直接删除。建议先停用或完成数据归档后再删除。",
                    "依赖明细：图片记录 " + (imageCount + legacyImageCount) + " 条。");
        }
        cameraStatusMapper.deleteById(id);
        jdbcTemplate.update("UPDATE dim_camera SET is_deleted = 1, is_enabled = 0 WHERE camera_id = ?", id);
        return ResultVO.success("删除成功");
    }

    private String validateAndFillCameraRelation(CameraStatus camera) {
        if (camera.getWorkstationId() == null) {
            return "请选择工位";
        }
        DimWorkstation station = workstationService.getById(camera.getWorkstationId());
        if (station == null) {
            return "工位不存在";
        }
        if (camera.getLineId() == null) {
            camera.setLineId(station.getLineId());
        }
        if (!camera.getLineId().equals(station.getLineId())) {
            return "相机所属产线与工位所属产线不一致";
        }
        DimProductionLine line = lineService.getById(camera.getLineId());
        if (line == null) {
            return "产线不存在";
        }
        return null;
    }

    private void syncDimCamera(CameraStatus camera) {
        DimProductionLine line = lineService.getById(camera.getLineId());
        if (line == null) {
            throw new IllegalArgumentException("产线不存在，无法同步相机维表");
        }
        jdbcTemplate.update(
                "INSERT INTO dim_camera(camera_id, factory_id, line_id, workstation_id, camera_code, camera_name, device_id, is_enabled, is_deleted) " +
                        "VALUES (?,?,?,?,?,?,?,1,0) " +
                        "ON DUPLICATE KEY UPDATE factory_id=VALUES(factory_id), line_id=VALUES(line_id), workstation_id=VALUES(workstation_id), " +
                        "camera_code=VALUES(camera_code), camera_name=VALUES(camera_name), device_id=VALUES(device_id), is_enabled=1, is_deleted=0",
                camera.getId(), line.getFactoryId(), camera.getLineId(), camera.getWorkstationId(),
                camera.getCameraCode(), camera.getCameraName(), camera.getCameraCode());
        jdbcTemplate.update(
                "UPDATE cfg_camera_defect_mapping SET factory_id=?, line_id=?, workstation_id=?, camera_code=? WHERE camera_id=?",
                line.getFactoryId(), camera.getLineId(), camera.getWorkstationId(), camera.getCameraCode(), camera.getId());
    }

    private int count(String sql, Object... args) {
        try {
            Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
            return value == null ? 0 : value;
        } catch (Exception e) {
            return 0;
        }
    }
}
