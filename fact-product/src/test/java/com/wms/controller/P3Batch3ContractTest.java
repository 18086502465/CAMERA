package com.wms.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wms.analytics.api.CameraDashboardController;
import com.wms.device.app.ICameraStatusService;
import com.wms.device.domain.model.CameraStatus;
import com.wms.device.infra.mapper.CameraStatusMapper;
import com.wms.device.infra.repository.CameraDirectoryRepository;
import com.wms.masterdata.app.IDimProductionLineService;
import com.wms.masterdata.app.IDimWorkstationService;
import com.wms.masterdata.domain.model.DimProductionLine;
import com.wms.masterdata.domain.model.DimWorkstation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DDD 重构 P3 批次③ 契约测试（相机域 JDBC 收敛）。
 *
 * <p>冻结点：
 * <ul>
 *   <li>相机依赖检查的<b>风险等级判定</b>（HIGH / MEDIUM / LOW）——
 *       这是删除相机前最关键的分支，SQL 下沉后最容易被改坏；</li>
 *   <li>设备编号唯一性校验（dim_camera 与 camera_status 两处都要查）；</li>
 *   <li>有历史图片/心跳时<b>拒绝删除</b>；</li>
 *   <li>删除时两表逻辑删除 + 映射/令牌级联清理。</li>
 * </ul>
 *
 * <p>重构期间这些断言失败 = 行为变化，必须停。
 */
@ExtendWith(MockitoExtension.class)
class P3Batch3ContractTest {

    private final ObjectMapper om = new ObjectMapper();

    @Mock
    private ICameraStatusService cameraStatusService;
    @Mock
    private CameraStatusMapper cameraStatusMapper;
    @Mock
    private IDimWorkstationService workstationService;
    @Mock
    private IDimProductionLineService lineService;
    @Mock
    private CameraDirectoryRepository cameraDirectoryRepository;

