package com.wms.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wms.masterdata.api.CascadeController;
import com.wms.masterdata.app.IDimFactoryService;
import com.wms.masterdata.app.IDimProductBrandService;
import com.wms.masterdata.app.IDimProductionLineService;
import com.wms.masterdata.app.IDimShiftService;
import com.wms.masterdata.app.IDimWorkstationService;
import com.wms.defect.app.IDefectImageService;
import com.wms.defect.api.DefectImageController;
import com.wms.iam.api.AuthController;
import com.wms.iam.app.ISysUserService;
import com.wms.iam.domain.JwtUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DDD 重构 P0 基线契约测试（冻结 HTTP 契约，不加载 Spring 上下文/数据库）：
 *   1. POST /api/auth/login —— 失败/缺参/未知用户的响应结构（成功路径由接口快照兜底）
 *   2. GET  /api/cascade/all —— 级联树顶层键结构
 *   3. GET  /api/v1/defect-images/camera-summaries —— 图像记录页数据源结构
 * 重构期间这些断言失败 = 行为变化，必须停。
 */
@ExtendWith(MockitoExtension.class)
class DddBaselineContractTest {

    private final ObjectMapper om = new ObjectMapper();

    // ---------------- 1. 登录契约 ----------------

    @Mock
    private ISysUserService userService;

    @Mock
    private JwtUtil jwtUtil;

    @InjectMocks
    private AuthController authController;

    @Test
    @DisplayName("login: 缺 password → 不 500，返回业务错误 code=500（防 NPE 回归）")
    void loginMissingPassword() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(authController).build();
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("login: 未知用户 → 业务错误 code=500 而非 HTTP 500")
    void loginUnknownUser() throws Exception {
        when(userService.findByUsername("nobody")).thenReturn(null);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(authController).build();
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody\",\"password\":\"x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
    }

    @Test
    @DisplayName("login: 禁用用户 → 业务错误 code=500")
    void loginDisabledUser() throws Exception {
        com.wms.iam.domain.model.SysUser u = new com.wms.iam.domain.model.SysUser();
        u.setUsername("admin");
        u.setIsDisabled(1);
        when(userService.findByUsername("admin")).thenReturn(u);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(authController).build();
        Map<String, String> body = new java.util.HashMap<>();
        body.put("username", "admin");
        body.put("password", "whatever");
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
    }

    // ---------------- 2. 级联树契约 ----------------

    @Mock
    private IDimFactoryService factoryService;
    @Mock
    private IDimProductionLineService lineService;
    @Mock
    private IDimWorkstationService workstationService;
    @Mock
    private IDimProductBrandService brandService;
    @Mock
    private IDimShiftService shiftService;
    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private CascadeController cascadeController;

    @Test
    @DisplayName("cascade/all: 顶层键集合固定（factories/flattenedFactories/lines/flattenedLines/workstations）")
    void cascadeStructure() throws Exception {
        when(factoryService.list()).thenReturn(Collections.emptyList());
        when(lineService.list()).thenReturn(Collections.emptyList());
        when(workstationService.list()).thenReturn(Collections.emptyList());
        when(brandService.list()).thenReturn(Collections.emptyList());
        when(shiftService.list()).thenReturn(Collections.emptyList());
        when(jdbcTemplate.queryForList(anyString())).thenReturn(Collections.emptyList());

        MockMvc mvc = MockMvcBuilders.standaloneSetup(cascadeController).build();
        mvc.perform(get("/api/cascade/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.factories").exists())
                .andExpect(jsonPath("$.data.flattenedFactories").exists())
                .andExpect(jsonPath("$.data.lines").exists())
                .andExpect(jsonPath("$.data.flattenedLines").exists())
                .andExpect(jsonPath("$.data.workstations").exists());
    }

    // ---------------- 3. camera-summaries 契约 ----------------

    @Mock
    private IDefectImageService defectImageService;

    @InjectMocks
    private DefectImageController defectImageController;

    @Test
    @DisplayName("camera-summaries: code=200 且 data 为数组（图像记录页数据源）")
    void cameraSummariesStructure() throws Exception {
        when(defectImageService.listCameraSummaries(any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        MockMvc mvc = MockMvcBuilders.standaloneSetup(defectImageController).build();
        mvc.perform(get("/api/v1/defect-images/camera-summaries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
    }
}
