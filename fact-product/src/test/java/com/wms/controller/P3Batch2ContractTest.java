package com.wms.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wms.system.api.FinalConfigController;
import com.wms.system.api.SystemSettingsController;
import com.wms.system.infra.repository.FinalConfigRepository;
import com.wms.system.infra.repository.SystemSettingsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DDD 重构 P3 批次② 契约测试（system 域 JDBC 收敛）。
 *
 * <p>冻结点：
 * <ul>
 *   <li>SystemSettings 的响应结构（settings / retentionPolicies / groups / summary）；</li>
 *   <li>FinalConfig 的分页结构（records / total / current / size）；</li>
 *   <li>参数校验分支（在线阈值 ≥ 离线阈值、编码重复、映射冲突）——
 *       这些分支在 SQL 下沉后最容易被改坏，必须逐个钉住。</li>
 * </ul>
 *
 * <p>重构期间这些断言失败 = 行为变化，必须停。
 */
@ExtendWith(MockitoExtension.class)
class P3Batch2ContractTest {

    private final ObjectMapper om = new ObjectMapper();

    // ---------------- 系统设置 ----------------

    @Mock
    private SystemSettingsRepository settingsRepository;

    @InjectMocks
    private SystemSettingsController systemSettingsController;

    @Test
    @DisplayName("system/settings: GET 顶层键结构固定（settings/retentionPolicies/groups/summary）")
    void settingsStructure() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("settingKey", "system.name");
        row.put("settingGroup", "BASIC");
        row.put("isEditable", 1);
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("dataType", "DEFECT_IMAGE_FILE");
        policy.put("isEnabled", 1);

