package com.wms.shared.license;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * License 载荷（明文 JSON，签名后整体交付给客户）。
 *
 * <p><b>字段冻结</b>：这是**对外协议**，字段增删必须提升 {@link #version}，
 * 否则老签发工具生成的 license 在新版本上会被判为无效。
 *
 * <p>约定：
 * <ul>
 *   <li>{@code maxCameras = -1} 表示不限相机数；{@code 0} 是非法值（会被判 INVALID）；</li>
 *   <li>{@code machineCode = "*"} 表示不绑定机器（演示/试用）；</li>
 *   <li>日期一律 {@code yyyy-MM-dd}，到期日<b>当天有效</b>，次日 00:00 起算过期。</li>
 * </ul>
 */
@Data
public class LicensePayload implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 载荷版本，当前固定 1。 */
    private Integer version;

    /** 授权单号，如 LIC-20261112-0001，用于客户对账与告警文案。 */
    private String licenseId;

    /** 客户名称（显示在授权页与告警里）。 */
    private String customer;

    /** 签发日期 yyyy-MM-dd。 */
    private String issuedAt;

    /** 到期日期 yyyy-MM-dd（含当天）。 */
    private String expireAt;

    /** 允许注册的相机总数上限；-1 = 不限。 */
    private Integer maxCameras;

    /** 功能开关，预留（当前不参与判定，仅展示）。 */
    private List<String> features = new ArrayList<>();

    /** 绑定的机器码；"*" = 不绑定。 */
    private String machineCode;
}
