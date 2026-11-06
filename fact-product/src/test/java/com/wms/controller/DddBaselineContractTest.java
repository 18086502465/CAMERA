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

    /** SEC-03（2026-11-06）：AuthController 新增的登录限速器，Mock 下空实现即放行 */
    @Mock
    private com.wms.iam.domain.LoginRateLimiter rateLimiter;

    @InjectMocks
    private AuthController authController;

    @Test
    @DisplayName("login: 缺 password → HTTP 400 + code=40004（防 NPE 回归；NEW-03 后凭据错误不再用 401）")
    void loginMissingPassword() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(authController)
                .setControllerAdvice(new com.wms.shared.web.ApiStatusAdvice())
                .build();
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40004))
                .andExpect(jsonPath("$.errorCode").value("AUTH_INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("login: 未知用户 → 400 + 40004（不暴露用户是否存在）")
    void loginUnknownUser() throws Exception {
        when(userService.findByUsername("nobody")).thenReturn(null);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(authController)
                .setControllerAdvice(new com.wms.shared.web.ApiStatusAdvice())
                .build();
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody\",\"password\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40004))
                .andExpect(jsonPath("$.errorCode").value("AUTH_INVALID_CREDENTIALS"));
    }

    @Test
    @DisplayName("login: 禁用用户 → 400 + 40004（与未知用户同一响应，不产生侧信道）")
    void loginDisabledUser() throws Exception {
        com.wms.iam.domain.model.SysUser u = new com.wms.iam.domain.model.SysUser();
        u.setUsername("admin");
        u.setIsDisabled(1);
        when(userService.findByUsername("admin")).thenReturn(u);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(authController)
                .setControllerAdvice(new com.wms.shared.web.ApiStatusAdvice())
                .build();
        Map<String, String> body = new java.util.HashMap<>();
        body.put("username", "admin");
        body.put("password", "whatever");
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40004));
    }

    @Test
    @DisplayName("login: 存量 ResultVO.fail(500) 语义未被牵连 —— 未登记 code 不改 HTTP 状态")
    void legacyFailStaysHttp200() {
        com.wms.shared.common.ResultVO<String> legacy = com.wms.shared.common.ResultVO.fail("老接口失败");
        org.junit.jupiter.api.Assertions.assertEquals(500, legacy.getCode());
        org.junit.jupiter.api.Assertions.assertNull(com.wms.shared.common.ApiErrorCode.httpStatusOf(legacy.getCode()));
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
    private com.wms.masterdata.infra.repository.MasterDataRepository masterDataRepository;

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
        // DDD P3：级联的相机计数已下沉到 MasterDataRepository（原语义：失败返回空 Map）
        when(masterDataRepository.countCamerasGroupByStation()).thenReturn(Collections.emptyMap());

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
