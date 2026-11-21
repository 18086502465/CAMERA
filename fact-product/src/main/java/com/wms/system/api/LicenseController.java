package com.wms.system.api;

import com.wms.shared.common.ResultVO;
import com.wms.shared.license.LicensePayload;
import com.wms.shared.license.LicenseState;
import com.wms.system.app.LicensePackageService;
import com.wms.system.app.LicenseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 服务器授权管理端点。
 *
 * <p>权限：状态与机器码所有登录用户可读（授权页要能看剩余天数）；
 * 上传/重载仅 ADMIN —— 换个授权文件等于改系统可用性，属管理动作。
 *
 * <p>上传为什么先验签再落盘：坏文件一旦覆盖了可用授权，系统重启后直接进入
 * MISSING，而且要恢复得靠 SSH。所以流程是「内存验签 → 备份旧文件 → 原子替换」，
 * 验签失败时原文件一个字节都不动。
 */
@RestController
@RequestMapping("/api/license")
@Slf4j
public class LicenseController {

    /** 授权文件是几百字节的文本，16KB 足够；限制大小防误传大文件。 */
    private static final long MAX_SIZE = 16 * 1024L;

    @Autowired
    private LicenseService licenseService;

    @Autowired
    private LicensePackageService packageService;

    @GetMapping
    public ResultVO<LicenseState> status() {
        return ResultVO.success(licenseService.getState());
    }

    /** 客户把这段码发回供应商即可签发绑定本机的授权。 */
    @GetMapping("/machine-code")
    public ResultVO<Map<String, Object>> machineCode() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("machineCode", licenseService.getMachineCode());
        data.put("source", licenseService.getFingerprintSource());
        data.put("note", "机器码不可逆推硬件序列号；若为 null 说明本机读不到任何指纹来源");
        return ResultVO.success(data);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/reload")
    public ResultVO<LicenseState> reload() {
        licenseService.reload();
        LicenseState s = licenseService.getState();
        log.info("管理员手动重载授权: status={}, message={}", s.getStatus(), s.getMessage());
        return ResultVO.success(s, "已重新读取授权文件");
    }

    /**
     * 下载机器码包（四步流程第 2 步，产品设计 §2.1）。
     *
     * <p>失败时返回 JSON 错误（HTTP 400）而不是空 zip；带 LIC-MACHINE-002 等
     * 机器可读错误码，前端按错误码给处置提示。
     */
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/machine-code-package")
    public ResponseEntity<?> machineCodePackage(@RequestParam(value = "order", required = false) String order) {
        try {
            Map<String, Object> pkg = packageService.buildMachineCodePackage(order);
            String filename = String.valueOf(pkg.get("filename"));
            byte[] zip = (byte[]) pkg.get("zip");
            log.info("管理员下载机器码包: file={}, order={}", filename, order);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + filename + "\"")
                    .body(zip);
        } catch (LicensePackageService.LicensePackageException e) {
            log.warn("机器码包下载被拒绝: code={}, message={}", e.getCode(), e.getMessage());
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ResultVO.fail(e.getMessage()).withErrorCode(e.getCode()));
        }
    }

    /**
     * 授权包预览（两步导入第一步）：解析 + 验签 + 机器预检，全部只读内存，
     * 返回摘要 + 5 分钟有效的 previewToken。阻断问题透传错误码。
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResultVO<Map<String, Object>> preview(@RequestParam("file") MultipartFile file) {
        try {
            return ResultVO.success(packageService.preview(file), "预览成功，请核对摘要后确认导入");
        } catch (LicensePackageService.LicensePackageException e) {
            log.warn("授权包预览被拒绝: code={}, message={}", e.getCode(), e.getMessage());
            ResultVO<Map<String, Object>> r = ResultVO.fail(e.getMessage());
            r.setErrorCode(e.getCode());
            return r;
        }
    }

    /**
     * 授权包导入（两步导入第二步）：previewToken + sha256 双重校验后落盘生效。
     * 上限低于现场台数时须 {@code ackQuota=true}（客户在页面显式勾选）。
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResultVO<Map<String, Object>> importPackage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "previewToken", required = false) String previewToken,
            @RequestParam(value = "ackQuota", required = false, defaultValue = "false") boolean ackQuota) {
        try {
            LicensePackageService.LicenseStateView view =
                    packageService.importPackage(file, previewToken, ackQuota);
            return ResultVO.success(view.getJson(), "授权已生效");
        } catch (LicensePackageService.LicensePackageException e) {
            log.warn("授权包导入被拒绝: code={}, message={}", e.getCode(), e.getMessage());
            ResultVO<Map<String, Object>> r = ResultVO.fail(e.getMessage());
            r.setErrorCode(e.getCode());
            return r;
        }
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResultVO<LicenseState> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResultVO.fail("请选择授权文件（.lic）");
        }
        if (file.getSize() > MAX_SIZE) {
            return ResultVO.fail("授权文件过大（" + file.getSize() + " 字节），正常不超过 16KB");
        }

        String text;
        try {
            text = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return ResultVO.fail("读取上传文件失败: " + e.getMessage());
        }

        // ---- 1. 先验签：签名/格式不过，绝不触碰现有文件 ----
        LicensePayload payload;
        try {
            // 必须走 service 的 verify（它用生效中的密钥，可能是 LICENSE_SECRET 覆盖值）
            payload = licenseService.verify(text);
        } catch (Exception e) {
            log.warn("授权上传被拒绝（验签失败）: {}", e.getMessage());
            return ResultVO.fail("授权文件校验失败：" + e.getMessage());
        }

        // ---- 2. 机器绑定预检：绑了别的机器就直接拒，避免装上去才发现 ----
        String local = licenseService.getMachineCode();
        if (!"*".equals(payload.getMachineCode()) && local != null
                && !payload.getMachineCode().equalsIgnoreCase(local)) {
            return ResultVO.fail("该授权绑定机器 " + payload.getMachineCode()
                    + "，本机为 " + local + "，请使用本机机器码重新签发");
        }

        // ---- 3. 备份 + 原子替换 ----
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
            return ResultVO.fail("写入授权文件失败：" + e.getMessage());
        }

        // ---- 4. 重新加载并回报 ----
        licenseService.reload();
        LicenseState s = licenseService.getState();
        log.info("授权文件已更新: id={}, customer={}, expire={}, status={}",
                payload.getLicenseId(), payload.getCustomer(), payload.getExpireAt(), s.getStatus());
        return ResultVO.success(s, "授权已更新：" + s.getStatus().getLabel());
    }
}
