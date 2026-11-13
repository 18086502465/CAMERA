package com.wms.shared.license;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 授权运行时状态（对外的唯一视图）。
 *
 * <p>{@link #status} 是**判定结果**，{@link #writeBlocked} 是**实际动作** ——
 * 两者分开的原因：{@code expired-mode=warn} 时已过期（status=EXPIRED）但
 * 不拦写入（writeBlocked=false），现场产线不能因为授权到期直接停摆。
 */
@Data
public class LicenseState implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 判定结果。 */
    private LicenseStatus status = LicenseStatus.MISSING;

    /** 状态说明，可直接展示给客户（含失败原因，如签名不符/机器码不匹配）。 */
    private String message;

    /** 是否可用（正常提供业务）。 */
    private boolean usable;

    /** 当前配置下是否拒绝设备写入。 */
    private boolean writeBlocked;

    private String licenseId;
    private String customer;
    private String issuedAt;
    private String expireAt;

    /** -1 = 不限。 */
    private Integer maxCameras;

    private List<String> features = new ArrayList<>();

    /** 本机机器码（授权页展示给客户复制回传）。 */
    private String machineCode;

    /** 指纹来源，如 machine-id；读不到时为 null。 */
    private String fingerprintSource;

    /** 剩余天数；未安装/无效时为 null。 */
    private Long daysLeft;

    /** 生效中的过期策略：warn / readonly / block。 */
    private String expiredMode;

    /** 授权文件路径（排障用）。 */
    private String licenseFile;

    /** 服务端当前时间，避免客户机时钟漂移造成误解。 */
    private String serverTime;
}
