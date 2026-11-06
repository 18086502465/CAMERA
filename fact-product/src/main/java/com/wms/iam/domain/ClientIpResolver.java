package com.wms.iam.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 客户端真实 IP 解析（NEW-02，2026-11-06 v5 评审）。
 *
 * <p><b>解决什么</b>：登录/注册限速用 <code>IP</code> 做键，
 * 原实现「只要带了 {@code X-Forwarded-For} 就取首段」。
 * 而本项目的 {@code installer/linux/} 全链路<b>没有 nginx</b>，
 * Spring Boot 直接在 8091 上 serve 静态页与 API（见 {@code installer/linux/scripts/install.sh}），
 * 也就是说请求都是直连 —— <b>XFF 完全由客户端控制</b>。
 * 攻击者每次请求换一个伪造 XFF，限速键就换一个槽位，5 次锁定永远触发不到。
 *
 * <p><b>规则</b>：
 * <ul>
 *   <li>默认<b>不信任</b>任何 XFF，直接用 socket 对端地址（{@code getRemoteAddr()}）；</li>
 *   <li>只有对端地址命中「可信代理」白名单时，才采信 XFF 里最左侧的地址；</li>
 *   <li>白名单支持精确 IP 与 IPv4 的 CIDR（如 {@code 10.0.0.0/8}、{@code 127.0.0.1}）；</li>
 *   <li>XFF 解析异常（空段、缺段）一律回退到对端地址，绝不抛出 —— 这里是安全边界，
 *       宁可少信任，不可因异常放行。</li>
 * </ul>
 *
 * <p>配置项：{@code app.security.trusted-proxies}（逗号分隔，默认空 = 不信任 XFF）。
 */
public final class ClientIpResolver {

    private static final Logger log = LoggerFactory.getLogger(ClientIpResolver.class);

    /** 可信代理列表（已解析成规则，空表示不信任任何 XFF） */
    private final List<Cidr> trustedProxies;

    public ClientIpResolver(String trustedProxiesConfig) {
        this.trustedProxies = parseRules(trustedProxiesConfig);
    }

    /**
     * 解析客户端 IP。
     *
     * @param remoteAddr socket 对端地址（{@code HttpServletRequest#getRemoteAddr()}），可能为 null
     * @param xffHeader  {@code X-Forwarded-For} 原始值，可能为 null
     * @return 用于限速键的 IP；两者都取不到时返回 {@code "unknown"}（所有异常流量共用同一个桶，
     *         对限速来说是「更严」而不是「更松」）
     */
    public String resolve(String remoteAddr, String xffHeader) {
        String remote = normalize(remoteAddr);
        if (remote == null) {
            return "unknown";
        }
        if (xffHeader == null || xffHeader.isEmpty() || !isTrustedProxy(remote)) {
            return remote;
        }
        String first = firstHop(xffHeader);
        return first != null ? first : remote;
    }

    /** 当前是否配置了可信代理（供启动日志与自检使用） */
    public boolean trustsAnyProxy() {
        return !trustedProxies.isEmpty();
    }

    /** 解析第一个非空段；非法字符一律拒绝（避免把整段 XFF 当 IP 写进限速键） */
    private static String firstHop(String xffHeader) {
        for (String part : xffHeader.split(",")) {
            String candidate = normalize(part);
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    /** 去空白 + 去端口 + 去 IPv6 方括号；明显不是 IP 的值返回 null */
    private static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty() || "unknown".equalsIgnoreCase(value)) {
            return null;
        }
        if (value.startsWith("[")) {           // [::1]:8080
            int end = value.indexOf(']');
            if (end > 0) {
                value = value.substring(1, end);
            }
        } else {
            int colon = value.indexOf(':');
            // 只有一个冒号才当成 IPv4:port；多个冒号是 IPv6 字面量，保留原样
            if (colon > 0 && colon == value.lastIndexOf(':')) {
                value = value.substring(0, colon);
            }
        }
        // 只允许 IP 字面量字符，挡掉 "evil,1.2.3.4" 这类注入进限速键的构造
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F') || c == '.' || c == ':';
            if (!ok) {
                return null;
            }
        }
        return value;
    }

    private boolean isTrustedProxy(String ip) {
        for (Cidr rule : trustedProxies) {
            if (rule.matches(ip)) {
                return true;
            }
        }
        return false;
    }

    private static List<Cidr> parseRules(String config) {
        if (config == null || config.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<Cidr> rules = new ArrayList<>();
        for (String part : config.split(",")) {
            String item = part.trim();
            if (item.isEmpty()) {
                continue;
            }
            try {
                rules.add(Cidr.parse(item));
            } catch (RuntimeException e) {
                log.warn("忽略非法可信代理配置项 [{}]: {}", item, e.getMessage());
            }
        }
        return rules;
    }

    /** IPv4 CIDR（IPv6 只支持精确匹配，够用且不做误判） */
    private static final class Cidr {
        private final String exactIp;
        private final long network;
        private final int prefix;

        private Cidr(String exactIp, long network, int prefix) {
            this.exactIp = exactIp;
            this.network = network;
            this.prefix = prefix;
        }

        static Cidr parse(String text) {
            int slash = text.indexOf('/');
            if (slash < 0) {
                return new Cidr(text, 0L, -1);
            }
            String ip = text.substring(0, slash).trim();
            int prefix = Integer.parseInt(text.substring(slash + 1).trim());
            long numeric = ipv4ToLong(ip);
            if (numeric < 0) {
                throw new IllegalArgumentException("CIDR 仅支持 IPv4: " + ip);
            }
            if (prefix < 0 || prefix > 32) {
                throw new IllegalArgumentException("prefix 必须在 0~32: " + prefix);
            }
            long mask = prefix == 0 ? 0L : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
            return new Cidr(null, numeric & mask, prefix);
        }

        boolean matches(String ip) {
            if (exactIp != null) {
                return exactIp.equals(ip);
            }
            long numeric = ipv4ToLong(ip);
            if (numeric < 0) {
                return false;
            }
            long mask = prefix == 0 ? 0L : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
            return (numeric & mask) == network;
        }

        private static long ipv4ToLong(String ip) {
            String[] parts = ip.split("\\.");
            if (parts.length != 4) {
                return -1L;
            }
            long result = 0L;
            for (String p : parts) {
                int v;
                try {
                    v = Integer.parseInt(p);
                } catch (NumberFormatException e) {
                    return -1L;
                }
                if (v < 0 || v > 255) {
                    return -1L;
                }
                result = (result << 8) | v;
            }
            return result;
        }
    }
}
