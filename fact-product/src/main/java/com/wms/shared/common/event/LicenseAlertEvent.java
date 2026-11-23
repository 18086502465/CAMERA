package com.wms.shared.common.event;

import java.io.Serializable;

/**
 * 授权状态变化事件（system 域 → analytics 域，2026-11-23 决策 #7 / LIC-12）。
 *
 * <p><b>为什么在 shared 定义</b>：授权巡检在 {@code system} 域，落库
 * {@code fact_anomaly_alert} 的 Repository 在 {@code analytics} 域。按 ArchUnit
 * 铁律①「跨域只走 app 层，不得直接依赖其他域的 infra」，system 不能注入
 * analytics 的 Repository。因此事件类型放 shared，由 analytics 的监听器落库 ——
 * 与 {@link UploadFailedEvent} / {@link SchemaIndexFailedEvent} 完全同构。
 *
 * <p><b>为什么用事件而不是在 getState() 里写库</b>：{@code getState()} 每次请求都会调用，
 * 在那里写库会瞬间刷屏；巡检任务 {@code LicenseCheckTask} 已有 lastLogged 状态抑制，
 * 只在状态切换时发布一次事件，天然去重。
 *
 * <p><b>恢复语义</b>：{@code recovered=true} 表示授权回到正常态，监听器按
 * {@code (0,0,LICENSE_EXPIRED)} 找未恢复行并 resolve，不新增告警。
 */
public class LicenseAlertEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 授权状态名（EXPIRED / INVALID / MACHINE_MISMATCH / VALID 等） */
    private final String status;
    /** 状态说明（LicenseState.message，含失败原因） */
    private final String message;
    /** 到期日期 yyyy-MM-dd，可为 null */
    private final String expireAt;
    /** 剩余天数，可为 null */
    private final Long daysLeft;
    /** true = 恢复正常，需关闭既有告警 */
    private final boolean recovered;

    public LicenseAlertEvent(String status, String message, String expireAt, Long daysLeft, boolean recovered) {
        this.status = status;
        this.message = message;
        this.expireAt = expireAt;
        this.daysLeft = daysLeft;
        this.recovered = recovered;
    }

    public String getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public String getExpireAt() {
        return expireAt;
    }

    public Long getDaysLeft() {
        return daysLeft;
    }

    public boolean isRecovered() {
        return recovered;
    }
}
