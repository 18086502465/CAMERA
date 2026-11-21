package com.wms.system.app;

import com.wms.shared.license.LicensePayload;
import com.wms.shared.license.LicenseSigner;
import com.wms.shared.license.LicenseState;
import com.wms.shared.license.LicenseStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 授权交付物流测试（机器码包 / 预览 / 导入，对应产品设计 §8 验收用例 1/4/5/6/7/8/9/13）。
 *
 * <p>全链路内存完成：签发 → 打包 zip → preview → import → 断言落盘与状态。
 * 机器码包测试按本机是否有指纹分两分支（CI 容器里读不到指纹属预期降级）。
 */
class LicensePackageServiceTest {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @TempDir
    Path tempDir;

    private LicenseService licenseService;
    private LicensePackageService packageService;
    private String licensePath;
    private Integer fakeCameraCount = 3;

    @BeforeEach
    void setUp() {
        licenseService = new LicenseService();
        licensePath = new File(tempDir.toFile(), "license.lic").getAbsolutePath();
        ReflectionTestUtils.setField(licenseService, "enabled", true);
        ReflectionTestUtils.setField(licenseService, "secret", "");
        ReflectionTestUtils.setField(licenseService, "licenseFile", licensePath);
        ReflectionTestUtils.setField(licenseService, "expiredMode", "warn");
        ReflectionTestUtils.setField(licenseService, "graceDays", 7);
        ReflectionTestUtils.setField(licenseService, "warnDays", 30);
        ReflectionTestUtils.setField(licenseService, "enforceMachine", true);

        packageService = new LicensePackageService();
        ReflectionTestUtils.setField(packageService, "licenseService", licenseService);
        ReflectionTestUtils.setField(packageService, "cameraCountProvider", stubProvider());
        ReflectionTestUtils.setField(packageService, "appName", "time-bucket-demo");
    }

    // ==================== ① 机器码包 ====================

    @Test
    void 机器码包_有指纹时应含四文件且机器码一致() throws Exception {
        if (licenseService.getMachineCode() == null) {
            // 本机读不到指纹：应明确报错而不是生成空包（验收用例 3）
            LicensePackageService.LicensePackageException e = assertThrows(
                    LicensePackageService.LicensePackageException.class,
                    () -> packageService.buildMachineCodePackage(null));
            assertEquals("LIC-MACHINE-002", e.getCode());
            assertTrue(e.getMessage().contains("machine-id"));
            return;
        }
        Map<String, Object> pkg = packageService.buildMachineCodePackage("LIC-UT-001");
        String filename = (String) pkg.get("filename");
        assertTrue(filename.startsWith("machine-"));
        assertTrue(filename.contains(licenseService.getMachineCode()));

        Map<String, String> entries = readZip((byte[]) pkg.get("zip"));
        assertEquals(4, entries.size(), "应恰好 4 个文件（含 MANIFEST）");
        assertTrue(entries.containsKey("machine-info.json"));
        assertTrue(entries.containsKey("machine-info.txt"));
        assertTrue(entries.containsKey("README.txt"));
        assertTrue(entries.containsKey("MANIFEST.sha256"));
        assertTrue(entries.get("machine-info.json").contains(licenseService.getMachineCode()));
        assertTrue(entries.get("machine-info.json").contains("LIC-UT-001"), "orderHint 应带入");
        // 不得含敏感字样
        assertFalse(entries.get("machine-info.json").toLowerCase().contains("password"));
        assertFalse(entries.get("machine-info.json").toLowerCase().contains("secret"));
        // MANIFEST 应能对上（sha256 首行 = machine-info.json 的哈希）
        String[] lines = entries.get("MANIFEST.sha256").split("\n");
        assertEquals("machine-info.json", lines[0].trim().split("\\s+")[1]);
    }

    // ==================== ② zip 结构校验 ====================

