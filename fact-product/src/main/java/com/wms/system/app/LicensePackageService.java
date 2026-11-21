package com.wms.system.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wms.shared.license.CameraCountProvider;
import com.wms.shared.license.LicensePayload;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 授权交付物流服务（机器码包 / 授权包）。
 *
 * <p>对应《服务器授权产品设计》C-1/C-2/C-3：四步流程里「客户下载机器码包」和
 * 「客户导入授权包」两个触点都在这里。协议细节（两个 zip 的文件清单、错误码）以
 * 设计文档 §2/§3.5 为准，本文只说明实现上的几个硬约束：
 *
 * <ul>
 *   <li><b>zip 解析全部只读内存</b>：限制总 256KB / ≤16 条目 / 单条 128KB，
 *       拒绝子目录、绝对路径、{@code ..} —— 客户传上来的东西不可信；</li>
 *   <li><b>预览绝不落盘</b>：preview 只在内存验签并缓存 5 分钟的 previewToken，
 *       import 时重算 sha256 与 token 比对，保证「预览的和导入的是同一个文件」；</li>
 *   <li><b>导入失败原文件一个字节不动</b>：与 {@code LicenseController.upload} 同序 ——
 *       验签 → 机器预检 → 配额 → 备份 → 原子替换。</li>
 * </ul>
 */
@Service
@Slf4j
public class LicensePackageService {

    // ==================== 协议常量（与设计文档 §2 冻结值一致） ====================

    static final String MACHINE_SCHEMA = "camera-detection-machine-info/1";
    static final String ORDER_SCHEMA = "camera-detection-license-order/1";
    static final String PRODUCT_NAME = "产线缺陷在线AI检测";

    /** zip 总解压上限。 */
    private static final long MAX_ZIP_TOTAL = 256 * 1024L;
    /** 单条目解压上限。 */
    private static final long MAX_ZIP_ENTRY = 128 * 1024L;
    /** zip 条目数上限。 */
    private static final int MAX_ZIP_ENTRIES = 16;
    /** previewToken 有效期（毫秒）。 */
    private static final long PREVIEW_TTL_MS = 5 * 60 * 1000L;

    private static final DateTimeFormatter DATETIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter ISO_OFFSET =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private LicenseService licenseService;

    /** 可选注入：device 域的相机数实现缺失（单测）时相机数为 null。 */
    @Autowired
    private ObjectProvider<CameraCountProvider> cameraCountProvider;

    @Value("${spring.application.name:camera-detection}")
    private String appName;

    // ==================== 预览令牌缓存 ====================

    @Getter
    private static class PreviewEntry {
        final String licText;
        final LicensePayload payload;
        final String licSha256;
        final Map<String, Object> orderJson;
        final long expiresAt;

        PreviewEntry(String licText, LicensePayload payload, String licSha256,
                     Map<String, Object> orderJson) {
            this.licText = licText;
            this.payload = payload;
            this.licSha256 = licSha256;
            this.orderJson = orderJson;
            this.expiresAt = System.currentTimeMillis() + PREVIEW_TTL_MS;
        }

        boolean expired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    private final ConcurrentHashMap<String, PreviewEntry> previews = new ConcurrentHashMap<>();

    // ==================== ① 机器码包（C-1） ====================

