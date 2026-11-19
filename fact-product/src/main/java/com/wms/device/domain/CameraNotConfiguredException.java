package com.wms.device.domain;

/**
 * 相机未在系统配置中维护（心跳上报时找不到对应相机档案）。
 *
 * <p><b>为什么用自定义异常而不是靠消息字符串匹配</b>（2026-11-19 评审 P0-2）：
 * 原来 {@code CameraStatusServiceImpl} 抛的是普通 {@code IllegalArgumentException}，
 * Controller 靠 catch 同一个异常类型再记一次 WARN —— 结果<b>同一件事记两遍</b>
 * （实测 7 天 26 万条 WARN 里，99% 是这几台未登记设备打出的成对重复日志）。
 * 若改成靠消息文本区分，字符串一改就失效；自定义异常让"这是未登记设备"成为
 * <b>类型信息</b>，处理方可以精确分流。
 *
 * <p>继承 {@code IllegalArgumentException} 是为了<b>向后兼容</b>：
 * 既有 catch 块与调用方无需改动即可继续工作。
 *
 * <p><b>处理约定</b>：
 * <ul>
 *   <li>由 {@code CameraStatusServiceImpl} 抛出（唯一抛出点）；</li>
 *   <li>日志在 <b>Service 层</b> 记录（且按 deviceId 节流），
 *       Controller 层<b>不再重复记录</b> —— 见该类 catch 块；</li>
 *   <li>这类心跳来自<b>未登记设备</b>，是配置问题而非系统故障，
 *       因此<b>不落告警</b>（避免未登记设备刷告警表），但要节流记录以便发现。</li>
 * </ul>
 */
public class CameraNotConfiguredException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /** 未登记的设备号（相机编号） */
    private final String deviceId;

    public CameraNotConfiguredException(String deviceId) {
        super("设备未在系统配置中维护: " + deviceId);
        this.deviceId = deviceId;
    }

    public String getDeviceId() {
        return deviceId;
    }
}