    @InjectMocks
    private CameraDashboardController cameraDashboardController;

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(cameraDashboardController).build();
    }

    private CameraStatus existingCamera() {
        CameraStatus c = new CameraStatus();
        c.setId(9L);
        c.setCameraCode("DA9200622");
        c.setCameraName("CV1_Cam1");
        c.setLineId(3);
        c.setWorkstationId(14);
        c.setIsActive(1);
        return c;
    }

    // ---------------- 依赖检查：风险等级 ----------------

    @Test
    @DisplayName("dependencies: 有历史图片 → riskLevel=HIGH")
    void dependenciesHighByImages() throws Exception {
        when(cameraStatusMapper.selectById(9L)).thenReturn(existingCamera());
        when(cameraDirectoryRepository.countDefectImageFiles(anyString(), anyString())).thenReturn(1347);
        when(cameraDirectoryRepository.countCompatImages(anyString())).thenReturn(0);
        when(cameraDirectoryRepository.countDefectImageBoxes(anyString(), anyString())).thenReturn(0);
        when(cameraDirectoryRepository.countDefectMappings(anyLong())).thenReturn(0);
        when(cameraDirectoryRepository.countCameraTokens(anyLong())).thenReturn(0);
        when(cameraDirectoryRepository.countHeartbeats(anyString())).thenReturn(0);

        MockMvc mvc = mvc();
        mvc.perform(get("/api/cameras/9/dependencies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.riskLevel").value("HIGH"))
                .andExpect(jsonPath("$.data.imageCount").value(1347))
                .andExpect(jsonPath("$.data.defectImageCount").value(1347))
                .andExpect(jsonPath("$.data.compatImageCount").value(0))
                .andExpect(jsonPath("$.data.detail").exists());
    }

    @Test
    @DisplayName("dependencies: 仅映射与令牌 → riskLevel=MEDIUM")
    void dependenciesMediumByMappingAndToken() throws Exception {
        when(cameraStatusMapper.selectById(9L)).thenReturn(existingCamera());
        when(cameraDirectoryRepository.countDefectImageFiles(anyString(), anyString())).thenReturn(0);
        when(cameraDirectoryRepository.countCompatImages(anyString())).thenReturn(0);
        when(cameraDirectoryRepository.countDefectImageBoxes(anyString(), anyString())).thenReturn(0);
        when(cameraDirectoryRepository.countDefectMappings(anyLong())).thenReturn(2);
        when(cameraDirectoryRepository.countCameraTokens(anyLong())).thenReturn(1);
        when(cameraDirectoryRepository.countHeartbeats(anyString())).thenReturn(0);

        MockMvc mvc = mvc();
        mvc.perform(get("/api/cameras/9/dependencies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.riskLevel").value("MEDIUM"))
                .andExpect(jsonPath("$.data.mappingCount").value(2))
                .andExpect(jsonPath("$.data.tokenCount").value(1));
    }

    @Test
    @DisplayName("dependencies: 无任何依赖 → riskLevel=LOW 且 detail=无依赖")
    void dependenciesLow() throws Exception {
        when(cameraStatusMapper.selectById(9L)).thenReturn(existingCamera());
        when(cameraDirectoryRepository.countDefectImageFiles(anyString(), anyString())).thenReturn(0);
        when(cameraDirectoryRepository.countCompatImages(anyString())).thenReturn(0);
        when(cameraDirectoryRepository.countDefectImageBoxes(anyString(), anyString())).thenReturn(0);
        when(cameraDirectoryRepository.countDefectMappings(anyLong())).thenReturn(0);
        when(cameraDirectoryRepository.countCameraTokens(anyLong())).thenReturn(0);
        when(cameraDirectoryRepository.countHeartbeats(anyString())).thenReturn(0);

        MockMvc mvc = mvc();
        mvc.perform(get("/api/cameras/9/dependencies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.riskLevel").value("LOW"))
                .andExpect(jsonPath("$.data.detail").value("无依赖"));
    }

    @Test
    @DisplayName("dependencies: 相机不存在 → code=500")
    void dependenciesCameraNotFound() throws Exception {
        when(cameraStatusMapper.selectById(999L)).thenReturn(null);

        MockMvc mvc = mvc();
        mvc.perform(get("/api/cameras/999/dependencies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("相机不存在"));
    }

    // ---------------- 删除：分支与级联 ----------------

    @Test
    @DisplayName("delete: 有历史图片 → 拒绝删除，不做任何写操作")
    void deleteBlockedByImages() throws Exception {
        when(cameraStatusMapper.selectById(9L)).thenReturn(existingCamera());
        when(cameraDirectoryRepository.countDefectImageFiles(anyString(), anyString())).thenReturn(10);
        when(cameraDirectoryRepository.countCompatImages(anyString())).thenReturn(0);
        when(cameraDirectoryRepository.countHeartbeats(anyString())).thenReturn(0);

        MockMvc mvc = mvc();
        mvc.perform(delete("/api/cameras/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));

        verify(cameraDirectoryRepository, never()).softDeleteCameraStatus(anyLong());
        verify(cameraDirectoryRepository, never()).softDeleteDimCamera(anyLong());
        verify(cameraDirectoryRepository, never()).deleteDefectMappings(anyLong());
        verify(cameraDirectoryRepository, never()).deleteCameraTokens(anyLong());
    }

    @Test
    @DisplayName("delete: 有心跳记录 → 拒绝删除")
    void deleteBlockedByHeartbeat() throws Exception {
        when(cameraStatusMapper.selectById(9L)).thenReturn(existingCamera());
        when(cameraDirectoryRepository.countDefectImageFiles(anyString(), anyString())).thenReturn(0);
        when(cameraDirectoryRepository.countCompatImages(anyString())).thenReturn(0);
        when(cameraDirectoryRepository.countHeartbeats(anyString())).thenReturn(5);

        MockMvc mvc = mvc();
        mvc.perform(delete("/api/cameras/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));

        verify(cameraDirectoryRepository, never()).softDeleteCameraStatus(anyLong());
    }

    @Test
    @DisplayName("delete: 已停用 → 拒绝重复删除")
    void deleteAlreadyInactive() throws Exception {
        CameraStatus inactive = existingCamera();
        inactive.setIsActive(0);
        when(cameraStatusMapper.selectById(9L)).thenReturn(inactive);

        MockMvc mvc = mvc();
        mvc.perform(delete("/api/cameras/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("相机已处于停用/删除状态，无需重复删除"));

        verify(cameraDirectoryRepository, never()).softDeleteCameraStatus(anyLong());
    }

    @Test
    @DisplayName("delete: 无依赖 → 两表逻辑删除 + 映射/令牌级联清理")
    void deleteCascades() throws Exception {
        when(cameraStatusMapper.selectById(9L)).thenReturn(existingCamera());
        when(cameraDirectoryRepository.countDefectImageFiles(anyString(), anyString())).thenReturn(0);
        when(cameraDirectoryRepository.countCompatImages(anyString())).thenReturn(0);
        when(cameraDirectoryRepository.countHeartbeats(anyString())).thenReturn(0);
        when(cameraDirectoryRepository.deleteDefectMappings(9L)).thenReturn(2);
        when(cameraDirectoryRepository.deleteCameraTokens(9L)).thenReturn(1);

        MockMvc mvc = mvc();
        mvc.perform(delete("/api/cameras/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("删除成功"));

        // 两表都做逻辑删除（不是物理删除，保持一致可恢复）
        verify(cameraDirectoryRepository).softDeleteCameraStatus(9L);
        verify(cameraDirectoryRepository).softDeleteDimCamera(9L);
        // 级联清理避免孤儿数据
        verify(cameraDirectoryRepository).deleteDefectMappings(9L);
        verify(cameraDirectoryRepository).deleteCameraTokens(9L);
    }

    // ---------------- 新增：设备编号唯一性 ----------------

    @Test
    @DisplayName("create: dim_camera 已有同编号 → 拒绝创建")
    void createRejectedByDimCamera() throws Exception {
        DimWorkstation station = new DimWorkstation();
        station.setStationId(14);
        station.setLineId(3);
        when(workstationService.getById(14)).thenReturn(station);

        DimProductionLine line = new DimProductionLine();
        line.setLineId(3);
        line.setFactoryId(1);
        when(lineService.getById(3)).thenReturn(line);

        when(cameraStatusMapper.selectOne(any())).thenReturn(null);
        when(cameraDirectoryRepository.countDimCameraByCode("ZZ-DUP-001")).thenReturn(1);

        Map<String, Object> body = new HashMap<>();
        body.put("cameraCode", "ZZ-DUP-001");
        body.put("cameraName", "ZZTEST相机");
        body.put("workstationId", 14);

        MockMvc mvc = mvc();
        mvc.perform(post("/api/cameras")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("设备编号已存在"));

        verify(cameraStatusMapper, never()).insert(any(CameraStatus.class));
    }

    @Test
    @DisplayName("create: 缺相机名称 → 拒绝创建")
    void createRejectedByMissingName() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("cameraCode", "ZZ-CODE-002");

        MockMvc mvc = mvc();
        mvc.perform(post("/api/cameras")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("相机名称不能为空"));

        verify(cameraStatusMapper, never()).insert(any(CameraStatus.class));
    }

    @Test
    @DisplayName("create: 缺工位 → 拒绝创建（不查维表）")
    void createRejectedByMissingStation() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("cameraCode", "ZZ-CODE-003");
        body.put("cameraName", "ZZTEST相机");

        MockMvc mvc = mvc();
        mvc.perform(post("/api/cameras")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("请选择工位"));

        verify(cameraStatusMapper, never()).insert(any(CameraStatus.class));
    }

    // ---------------- 列表 ----------------

    @Test
    @DisplayName("cameras: 分页列表返回 records 结构")
    void cameraListStructure() throws Exception {
        IPage<CameraStatus> page = new Page<>(1, 20, 0);
        page.setRecords(Collections.emptyList());
        when(cameraStatusService.pageQuery(anyInt(), anyInt(), any(), any(), any())).thenReturn(page);
        when(workstationService.list()).thenReturn(Collections.emptyList());
        when(lineService.list()).thenReturn(Collections.emptyList());

        MockMvc mvc = mvc();
        mvc.perform(get("/api/cameras").param("page", "1").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    @DisplayName("cameras: 按工位过滤且无匹配工位 → 返回空页（不查 camera_status）")
    void cameraListEmptyByStationFilter() throws Exception {
        when(workstationService.list()).thenReturn(Collections.emptyList());

        MockMvc mvc = mvc();
        mvc.perform(get("/api/cameras").param("stationId", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.total").value(0));

        verify(cameraStatusService, never()).pageQuery(anyInt(), anyInt(), any(), any(), any());
    }
}
