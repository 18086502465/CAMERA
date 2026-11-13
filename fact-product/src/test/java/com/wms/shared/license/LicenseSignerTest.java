package com.wms.shared.license;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 授权签名/校验测试。
 *
 * <p>{@link #GOLDEN} 是 {@code installer/linux/scripts/gen_license.py} 真实生成的文件，
 * 作用是把「Java 端算法」与「签发工具」锁死：任何一端改了密钥推导、编码方式
 * 或字段名，这条测试立刻红灯。**不要为了让它变绿去改这个常量**。
 */
class LicenseSignerTest {

    /** gen_license.py issue --customer 回归测试客户 --expire 2027-12-31 --max-cameras 20 --id LIC-TEST-0001 */
    private static final String GOLDEN =
            "eyJ2ZXJzaW9uIjoxLCJsaWNlbnNlSWQiOiJMSUMtVEVTVC0wMDAxIiwiY3VzdG9tZXIiOiLlm57lvZLmtYvor5XlrqLmiLci"
            + "LCJpc3N1ZWRBdCI6IjIwMjYtMTEtMTIiLCJleHBpcmVBdCI6IjIwMjctMTItMzEiLCJtYXhDYW1lcmFzIjoyMCwiZmVhdHVy"
            + "ZXMiOltdLCJtYWNoaW5lQ29kZSI6IioifQ.lfZAZBYUbfXVeuls_oB1DpB91Dr9wn33bSlMoN2iSZY";

    @Test
    void 签发工具生成的授权应能被Java解析() {
        LicensePayload p = LicenseSigner.decode(GOLDEN, null);
        assertEquals("LIC-TEST-0001", p.getLicenseId());
        assertEquals("回归测试客户", p.getCustomer());
        assertEquals("2027-12-31", p.getExpireAt());
        assertEquals(Integer.valueOf(20), p.getMaxCameras());
        assertEquals("*", p.getMachineCode());
    }

    @Test
    void 篡改签名段应被拒绝() {
        String bad = GOLDEN.substring(0, GOLDEN.length() - 1) + "A";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LicenseSigner.decode(bad, null));
        assertTrue(e.getMessage().contains("签名"));
    }

    @Test
    void 篡改载荷应被拒绝() {
        // 冒充「把 maxCameras 从 20 改成 999」：换掉整个载荷段，签名照旧
        int dot = GOLDEN.indexOf('.');
        String sig = GOLDEN.substring(dot);
        String payload = "eyJ2ZXJzaW9uIjoxLCJsaWNlbnNlSWQiOiJYIiwiZXhwaXJlQXQiOiIyMDk5LTEyLTMxIiwibWF4Q2FtZXJhcyI6OTk5fQ";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LicenseSigner.decode(payload + sig, null));
        assertTrue(e.getMessage().contains("签名"));
    }

    @Test
    void 错误密钥应被拒绝() {
        String signed = LicenseSigner.encode(samplePayload(), "another-secret");
        assertThrows(IllegalArgumentException.class,
                () -> LicenseSigner.decode(signed, null));
    }

    @Test
    void 往返编解码应保持一致() {
        LicensePayload src = samplePayload();
        String text = LicenseSigner.encode(src, null);
        LicensePayload back = LicenseSigner.decode(text, null);
        assertEquals(src.getLicenseId(), back.getLicenseId());
        assertEquals(src.getExpireAt(), back.getExpireAt());
        assertEquals(src.getMaxCameras(), back.getMaxCameras());
    }

    @Test
    void 相机上限为0应视为非法() {
        LicensePayload p = samplePayload();
        p.setMaxCameras(0);
        String text = LicenseSigner.encode(p, null);
        assertThrows(IllegalArgumentException.class, () -> LicenseSigner.decode(text, null));
    }

    @Test
    void 版本不支持应被拒绝() {
        LicensePayload p = samplePayload();
        p.setVersion(2);
        String text = LicenseSigner.encode(p, null);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LicenseSigner.decode(text, null));
        assertTrue(e.getMessage().contains("版本"));
    }

    @Test
    void 空内容与缺签名段应给出可读错误() {
        assertThrows(IllegalArgumentException.class, () -> LicenseSigner.decode("", null));
        assertThrows(IllegalArgumentException.class, () -> LicenseSigner.decode("abc", null));
    }

    @Test
    void 机器码应为16位大写且稳定() {
        String a = MachineFingerprint.machineCode();
        String b = MachineFingerprint.machineCode();
        assertEquals(a, b, "同一进程内机器码必须稳定");
        if (a != null) {
            assertEquals(16, a.length());
            assertEquals(a.toUpperCase(), a);
            assertNotNull(MachineFingerprint.sourceName());
        }
    }

    private LicensePayload samplePayload() {
        LicensePayload p = new LicensePayload();
        p.setVersion(1);
        p.setLicenseId("LIC-UT-0001");
        p.setCustomer("单元测试");
        p.setIssuedAt("2026-01-01");
        p.setExpireAt("2099-12-31");
        p.setMaxCameras(-1);
        p.setFeatures(new ArrayList<>(Collections.singletonList("defect-image")));
        p.setMachineCode("*");
        return p;
    }
}
