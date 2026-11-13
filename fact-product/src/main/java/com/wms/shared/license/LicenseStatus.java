package com.wms.shared.license;

/**
 * License 判定结果枚举。
 *
 * <p>顺序即严重度（越靠后越严重），{@code warn} 模式下只有 {@link #EXPIRED}
 * 会真正拦住设备写入，其余只打日志/告警。
 */
public enum LicenseStatus {

    /** 授权功能关闭（license.enabled=false），一切放行。 */
    DISABLED("授权校验已关闭"),

    /** 文件不存在。 */
    MISSING("未安装授权文件"),

    /** 签名不符、JSON 损坏、字段非法、版本不支持。 */
    INVALID("授权文件无效"),

    /** 机器码与授权绑定不一致。 */
    MACHINE_MISMATCH("授权与本机不符"),

    /** 尚未到生效日期（客户提前拿到 license 的正常情况）。 */
    NOT_YET("授权尚未生效"),

    /** 正常，且剩余天数 > 预警阈值。 */
    VALID("授权正常"),

    /** 正常，但剩余天数 <= 预警阈值，需要提醒续期。 */
    EXPIRING("授权即将到期"),

    /** 已过到期日（含宽限期）。 */
    EXPIRED("授权已过期");

    private final String label;

    LicenseStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 是否属于「不可用」状态（需要运维介入）。 */
    public boolean isProblem() {
        return this == MISSING || this == INVALID || this == MACHINE_MISMATCH
                || this == NOT_YET || this == EXPIRED;
    }

    /** 是否可以正常提供业务功能。 */
    public boolean isUsable() {
        return this == DISABLED || this == VALID || this == EXPIRING;
    }
}
