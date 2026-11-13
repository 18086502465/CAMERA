package com.wms.shared.license;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * License 编解码与 HMAC-SHA256 签名。
 *
 * <p><b>文件格式</b>（单行文本，可换行传输但解析前会 trim）：
 * <pre>
 *   base64url(payloadJson) + "." + base64url(hmacSha256(payloadJson, secret))
 * </pre>
 * 载荷明文可读（客户能看到到期日，减少扯皮），签名保证不可篡改。
 *
 * <p><b>为什么不引第三方库</b>：只需要 JDK 自带的 {@code javax.crypto} 与
 * {@code java.util.Base64}，不新增依赖、不影响离线部署。
 *
 * <p><b>密钥强度说明（必须如实告知）</b>：这是<b>对称</b>密钥，内置在 jar 里，
 * 任何人拿到 jar 都能反编译出密钥并自行签发。它挡的是「误用 / 过期继续用 /
 * 换机器直接拷过去」，<b>挡不住逆向</b>。若将来需要真正防伪造，应换成
 * ECDSA/RSA：私钥只留在签发机，jar 里只放公钥 —— 届时只需替换本类实现，
 * 上层 {@code LicenseService} 不用改。
 *
 * <p><b>改密钥的位置</b>：{@link #SECRET_PART1} / {@link #SECRET_PART2}。
 * 两段必须同时改，且要与签发工具 {@code installer/linux/scripts/gen_license.py}
 * 里的同名常量保持一致，否则签出来的文件全部校验失败。
 */
public final class LicenseSigner {

    // ===== 固定密钥（两段拼接后再 hash 一次，避免源码里出现可直接使用的字面量）=====
    private static final String SECRET_PART1 = "cds-lic";
    private static final String SECRET_PART2 = "-2026-zlp";
    private static final String SECRET_SALT = "wms.camera-detection/license/v1";

    /** 内置默认密钥（推导值），签发工具必须用同一个。 */
    public static final String DEFAULT_SECRET = deriveSecret();

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private LicenseSigner() {
    }

    private static String deriveSecret() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((SECRET_SALT + "|" + SECRET_PART1 + SECRET_PART2)
                    .getBytes(StandardCharsets.UTF_8));
            return hex(d);
        } catch (Exception e) {
            throw new IllegalStateException("无法推导默认密钥", e);
        }
    }

    /**
     * 解析实际使用的密钥：配置里给了就用配置，否则用内置默认。
     * 这样现场可在不重新打包的前提下换密钥（{@code LICENSE_SECRET} 环境变量）。
     */
    public static String resolveSecret(String configured) {
        return (configured == null || configured.trim().isEmpty()) ? DEFAULT_SECRET : configured.trim();
    }

    /** 生成 license 文本。 */
    public static String encode(LicensePayload payload, String secret) {
        try {
            String json = MAPPER.writeValueAsString(payload);
            String b64 = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(json.getBytes(StandardCharsets.UTF_8));
            return b64 + "." + hmacBase64(b64, resolveSecret(secret));
        } catch (Exception e) {
            throw new IllegalStateException("生成 license 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析并校验签名。
     *
     * @throws IllegalArgumentException 格式非法或签名不符（消息可直接回给客户）
     */
    public static LicensePayload decode(String text, String secret) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("授权文件内容为空");
        }
        String body = text.trim().replace("\r", "").replace("\n", "");
        int dot = body.indexOf('.');
        if (dot <= 0 || dot == body.length() - 1) {
            throw new IllegalArgumentException("授权文件格式错误：缺少签名段");
        }
        String payloadB64 = body.substring(0, dot);
        String sigB64 = body.substring(dot + 1);

        String expect = hmacBase64(payloadB64, resolveSecret(secret));
        // 定长比较，避免按字节提前返回造成时序侧信道
        if (!MessageDigest.isEqual(expect.getBytes(StandardCharsets.UTF_8),
                sigB64.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("授权文件签名校验失败（内容被修改或密钥不匹配）");
        }

        try {
            byte[] json = Base64.getUrlDecoder().decode(payloadB64);
            LicensePayload payload = MAPPER.readValue(json, LicensePayload.class);
            validate(payload);
            return payload;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("授权文件内容无法解析: " + e.getMessage(), e);
        }
    }

    /** 结构校验：只做「格式自洽」，不判断时间与机器。 */
    public static void validate(LicensePayload payload) {
        if (payload == null) {
            throw new IllegalArgumentException("授权载荷为空");
        }
        if (payload.getVersion() == null || payload.getVersion() != 1) {
            throw new IllegalArgumentException("不支持的授权版本: " + payload.getVersion());
        }
        if (isBlank(payload.getExpireAt())) {
            throw new IllegalArgumentException("授权缺少到期日期");
        }
        if (payload.getMaxCameras() == null) {
            payload.setMaxCameras(-1);
        }
        if (payload.getMaxCameras() == 0 || payload.getMaxCameras() < -1) {
            throw new IllegalArgumentException("相机数上限非法: " + payload.getMaxCameras());
        }
        if (isBlank(payload.getMachineCode())) {
            payload.setMachineCode("*");
        }
    }

    private static String hmacBase64(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("计算签名失败", e);
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
