package com.wms.iam.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户端 IP 解析契约测试（NEW-02，2026-11-06 v5 评审）。
 *
 * <p>背景：登录/注册限速按 IP 做键，原实现「有 X-Forwarded-For 就取首段」。
 * 本项目安装脚本不部署 nginx、请求直连 8091，XFF 完全由客户端伪造 ——
 * 换一个头就换一个限速桶，5 次锁定永远触发不到。
 *
 * <p>这里守住的行为：**默认不信任 XFF**，只有对端命中可信代理白名单才采信。
 */
class ClientIpResolverTest {

    @Test
    @DisplayName("未配置可信代理 → 完全忽略 X-Forwarded-For（防伪造绕过限速）")
    void ignoresXffByDefault() {
        ClientIpResolver resolver = new ClientIpResolver("");
        assertFalse(resolver.trustsAnyProxy());
        assertEquals("203.0.113.9",
                resolver.resolve("203.0.113.9", "1.2.3.4"),
                "默认必须用 socket 对端地址，不能采信客户端自报的 XFF");
        assertEquals("203.0.113.9",
                resolver.resolve("203.0.113.9", "1.2.3.4, 5.6.7.8"));
    }

    @Test
    @DisplayName("对端命中可信代理（精确 IP）→ 采信 XFF 首段")
    void trustsXffFromExactProxy() {
        ClientIpResolver resolver = new ClientIpResolver("127.0.0.1");
        assertTrue(resolver.trustsAnyProxy());
        assertEquals("1.2.3.4", resolver.resolve("127.0.0.1", "1.2.3.4, 10.0.0.1"));
    }

    @Test
    @DisplayName("对端命中可信代理（CIDR）→ 采信 XFF；不在网段则忽略")
    void trustsXffFromCidrProxy() {
        ClientIpResolver resolver = new ClientIpResolver("10.2.77.0/24");
        assertEquals("8.8.8.8", resolver.resolve("10.2.77.72", "8.8.8.8"));
        assertEquals("10.2.78.5",
                resolver.resolve("10.2.78.5", "8.8.8.8"),
                "10.2.78.5 不在 10.2.77.0/24 内，必须忽略 XFF");
    }

    @Test
    @DisplayName("XFF 带端口 / 空白 / unknown 都能正确归一")
    void normalizesXffSegments() {
        ClientIpResolver resolver = new ClientIpResolver("127.0.0.1");
        assertEquals("1.2.3.4", resolver.resolve("127.0.0.1", " 1.2.3.4:5678 "));
        assertEquals("1.2.3.4", resolver.resolve("127.0.0.1", "unknown, 1.2.3.4"));
        assertEquals("127.0.0.1", resolver.resolve("127.0.0.1", "unknown"),
                "全部段都不可用时回退到对端地址，不能返回 \"unknown\" 当键");
    }

    @Test
    @DisplayName("XFF 里塞非法字符 → 不会被当成 IP 写进限速键")
    void rejectsGarbageXff() {
        ClientIpResolver resolver = new ClientIpResolver("127.0.0.1");
        assertEquals("127.0.0.1", resolver.resolve("127.0.0.1", "evil<script>"));
        assertEquals("127.0.0.1", resolver.resolve("127.0.0.1", ""));
    }

    @Test
    @DisplayName("取不到对端地址 → 统一归到 unknown（更严，不更松）")
    void nullRemoteAddr() {
        ClientIpResolver resolver = new ClientIpResolver("127.0.0.1");
        assertEquals("unknown", resolver.resolve(null, "1.2.3.4"));
        assertEquals("unknown", resolver.resolve("", null));
    }

    @Test
    @DisplayName("非法可信代理配置被忽略，不抛异常（不能让配置写错导致登录 500）")
    void toleratesBadConfig() {
        ClientIpResolver resolver = new ClientIpResolver("not-an-ip, 10.0.0.0/33, 192.168.1.0/24");
        assertEquals("9.9.9.9", resolver.resolve("192.168.1.10", "9.9.9.9"),
                "合法的那条（192.168.1.0/24）应仍然生效");
    }
}
