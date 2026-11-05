package com.wms.analytics.api;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.wms.device.domain.model.CameraStatus;
import com.wms.masterdata.domain.model.DimProductionLine;
import com.wms.masterdata.domain.model.DimWorkstation;
import com.wms.device.infra.mapper.CameraStatusMapper;
import com.wms.device.app.ICameraStatusService;
import com.wms.device.infra.repository.CameraDirectoryRepository;
import com.wms.masterdata.app.IDimProductionLineService;
import com.wms.masterdata.app.IDimWorkstationService;
import com.wms.shared.common.ResultVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 相机看板 API。
 *
 * <p>DDD P3 批次③：所有 SQL 已下沉到 {@link CameraDirectoryRepository}，
 * 本类只保留参数校验、依赖判定与响应封装。
 */
@RestController
@RequestMapping("/api/cameras")
public class CameraDashboardController {

    @Autowired private ICameraStatusService cameraStatusService;
    @Autowired private CameraStatusMapper cameraStatusMapper;
    @Autowired private IDimWorkstationService workstationService;
    @Autowired private IDimProductionLineService lineService;
    @Autowired private CameraDirectoryRepository cameraDirectoryRepository;

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
        if (cameraDirectoryRepository.countDimCameraByCode(camera.getCameraCode()) > 0) {
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
        if (cameraDirectoryRepository.countDimCameraByCodeExcept(camera.getCameraCode(), id) > 0) {
            return ResultVO.fail("设备编号已存在");
        }
        camera.setId(id);
        // ★ 2026-11-02 修正：删除改为逻辑删除后（camera_status.is_active=0），
        //   编辑保存时必须把 is_active 恢复为 1，否则「删除 → 再编辑恢复」后
        //   相机仍不出现在列表里（实测：PUT 返回"更新成功"但 is_active 仍为 0）。
        //   前端编辑表单不携带 is_active 字段，这里显式置 1。
        camera.setIsActive(1);
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
        // ★ 2026-11-02 修正：
        //   原实现查的是 `camera_image` —— **这张表根本不存在**（真实表名是 `camera_images`），
        //   而 count() 内部 catch 掉异常返回 0，导致这项依赖检查**永远失效**。
        //   实测：删除一台有 1347 条图片的相机时，legacyImageCount 恒为 0。
        int imageCount = cameraDirectoryRepository.countDefectImageFiles(existing.getCameraCode(), existing.getCameraName());
        int compatImageCount = cameraDirectoryRepository.countCompatImages(existing.getCameraName());
        int defectBoxCount = cameraDirectoryRepository.countDefectImageBoxes(existing.getCameraCode(), existing.getCameraName());
        int mappingCount = cameraDirectoryRepository.countDefectMappings(id);
        int tokenCount = cameraDirectoryRepository.countCameraTokens(id);
        int heartbeatCount = cameraDirectoryRepository.countHeartbeats(existing.getCameraCode());

        int totalImages = imageCount + compatImageCount;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("imageCount", totalImages);
        data.put("defectImageCount", imageCount);
        data.put("compatImageCount", compatImageCount);
        data.put("defectBoxCount", defectBoxCount);
        data.put("mappingCount", mappingCount);
        data.put("tokenCount", tokenCount);
        data.put("heartbeatCount", heartbeatCount);
        // 有历史图片/心跳 → HIGH（不可直接删）；仅有映射与令牌 → MEDIUM（可删，自动级联清理）
        if (totalImages > 0 || heartbeatCount > 0) {
            data.put("riskLevel", "HIGH");
        } else if (mappingCount > 0 || tokenCount > 0) {
            data.put("riskLevel", "MEDIUM");
        } else {
            data.put("riskLevel", "LOW");
        }
        data.put("detail", buildDependencyDetail(totalImages, defectBoxCount, mappingCount, tokenCount, heartbeatCount));
        return ResultVO.success(data);
    }

    /** 依赖明细文案（供前端确认弹窗展示） */
    private String buildDependencyDetail(int imageCount, int boxCount, int mappingCount,
                                        int tokenCount, int heartbeatCount) {
        StringBuilder sb = new StringBuilder();
        if (imageCount > 0) sb.append("图片记录 ").append(imageCount).append(" 条，");
        if (boxCount > 0) sb.append("缺陷框 ").append(boxCount).append(" 条，");
        if (heartbeatCount > 0) sb.append("心跳记录 ").append(heartbeatCount).append(" 条，");
        if (mappingCount > 0) sb.append("缺陷映射 ").append(mappingCount).append(" 条（将一并清理），");
        if (tokenCount > 0) sb.append("设备令牌 ").append(tokenCount).append(" 个（将一并清理），");
        String s = sb.toString();
        if (s.endsWith("，")) s = s.substring(0, s.length() - 1);
        return s.isEmpty() ? "无依赖" : s;
    }

