package com.wms.device.app;

import com.wms.device.api.vo.ServerMetricsVO;

/**
 * 服务器资源指标采集服务。
 *
 * <p>实现要点见 {@link com.wms.service.impl.ServerMetricsServiceImpl}。
 */
public interface IServerMetricsService {

    /**
     * 取得当前服务器指标快照（含内存缓存的历史采样点）。
     *
     * @return 指标快照；采集器尚未完成首次采集时，各指标字段为空
     */
    ServerMetricsVO getMetrics();

    /**
     * 立即执行一次采集并返回结果。
     *
     * <p>正常情况下由定时任务驱动，接口只读缓存；
     * 该方法用于测试与「手动刷新」场景。
     *
     * @return 本次采集结果
     */
    ServerMetricsVO collectNow();
}