    /**
     * 构建机器码包。指纹不可用时抛 {@link LicensePackageException}（code=LIC-MACHINE-002），
     * 调用方转 400 并给出检查路径 —— 绝不生成空包。
     *
     * @param order 客户可选填的商务单号（无则 null）
     * @return 文件名 + zip 字节
     */
    public Map<String, Object> buildMachineCodePackage(String order) {
        String machineCode = licenseService.getMachineCode();
        String source = licenseService.getFingerprintSource();
        if (machineCode == null || machineCode.trim().isEmpty()) {
            throw new LicensePackageException("LIC-MACHINE-002",
                    "本机读不到硬件指纹，无法生成机器码包；请检查 /sys/class/dmi/id/product_uuid"
                            + " 与 /etc/machine-id 是否可读，或联系研发改用手工机器码模式");
        }

        String hostname = safeHostname();
        Integer cameraCount = currentCameraCount();
        LicenseStateView view = currentLicenseView();
        String generatedAt = ISO_OFFSET.format(LocalDateTime.now().atZone(ZoneId.systemDefault()))
                + ZoneId.systemDefault().getRules().getOffset(LocalDateTime.now()).toString();

        // ---- machine-info.json（结构化，签发工具直接读）----
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("schema", MACHINE_SCHEMA);
        info.put("machineCode", machineCode);
        info.put("fingerprintSource", source);
        info.put("hostname", hostname);
        info.put("appVersion", resolveAppVersion());
        info.put("product", PRODUCT_NAME);
        info.put("generatedAt", generatedAt);
        info.put("cameraCount", cameraCount);
        info.put("currentLicense", view.json);
        info.put("orderHint", (order == null || order.trim().isEmpty()) ? null : order.trim());

        String infoJson;
        try {
            infoJson = MAPPER.writeValueAsString(info);
        } catch (Exception e) {
            throw new IllegalStateException("序列化 machine-info.json 失败", e);
        }

        // ---- machine-info.txt（人读版，方便贴邮件/微信）----
        StringBuilder txt = new StringBuilder();
        txt.append("机器码信息（").append(PRODUCT_NAME).append("）\n");
        txt.append("========================================\n");
        txt.append("机器码        ").append(machineCode).append('\n');
        txt.append("指纹来源      ").append(source == null ? "未知" : source).append('\n');
        txt.append("主机名        ").append(hostname == null ? "-" : hostname).append('\n');
        txt.append("现场相机数    ").append(cameraCount == null ? "未知" : cameraCount + " 台").append('\n');
        txt.append("生成时间      ").append(generatedAt).append('\n');
        if (view.licenseId != null) {
            txt.append("当前授权      ").append(view.licenseId).append("（")
                    .append(view.status).append("，到期 ").append(view.expireAt)
                    .append("，上限 ").append(view.maxCameras).append(" 台）\n");
        } else {
            txt.append("当前授权      未安装\n");
        }
        if (order != null && !order.trim().isEmpty()) {
            txt.append("商务单号      ").append(order.trim()).append('\n');
        }

        String readme = "把这个 zip 整体发给研发签发授权。\n"
                + "不要截图、不要手抄机器码 —— 16 位码抄错一位就会签到别的机器上。\n"
                + "研发会用签发工具直接读取本包生成授权包，回传后在本平台「服务器授权」页导入。\n";

        byte[] zip = buildZip(new String[]{
                "machine-info.json", infoJson,
                "machine-info.txt", txt.toString(),
                "README.txt", readme,
        });

        String filename = "machine-" + machineCode + "-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".zip";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("filename", filename);
        result.put("zip", zip);
        return result;
    }

    // ==================== ② 授权包解析（C-2/C-3 共用） ====================

    /** zip 解析结果：lic 文本 + 授权包内可选的 order.json。 */
    static class ParsedPackage {
        String licText;
        Map<String, Object> orderJson;
    }

    /**
     * 解析授权 zip。协议与体积限制见类注释；任何不合法都抛带错误码的异常，
     * 且绝不触碰磁盘上的现有授权文件。
     */
    ParsedPackage parseLicenseZip(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new LicensePackageException("LIC-ZIP-001", "上传内容为空，请重新选择授权包");
        }
        if (bytes.length > MAX_ZIP_TOTAL) {
            throw new LicensePackageException("LIC-ZIP-005",
                    "授权包异常偏大（" + bytes.length + " 字节），正常不超过 256KB，请确认来源");
        }
        // ZipInputStream 对非 zip 内容会「优雅地」当作空包返回（getNextEntry=null），
        // 不抛异常 → 必须先校验 PK 魔数，否则损坏文件会被误报成「包内无 .lic」
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') {
            throw new LicensePackageException("LIC-ZIP-001",
                    "授权包不完整或已损坏（不是有效的 zip 文件），请让研发重新发送");
        }