    /**
     * 删除相机。
     *
     * <p><b>2026-11-02 修正的 4 个缺陷</b>（均为实机复现）：
     * <ol>
     *   <li><b>两表状态不一致</b>：原实现 {@code cameraStatusMapper.deleteById(id)}
     *       把 {@code camera_status} 行<b>物理删除</b>，而 {@code dim_camera}
     *       只标记 {@code is_deleted=1}。结果：维表里相机还在、状态表里没了 ——
     *       前端列表查到 {@code camera_status} 无该行，"编辑"按钮点不动（提示"相机不存在"），
     *       而删除按钮还在（截图里 CAM-003~006 就是这个现象）。
     *       <b>现在两表都做逻辑删除</b>，保持一致且可恢复。</li>
     *   <li><b>孤儿数据残留</b>：原实现不清理 {@code cfg_camera_defect_mapping}
     *       与 {@code cfg_camera_token}，删除后这些行还指向已删相机。
     *       <b>现在一并清理</b>。</li>
     *   <li><b>依赖检查漏表</b>：查的是不存在的 {@code camera_image} 表，
     *       真实表名是 {@code camera_images}（1355 行）。
     *       <b>现在两张表都查</b>。</li>
     *   <li><b>删除后无法恢复</b>：物理删除后 {@code PUT /api/cameras/{id}} 恒返回"相机不存在"。
     *       <b>现在逻辑删除可恢复</b>（{@code PUT} 允许把 {@code is_active=0} 的相机重新启用）。</li>
     * </ol>
     */
    /**
     * <p><b>2026-11-04 修正</b>：原方法<b>缺少 {@code @PreAuthorize("hasRole('ADMIN')")}</b>，
     * 而同类的产线/工位/工厂删除接口都有 —— 导致任何已登录用户都能删相机。
     * 这是权限漏洞，本次补齐。
     */
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    @Transactional
    public ResultVO<String> delete(@PathVariable Long id) {
        CameraStatus existing = cameraStatusMapper.selectById(id);
        if (existing == null) {
            return ResultVO.fail("相机不存在");
        }
        if (existing.getIsActive() != null && existing.getIsActive() == 0) {
            return ResultVO.fail("相机已处于停用/删除状态，无需重复删除");
        }

        int imageCount = cameraDirectoryRepository.countDefectImageFiles(existing.getCameraCode(), existing.getCameraName());
        int compatImageCount = cameraDirectoryRepository.countCompatImages(existing.getCameraName());
        int heartbeatCount = cameraDirectoryRepository.countHeartbeats(existing.getCameraCode());

        // 有历史图片或心跳 → 拒绝删除（历史数据不可自动清理，必须人工确认归档）
        if (imageCount + compatImageCount > 0 || heartbeatCount > 0) {
            return ResultVO.fail("相机存在历史图片/心跳记录，禁止直接删除。建议先停用或完成数据归档后再删除。",
                    "依赖明细：图片记录 " + (imageCount + compatImageCount) + " 条，心跳记录 " + heartbeatCount + " 条。");
        }

        // ---- 逻辑删除：两表一致 ----
        cameraDirectoryRepository.softDeleteCameraStatus(id);
        cameraDirectoryRepository.softDeleteDimCamera(id);

        // ---- 级联清理：避免孤儿数据 ----
        int mappingRows = cameraDirectoryRepository.deleteDefectMappings(id);
        int tokenRows = cameraDirectoryRepository.deleteCameraTokens(id);

        String detail = "缺陷映射 " + mappingRows + " 条、设备令牌 " + tokenRows + " 个已一并清理。";
        return ResultVO.success("删除成功", detail);
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
        cameraDirectoryRepository.upsertDimCamera(camera.getId(), line.getFactoryId(), camera.getLineId(),
                camera.getWorkstationId(), camera.getCameraCode(), camera.getCameraName());
        cameraDirectoryRepository.syncMappingLocation(line.getFactoryId(), camera.getLineId(),
                camera.getWorkstationId(), camera.getCameraCode(), camera.getId());
    }
}
