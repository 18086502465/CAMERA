package com.wms.masterdata.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wms.masterdata.domain.model.DimWorkstation;
import com.wms.masterdata.app.IDimWorkstationService;
import com.wms.shared.common.ResultVO;
import org.springframework.beans.factory.annotation.Autowired;
import com.wms.masterdata.infra.repository.MasterDataRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.LinkedHashMap;

@RestController
@RequestMapping("/api/workstations")
public class DimWorkstationController {

    @Autowired
    private IDimWorkstationService workstationService;

    @Autowired
    private MasterDataRepository masterDataRepository;

    @GetMapping
    public ResultVO<?> listAll(@RequestParam(required = false) Integer lineId,
                               @RequestParam(required = false) Integer page,
                               @RequestParam(required = false) Integer size,
                               @RequestParam(required = false) String keyword) {
        if (page != null || size != null) {
            int pageNo = page == null ? 1 : page;
            int pageSize = size == null ? 10 : size;
            LambdaQueryWrapper<DimWorkstation> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(lineId != null, DimWorkstation::getLineId, lineId);
            if (keyword != null && !keyword.trim().isEmpty()) {
                wrapper.and(w -> w.like(DimWorkstation::getStationName, keyword).or().like(DimWorkstation::getStationCode, keyword));
            }
            wrapper.orderByAsc(DimWorkstation::getLineId).orderByAsc(DimWorkstation::getStationId);
            return ResultVO.success(workstationService.page(new Page<>(pageNo, pageSize), wrapper));
        }
        if (lineId != null) return ResultVO.success(workstationService.getByLineId(Long.valueOf(lineId)));
        return ResultVO.success(workstationService.list());
    }

    @GetMapping("/{id}")
    public ResultVO<DimWorkstation> getById(@PathVariable Integer id) {
        return ResultVO.success(workstationService.getById(id));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResultVO<String> create(@RequestBody DimWorkstation ws) {
        workstationService.save(ws);
        return ResultVO.success("创建成功");
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    @Transactional
    public ResultVO<String> update(@PathVariable Integer id, @RequestBody DimWorkstation ws) {
        if (workstationService.getById(id) == null) {
            return ResultVO.fail("工位不存在");
        }
        ws.setStationId(id);
        workstationService.updateById(ws);
        if (ws.getLineId() != null) {
            masterDataRepository.touchCameraStatusOnWorkstationMove(ws.getLineId(), id);
            masterDataRepository.syncCameraLocation(ws.getLineId(), id);
            masterDataRepository.remapDefectMappingByStation(id);
        }
        return ResultVO.success("更新成功");
    }

    /**
     * 删除工位。
     *
     * <p><b>2026-11-02 修正</b>：依赖校验原先查 {@code camera_status.workstation_id}，
     * 但<b>相机与工位的绑定关系实际维护在 {@code dim_camera.workstation_id}</b>
     * （{@code camera_status} 只是状态快照，可能没有对应行，或行已随删除消失）。
     * 结果：绑定了相机的工位<b>可以被误删</b>，留下悬空的相机绑定。
     * <p>现在改为查 {@code dim_camera}（含 {@code is_deleted=0} 的有效绑定）。
     */
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResultVO<String> delete(@PathVariable Integer id) {
        int cameraCount = masterDataRepository.countCamerasByStation(id);
        int productionCount = masterDataRepository.countProductionByStation(id);
        int defectCount = masterDataRepository.countDetectionByStation(id);
        if (cameraCount > 0) {
            return ResultVO.fail("工位已绑定相机，禁止直接删除。请先迁移或解绑相机后再操作。",
                    "依赖明细：相机 " + cameraCount + " 台，生产记录 " + productionCount + " 条，缺陷记录 " + defectCount + " 条。");
        }
        // ★ 2026-11-04 修正（关键）：
        //   原实现先执行 `UPDATE dim_camera SET workstation_id = NULL WHERE workstation_id = ?`，
        //   但 dim_camera.workstation_id 是 **NOT NULL** 列 →
        //   抛 SQLIntegrityConstraintViolationException: Column 'workstation_id' cannot be null
        //   → 整个 DELETE 请求 500，用户点「删除」**什么都没发生**（这就是按钮无效的直接原因）。
        //   现在改为：把绑定相机的 workstation_id 改成一个「已删除工位」占位值不可行，
        //   正确做法是**只停用相机**（is_enabled=0 + is_deleted=1 走相机自身的删除语义），
        //   由前端在相机行上看到「工位已删除」提示；这里不再写 NULL。
        int boundCameras = masterDataRepository.countCamerasByStation(id);
        masterDataRepository.disableCamerasByStation(id);
        workstationService.removeById(id);
        // 注意 ResultVO.success(data, message) 的参数顺序：data 在前、message 在后。
        String message = boundCameras > 0
                ? "删除成功。已停用绑定该工位的相机 " + boundCameras + " 台。"
                : "删除成功";
        return ResultVO.success("删除成功", message);
    }

    @GetMapping("/{id}/dependencies")
    public ResultVO<Map<String, Object>> dependencies(@PathVariable Integer id) {
        // ★ 查 dim_camera（绑定关系的真实来源），而非 camera_status（状态快照）
        int cameraCount = masterDataRepository.countCamerasByStation(id);
        int productionCount = masterDataRepository.countProductionByStation(id);
        int defectCount = masterDataRepository.countDetectionByStation(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("cameraCount", cameraCount);
        data.put("productionCount", productionCount);
        data.put("defectCount", defectCount);
        data.put("riskLevel", (cameraCount + productionCount + defectCount) > 0 ? "HIGH" : "LOW");
        return ResultVO.success(data);
    }

}