        when(settingsRepository.listSettings()).thenReturn(Collections.singletonList(row));
        when(settingsRepository.listRetentionPolicies()).thenReturn(Collections.singletonList(policy));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(systemSettingsController).build();
        mvc.perform(get("/api/v1/system/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.settings").isArray())
                .andExpect(jsonPath("$.data.retentionPolicies").isArray())
                .andExpect(jsonPath("$.data.groups.BASIC").isArray())
                .andExpect(jsonPath("$.data.summary.settingCount").value(1))
                .andExpect(jsonPath("$.data.summary.policyCount").value(1))
                .andExpect(jsonPath("$.data.summary.editableCount").value(1))
                .andExpect(jsonPath("$.data.summary.enabledPolicyCount").value(1));
    }

    @Test
    @DisplayName("system/settings: 在线阈值 ≥ 离线阈值 → code=422 且 errors 提示（校验下沉后不回退）")
    void settingsValidationRejectsBadThreshold() throws Exception {
        when(settingsRepository.loadCurrentSettingValues()).thenReturn(new HashMap<>());

        Map<String, Object> setting = new HashMap<>();
        setting.put("settingKey", "heartbeat.onlineSeconds");
        setting.put("settingValue", "300");
        Map<String, Object> body = new HashMap<>();
        body.put("settings", Collections.singletonList(setting));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(systemSettingsController).build();
        mvc.perform(put("/api/v1/system/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(422))
                .andExpect(jsonPath("$.data.errors").isArray());

        // 校验未通过时不得写库
        verify(settingsRepository, never()).updateSettingValue(anyString(), anyString());
    }

    @Test
    @DisplayName("system/settings: 请求体为空 JSON → code=422（走 body 为空的业务分支，防 NPE 回归）")
    void settingsEmptyBody() throws Exception {
        when(settingsRepository.loadCurrentSettingValues()).thenReturn(new HashMap<>());

        MockMvc mvc = MockMvcBuilders.standaloneSetup(systemSettingsController).build();
        mvc.perform(put("/api/v1/system/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(422));
    }

    // ---------------- 最终模型配置 ----------------

    @Mock
    private FinalConfigRepository finalConfigRepository;

    @InjectMocks
    private FinalConfigController finalConfigController;

    private Map<String, Object> pageData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", Collections.emptyList());
        data.put("total", 0);
        data.put("current", 1);
        data.put("size", 10);
        return data;
    }

    @Test
    @DisplayName("config/products: GET 分页结构固定（records/total/current/size）")
    void productsStructure() throws Exception {
        when(finalConfigRepository.queryProductPage(any(), any(), anyInt(), anyInt())).thenReturn(pageData());

        MockMvc mvc = MockMvcBuilders.standaloneSetup(finalConfigController).build();
        mvc.perform(get("/api/v1/config/products").param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.current").value(1))
                .andExpect(jsonPath("$.data.size").value(10));
    }

    @Test
    @DisplayName("config/defect-types: GET 分页结构固定")
    void defectTypesStructure() throws Exception {
        when(finalConfigRepository.queryDefectTypePage(any(), any(), any(), anyInt(), anyInt())).thenReturn(pageData());

        MockMvc mvc = MockMvcBuilders.standaloneSetup(finalConfigController).build();
        mvc.perform(get("/api/v1/config/defect-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    @DisplayName("config/camera-defect-mappings: GET 分页结构固定")
    void mappingsStructure() throws Exception {
        when(finalConfigRepository.queryMappingPage(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(pageData());

        MockMvc mvc = MockMvcBuilders.standaloneSetup(finalConfigController).build();
        mvc.perform(get("/api/v1/config/camera-defect-mappings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray());
    }

    @Test
    @DisplayName("config/mapping-exceptions: GET 分页结构固定")
    void mappingExceptionsStructure() throws Exception {
        when(finalConfigRepository.queryMappingExceptionPage(any(), any(), anyInt(), anyInt())).thenReturn(pageData());

        MockMvc mvc = MockMvcBuilders.standaloneSetup(finalConfigController).build();
        mvc.perform(get("/api/v1/config/mapping-exceptions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray());
    }

    @Test
    @DisplayName("config/products: 编码重复 → code=500 且不写库（唯一性校验下沉后不回退）")
    void productDuplicateRejected() throws Exception {
        when(finalConfigRepository.countProductByCodeOrName("P001", "产品A")).thenReturn(1);

        Map<String, Object> body = new HashMap<>();
        body.put("productCode", "P001");
        body.put("productName", "产品A");

        MockMvc mvc = MockMvcBuilders.standaloneSetup(finalConfigController).build();
        mvc.perform(post("/api/v1/config/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("产品编码或名称已存在"));

        verify(finalConfigRepository, never()).insertProduct(any());
    }

    @Test
    @DisplayName("config/products: 缺 productCode → 业务错误且不写库")
    void productMissingCodeRejected() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("productName", "产品A");

        MockMvc mvc = MockMvcBuilders.standaloneSetup(finalConfigController).build();
        mvc.perform(post("/api/v1/config/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));

        verify(finalConfigRepository, never()).insertProduct(any());
    }

    @Test
    @DisplayName("config/products: 删除时存在映射 → 拒绝删除且不逻辑删除")
    void productDeleteBlockedByMapping() throws Exception {
        when(finalConfigRepository.countProductMappings(9L)).thenReturn(1);
        when(finalConfigRepository.countProductInspectionBatches(9L)).thenReturn(0);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(finalConfigController).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/config/products/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));

        verify(finalConfigRepository, never()).softDeleteProduct(anyLong());
    }

    @Test
    @DisplayName("config/camera-defect-mappings: 相机不存在 → 业务错误（映射写入前置校验）")
    void mappingCameraNotFound() throws Exception {
        when(finalConfigRepository.findCameraById(404L)).thenReturn(null);

        Map<String, Object> body = new HashMap<>();
        body.put("cameraId", 404);
        body.put("defectTypeId", 1);
        body.put("sourceCode", "SRC-1");

        MockMvc mvc = MockMvcBuilders.standaloneSetup(finalConfigController).build();
        mvc.perform(post("/api/v1/config/camera-defect-mappings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("相机不存在"));

        verify(finalConfigRepository, never()).insertMapping(any());
    }

    @Test
    @DisplayName("config/camera-defect-mappings: 生效期冲突 → 业务错误且不写库")
    void mappingConflictRejected() throws Exception {
        Map<String, Object> camera = new HashMap<>();
        camera.put("camera_id", 5L);
        camera.put("camera_code", "DA9200622");
        camera.put("factory_id", 1);
        camera.put("line_id", 2);
        camera.put("workstation_id", 3);
        Map<String, Object> defect = new HashMap<>();
        defect.put("defect_code", "D01");
        defect.put("defect_name", "缺陷1");
        defect.put("default_severity", "HIGH");
        defect.put("is_ng", 1);

        when(finalConfigRepository.findCameraById(5L)).thenReturn(camera);
        when(finalConfigRepository.findDefectTypeById(1L)).thenReturn(defect);
        when(finalConfigRepository.countConflictingMapping(anyLong(), anyString(), any(), any(), any(), any()))
                .thenReturn(1);

        Map<String, Object> body = new HashMap<>();
        body.put("cameraId", 5);
        body.put("defectTypeId", 1);
        body.put("sourceCode", "SRC-1");

        MockMvc mvc = MockMvcBuilders.standaloneSetup(finalConfigController).build();
        mvc.perform(post("/api/v1/config/camera-defect-mappings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("同一相机、产品、上报编码存在重叠生效映射"));

        verify(finalConfigRepository, never()).insertMapping(any());
    }

    @Test
    @DisplayName("config/camera-defect-mappings: 正常创建 → 写库且缺陷信息取自缺陷类型档案")
    void mappingCreated() throws Exception {
        Map<String, Object> camera = new HashMap<>();
        camera.put("camera_id", 5L);
        camera.put("camera_code", "DA9200622");
        camera.put("factory_id", 1);
        camera.put("line_id", 2);
        camera.put("workstation_id", 3);
        Map<String, Object> defect = new HashMap<>();
        defect.put("defect_code", "D01");
        defect.put("defect_name", "缺陷1");
        defect.put("default_position_id", 7);
        defect.put("default_position_code", "POS-A");
        defect.put("default_position_name", "正面");
        defect.put("default_severity", "HIGH");
        defect.put("is_ng", 1);

        when(finalConfigRepository.findCameraById(5L)).thenReturn(camera);
        when(finalConfigRepository.findDefectTypeById(1L)).thenReturn(defect);
        when(finalConfigRepository.countConflictingMapping(anyLong(), anyString(), any(), any(), any(), any()))
                .thenReturn(0);

        Map<String, Object> body = new HashMap<>();
        body.put("cameraId", 5);
        body.put("defectTypeId", 1);
        body.put("sourceCode", "SRC-1");

        MockMvc mvc = MockMvcBuilders.standaloneSetup(finalConfigController).build();
        mvc.perform(post("/api/v1/config/camera-defect-mappings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("创建成功"));

        org.mockito.ArgumentCaptor<com.wms.system.domain.model.CameraDefectMappingRow> captor =
                org.mockito.ArgumentCaptor.forClass(com.wms.system.domain.model.CameraDefectMappingRow.class);
        verify(finalConfigRepository).insertMapping(captor.capture());
        com.wms.system.domain.model.CameraDefectMappingRow saved = captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals("DA9200622", saved.getCameraCode());
        org.junit.jupiter.api.Assertions.assertEquals("D01", saved.getDefectCode());
        org.junit.jupiter.api.Assertions.assertEquals("POS-A", saved.getPositionCode());
        org.junit.jupiter.api.Assertions.assertEquals("HIGH", saved.getSeverity());
        org.junit.jupiter.api.Assertions.assertEquals(1, saved.getIsNg());
    }

    @Test
    @DisplayName("config/mapping-exceptions/{id}/close: 关闭异常写库且返回固定文案")
    void closeException() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(finalConfigController).build();
        mvc.perform(put("/api/v1/config/mapping-exceptions/12/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("已关闭"));

        verify(finalConfigRepository).closeMappingException(12L);
    }

    @Test
    @DisplayName("config/camera-defect-mappings/{id}: 停用映射走 disableMapping（不物理删除）")
    void disableMapping() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(finalConfigController).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/config/camera-defect-mappings/8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("已停用映射"));

        verify(finalConfigRepository).disableMapping(8L);
    }

    @Test
    @DisplayName("config/defect-types: 同一产品下编码重复 → 拒绝创建")
    void defectTypeDuplicateRejected() throws Exception {
        when(finalConfigRepository.countDefectTypeByCode(1L, "D01")).thenReturn(1);

        Map<String, Object> body = new HashMap<>();
        body.put("productId", 1);
        body.put("defectCode", "D01");
        body.put("defectName", "缺陷1");

        MockMvc mvc = MockMvcBuilders.standaloneSetup(finalConfigController).build();
        mvc.perform(post("/api/v1/config/defect-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("同一产品下缺陷编码已存在"));

        verify(finalConfigRepository, never()).insertDefectType(any());
    }

    @Test
    @DisplayName("config/products: 更新后联动映射表冗余产品名（两段 SQL 都要走）")
    void productUpdateSyncsMappingName() throws Exception {
        when(finalConfigRepository.countProductByCodeOrNameExcept(eq(3L), anyString(), anyString())).thenReturn(0);

        Map<String, Object> body = new HashMap<>();
        body.put("productCode", "P003");
        body.put("productName", "新产品名");

        MockMvc mvc = MockMvcBuilders.standaloneSetup(finalConfigController).build();
        mvc.perform(put("/api/v1/config/products/3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("更新成功"));

        verify(finalConfigRepository).updateProduct(eq(3L), any());
        verify(finalConfigRepository).updateMappingProductName(3L, "新产品名");
    }

    @Test
    @DisplayName("config/defect-types: 更新后联动映射表冗余缺陷信息")
    void defectTypeUpdateSyncsMapping() throws Exception {
        when(finalConfigRepository.countDefectTypeByCodeExcept(eq(4L), any(), anyString())).thenReturn(0);

        Map<String, Object> body = new HashMap<>();
        body.put("defectCode", "D04");
        body.put("defectName", "缺陷4");
        body.put("defaultPositionCode", "POS-B");

        MockMvc mvc = MockMvcBuilders.standaloneSetup(finalConfigController).build();
        mvc.perform(put("/api/v1/config/defect-types/4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(finalConfigRepository).updateDefectType(eq(4L), any());
        verify(finalConfigRepository).updateMappingDefectInfo(eq(4L), eq("D04"), eq("缺陷4"),
                any(), eq("POS-B"), any(), any());
    }
}
