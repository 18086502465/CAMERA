package com.wms.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wms.analytics.api.CameraDashboardController;
import com.wms.device.app.CameraDirectory;
import com.wms.device.app.ICameraStatusService;
import com.wms.device.domain.model.CameraStatus;
import com.wms.device.infra.mapper.CameraStatusMapper;
import com.wms.masterdata.app.IDimProductionLineService;
import com.wms.masterdata.app.IDimWorkstationService;
import com.wms.masterdata.domain.model.DimProductionLine;
import com.wms.masterdata.domain.model.DimWorkstation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 相机类型隔离契约测试（2026-11-05）。
 *
 * <p>背景：给相机加 {@code camera_type}（REAL=真实产线相机，数据不参与定时清理；
 * TEST=测试相机，参与清理）后，最危险的场景是<b>真实相机被误降级成测试相机</b> ——
 * 其数据会被每小时清理任务删除且无法恢复。本类冻结以下行为：
 *
 * <ul>
 *   <li>新建相机未指定类型 → 默认 TEST（与存量现状一致）；</li>
 *   <li>新建相机显式指定 REAL → 写入 camera_status 且同步到 dim_camera；</li>
 *   <li>非法类型值 → 拒绝创建，不落库；</li>
 *   <li>编辑相机未带类型 → <b>保留原值</b>（REAL 不被降级）；</li>
 *   <li>编辑相机显式改类型 → 正常写入两表。</li>
 * </ul>
 *
 * <p>这些断言失败 = 行为变化，可能导致真实相机数据被清理，必须停。
 */
@ExtendWith(MockitoExtension.class)
class CameraTypeContractTest {

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
    private CameraDirectory cameraDirectory;
    /**
     * 2026-11-12：create 增加了授权相机数配额校验。
     * 不 mock 它会导致 4 个 create 用例全部 NPE（standalone MockMvc 注入 null）。
     */
    @Mock
    private com.wms.system.app.LicenseService licenseService;

