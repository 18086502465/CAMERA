package com.wms.controller;

import com.wms.service.IServerMetricsService;
import com.wms.vo.ResultVO;
import com.wms.vo.ServerMetricsVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 服务器资源监控接口。
 *
 * <p>暴露目标服务器（本机，即应用部署所在服务器）的 CPU、内存、
 * 磁盘空间与磁盘 I/O（读写速率 + IOPS）实时指标。
 *
 * <h3>权限说明</h3>
 * <p>路径 {@code /api/v1/system/server/**} 已被 {@code SecurityConfig} 的
 * {@code .antMatchers("/api/v1/system/**").authenticated()} 覆盖，
 * 因此<b>所有登录用户</b>（ROLE_USER 与 ROLE_ADMIN 均可）都能访问 ——
 * 现场运维人员也需要能看到服务器负载。
 *
 * <p>方法级再声明一次 {@code hasAnyRole('ADMIN','USER')} 作为双保险，
 * 防止将来调整 SecurityConfig 路径规则时意外放开。
 *
 * <h3>注意：操作日志</h3>
 * <p>本接口会被前端以 5 秒周期轮询，若不处理，
 * {@code OperationLogAspect} 会把每次轮询都写进 {@code sys_operation_log}
 * （每天 17280 条垃圾）。已在 {@code OperationLogAspect.shouldSkipOperationLog()}
 * 中按 URI 前缀排除。
 */
@RestController
@RequestMapping("/api/v1/system/server")
public class ServerMetricsController {

    @Autowired
    private IServerMetricsService serverMetricsService;

    /**
     * 服务器实时指标快照。
     *
     * @param history 指定返回的历史点数；不传则返回全部缓存点
     * @return 指标快照
     */
    @GetMapping("/metrics")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    public ResultVO<Map<String, Object>> getMetrics(
            @RequestParam(required = false) Integer history) {

        ServerMetricsVO vo = serverMetricsService.getMetrics();

        // 按请求裁剪历史点数（前端通常只需要最近 N 个点）
        if (history != null && history >= 0 && vo.getHistory() != null) {
            List<ServerMetricsVO.HistoryPoint> all = vo.getHistory();
            int from = Math.max(0, all.size() - history);
            List<ServerMetricsVO.HistoryPoint> trimmed =
                    new java.util.ArrayList<>(all.subList(from, all.size()));
            vo.setHistory(trimmed);
        }

        return ResultVO.success(ServerMetricsVO.toMap(vo), "服务器指标获取成功");
    }
}