    @Test
    void 授权包_无lic应报LIC_ZIP_002() {
        byte[] zip = buildZip("order.json", "{\"orderNo\":\"X\"}", "README.txt", "说明");
        LicensePackageService.LicensePackageException e = assertThrows(
                LicensePackageService.LicensePackageException.class,
                () -> packageService.parseLicenseZip(zip));
        assertEquals("LIC-ZIP-002", e.getCode());
    }

    @Test
    void 授权包_两个lic应报LIC_ZIP_003() {
        String lic = signPayload(samplePayload());
        byte[] zip = buildZip("a.lic", lic, "b.lic", lic);
        LicensePackageService.LicensePackageException e = assertThrows(
                LicensePackageService.LicensePackageException.class,
                () -> packageService.parseLicenseZip(zip));
        assertEquals("LIC-ZIP-003", e.getCode());
    }

    @Test
    void 授权包_路径穿越应报LIC_ZIP_004() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("../evil.lic"));
            zos.write("x".getBytes(StandardCharsets.UTF_8));
        }
        LicensePackageService.LicensePackageException e = assertThrows(
                LicensePackageService.LicensePackageException.class,
                () -> packageService.parseLicenseZip(bos.toByteArray()));
        assertEquals("LIC-ZIP-004", e.getCode());
    }

    @Test
    void 授权包_超大应报LIC_ZIP_005() {
        byte[] big = new byte[300 * 1024];
        new Random(42).nextBytes(big);
        LicensePackageService.LicensePackageException e = assertThrows(
                LicensePackageService.LicensePackageException.class,
                () -> packageService.parseLicenseZip(big));
        assertEquals("LIC-ZIP-005", e.getCode());
    }

    @Test
    void 授权包_损坏应报LIC_ZIP_001() {
        LicensePackageService.LicensePackageException e = assertThrows(
                LicensePackageService.LicensePackageException.class,
                () -> packageService.parseLicenseZip("not-a-zip".getBytes(StandardCharsets.UTF_8)));
        assertEquals("LIC-ZIP-001", e.getCode());
    }

    // ==================== ③ 预览 ====================

    @Test
    void 预览_正确密钥应返回摘要且机器匹配() {
        LicensePayload p = samplePayload();
        p.setMaxCameras(30);
        byte[] zip = buildLicenseZip(p);
        Map<String, Object> summary = packageService.preview(multipart(zip));

        assertNotNull(summary.get("previewToken"));
        assertEquals(p.getLicenseId(), summary.get("orderNo"));
        assertEquals("单元测试", summary.get("customer"));
        assertEquals(Boolean.TRUE, summary.get("machineMatch"), "* 机器码应匹配本机");
        assertEquals(Boolean.FALSE, summary.get("quotaExceeded"), "3 台 < 30 台不应告警");
        assertEquals(Integer.valueOf(3), summary.get("currentCameras"));
        assertNotNull(summary.get("licSha256"));
    }

    @Test
    void 预览_错误密钥应报LIC_FILE_001() {
        String lic = LicenseSigner.encode(samplePayload(), "wrong-secret");
        byte[] zip = buildZip("license.lic", lic, "order.json", "{}");
        LicensePackageService.LicensePackageException e = assertThrows(
                LicensePackageService.LicensePackageException.class,
                () -> packageService.preview(multipart(zip)));
        assertEquals("LIC-FILE-001", e.getCode());
    }

    @Test
    void 预览_版本过新应报LIC_FILE_003() {
        LicensePayload p = samplePayload();
        p.setVersion(LicenseSigner.MAX_SUPPORTED_VERSION + 1);
        byte[] zip = buildLicenseZip(p);
        LicensePackageService.LicensePackageException e = assertThrows(
                LicensePackageService.LicensePackageException.class,
                () -> packageService.preview(multipart(zip)));
        assertEquals("LIC-FILE-003", e.getCode());
    }

    @Test
    void 预览_机器不符应报LIC_MACHINE_001() {
        String local = licenseService.getMachineCode();
        if (local == null) {
            return; // 本机无指纹时绑定校验被跳过，属预期降级
        }
        LicensePayload p = samplePayload();
        p.setMachineCode("FFFFFFFFFFFFFFFF");
        byte[] zip = buildLicenseZip(p);
        LicensePackageService.LicensePackageException e = assertThrows(
                LicensePackageService.LicensePackageException.class,
                () -> packageService.preview(multipart(zip)));
        assertEquals("LIC-MACHINE-001", e.getCode());
    }

    @Test
    void 预览_上限低于现场台数应标记quotaExceeded() {
        LicensePayload p = samplePayload();
        p.setMaxCameras(10);
        fakeCameraCount = 24;
        Map<String, Object> summary = packageService.preview(multipart(buildLicenseZip(p)));
        assertEquals(Boolean.TRUE, summary.get("quotaExceeded"));
    }

    // ==================== ④ 导入 ====================

    @Test
    void 导入_无token应报LIC_PREVIEW_001() {
        byte[] zip = buildLicenseZip(samplePayload());
        LicensePackageService.LicensePackageException e = assertThrows(
                LicensePackageService.LicensePackageException.class,
                () -> packageService.importPackage(multipart(zip), null, false));
        assertEquals("LIC-PREVIEW-001", e.getCode());
    }

    @Test
    void 导入_与预览不同文件应报LIC_PREVIEW_002() {
        Map<String, Object> summary = packageService.preview(multipart(buildLicenseZip(samplePayload())));
        LicensePayload otherPayload = samplePayload();
        otherPayload.setLicenseId("LIC-UT-OTHER");
        byte[] other = buildLicenseZip(otherPayload);
        LicensePackageService.LicensePackageException e = assertThrows(
                LicensePackageService.LicensePackageException.class,
                () -> packageService.importPackage(multipart(other),
                        (String) summary.get("previewToken"), false));
        assertEquals("LIC-PREVIEW-002", e.getCode());
    }

    @Test
    void 导入_配额未确认应拒绝_确认后生效() throws Exception {
        fakeCameraCount = 24;
        LicensePayload p = samplePayload();
        p.setMaxCameras(10);
        byte[] zip = buildLicenseZip(p);

        Map<String, Object> summary = packageService.preview(multipart(zip));
        LicensePackageService.LicensePackageException e = assertThrows(
                LicensePackageService.LicensePackageException.class,
                () -> packageService.importPackage(multipart(zip),
                        (String) summary.get("previewToken"), false));
        assertEquals("LIC-QUOTA-001", e.getCode(), "未勾选确认必须拒绝");

        // 预览令牌未被消费，勾选后可导入
        LicensePackageService.LicenseStateView view = packageService.importPackage(multipart(zip),
                (String) summary.get("previewToken"), true);
        assertEquals("VALID", view.getStatus());
        assertTrue(new File(licensePath).isFile(), "授权文件应已落盘");
        LicenseState s = licenseService.getState();
        assertEquals(LicenseStatus.VALID, s.getStatus(), "导入后应即时生效，无需重启");
    }

    @Test
    void 导入_旧文件应自动备份() throws Exception {
        // 预置一个旧授权文件
        Files.write(new File(licensePath).toPath(),
                LicenseSigner.encode(samplePayload(), null).getBytes(StandardCharsets.UTF_8));
        licenseService.reload();

        LicensePayload p = samplePayload();
        p.setLicenseId("LIC-UT-NEW");
        byte[] zip = buildLicenseZip(p);
        Map<String, Object> summary = packageService.preview(multipart(zip));
        packageService.importPackage(multipart(zip), (String) summary.get("previewToken"), false);

        File dir = new File(licensePath).getParentFile();
        boolean hasBak = false;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().startsWith("license.lic.bak-")) {
                    hasBak = true;
                }
            }
        }
        assertTrue(hasBak, "导入前应备份旧文件为 license.lic.bak-<ts>");
        // 授权文件是 base64 签名文本，不能查明文单号 —— 解码后断言
        try {
            LicensePayload written = LicenseSigner.decode(new String(
                    Files.readAllBytes(new File(licensePath).toPath()), StandardCharsets.UTF_8), null);
            assertTrue(p.getLicenseId().equals(written.getLicenseId()), "新授权应已写入");
        } catch (java.io.IOException io) {
            throw io;
        }
    }

    @Test
    void 导入_验签失败原文件不受影响() throws Exception {
        Files.write(new File(licensePath).toPath(),
                LicenseSigner.encode(samplePayload(), null).getBytes(StandardCharsets.UTF_8));
        licenseService.reload();
        byte[] before = Files.readAllBytes(new File(licensePath).toPath());

        String badLic = LicenseSigner.encode(samplePayload(), "wrong-secret");
        byte[] zip = buildZip("license.lic", badLic, "order.json", "{}");
        // preview 阶段就应拒绝（LIC-FILE-001），不会产生 token，更不会落盘
        LicensePackageService.LicensePackageException e = assertThrows(
                LicensePackageService.LicensePackageException.class,
                () -> packageService.preview(multipart(zip)));
        assertEquals("LIC-FILE-001", e.getCode());

        assertEquals(new String(before, StandardCharsets.UTF_8),
                new String(Files.readAllBytes(new File(licensePath).toPath()), StandardCharsets.UTF_8),
                "原授权文件一个字节都不能动");
    }

    // ==================== 辅助 ====================

    private ObjectProvider<com.wms.shared.license.CameraCountProvider> stubProvider() {
        com.wms.shared.license.CameraCountProvider impl = () -> fakeCameraCount;
        return new ObjectProvider<com.wms.shared.license.CameraCountProvider>() {
            @Override
            public com.wms.shared.license.CameraCountProvider getObject() {
                return impl;
            }

            @Override
            public com.wms.shared.license.CameraCountProvider getObject(Object... args) {
                return impl;
            }

            @Override
            public com.wms.shared.license.CameraCountProvider getIfAvailable() {
                return impl;
            }

            @Override
            public com.wms.shared.license.CameraCountProvider getIfUnique() {
                return impl;
            }
        };
    }

    private MockMultipartFile multipart(byte[] zip) {
        return new MockMultipartFile("file", "license-UT.zip",
                "application/zip", zip);
    }

    private LicensePayload samplePayload() {
        LicensePayload p = new LicensePayload();
        p.setVersion(2);
        p.setLicenseId("LIC-UT-" + System.nanoTime());
        p.setCustomer("单元测试");
        p.setIssuedAt(LocalDate.now().minusDays(1).format(DATE));
        p.setExpireAt(LocalDate.now().plusDays(365).format(DATE));
        p.setMaxCameras(-1);
        p.setFeatures(new ArrayList<String>());
        p.setMachineCode("*");
        return p;
    }

    private String signPayload(LicensePayload p) {
        return LicenseSigner.encode(p, null);
    }

    /** 组装标准授权包：license.lic + order.json。 */
    private byte[] buildLicenseZip(LicensePayload p) {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("schema", "camera-detection-license-order/1");
        order.put("orderNo", p.getLicenseId());
        order.put("customer", p.getCustomer());
        return buildZip("license.lic", signPayload(p),
                "order.json", toJson(order),
                "授权说明.txt", "导入后立即生效",
                "README.txt", "有问题联系研发");
    }

    private String toJson(Map<String, Object> map) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper m =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            return m.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 成对打包（名/内容），不带 MANIFEST。 */
    private byte[] buildZip(String... pairs) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(bos)) {
                for (int i = 0; i < pairs.length; i += 2) {
                    zos.putNextEntry(new ZipEntry(pairs[i]));
                    zos.write(pairs[i + 1].getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                }
            }
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, String> readZip(byte[] bytes) throws Exception {
        Map<String, String> out = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            byte[] buf = new byte[4096];
            while ((e = zis.getNextEntry()) != null) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                int n;
                while ((n = zis.read(buf)) > 0) {
                    b.write(buf, 0, n);
                }
                out.put(e.getName(), new String(b.toByteArray(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }
}
