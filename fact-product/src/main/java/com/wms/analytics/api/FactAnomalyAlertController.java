package com.wms.analytics.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wms.analytics.domain.model.FactAnomalyAlert;
import com.wms.analytics.app.IFactAnomalyAlertService;
import com.wms.shared.common.ResultVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/alerts")
public class FactAnomalyAlertController {

    @Autowired
    private IFactAnomalyAlertService alertService;

    @GetMapping("/active")
    public ResultVO<Map<String, Object>> getActiveAlerts() {
        LambdaQueryWrapper<FactAnomalyAlert> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FactAnomalyAlert::getIsResolved, 0);
        wrapper.orderByDesc(FactAnomalyAlert::getCreateTime);
        wrapper.last("LIMIT 20");
        List<FactAnomalyAlert> list = alertService.list(wrapper);

        List<Map<String, Object>> alerts = list.stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getAlertId());
            m.put("type", a.getAlertType() != null ? a.getAlertType() : "anomaly");
            m.put("level", a.getAlertLevel() != null ? a.getAlertLevel() : "WARNING");
            m.put("title", a.getAlertTitle() != null ? a.getAlertTitle() : "告警");
            m.put("message", a.getAlertMessage() != null ? a.getAlertMessage() : "");
            m.put("lineId", a.getLineId());
            m.put("workstationId", a.getWorkstationId());
            m.put("createTime", a.getCreateTime() != null ? a.getCreateTime().toString() : "");
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("alerts", alerts);
        result.put("total", alerts.size());
        return ResultVO.success(result);
    }

    @PostMapping("/{id}/resolve")
    public ResultVO<String> resolveAlert(@PathVariable Long id) {
        FactAnomalyAlert alert = alertService.getById(id);
        if (alert != null) {
            alert.setIsResolved(1);
            alert.setResolvedAt(LocalDateTime.now());
            alertService.updateById(alert);
            return ResultVO.success("已解决");
        }
        return ResultVO.success("告警不存在");
    }
}
