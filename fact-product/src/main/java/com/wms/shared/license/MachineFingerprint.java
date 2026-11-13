package com.wms.shared.license;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;

/**
 * 机器指纹采集（Linux）。
 *
 * <p><b>降级链</b>（取到第一个有效值即停）：
 * <ol>
 *   <li>{@code /sys/class/dmi/id/product_uuid} —— 主板/整机 UUID，重装系统不变；
 *       部分内核需 root，服务以 {@code cdsys} 运行时可能读不到；</li>
 *   <li>{@code /etc/machine-id} —— systemd 生成，644 可读，重装系统会变；</li>
 *   <li>{@code /var/lib/dbus/machine-id} —— 同上，老发行版才有；</li>
 *   <li>{@code /sys/class/dmi/id/board_serial} —— 虚拟机上常见 "Not Specified"。</li>
 * </ol>
 *
 * <p><b>为什么不直接暴露原始值</b>：原始 UUID 属设备隐私，且客户可能不希望在
 * 截图里泄露。对外只给 {@code sha256(salt + raw)} 的前 16 位大写十六进制，
 * 既能作为绑定依据，又不可逆推出硬件序列号。
 *
 * <p><b>取不到时返回 null</b>：由上层决定是放行还是拒绝，本类不抛异常 ——
 * 在容器/精简系统里读不到是常态，不该让授权校验因此崩溃。
 */
public final class MachineFingerprint {

    /** 加盐，避免同一台机器在别处算出同样的短码被撞库。 */
    private static final String SALT = "wms-cds-machine-v1|";

    private static final String[] SOURCES = new String[]{
            "/sys/class/dmi/id/product_uuid",
            "/etc/machine-id",
            "/var/lib/dbus/machine-id",
            "/sys/class/dmi/id/board_serial"
    };

    /** 这些是厂商占位符，不能当指纹用。 */
    private static final String[] INVALID = new String[]{
            "", "not specified", "not applicable", "none", "null", "0",
            "to be filled by o.e.m.", "system serial number", "default string",
            "00000000-0000-0000-0000-000000000000"
    };

    private MachineFingerprint() {
    }

    /**
     * @return 16 位大写机器码；所有来源都不可读时返回 {@code null}
     */
    public static String machineCode() {
        String raw = firstValidRaw();
        if (raw == null) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String hex = LicenseSigner.hex(md.digest((SALT + raw).getBytes(StandardCharsets.UTF_8)));
            return hex.substring(0, 16).toUpperCase();
        } catch (Exception e) {
            return null;
        }
    }

    /** 采集来源文件名，仅用于诊断（授权页可显示「指纹取自 machine-id」）。 */
    public static String sourceName() {
        for (String s : SOURCES) {
            if (readClean(s) != null) {
                return Paths.get(s).getFileName().toString();
            }
        }
        return null;
    }

    private static String firstValidRaw() {
        for (String s : SOURCES) {
            String v = readClean(s);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static String readClean(String path) {
        try {
            Path p = Paths.get(path);
            if (!Files.isReadable(p)) {
                return null;
            }
            String v = new String(Files.readAllBytes(p), StandardCharsets.UTF_8).trim();
            if (v.isEmpty() || v.length() > 128) {
                return null;
            }
            String lower = v.toLowerCase();
            for (String bad : INVALID) {
                if (lower.equals(bad)) {
                    return null;
                }
            }
            return v;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
