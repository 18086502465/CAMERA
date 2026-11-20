package com.wms.analytics.api;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.wms.device.domain.model.CameraStatus;
import com.wms.masterdata.domain.model.DimProductionLine;
import com.wms.masterdata.domain.model.DimWorkstation;
import com.wms.device.infra.mapper.CameraStatusMapper;
import com.wms.device.app.ICameraStatusService;
import com.wms.device.app.CameraDirectory;
import com.wms.masterdata.app.IDimProductionLineService;
import com.wms.masterdata.app.IDimWorkstationService;
import com.wms.shared.common.PageParams;
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

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(CameraDashboardController.class);

    /** 相机类型：真实产线相机（数据不参与定时清理） */
    private static final String CAMERA_TYPE_REAL = "REAL";
    /** 相机类型：测试相机（参与定时清理） */
    private static final String CAMERA_TYPE_TEST = "TEST";

    @Autowired private ICameraStatusService cameraStatusService;
    @Autowired private CameraStatusMapper cameraStatusMapper;
    @Autowired private IDimWorkstationService workstationService;
    @Autowired private IDimProductionLineService lineService;
    @Autowired private CameraDirectory cameraDirectory;   // DDD P4：跨域只走 app 接口
    @Autowired private com.wms.system.app.LicenseService licenseService;  // 授权配额（跨域只走 app 层）
    // ★ 2026-11-20：相机卡片要展示「工厂」名称 —— camera_status 只存 factory_id（数字），
    //   工厂名在 dim_factory。跨域只走 app 层（ArchUnit 铁律①）。
    @Autowired private com.wms.masterdata.app.IDimFactoryService factoryService;

    @GetMapping
    public ResultVO<IPage<CameraStatus>> pageQuery(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer factoryId,
            @RequestParam(required = false) Integer lineId,
            @RequestParam(required = false) Integer stationId) {

        // ★ 2026-11-18 评审 M-02：分页参数归一化（上限 PageParams.MAX_SIZE）——
        //   防止误传超大 size 触发全表 COUNT(*) + 全表扫描 + 完整对象构建。
        int safePage = PageParams.normalizePage(page);
        int safeSize = PageParams.normalizeSize(size, 20);

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
                return ResultVO.success(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(safePage, safeSize, 0));
            }
        }

        IPage<CameraStatus> result = cameraStatusService.pageQuery(safePage, safeSize, keyword, status, wsIds);

        // ★ 维度填充已抽到 enrichCameraDimensions（list 与 options 共用，避免再次分叉）
        enrichCameraDimensions(result.getRecords());

        return ResultVO.success(result);
    }

    /**
     * 补齐相机的**维度展示字段**（工位名 / 工位编码 / 工位标签 / 产线名 / 产线编码 / 工厂 ID / 工厂名）。
     *
     * <p>★ 2026-11-20 修复：这些字段在 {@code CameraStatus} 上都是
     * {@code @TableField(exist = false)} 的**瞬态字段**（{@code camera_status} 表里没有这些列），
     * 必须由代码按 {@code dim_workstation} / {@code dim_production_line} / {@code dim_factory}
     * 关联填充。
     *
     * <p><b>原缺陷</b>：只有 list 端点做了这个填充，而 {@code GET /cameras/options}
     * 直接返回 {@code cameraStatusService.listAll()} 的原始实体 →
     * {@code stationLabel} 等字段全为 null →
     * 前端 {@code stationLabel(item)} 全部落空 → 页面显示「**未配置工位**」。
     * 而 {@code /cameras/options} 被**图像记录 / 相机状态 / 首页看板 / 系统配置** 4 个页面使用，
     * 全部中招（现场实测：CV7_Cam1 的工位其实是「产品正面胶污检测」，却显示"未配置工位"）。
     *
     * <p><b>修法</b>：把填充逻辑抽成本方法，list 与 options **共用** —— 杜绝再次分叉。
     *
     * <p>★ 主数据权威：工位归属以 {@code dim_camera → dim_workstation} 为准，
     * 但 {@code camera_status} 的 line_id/workstation_id 由心跳同步而来，
     * 现场两者一致；这里以快照值为准，缺失时用工位所属产线补（与原 list 逻辑一致）。
     */
    private void enrichCameraDimensions(List<CameraStatus> cameras) {
        if (cameras == null || cameras.isEmpty()) {
            return;
        }
        Map<Integer, DimWorkstation> stationMap = workstationService.list().stream()
                .collect(Collectors.toMap(DimWorkstation::getStationId, w -> w, (a, b) -> a));
        Map<Integer, DimProductionLine> lineMap = lineService.list().stream()
                .collect(Collectors.toMap(DimProductionLine::getLineId, l -> l, (a, b) -> a));
        Map<Integer, String> factoryNameMap = factoryNameMap();
        for (CameraStatus cam : cameras) {
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
            // ★ 工厂名（camera_status 只存 factory_id 数字）
            if (cam.getFactoryId() != null) {
                cam.setFactoryName(factoryNameMap.get(cam.getFactoryId()));
            }
        }
    }

    /** 工厂 ID → 工厂名（一次性取全量，避免逐相机查库） */
    private Map<Integer, String> factoryNameMap() {
        Map<Integer, String> map = new HashMap<>();
        if (factoryService == null) {
            // 防御：测试环境/裁剪部署下未注入工厂服务 → 只少了工厂名这一项展示
            return map;
        }
        try {
            for (com.wms.masterdata.domain.model.DimFactory f : factoryService.list()) {
                if (f.getFactoryId() != null) {
                    map.put(f.getFactoryId(), f.getFactoryName());
                }
            }
        } catch (Exception e) {
            // 工厂主数据取不到不影响主流程（只是少了工厂名这一项展示）
            log.warn("读取工厂列表失败（相机卡片将不显示工厂名）: {}", e.getMessage());
        }
        return map;
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

    /**
     * 相机**轻量全量列表**（不分页、不带计数大字段以外的冗余）。
     *
     * <p><b>用途</b>（2026-11-18 评审 M-02）：前端此前有 5 处按"全量"语义硬编码
     * {@code getCameraList({page:1, size:999})} —— 首页相机表、图像记录、配置页、
     * Dashboard 的相机下拉。相机规模上去后首屏传输量随总数线性膨胀，
     * 且 size=999 会被后端分页上限截断（截断后前端**静默少数据**，用户看不出来）。
     *
     * <p><b>与 {@code GET /api/cameras} 的区别</b>：本端点**不返回 IPage**
     * （没有总数 COUNT 查询），只返回列表要用到的字段；相机数量级的数据量下
     * 载荷仅为全量实体的 1/3 左右。
     *
     * <p><b>返回字段</b>：id / cameraCode / cameraName / lineId / lineName / lineCode /
     * workstationId / stationName / stationCode / stationLabel / factoryId / factoryName /
     * status / cameraType / productName / totalCount / okCount /
     * ngCount / lastHeartbeatAt / lastUploadAt / reportTime。
     *
     * <p>★ 2026-11-20：补齐了**产线名 / 工位名 / 工厂名**三类维度字段 ——
     *   本端点原先只返回数字 ID，且**没有做维度填充**（瞬态字段全 null），
     *   导致图像记录页显示「未配置工位」。
     */
    @GetMapping("/options")
    public ResultVO<List<Map<String, Object>>> options(
            @RequestParam(required = false) Integer factoryId,
            @RequestParam(required = false) Integer lineId,
            @RequestParam(required = false) Integer workstationId) {
        List<CameraStatus> all = cameraStatusService.listAll();
        // ★ 2026-11-20：这里原来直接返回**原始实体**，没做维度填充
        //   （stationLabel / stationName / stationCode / lineName / lineCode / factoryName
        //   全是 @TableField(exist=false) 瞬态字段，camera_status 表里根本没有这些列）
        //   → 前端 stationLabel() 全部落空 → 页面显示「未配置工位」。
        //   现与 list 端点共用 enrichCameraDimensions。
        enrichCameraDimensions(all);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CameraStatus c : all) {
            if (factoryId != null && !Objects.equals(factoryId, c.getFactoryId())) {
                continue;
            }
            if (lineId != null && !Objects.equals(lineId, c.getLineId())) {
                continue;
            }
            if (workstationId != null && !Objects.equals(workstationId, c.getWorkstationId())) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", c.getId());
            row.put("cameraCode", c.getCameraCode());
            row.put("cameraName", c.getCameraName());
            row.put("lineId", c.getLineId());
            row.put("lineName", c.getLineName());
            row.put("lineCode", c.getLineCode());
            row.put("workstationId", c.getWorkstationId());
            row.put("stationName", c.getStationName());
            row.put("stationCode", c.getStationCode());
            row.put("factoryId", c.getFactoryId());
            row.put("factoryName", c.getFactoryName());
            row.put("status", c.getStatus());
            row.put("cameraType", c.getCameraType());
            row.put("productName", c.getProductName());
            row.put("totalCount", c.getTotalCount());
            row.put("okCount", c.getOkCount());
            row.put("ngCount", c.getNgCount());
            row.put("lastHeartbeatAt", c.getLastHeartbeatAt());
            row.put("lastUploadAt", c.getLastUploadAt());
            row.put("reportTime", c.getReportTime());
            row.put("stationLabel", c.getStationLabel());
            rows.add(row);
        }
        return ResultVO.success(rows);
    }

    /**
     * 单台相机详情 —— 补齐 REST 语义。
     *
     * <p><b>用途</b>（评审 M-02）：相机详情页此前只能"拉全量相机列表再 find 一台"
     * （{@code getCameraList({page:1,size:999})} 后本地 find）—— 载荷浪费且随规模恶化。
     */
    @GetMapping("/{id}")
    public ResultVO<Map<String, Object>> detail(@PathVariable Long id) {
        CameraStatus c = cameraStatusService.getById(id);
        if (c == null) {
            return ResultVO.fail("相机不存在: " + id);
        }
        // ★ 2026-11-20：详情端点同样没做维度填充 —— stationLabel/stationName 等瞬态字段全 null
        //   → 相机详情页显示「未配置工位」（与 /cameras/options 同一个 bug 的第三个实例）。
        enrichCameraDimensions(java.util.Collections.singletonList(c));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", c.getId());
        row.put("cameraCode", c.getCameraCode());
        row.put("cameraName", c.getCameraName());
        row.put("lineId", c.getLineId());
        row.put("workstationId", c.getWorkstationId());
        row.put("factoryId", c.getFactoryId());
        row.put("status", c.getStatus());
        row.put("cameraType", c.getCameraType());
        row.put("productName", c.getProductName());
        row.put("totalCount", c.getTotalCount());
        row.put("okCount", c.getOkCount());
        row.put("ngCount", c.getNgCount());
        row.put("recognizeCount", c.getRecognizeCount());
        row.put("correctCount", c.getCorrectCount());
        row.put("abnormalCount", c.getAbnormalCount());
        row.put("yieldRate", c.getYieldRate());
        row.put("cpuUsage", c.getCpuUsage());
        row.put("memoryUsage", c.getMemoryUsage());
        row.put("temperature", c.getTemperature());
        row.put("latestImagePath", c.getLatestImagePath());
        row.put("lastHeartbeatAt", c.getLastHeartbeatAt());
        row.put("lastUploadAt", c.getLastUploadAt());
        row.put("reportTime", c.getReportTime());
        row.put("errorMessage", c.getErrorMessage());
        row.put("stationName", c.getStationName());
        row.put("stationCode", c.getStationCode());
        row.put("stationLabel", c.getStationLabel());
        // ★ 补产线与工厂的名称（原先只返回数字 ID，详情页无法展示）
        row.put("lineName", c.getLineName());
        row.put("lineCode", c.getLineCode());
        row.put("factoryName", c.getFactoryName());
        return ResultVO.success(row);
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
        // ★ 2026-11-16 复活软删行：删除是逻辑删除（camera_status.is_active=0，行还在库里），
        //   而 camera_status 有物理唯一键 uk_camera_code —— 软删行不处理，同编号重新新增
        //   要么被查重挡住（旧行为报「设备编号已存在」，现场实测 DA6090850：加错位置 → 删除
        //   → 重加被自己删掉的行挡死），要么撞唯一键。正确做法是原位复活：字段按本次请求
        //   更新、is_active 置回 1；dim_camera 由 upsertDimCamera 的 ON DUPLICATE KEY
        //   自动复活（is_deleted=0），两表口径保持一致。
        boolean revivingDeleted = exists != null
                && exists.getIsActive() != null && exists.getIsActive() == 0;
        if (exists != null && !revivingDeleted) {
            return ResultVO.fail("设备编号已存在");
        }
        if (cameraDirectory.countDimCameraByCode(camera.getCameraCode()) > 0) {
            return ResultVO.fail("设备编号已存在");
        }
        // 授权相机数配额（2026-11-12）。放在「编号已存在」之后：重号是更明确的原因，
        // 先报配额会让用户以为「加不了」而实际只是编号冲突。复活同样占用配额，不能跳过。
        String quotaError = licenseService.checkCameraQuota(cameraDirectory.countDimCameras());
        if (quotaError != null) {
            return ResultVO.fail(quotaError);
        }
        if (camera.getStatus() == null || camera.getStatus().trim().isEmpty()) camera.setStatus("RUNNING");
        if (camera.getIsActive() == null) camera.setIsActive(1);
        // ★ 2026-11-05 新增：相机类型（REAL=真实产线相机，不参与定时清理；TEST=测试相机，参与清理）。
        //   未指定时新增默认 TEST（与存量现状一致：现场 16 台中 14 台为测试相机）；
        //   复活场景传原值 —— 「REAL 相机删了重加」绝不能被静默降级成 TEST（会被每小时清理删数据）。
        String typeError = normalizeCameraType(camera, revivingDeleted ? exists.getCameraType() : null);
        if (typeError != null) {
            return ResultVO.fail(typeError);
        }
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        camera.setUpdateTime(now);
        if (revivingDeleted) {
            camera.setId(exists.getId());
            camera.setIsActive(1);
            camera.setCreateTime(exists.getCreateTime());
            cameraStatusMapper.updateById(camera);
            syncDimCamera(camera);
            return ResultVO.success("创建成功");
        }
        camera.setId(null);
        camera.setCreateTime(now);
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
        if (cameraDirectory.countDimCameraByCodeExcept(camera.getCameraCode(), id) > 0) {
            return ResultVO.fail("设备编号已存在");
        }
        camera.setId(id);
        // ★ 2026-11-02 修正：删除改为逻辑删除后（camera_status.is_active=0），
        //   编辑保存时必须把 is_active 恢复为 1，否则「删除 → 再编辑恢复」后
        //   相机仍不出现在列表里（实测：PUT 返回"更新成功"但 is_active 仍为 0）。
        //   前端编辑表单不携带 is_active 字段，这里显式置 1。
        camera.setIsActive(1);
        // ★ 2026-11-05：相机类型。请求未带 cameraType 时**保留原值**，
        //   避免编辑其他属性时把真实相机意外降级成测试相机（会导致其数据被定时清理）。
        String typeError = normalizeCameraType(camera, existing.getCameraType());
        if (typeError != null) {
            return ResultVO.fail(typeError);
        }
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
        int imageCount = cameraDirectory.countDefectImageFiles(existing.getCameraCode(), existing.getCameraName());
        int compatImageCount = cameraDirectory.countCompatImages(existing.getCameraName());
        int defectBoxCount = cameraDirectory.countDefectImageBoxes(existing.getCameraCode(), existing.getCameraName());
        int mappingCount = cameraDirectory.countDefectMappings(id);
        int tokenCount = cameraDirectory.countCameraTokens(id);
        int heartbeatCount = cameraDirectory.countHeartbeats(existing.getCameraCode());

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

        int imageCount = cameraDirectory.countDefectImageFiles(existing.getCameraCode(), existing.getCameraName());
        int compatImageCount = cameraDirectory.countCompatImages(existing.getCameraName());
        int heartbeatCount = cameraDirectory.countHeartbeats(existing.getCameraCode());

        // 有历史图片或心跳 → 拒绝删除（历史数据不可自动清理，必须人工确认归档）
        if (imageCount + compatImageCount > 0 || heartbeatCount > 0) {
            return ResultVO.fail("相机存在历史图片/心跳记录，禁止直接删除。建议先停用或完成数据归档后再删除。",
                    "依赖明细：图片记录 " + (imageCount + compatImageCount) + " 条，心跳记录 " + heartbeatCount + " 条。");
        }

        // ---- 逻辑删除：两表一致 ----
        cameraDirectory.softDeleteCameraStatus(id);
        cameraDirectory.softDeleteDimCamera(id);

        // ---- 级联清理：避免孤儿数据 ----
        int mappingRows = cameraDirectory.deleteDefectMappings(id);
        int tokenRows = cameraDirectory.deleteCameraTokens(id);

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

    /**
     * 归一化相机类型（2026-11-05 新增）。
     *
     * <p>取值仅允许 REAL（真实产线相机，数据不参与定时清理）与 TEST（测试相机，参与清理）。
     *
     * @param camera        待写入的相机
     * @param existingType  已有类型（新增场景传 null；编辑场景传原值用于"未指定则保留"）
     * @return null 表示合法；非 null 为错误提示文案
     */
    private String normalizeCameraType(CameraStatus camera, String existingType) {
        String raw = camera.getCameraType();
        if (raw == null || raw.trim().isEmpty()) {
            // 未指定：新增时默认 TEST（与存量现状一致），编辑时保留原值
            camera.setCameraType(existingType == null || existingType.trim().isEmpty()
                    ? CAMERA_TYPE_TEST : existingType);
            return null;
        }
        String normalized = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (!CAMERA_TYPE_REAL.equals(normalized) && !CAMERA_TYPE_TEST.equals(normalized)) {
            return "相机类型无效：仅支持 REAL（真实产线相机）或 TEST（测试相机）";
        }
        camera.setCameraType(normalized);
        return null;
    }

    private void syncDimCamera(CameraStatus camera) {
        DimProductionLine line = lineService.getById(camera.getLineId());
        if (line == null) {
            throw new IllegalArgumentException("产线不存在，无法同步相机维表");
        }
        cameraDirectory.upsertDimCamera(camera.getId(), line.getFactoryId(), camera.getLineId(),
                camera.getWorkstationId(), camera.getCameraCode(), camera.getCameraName(),
                camera.getCameraType());
        cameraDirectory.syncMappingLocation(line.getFactoryId(), camera.getLineId(),
                camera.getWorkstationId(), camera.getCameraCode(), camera.getId());
    }
}