        String licText = null;
        Map<String, Object> orderJson = null;
        int entries = 0;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                entries++;
                if (entries > MAX_ZIP_ENTRIES) {
                    throw new LicensePackageException("LIC-ZIP-005",
                            "授权包内文件数超过 " + MAX_ZIP_ENTRIES + "，结构异常，请确认来源");
                }
                String name = entry.getName();
                if (name.contains("\\") || name.contains("/") || name.startsWith("/")
                        || name.contains("..")) {
                    throw new LicensePackageException("LIC-ZIP-004",
                            "授权包内含非法路径（" + name + "），已拒绝");
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                long total = 0;
                int n;
                while ((n = zis.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_ZIP_ENTRY) {
                        throw new LicensePackageException("LIC-ZIP-005",
                                "授权包内单文件超过 128KB，结构异常，请确认来源");
                    }
                    out.write(buf, 0, n);
                }
                String content = new String(out.toByteArray(), StandardCharsets.UTF_8);
                if (name.toLowerCase(Locale.ROOT).endsWith(".lic")) {
                    if (licText != null) {
                        throw new LicensePackageException("LIC-ZIP-003",
                                "授权包内有多份授权文件，请让研发重新打包");
                    }
                    licText = content;
                } else if ("order.json".equalsIgnoreCase(name)) {
                    try {
                        orderJson = MAPPER.readValue(content, Map.class);
                    } catch (Exception e) {
                        // order.json 只供对账，坏了不阻断（票据才是权威）
                        log.warn("授权包内 order.json 解析失败（不阻断）: {}", e.getMessage());
                    }
                }
                // 其余文件（授权说明.txt / MANIFEST.sha256 / README.txt）直接忽略
            }
        } catch (LicensePackageException e) {
            throw e;
        } catch (Exception e) {
            throw new LicensePackageException("LIC-ZIP-001",
                    "授权包不完整或已损坏（" + e.getMessage() + "），请让研发重新发送");
        }

        if (licText == null) {
            throw new LicensePackageException("LIC-ZIP-002",
                    "这不是授权包（包内没有 .lic 授权文件），请确认文件名以 license- 开头");
        }
        ParsedPackage parsed = new ParsedPackage();
        parsed.licText = licText;
        parsed.orderJson = orderJson;
        return parsed;
    }

    // ==================== ③ 预览（C-2） ====================

    /**
     * 预览：解析 + 验签 + 机器预检 + 配额比对，全部只读内存，返回摘要与 previewToken。
     * 阻断性问题抛 {@link LicensePackageException}；警告（降档 / 单号不一致）进 warnings。
     *
     * <p>兼容裸 {@code .lic}：非 zip 开头（PK 魔数）的内容按单文件授权文本处理，
     * 老流程仍可用；但 {@code import} 只收 zip 包（走 previewToken 校验）。
     */
    public Map<String, Object> preview(MultipartFile file) {
        byte[] bytes = readUpload(file);
        ParsedPackage parsed;
        if (bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K') {
            parsed = parseLicenseZip(bytes);
        } else {
            // 裸 .lic：直接当授权文本，order.json 不存在
            parsed = new ParsedPackage();
            parsed.licText = new String(bytes, StandardCharsets.UTF_8);
        }

        // 验签 + 结构校验（用生效中的密钥，可能是 LICENSE_SECRET 覆盖值）
        LicensePayload payload;
        try {
            payload = licenseService.verify(parsed.licText);
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            String code = msg.contains("更新版本") ? "LIC-FILE-003"
                    : msg.contains("签名") ? "LIC-FILE-001" : "LIC-FILE-002";
            throw new LicensePackageException(code, msg);
        }

        // 机器预检
        String local = licenseService.getMachineCode();
        boolean machineMatch = "*".equals(payload.getMachineCode())
                || (local != null && payload.getMachineCode().equalsIgnoreCase(local));
        if (!machineMatch) {
            throw new LicensePackageException("LIC-MACHINE-001",
                    "该授权绑定机器 " + payload.getMachineCode()
                            + "，本机为 " + (local == null ? "未知" : local)
                            + "；若重装过系统，请重新下载机器码包签发");
        }

        // 配额比对（仅警告级，导入时可勾选确认）
        Integer current = currentCameraCount();
        boolean quotaExceeded = current != null && payload.getMaxCameras() != null
                && payload.getMaxCameras() >= 0 && current > payload.getMaxCameras();

        // 单号一致性（仅警告级）
        String orderNo = payload.getLicenseId();
        String orderFileNo = parsed.orderJson == null ? null
                : asText(parsed.orderJson.get("orderNo"));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("previewToken", UUID.randomUUID().toString());
        summary.put("licSha256", sha256Hex(parsed.licText));
        summary.put("orderNo", orderNo);
        summary.put("orderFileNo", orderFileNo);
        summary.put("orderMatch", orderFileNo == null || orderFileNo.equals(orderNo));
        summary.put("customer", payload.getCustomer());
        summary.put("issuedAt", payload.getIssuedAt());
        summary.put("issuedBy", parsed.orderJson == null ? null : asText(parsed.orderJson.get("issuedBy")));
        summary.put("expireAt", payload.getExpireAt());
        summary.put("daysLeft", daysLeft(payload.getExpireAt()));
        summary.put("maxCameras", payload.getMaxCameras());
        summary.put("currentCameras", current);
        summary.put("quotaExceeded", quotaExceeded);
        summary.put("packageMachineCode", payload.getMachineCode());
        summary.put("localMachineCode", local);
        summary.put("machineMatch", machineMatch);
        summary.put("expiredMode", effectiveMode(payload));
        summary.put("expiredModePlain", modePlain(payload));
        summary.put("version", payload.getVersion());

        // 缓存预览（5 分钟）
        previews.values().removeIf(PreviewEntry::expired);
        previews.put((String) summary.get("previewToken"), new PreviewEntry(
                parsed.licText, payload, (String) summary.get("licSha256"), parsed.orderJson));

        log.info("授权包预览成功: order={}, customer={}, expire={}, maxCameras={}, quotaExceeded={}",
                orderNo, payload.getCustomer(), payload.getExpireAt(),
                payload.getMaxCameras(), quotaExceeded);
        return summary;
    }

    // ==================== ④ 导入（C-3） ====================

    /**
     * 确认导入：token + sha256 双重校验「预览与导入是同一个文件」，然后与
     * {@code upload} 相同的落盘顺序（备份 → 原子替换 → reload）。
     *
     * @param ackQuota 预览提示「上限低于现场台数」时，客户须显式勾选确认
     */
    public LicenseStateView importPackage(MultipartFile file, String previewToken, boolean ackQuota) {
        if (previewToken == null || previewToken.trim().isEmpty()) {
            throw new LicensePackageException("LIC-PREVIEW-001",
                    "缺少预览凭证，请先预览再确认导入");
        }
        PreviewEntry entry = previews.get(previewToken.trim());
        if (entry == null || entry.expired()) {
            previews.remove(previewToken.trim());
            throw new LicensePackageException("LIC-PREVIEW-001",
                    "预览已超时（有效期 5 分钟），请重新选择授权包预览后再导入");
        }

        byte[] bytes = readUpload(file);
        ParsedPackage parsed = parseLicenseZip(bytes);
        String sha = sha256Hex(parsed.licText);
        if (!sha.equals(entry.licSha256)) {
            throw new LicensePackageException("LIC-PREVIEW-002",
                    "导入的文件与预览时不是同一个文件，请重新预览后再导入");
        }

        // 配额：预览阶段是警告，导入阶段必须显式确认
        Integer current = currentCameraCount();
        if (current != null && entry.payload.getMaxCameras() != null
                && entry.payload.getMaxCameras() >= 0 && current > entry.payload.getMaxCameras()
                && !ackQuota) {
            throw new LicensePackageException("LIC-QUOTA-001",
                    "授权相机上限 " + entry.payload.getMaxCameras() + " 台，低于现场实际 "
                            + current + " 台；请勾选「确认按该上限运行」后再导入，或联系商务扩容");
        }

        // ---- 备份 + 原子替换（与 LicenseController.upload 同序同语义）----
        String text = entry.licText;
        File target = new File(licenseService.getLicenseFile());
        try {
            Path dir = target.toPath().getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            if (target.isFile()) {
                String stamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
                File bak = new File(target.getParentFile(), "license.lic.bak-" + stamp);
                Files.copy(target.toPath(), bak.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            Path tmp = new File(target.getParentFile(), ".license.tmp").toPath();
            Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            log.error("写入授权文件失败: {}", e.getMessage(), e);
            throw new LicensePackageException("LIC-FILE-010",
                    "写入授权文件失败：" + e.getMessage() + "（原授权未受影响，仍按原状态运行）");
        }

        licenseService.reload();
        LicenseStateView view = currentLicenseView();
        log.info("授权包已导入生效: order={}, customer={}, expire={}, maxCameras={}, status={}",
                entry.payload.getLicenseId(), entry.payload.getCustomer(),
                entry.payload.getExpireAt(), entry.payload.getMaxCameras(), view.status);
        previews.remove(previewToken.trim());
        return view;
    }

    // ==================== 内部工具 ====================

    /** 供控制器展示导入/预览结果的最小状态视图。 */
    @Getter
    public static class LicenseStateView {
        final Map<String, Object> json = new LinkedHashMap<>();
        String licenseId;
        String status;
        String expireAt;
        Integer maxCameras;

        LicenseStateView put(String key, Object value) {
            json.put(key, value);
            return this;
        }
    }

    /** 当前授权状态摘要（机器码包 / 导入结果共用）。 */
    public LicenseStateView currentLicenseView() {
        com.wms.shared.license.LicenseState s = licenseService.getState();
        LicenseStateView v = new LicenseStateView();
        v.status = s.getStatus() == null ? null : s.getStatus().name();
        v.expireAt = s.getExpireAt();
        v.maxCameras = s.getMaxCameras();
        v.licenseId = s.getLicenseId();
        v.put("status", v.status)
                .put("message", s.getMessage())
                .put("daysLeft", s.getDaysLeft())
                .put("expireAt", s.getExpireAt())
                .put("maxCameras", s.getMaxCameras())
                .put("licenseId", s.getLicenseId())
                .put("expiredMode", s.getExpiredMode())
                .put("expiredModeSource", s.getExpiredModeSource());
        return v;
    }

    private Integer currentCameraCount() {
        CameraCountProvider p = cameraCountProvider == null ? null : cameraCountProvider.getIfAvailable();
        return p == null ? null : p.currentCameraCount();
    }

    private byte[] readUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new LicensePackageException("LIC-ZIP-001", "请选择授权包（.zip）");
        }
        try {
            return file.getBytes();
        } catch (Exception e) {
            throw new LicensePackageException("LIC-ZIP-001",
                    "读取上传文件失败：" + e.getMessage());
        }
    }

    private String effectiveMode(LicensePayload p) {
        if (p.getExpiredMode() != null && !p.getExpiredMode().trim().isEmpty()) {
            return p.getExpiredMode().trim().toLowerCase(Locale.ROOT);
        }
        return null; // 前端自行回退到 GET /api/license 的全局值
    }

    private String modePlain(LicensePayload p) {
        String mode = effectiveMode(p);
        if (mode == null) {
            return "跟随服务器配置（默认只提示）";
        }
        switch (mode) {
            case "readonly":
                return "到期后停止设备数据上报（管理端仍可导入新授权）";
            case "block":
                return "到期后仅保留登录与授权页";
            default:
                return "到期后只弹提示，不影响功能";
        }
    }

    private Long daysLeft(String expireAt) {
        try {
            return ChronoUnit.DAYS.between(LocalDate.now(),
                    LocalDate.parse(expireAt.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd")));
        } catch (Exception e) {
            return null;
        }
    }

    private String asText(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private String safeHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveAppVersion() {
        try {
            String v = LicensePackageService.class.getPackage() != null
                    ? LicensePackageService.class.getPackage().getImplementationVersion() : null;
            return v == null ? appName : v;
        } catch (Exception e) {
            return appName;
        }
    }

    static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(text.getBytes(StandardCharsets.UTF_8))) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("计算 sha256 失败", e);
        }
    }

    /**
     * 按设计文档 §2 组装 zip：每两个字符串一个文件（名/内容），末尾自动追加
     * {@code MANIFEST.sha256}（逐行 {@code <sha256>  <文件名>}）。
     */
    static byte[] buildZip(String[] nameContentPairs) {
        if (nameContentPairs == null || nameContentPairs.length == 0
                || nameContentPairs.length % 2 != 0) {
            throw new IllegalStateException("buildZip 参数必须成对");
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        StringBuilder manifest = new StringBuilder();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (int i = 0; i < nameContentPairs.length; i += 2) {
                String name = nameContentPairs[i];
                byte[] data = nameContentPairs[i + 1].getBytes(StandardCharsets.UTF_8);
                zos.putNextEntry(new ZipEntry(name));
                zos.write(data);
                zos.closeEntry();
                manifest.append(sha256Hex(nameContentPairs[i + 1]))
                        .append("  ").append(name).append('\n');
            }
            zos.putNextEntry(new ZipEntry("MANIFEST.sha256"));
            zos.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        } catch (Exception e) {
            throw new IllegalStateException("构建 zip 失败", e);
        }
        return bos.toByteArray();
    }

    /** 带错误码的业务异常；控制器转 ResultVO 并透传 errorCode。 */
    public static class LicensePackageException extends RuntimeException {
        @Getter
        private final String code;

        public LicensePackageException(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