    @InjectMocks
    private CameraDashboardController cameraDashboardController;

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(cameraDashboardController).build();
    }

    private void stubRelation() {
        // 授权配额不设限：本类冻结的是相机类型契约，不是授权上限。
        // 用 lenient —— update 相关用例不会走到配额分支，严格模式会误报未使用 stub。
        org.mockito.Mockito.lenient().when(licenseService.checkCameraQuota(anyInt())).thenReturn(null);

        DimWorkstation station = new DimWorkstation();
        station.setStationId(14);
        station.setStationName("CV1");
        station.setLineId(3);
        when(workstationService.getById(14)).thenReturn(station);

        DimProductionLine line = new DimProductionLine();
        line.setLineId(3);
        line.setFactoryId(1);
        when(lineService.getById(3)).thenReturn(line);
    }

    private Map<String, Object> cameraBody(String code, String type) {
        Map<String, Object> body = new HashMap<>();
        body.put("cameraName", "CV1_Cam1");
        body.put("cameraCode", code);
        body.put("lineId", 3);
        body.put("workstationId", 14);
        if (type != null) {
            body.put("cameraType", type);
        }
        return body;
    }

    // ---------------- 新建：默认类型 ----------------

    @Test
    @DisplayName("create: 未指定 cameraType → 默认 TEST")
    void createDefaultsToTest() throws Exception {
        stubRelation();
        when(cameraStatusMapper.selectOne(any())).thenReturn(null);
        when(cameraDirectory.countDimCameraByCode(anyString())).thenReturn(0);

        mvc().perform(post("/api/cameras")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(cameraBody("ZZTEST-CAM-A", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        ArgumentCaptor<CameraStatus> captor = ArgumentCaptor.forClass(CameraStatus.class);
        verify(cameraStatusMapper).insert(captor.capture());
        assertEquals("TEST", captor.getValue().getCameraType());
    }

    // ---------------- 新建：显式 REAL ----------------

    @Test
    @DisplayName("create: 显式 cameraType=REAL → 两表都写入 REAL")
    void createWithRealType() throws Exception {
        stubRelation();
        when(cameraStatusMapper.selectOne(any())).thenReturn(null);
        when(cameraDirectory.countDimCameraByCode(anyString())).thenReturn(0);

        mvc().perform(post("/api/cameras")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(cameraBody("ZZTEST-CAM-B", "REAL"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        ArgumentCaptor<CameraStatus> captor = ArgumentCaptor.forClass(CameraStatus.class);
        verify(cameraStatusMapper).insert(captor.capture());
        assertEquals("REAL", captor.getValue().getCameraType());

        // dim_camera 是清理任务判定的权威源，必须同步写入
        // ★ 第一参数用 any() 而非 anyLong()：create 里 camera.setId(null)，
        //   主键由 DB 回填（mock 环境下不回填）→ 实际传入 null，anyLong() 不匹配 null。
        verify(cameraDirectory).upsertDimCamera(any(), anyInt(), anyInt(), anyInt(),
                eq("ZZTEST-CAM-B"), anyString(), eq("REAL"));
    }

    @Test
    @DisplayName("create: 小写 real 也能识别（大小写不敏感）")
    void createAcceptsLowerCase() throws Exception {
        stubRelation();
        when(cameraStatusMapper.selectOne(any())).thenReturn(null);
        when(cameraDirectory.countDimCameraByCode(anyString())).thenReturn(0);

        mvc().perform(post("/api/cameras")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(cameraBody("ZZTEST-CAM-C", "real"))))
                .andExpect(status().isOk());

        ArgumentCaptor<CameraStatus> captor = ArgumentCaptor.forClass(CameraStatus.class);
        verify(cameraStatusMapper).insert(captor.capture());
        assertEquals("REAL", captor.getValue().getCameraType());
    }

    // ---------------- 新建：非法类型 ----------------

    @Test
    @DisplayName("create: 非法 cameraType → 拒绝，不落库")
    void createRejectsInvalidType() throws Exception {
        stubRelation();
        when(cameraStatusMapper.selectOne(any())).thenReturn(null);
        when(cameraDirectory.countDimCameraByCode(anyString())).thenReturn(0);

        mvc().perform(post("/api/cameras")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(cameraBody("ZZTEST-CAM-D", "TRIAL"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));

        verify(cameraStatusMapper, never()).insert(any());
        verify(cameraDirectory, never()).upsertDimCamera(any(), anyInt(), anyInt(), anyInt(),
                anyString(), anyString(), anyString());
    }

    // ---------------- 编辑：保留原类型（防误降级） ----------------

    @Test
    @DisplayName("update: 未带 cameraType → 保留原值 REAL（不得降级为 TEST）")
    void updateKeepsExistingRealType() throws Exception {
        CameraStatus existing = new CameraStatus();
        existing.setId(9L);
        existing.setCameraCode("DA8119489");
        existing.setCameraName("CV1_Cam1");
        existing.setLineId(3);
        existing.setWorkstationId(14);
        existing.setIsActive(1);
        existing.setCameraType("REAL");
        when(cameraStatusMapper.selectById(9L)).thenReturn(existing);

        stubRelation();
        when(cameraStatusMapper.selectOne(any())).thenReturn(null);
        when(cameraDirectory.countDimCameraByCodeExcept(anyString(), anyLong())).thenReturn(0);

        mvc().perform(put("/api/cameras/9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(cameraBody("DA8119489", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        ArgumentCaptor<CameraStatus> captor = ArgumentCaptor.forClass(CameraStatus.class);
        verify(cameraStatusMapper).updateById(captor.capture());
        assertEquals("REAL", captor.getValue().getCameraType());

        // 档案表同样必须保持 REAL
        verify(cameraDirectory).upsertDimCamera(anyLong(), anyInt(), anyInt(), anyInt(),
                eq("DA8119489"), anyString(), eq("REAL"));
    }

    @Test
    @DisplayName("update: 显式把 REAL 改成 TEST → 允许（用户主动降级）")
    void updateAllowsExplicitDowngrade() throws Exception {
        CameraStatus existing = new CameraStatus();
        existing.setId(9L);
        existing.setCameraCode("DA8119489");
        existing.setCameraName("CV1_Cam1");
        existing.setLineId(3);
        existing.setWorkstationId(14);
        existing.setIsActive(1);
        existing.setCameraType("REAL");
        when(cameraStatusMapper.selectById(9L)).thenReturn(existing);

        stubRelation();
        when(cameraStatusMapper.selectOne(any())).thenReturn(null);
        when(cameraDirectory.countDimCameraByCodeExcept(anyString(), anyLong())).thenReturn(0);

        mvc().perform(put("/api/cameras/9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(cameraBody("DA8119489", "TEST"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        ArgumentCaptor<CameraStatus> captor = ArgumentCaptor.forClass(CameraStatus.class);
        verify(cameraStatusMapper).updateById(captor.capture());
        assertEquals("TEST", captor.getValue().getCameraType());
    }

    @Test
    @DisplayName("update: 非法 cameraType → 拒绝，不落库")
    void updateRejectsInvalidType() throws Exception {
        CameraStatus existing = new CameraStatus();
        existing.setId(9L);
        existing.setCameraCode("DA8119489");
        existing.setCameraName("CV1_Cam1");
        existing.setLineId(3);
        existing.setWorkstationId(14);
        existing.setIsActive(1);
        existing.setCameraType("REAL");
        when(cameraStatusMapper.selectById(9L)).thenReturn(existing);

        stubRelation();
        when(cameraStatusMapper.selectOne(any())).thenReturn(null);
        when(cameraDirectory.countDimCameraByCodeExcept(anyString(), anyLong())).thenReturn(0);

        mvc().perform(put("/api/cameras/9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(cameraBody("DA8119489", "XXX"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));

        verify(cameraStatusMapper, never()).updateById(any());
    }

    // ---------------- 实体默认值 ----------------

    @Test
    @DisplayName("实体: cameraType 字段可读写（MyBatis-Plus 映射 camera_type）")
    void entityHasCameraTypeField() {
        CameraStatus c = new CameraStatus();
        c.setCameraType("REAL");
        assertEquals("REAL", c.getCameraType());
    }
}
