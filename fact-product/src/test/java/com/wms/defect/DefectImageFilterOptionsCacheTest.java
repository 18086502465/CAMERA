package com.wms.defect;

import com.wms.defect.app.DefectImageServiceImpl;
import com.wms.defect.infra.repository.DefectImageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 筛选候选短缓存契约（2026-11-21 性能第二批 P0-3）。
 *
 * <p>背景：{@code listFilterOptions()} 每次跑两次全表 JOIN + COUNT DISTINCT，
 * 现场实测 0.49–0.78s，且统计页 / 日报页 / 图像记录页首屏都调用。
 *
 * <p>冻结点：同一实例连续两次请求只查一次库；新图片入库后缓存被主动失效，
 * 下次请求重新取数（缺陷类型与分辨率候选必须能跟上实际上报）。
 */
@ExtendWith(MockitoExtension.class)
class DefectImageFilterOptionsCacheTest {

    @Mock
    private DefectImageRepository repository;

    @InjectMocks
    private DefectImageServiceImpl service;

    @Test
    @DisplayName("同参数连续两次请求只查一次库")
    void filterOptionsAreCached() {
        when(repository.listDefectTypeOptions()).thenReturn(Collections.emptyList());
        when(repository.listResolutionOptions()).thenReturn(Collections.emptyList());

        Map<String, Object> first = service.listFilterOptions();
        Map<String, Object> second = service.listFilterOptions();

        assertEquals(first, second);
        verify(repository, times(1)).listDefectTypeOptions();
        verify(repository, times(1)).listResolutionOptions();
    }
}
