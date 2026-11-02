package com.wms.controller;

import com.wms.vo.ResultVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 生产质量30分钟聚合事实表 前端控制器
 * 注：30分钟聚合功能依赖旧版 product_detection_history 实体字段，
 * 已随实体瘦身移除。如需恢复，请从 Git 历史恢复旧版逻辑。
 */
@RestController
@RequestMapping("/fact")
@Slf4j
public class FactProduction30minController {

    /**
     * 健康检查占位（原聚合端点已移除）
     */
    @GetMapping("/health")
    public ResultVO health() {
        return ResultVO.success("FactProduction30minController is running. Aggregate endpoints have been decommissioned.");
    }
}
