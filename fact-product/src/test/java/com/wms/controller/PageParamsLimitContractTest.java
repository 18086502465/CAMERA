package com.wms.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wms.analytics.api.CameraDashboardController;
import com.wms.device.app.CameraDirectory;
import com.wms.device.app.ICameraStatusService;
import com.wms.device.domain.model.CameraStatus;
import com.wms.masterdata.app.IDimProductionLineService;
import com.wms.masterdata.app.IDimWorkstationService;
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

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 分页参数归一化契约测试（2026-11-18 评审 M-02）。
 *
 * <p>背景：{@code GET /api/cameras} 等端点把 {@code size} 直接透传给 MyBatis-Plus
 * 分页，<b>没有任何上限</b>。而 MyBatis-Plus 的 {@code IPage} 分页必带总数查询 ——
 * 传 {@code size=100000} 就是一次全表 {@code COUNT(*)} + 全表扫描 + 完整对象构建。
 * 前端此前有 6 处按"全量"语义硬编码 {@code size: 999}。
 *
 * <p>本测试冻结：<b>size 被截断到上限，而不是原样透传</b>。
 */
@ExtendWith(MockitoExtension.class)
class PageParamsLimitContractTest {

    @Mock
    private ICameraStatusService cameraStatusService;
    @Mock
    private IDimWorkstationService workstationService;
    @Mock
    private IDimProductionLineService lineService;
    @Mock
    private CameraDirectory cameraDirectory;

    @InjectMocks
    private CameraDashboardController controller;

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    private CameraStatus camera(Long id) {
        CameraStatus c = new CameraStatus();
        c.setId(id);
        c.setCameraCode("DA" + id);
        c.setCameraName("Cam" + id);
        c.setIsActive(1);
        c.setLineId(5);
        c.setWorkstationId(23);
        return c;
    }

    @Test
    @DisplayName("GET /api/cameras: size 超上限被截断到 500（不再原样透传给分页）")
    void sizeIsTruncatedToLimit() throws Exception {
        IPage<CameraStatus> page = new Page<>(1, 500);
        when(cameraStatusService.pageQuery(eq(1), eq(500), any(), any(), any())).thenReturn(page);

        mvc().perform(get("/api/cameras").param("size", "100000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // ★ 关键断言：传给 Service 的 size 必须是 500，不能是 100000
        verify(cameraStatusService).pageQuery(eq(1), eq(500), any(), any(), any());
        verify(cameraStatusService, never()).pageQuery(anyInt(), eq(100000), any(), any(), any());
    }

    @Test
    @DisplayName("GET /api/cameras: 正常 size 原样透传")
    void normalSizePassedThrough() throws Exception {
        IPage<CameraStatus> page = new Page<>(1, 20);
        when(cameraStatusService.pageQuery(eq(1), eq(20), any(), any(), any())).thenReturn(page);

        mvc().perform(get("/api/cameras").param("size", "20"))
                .andExpect(status().isOk());

        verify(cameraStatusService).pageQuery(eq(1), eq(20), any(), any(), any());
    }

    @Test
    @DisplayName("GET /api/cameras: size 非法（0 / 负数）归一为默认值 20")
    void illegalSizeFallsBackToDefault() throws Exception {
        IPage<CameraStatus> page = new Page<>(1, 20);
        when(cameraStatusService.pageQuery(eq(1), eq(20), any(), any(), any())).thenReturn(page);

        mvc().perform(get("/api/cameras").param("size", "0"))
                .andExpect(status().isOk());
        mvc().perform(get("/api/cameras").param("size", "-5"))
                .andExpect(status().isOk());

        verify(cameraStatusService, org.mockito.Mockito.times(2)).pageQuery(eq(1), eq(20), any(), any(), any());
    }

    @Test
    @DisplayName("GET /api/cameras: page 非法归一为 1")
    void illegalPageFallsBackToOne() throws Exception {
        IPage<CameraStatus> page = new Page<>(1, 20);
        when(cameraStatusService.pageQuery(eq(1), eq(20), any(), any(), any())).thenReturn(page);

        mvc().perform(get("/api/cameras").param("page", "0"))
                .andExpect(status().isOk());

        verify(cameraStatusService).pageQuery(eq(1), eq(20), any(), any(), any());
    }

    @Test
    @DisplayName("GET /api/cameras/options: 轻量全量列表 —— 不分页、无 COUNT 查询")
    void optionsReturnsPlainArray() throws Exception {
        when(cameraStatusService.listAll()).thenReturn(Arrays.asList(camera(1L), camera(2L)));

        mvc().perform(get("/api/cameras/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].cameraCode").value("DA1"))
                .andExpect(jsonPath("$.data[0].lineId").value(5));
    }

    @Test
    @DisplayName("GET /api/cameras/options: 支持按产线过滤")
    void optionsFiltersByLine() throws Exception {
        CameraStatus inLine = camera(1L);
        CameraStatus other = camera(2L);
        other.setLineId(3);
        when(cameraStatusService.listAll()).thenReturn(Arrays.asList(inLine, other));

        mvc().perform(get("/api/cameras/options").param("lineId", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(1));
    }

    @Test
    @DisplayName("GET /api/cameras/{id}: 单台详情，不再需要拉全量列表")
    void detailReturnsSingleCamera() throws Exception {
        when(cameraStatusService.getById(9L)).thenReturn(camera(9L));

        mvc().perform(get("/api/cameras/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(9))
                .andExpect(jsonPath("$.data.cameraCode").value("DA9"));
    }

    @Test
    @DisplayName("GET /api/cameras/{id}: 相机不存在返回失败而非空对象")
    void detailNotFound() throws Exception {
        when(cameraStatusService.getById(404L)).thenReturn(null);

        mvc().perform(get("/api/cameras/404"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
    }
}
