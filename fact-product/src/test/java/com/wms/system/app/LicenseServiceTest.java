package com.wms.system.app;

import com.wms.shared.license.LicensePayload;
import com.wms.shared.license.LicenseSigner;
import com.wms.shared.license.LicenseState;
import com.wms.shared.license.LicenseStatus;
import com.wms.system.infra.repository.SystemSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 授权判定逻辑测试。
 *
 * <p>这里覆盖的是「最容易被改坏、坏了也不报错」的部分：过期边界、宽限期、
 * 过期策略、机器绑定、相机配额。日期一律按 {@link LocalDate#now()} 动态生成，
 * 避免「测试文件里写死 2027 年，跑到那年就变红」。
 */
class LicenseServiceTest {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @TempDir
    Path tempDir;

    private LicenseService service;
    private String licensePath;

    @BeforeEach
    void setUp() {
        service = new LicenseService();
        licensePath = new File(tempDir.toFile(), "license.lic").getAbsolutePath();
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "secret", "");
        ReflectionTestUtils.setField(service, "licenseFile", licensePath);
        ReflectionTestUtils.setField(service, "expiredMode", "warn");
        ReflectionTestUtils.setField(service, "graceDays", 7);
        ReflectionTestUtils.setField(service, "warnDays", 30);
        ReflectionTestUtils.setField(service, "enforceMachine", true);
    }

    @Test
    void 未安装授权时应为MISSING且warn模式不拦截() {
        service.reload();
        LicenseState s = service.getState();
        assertEquals(LicenseStatus.MISSING, s.getStatus());
        assertFalse(s.isWriteBlocked(), "warn 模式绝不能让产线停摆");
        assertNotNull(s.getLicenseFile());
    }

    @Test
    void 有效授权应为VALID() {
        writeLicense(LocalDate.now().plusDays(365), -1, "*");
        service.reload();
        LicenseState s = service.getState();
        assertEquals(LicenseStatus.VALID, s.getStatus());
        assertFalse(s.isWriteBlocked());
        assertTrue(s.getDaysLeft() > 300);
    }

    @Test
    void 剩余天数小于预警阈值应为EXPIRING() {
        writeLicense(LocalDate.now().plusDays(10), -1, "*");
        service.reload();
        LicenseState s = service.getState();
        assertEquals(LicenseStatus.EXPIRING, s.getStatus());
        assertFalse(s.isWriteBlocked());
        assertEquals(Long.valueOf(10), s.getDaysLeft());
    }

    @Test
    void 已过期且warn模式仍不拦截() {
        writeLicense(LocalDate.now().minusDays(1), -1, "*");
        service.reload();
        LicenseState s = service.getState();
        assertEquals(LicenseStatus.EXPIRED, s.getStatus());
        assertFalse(s.isWriteBlocked());
    }

    @Test
    void readonly模式在宽限期内不拦截_超过宽限期才拦截() {
        ReflectionTestUtils.setField(service, "expiredMode", "readonly");

        // 昨天到期，宽限 7 天 → 仍在宽限期内
        writeLicense(LocalDate.now().minusDays(1), -1, "*");
        service.reload();
        assertFalse(service.getState().isWriteBlocked(), "宽限期内不应拦截");

        // 到期 10 天，超过 7 天宽限 → 拦截
        writeLicense(LocalDate.now().minusDays(10), -1, "*");
        service.reload();
        assertTrue(service.getState().isWriteBlocked(), "超过宽限期应拒绝设备写入");
        assertFalse(service.isAccessBlocked(), "readonly 不应封禁管理端");
    }

    @Test
    void block模式超宽限期应封禁全站() {
        ReflectionTestUtils.setField(service, "expiredMode", "block");
        writeLicense(LocalDate.now().minusDays(10), -1, "*");
        service.reload();
        assertTrue(service.isAccessBlocked());
    }

    @Test
    void 绑定其他机器时应判为MACHINE_MISMATCH并拦截() {
        String local = service.getMachineCode();
        writeLicense(LocalDate.now().plusDays(365), -1, "FFFFFFFFFFFFFFFF");
        service.reload();
        LicenseState s = service.getState();
        if (local == null) {
            // 本机采集不到指纹时跳过绑定校验，属预期降级
            assertEquals(LicenseStatus.VALID, s.getStatus());
            return;
        }
        assertEquals(LicenseStatus.MACHINE_MISMATCH, s.getStatus());
        assertTrue(s.isWriteBlocked(), "机器不匹配必须拦截，否则授权形同虚设");
    }

    @Test
    void 关闭机器校验后绑定其他机器也应通过() {
        ReflectionTestUtils.setField(service, "enforceMachine", false);
        writeLicense(LocalDate.now().plusDays(365), -1, "FFFFFFFFFFFFFFFF");
        service.reload();
        assertEquals(LicenseStatus.VALID, service.getState().getStatus());
    }

    @Test
    void 签名被破坏时应为INVALID且不抛异常() throws Exception {
        writeLicense(LocalDate.now().plusDays(365), -1, "*");
        File f = new File(licensePath);
        String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
        // ★ 2026-11-15 修一个真实的假绿/假红源：原实现把**最后一个字符**替换成 'A'，
        //   ① 末位本来就是 'A' 时文件根本没变 → 签名仍然合法 → 断言 VALID != INVALID 假红；
        //   ② 即便变了，base64 末位只有 4 位有效信息（32 字节摘要 = 43 字符），
        //      同高 4 位、只差低 2 个填充位的字符会解码出**同一份字节**，签名照样合法。
        //   改为改中间字符：一定落在有效位上，且保证字符真的发生变化。
        int pos = text.length() / 2;
        char current = text.charAt(pos);
        char replacement = current == 'Z' ? 'Y' : 'Z';
        Files.write(f.toPath(), (text.substring(0, pos) + replacement + text.substring(pos + 1))
                .getBytes(StandardCharsets.UTF_8));
        service.reload();
        LicenseState s = service.getState();
        assertEquals(LicenseStatus.INVALID, s.getStatus());
        assertTrue(s.getMessage().contains("签名"));
    }

    @Test
    void 相机配额_未达上限应放行_达到上限应拒绝_不限应放行() {
        writeLicense(LocalDate.now().plusDays(365), 3, "*");
        service.reload();
        assertNull(service.checkCameraQuota(2), "未达上限应允许新增");
        assertNotNull(service.checkCameraQuota(3), "达到上限应拒绝");
        assertNotNull(service.checkCameraQuota(10), "超过上限应拒绝");

        writeLicense(LocalDate.now().plusDays(365), -1, "*");
        service.reload();
        assertNull(service.checkCameraQuota(9999), "-1 表示不限");
    }

    @Test
    void 授权不可用时不做配额拦截() {
        // 未安装授权（MISSING）时，不该表现为「加不了相机」—— 问题在授权本身
        service.reload();
        assertNull(service.checkCameraQuota(9999));
    }

    @Test
    void 关闭授权校验后应全部放行() {
        ReflectionTestUtils.setField(service, "enabled", false);
        service.reload();
        LicenseState s = service.getState();
        assertEquals(LicenseStatus.DISABLED, s.getStatus());
        assertFalse(s.isWriteBlocked());
        assertTrue(s.isUsable());
    }

    // ==================== 试用期（2026-11-21 LIC-13）====================

    @Test
    void 未装授权且试用未过应为TRIAL() {
        SystemSettingsRepository repo = org.mockito.Mockito.mock(SystemSettingsRepository.class);
        org.mockito.Mockito.when(repo.findOrCreateInternalSetting(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(LocalDate.now().format(DATE)); // 今天首启
        ReflectionTestUtils.setField(service, "trialEnabled", true);
        ReflectionTestUtils.setField(service, "trialDays", 60);
        ReflectionTestUtils.setField(service, "trialCameras", 30);
        ReflectionTestUtils.setField(service, "settingsRepository", repo);

        service.reload();
        LicenseState s = service.getState();
        assertEquals(LicenseStatus.TRIAL, s.getStatus());
        assertTrue(s.isTrial());
        assertTrue(s.isUsable(), "试用期内功能必须可用");
        assertFalse(s.isWriteBlocked());
        assertEquals(Integer.valueOf(30), s.getMaxCameras(), "试用上限应生效");
        assertEquals(Long.valueOf(60), s.getTrialDaysLeft());
    }

    @Test
    void 试用过期应回到MISSING且不阻断warn模式() {
        SystemSettingsRepository repo = org.mockito.Mockito.mock(SystemSettingsRepository.class);
        org.mockito.Mockito.when(repo.findOrCreateInternalSetting(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(LocalDate.now().minusDays(90).format(DATE)); // 90 天前首启 > 60 天试用
        ReflectionTestUtils.setField(service, "trialEnabled", true);
        ReflectionTestUtils.setField(service, "trialDays", 60);
        ReflectionTestUtils.setField(service, "trialCameras", 30);
        ReflectionTestUtils.setField(service, "settingsRepository", repo);

        service.reload();
        LicenseState s = service.getState();
        assertEquals(LicenseStatus.MISSING, s.getStatus(), "试用过期不是正式授权过期");
        assertTrue(s.getMessage().contains("试用"), "文案应说明试用已结束");
    }

    // ==================== 策略优先级（载荷 v2 > 配置）====================

    @Test
    void 载荷内带策略应覆盖配置且来源为license() {
        // 配置 warn，授权内带 readonly：昨天到期已超宽限期 → 依据载荷策略拦截
        writeLicenseV2(LocalDate.now().minusDays(10), -1, "*", "readonly");
        service.reload();
        LicenseState s = service.getState();
        assertEquals(LicenseStatus.EXPIRED, s.getStatus());
        assertTrue(s.isWriteBlocked(), "载荷 readonly 应覆盖配置 warn");
        assertEquals("readonly", s.getExpiredMode());
        assertEquals("license", s.getExpiredModeSource());
    }

    @Test
    void 载荷无策略时应回退配置且来源为config() {
        writeLicense(LocalDate.now().plusDays(365), -1, "*"); // v1 载荷无 expiredMode
        service.reload();
        LicenseState s = service.getState();
        assertEquals("warn", s.getExpiredMode());
        assertEquals("config", s.getExpiredModeSource());
    }

    // ==================== 辅助 ====================

    private void writeLicense(LocalDate expire, int maxCameras, String machine) {
        writeLicenseV2(expire, maxCameras, machine, null);
    }

    /** v2 载荷：可带 expiredMode（null 表示不写该字段，模拟旧版签发）。 */
    private void writeLicenseV2(LocalDate expire, int maxCameras, String machine, String expiredMode) {
        LicensePayload p = new LicensePayload();
        p.setVersion(1);
        p.setLicenseId("LIC-UT-" + expire.toString());
        p.setCustomer("单元测试");
        p.setIssuedAt(LocalDate.now().minusDays(30).format(DATE));
        p.setExpireAt(expire.format(DATE));
        p.setMaxCameras(maxCameras);
        p.setFeatures(new ArrayList<String>());
        p.setMachineCode(machine);
        if (expiredMode != null) {
            p.setVersion(2);
            p.setExpiredMode(expiredMode);
        }
        String text = LicenseSigner.encode(p, null);
        try {
            Files.write(new File(licensePath).toPath(), text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
