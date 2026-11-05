package com.wms.device.app;

/**
 * 系统设置读取端口（DDD P4：device 域心跳处理需要读 system 域的告警阈值）。
 *
 * <p>按"消费方定义端口、提供方适配"模式：接口定义在 device 域，
 * system 域提供实现（{@code SystemSettingReaderAdapter}）。
 * 读取失败由调用方兜底默认值（与原行为一致）。
 */
public interface SystemSettingReader {

    /**
     * 按键读取设置值；不存在时返回 null（调用方自行兜底）。
     * 与 system 域原 findSettingValue 行为一致。
     */
    String findSettingValue(String key);
}
