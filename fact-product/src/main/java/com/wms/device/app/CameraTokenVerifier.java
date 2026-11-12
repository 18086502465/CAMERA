package com.wms.device.app;

import com.wms.device.infra.repository.CameraDirectoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/**
 * 设备令牌校验（{@code cfg_camera_token}）。
 *
 * <p><b>为什么需要这个类</b>：3 个设备端点在 {@code SecurityConfig} 里是 permitAll，
 * 注释写着「由 service 层用 cfg_camera_token 校验」。但 2026-11-12 全项目评审实测：
 * <ul>
 *   <li>{@code CameraStatusHeartbeatController} 只判断 Authorization 是否以
 *       {@code "Bearer "} 开头，{@code substring(7)} 取出的 token <b>从未使用</b> ——
 *       任何人可伪造任意相机的心跳与累计计数，且该端点会写库写告警；</li>
 *   <li>{@code InspectionUploadServiceImpl.validateToken} 在该相机未配令牌时直接
 *       {@code return} —— 未配置即完全绕过。</li>
 * </ul>
 * 本类把「令牌有效 / 未配置 / 不匹配」三种结果显式分开，两个端点共用同一判定。
 *
 * <p><b>未配置为什么默认放行</b>：现场大量相机尚未配 token，一律拒绝会让产线
 * 直接停摆。所以 {@code camera.token.strict} 默认 false：
 * <ul>
 *   <li>{@code false}（默认）：未配置 → 放行 + WARN（风险可见、不阻断生产）；</li>
 *   <li>{@code true}：未配置 → 拒绝（运维配齐后一键收紧）。</li>
 * </ul>
 * 注意「配了令牌但传错」在两种模式下<b>都是拒绝</b>，本次修复堵住的正是这个洞。
 */
@Component
@Slf4j
public class CameraTokenVerifier {

    public enum Result {
        /** 令牌与已配置的哈希匹配 */
        OK,
        /** 该相机没有任何有效令牌记录 */
        NOT_CONFIGURED,
        /** 有令牌记录但传入的不匹配（含未传、传空） */
        MISMATCH
    }

    @Autowired
    private CameraDirectoryRepository cameraDirectoryRepository;

    /** true = 未配置令牌的相机一律拒绝；false = 放行并告警。见类注释。 */
    @Value("${camera.token.strict:false}")
    private boolean strictMode;

    /**
     * 校验设备令牌。
     *
     * @param rawToken   Authorization 去掉 "Bearer " 后的原始串
     * @param cameraCode 相机编码（心跳里就是 header {@code X-Device-Id}）
     */
    public Result verify(String rawToken, String cameraCode) {
        if (rawToken == null || rawToken.trim().isEmpty()) {
            return Result.MISMATCH;
        }
        Long cameraId = cameraDirectoryRepository.findCameraIdByCode(cameraCode);
        if (cameraId == null) {
            // 设备先于建档上线：按未配置处理，不阻断
            return Result.NOT_CONFIGURED;
        }
        List<Map<String, Object>> rows = cameraDirectoryRepository.listActiveCameraTokens(cameraId);
        if (rows == null || rows.isEmpty()) {
            return Result.NOT_CONFIGURED;
        }
        String sha256 = sha256(rawToken);
        for (Map<String, Object> row : rows) {
            Object configured = row.get("token_hash");
            if (configured == null) {
                continue;
            }
            String v = String.valueOf(configured);
            // 兼容明文配置与 sha256 配置两种历史写法（InspectionUploadServiceImpl 同口径）
            if (v.equals(rawToken) || v.equalsIgnoreCase(sha256)) {
                return Result.OK;
            }
        }
        return Result.MISMATCH;
    }

    /** 严格模式：未配置令牌是否应当拒绝 */
    public boolean rejectWhenNotConfigured() {
        return strictMode;
    }

    private String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("SHA-256 不可用，令牌比对退化为明文比较", e);
            return "";
        }
    }
}
