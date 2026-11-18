package com.wms.shared.common.event;

import java.io.Serializable;

/**
 * 设备上传失败事件（共享内核 → analytics 域）。
 *
 * <p><b>为什么在 shared 定义</b>（2026-11-18 评审 M-04）：
 * multipart 解析失败发生在 <b>Spring 参数绑定阶段</b> —— 请求还没进入 Controller，
 * Controller 的 try/catch <b>根本捕获不到</b>。只有
 * {@code GlobalExceptionHandler}（位于 shared/web）能看到。
 * 而 shared 是共享内核，<b>不得依赖任何业务域</b>（ArchUnit 铁律②）——
 * 所以事件类型定义在 shared，由 analytics 域的监听器落库 {@code fact_anomaly_alert}。
 *
 * <p><b>为什么要告警</b>：现场近 24h 有 39 条
 * {@code Failed to parse multipart servlet request} + 19 条 {@code Broken pipe}，
 * 全部只进了 journald —— 失败的那张图<b>既没入库也没告警</b>，
 * 设备端若不重试就永久丢失，现场完全无感知。
 *
 * <p>本事件只带设备号（解析失败时拿不到业务维度），落库时
 * {@code line_id / workstation_id} 记 0 —— 与 {@code BACKUP_FAILED}
 * 等运维类告警同一口径。
 */
public class UploadFailedEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 上报设备号（X-Device-Id），解析失败时可能为空 */
    private final String deviceId;
    /** 失败原因（截断后用于告警正文） */
    private final String reason;
    /** 异常类型名（便于按类型聚合统计） */
    private final String errorType;

    public UploadFailedEvent(String deviceId, String reason, String errorType) {
        this.deviceId = deviceId;
        this.reason = reason;
        this.errorType = errorType;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getReason() {
        return reason;
    }

    public String getErrorType() {
        return errorType;
    }
}
