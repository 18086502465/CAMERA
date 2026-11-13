package com.wms.system.app;

import com.wms.shared.license.LicensePayload;
import com.wms.shared.license.LicenseSigner;
import com.wms.shared.license.LicenseState;
import com.wms.shared.license.LicenseStatus;
import com.wms.shared.license.MachineFingerprint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * 服务器授权（License）判定中心。
 *
 * <p><b>职责边界</b>：只做「读文件 → 验签 → 比对机器 → 算剩余天数 → 给出动作」，
 * 不查业务库、不依赖任何业务域，因此放在 {@code system.app} 且可被任意域调用。
 * 相机数量上限采用「外部传入当前数量再判定」的方式（见 {@link #checkCameraQuota(int)}），
 * 避免本类反向依赖 device 域。
 *
 * <p><b>三种过期策略</b>（{@code license.expired-mode}）：
 * <ul>
 *   <li>{@code warn}（默认）—— 只告警，永不阻断。产线不会因为授权到期停摆；</li>
 *   <li>{@code readonly} —— 过宽限期后拒绝<b>设备写入</b>（上传/心跳/batch），
 *       管理端仍可登录、可查看、可上传新授权；</li>
 *   <li>{@code block} —— 在 readonly 基础上再拒绝普通业务查询，只放行登录与授权页。</li>
 * </ul>
 *
 * <p><b>为什么默认 warn</b>：这是产线系统，一次误判就是停线。授权机制先做到
 * 「可见、可告警、可续期」，强制阻断必须由运维在装好授权后显式开启。
 */
@Service
@Slf4j
public class LicenseService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** mtime 检查间隔：避免每个请求都 stat 一次文件。 */
    private static final long MTIME_CHECK_MS = 30_000L;

    @Value("${license.enabled:true}")
    private boolean enabled;

    /** 留空则用 LicenseSigner 的内置默认密钥。生产可用 LICENSE_SECRET 覆盖。 */
    @Value("${license.secret:}")
    private String secret;

    /**
     * 授权文件路径。放 data 盘而非 /etc：应用以 cdsys 运行，/etc 下 640 root 的文件
     * 它写不进去，会导致「上传新授权」永远失败。data/license 目录属 cdsys 且已在
     * systemd ReadWritePaths 内。
     */
    @Value("${license.file:/main/app/data/license/license.lic}")
    private String licenseFile;

    /** warn | readonly | block */
    @Value("${license.expired-mode:warn}")
    private String expiredMode;

    /** 过期后的宽限天数，宽限期内即使 readonly/block 也不拦。 */
    @Value("${license.grace-days:7}")
    private int graceDays;

    /** 剩余天数 <= 该值时进入 EXPIRING，触发续期提醒。 */
    @Value("${license.warn-days:30}")
    private int warnDays;

    /** 是否强制校验机器绑定；关掉用于虚拟机批量部署排障。 */
    @Value("${license.enforce-machine:true}")
    private boolean enforceMachine;

    private volatile LicensePayload payload;
    private volatile LicenseStatus staticStatus;
    private volatile String staticMessage;
    private volatile long lastModified = -1L;
    private volatile long lastMtimeCheck = 0L;

    /**
     * 机器码缓存。每请求都算会反复读 /sys 与 /etc/machine-id，
     * 而机器码在进程生命周期内不会变，算一次即可。
     */
    private volatile String cachedMachineCode;
    private volatile String cachedFingerprintSource;
    private volatile boolean machineResolved;

    @PostConstruct
    public void init() {
        reload();
        LicenseState s = getState();
        log.info("授权状态: {} —— {}（模式 {}，文件 {}）",
                s.getStatus(), s.getMessage(), expiredMode, licenseFile);
    }

    /** 重新读取授权文件（上传新文件后必须调用）。 */
    public synchronized void reload() {
        payload = null;
        staticStatus = null;
        staticMessage = null;
        lastMtimeCheck = System.currentTimeMillis();

        if (!enabled) {
            staticStatus = LicenseStatus.DISABLED;
            staticMessage = "授权校验已关闭（license.enabled=false）";
            return;
        }
        File f = new File(licenseFile);
        if (!f.isFile()) {
            staticStatus = LicenseStatus.MISSING;
            staticMessage = "未安装授权文件：" + licenseFile;
            lastModified = -1L;
            return;
        }
        lastModified = f.lastModified();
        try {
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            payload = LicenseSigner.decode(text, secret);
            log.info("授权文件已加载: id={}, customer={}, expire={}, maxCameras={}",
                    payload.getLicenseId(), payload.getCustomer(),
                    payload.getExpireAt(), payload.getMaxCameras());
        } catch (Exception e) {
            staticStatus = LicenseStatus.INVALID;
            staticMessage = e.getMessage();
            log.warn("授权文件无效: {}", e.getMessage());
        }
    }

    /**
     * 当前状态。每次调用都会按「今天」重算剩余天数，因此跨天无需重启。
     */
    public LicenseState getState() {
        maybeReloadByMtime();

        LicenseState s = new LicenseState();
        s.setLicenseFile(licenseFile);
        s.setExpiredMode(expiredMode);
        s.setMachineCode(getMachineCode());
        s.setFingerprintSource(getFingerprintSource());
        s.setServerTime(LocalDateTime.now().format(DATETIME));

        if (staticStatus != null) {
            s.setStatus(staticStatus);
            s.setMessage(staticMessage);
            s.setUsable(staticStatus == LicenseStatus.DISABLED);
            s.setWriteBlocked(computeBlocked(staticStatus, false));
            return s;
        }

        LicensePayload p = payload;
        if (p == null) {
            s.setStatus(LicenseStatus.MISSING);
            s.setMessage("授权未加载");
            s.setWriteBlocked(computeBlocked(LicenseStatus.MISSING, false));
            return s;
        }

        s.setLicenseId(p.getLicenseId());
        s.setCustomer(p.getCustomer());
        s.setIssuedAt(p.getIssuedAt());
        s.setExpireAt(p.getExpireAt());
        s.setMaxCameras(p.getMaxCameras());
        s.setFeatures(p.getFeatures());

        // ---- 机器绑定 ----
        if (enforceMachine && !"*".equals(p.getMachineCode())) {
            String local = s.getMachineCode();
            if (local == null) {
                log.warn("无法采集本机机器码，跳过机器绑定校验（enforce-machine 可关此项告警）");
            } else if (!p.getMachineCode().equalsIgnoreCase(local)) {
                s.setStatus(LicenseStatus.MACHINE_MISMATCH);
                s.setMessage("授权绑定机器 " + p.getMachineCode() + "，本机 " + local
                        + "；请联系供应商重新签发");
                s.setWriteBlocked(true);
                s.setDaysLeft(daysLeft(p));
                return s;
            }
        }

        // ---- 日期 ----
        LocalDate today = LocalDate.now();
        LocalDate expire = parseDate(p.getExpireAt());
        if (expire == null) {
            s.setStatus(LicenseStatus.INVALID);
            s.setMessage("授权到期日期格式非法: " + p.getExpireAt());
            s.setWriteBlocked(true);
            return s;
        }
        LocalDate issued = parseDate(p.getIssuedAt());
        long left = ChronoUnit.DAYS.between(today, expire);
        s.setDaysLeft(left);

        if (issued != null && today.isBefore(issued)) {
            s.setStatus(LicenseStatus.NOT_YET);
            s.setMessage("授权自 " + p.getIssuedAt() + " 起生效");
            s.setWriteBlocked(computeBlocked(LicenseStatus.NOT_YET, false));
        } else if (today.isAfter(expire)) {
            boolean inGrace = !today.isAfter(expire.plusDays(Math.max(graceDays, 0)));
            s.setStatus(LicenseStatus.EXPIRED);
            s.setMessage(inGrace
                    ? "授权已于 " + p.getExpireAt() + " 到期，处于 " + graceDays + " 天宽限期内"
                    : "授权已于 " + p.getExpireAt() + " 到期");
            s.setWriteBlocked(computeBlocked(LicenseStatus.EXPIRED, inGrace));
        } else if (left <= warnDays) {
            s.setStatus(LicenseStatus.EXPIRING);
            s.setMessage("授权将于 " + p.getExpireAt() + " 到期，剩余 " + left + " 天");
            s.setWriteBlocked(false);
        } else {
            s.setStatus(LicenseStatus.VALID);
            s.setMessage("授权正常，剩余 " + left + " 天");
            s.setWriteBlocked(false);
        }
        s.setUsable(s.getStatus().isUsable());
        return s;
    }

    /** 是否应拒绝设备写入（上传/心跳/批量）。 */
    public boolean isWriteBlocked() {
        return getState().isWriteBlocked();
    }

    /** 是否应拒绝全部业务请求（仅 block 模式且已超宽限）。 */
    public boolean isAccessBlocked() {
        LicenseState s = getState();
        return "block".equalsIgnoreCase(expiredMode) && s.isWriteBlocked();
    }

    /**
     * 相机数量上限校验，由调用方传入当前已注册数量。
     *
     * @return null 表示可以新增；否则返回可直接展示的拒绝原因
     */
    public String checkCameraQuota(int currentCount) {
        LicenseState s = getState();
        Integer max = s.getMaxCameras();
        if (max == null || max < 0) {
            return null;
        }
        // 授权自身不可用时不做配额拦截 —— 此时问题在授权，不该表现为「加不了相机」
        if (!s.getStatus().isUsable()) {
            return null;
        }
        if (currentCount >= max) {
            return "已达授权上限 " + max + " 台相机（当前 " + currentCount + " 台），"
                    + "请升级授权后再新增";
        }
        return null;
    }

    public int getGraceDays() {
        return graceDays;
    }

    public int getWarnDays() {
        return warnDays;
    }

    public String getLicenseFile() {
        return licenseFile;
    }

    /** 本机机器码（带缓存）。 */
    public String getMachineCode() {
        if (!machineResolved) {
            synchronized (this) {
                if (!machineResolved) {
                    cachedMachineCode = MachineFingerprint.machineCode();
                    cachedFingerprintSource = MachineFingerprint.sourceName();
                    machineResolved = true;
                }
            }
        }
        return cachedMachineCode;
    }

    public String getFingerprintSource() {
        getMachineCode();
        return cachedFingerprintSource;
    }

    /**
     * 用当前生效密钥验签并解析授权文本，不落盘。
     * 供上传接口在覆盖现有文件之前做预检。
     */
    public LicensePayload verify(String text) {
        return LicenseSigner.decode(text, secret);
    }

    // ==================== 内部 ====================

    private boolean computeBlocked(LicenseStatus status, boolean inGrace) {
        if (!status.isProblem()) {
            return false;
        }
        if ("warn".equalsIgnoreCase(expiredMode)) {
            return false;
        }
        // MACHINE_MISMATCH / INVALID / MISSING 不给宽限：这些是配置错误，不是自然到期
        if (status == LicenseStatus.EXPIRED) {
            return !inGrace;
        }
        return true;
    }

    private void maybeReloadByMtime() {
        if (!enabled) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastMtimeCheck < MTIME_CHECK_MS) {
            return;
        }
        lastMtimeCheck = now;
        File f = new File(licenseFile);
        long mtime = f.isFile() ? f.lastModified() : -1L;
        if (mtime != lastModified) {
            reload();
        }
    }

    private Long daysLeft(LicensePayload p) {
        LocalDate expire = parseDate(p.getExpireAt());
        return expire == null ? null : ChronoUnit.DAYS.between(LocalDate.now(), expire);
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim(), DATE);
        } catch (Exception e) {
            return null;
        }
    }
}
