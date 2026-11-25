package com.wms.device.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wms.device.domain.model.CameraImage;
import com.wms.device.domain.model.CameraStatus;
import com.wms.device.infra.mapper.CameraImageMapper;
import com.wms.device.infra.mapper.CameraStatusMapper;
import com.wms.shared.common.ResultVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * 相机图片上传与查看接口
 */
@RestController
@RequestMapping("/api/v1/camera")
@Slf4j
public class CameraImageController {

    @Value("${app.upload.image-dir:uploads/images}")
    private String imageDir;

    @Autowired
    private CameraStatusMapper cameraStatusMapper;

    @Autowired
    private CameraImageMapper cameraImageMapper;

    /**
     * 上传相机采集图片（支持多张上传）
     * POST /api/v1/camera/image/upload
     *
     * SEC-H1（2026-11-24 评审）：本端点为 permitAll（相机设备无 JWT），
     * cameraName 是外部输入，曾直接拼入落盘文件名且 resolve 后无边界校验，
     * 存在路径遍历写风险。修复对齐 DefectImageServiceImpl 的既有防御：
     *   1) 文件名中 cameraName 段经 sanitizePathSegment 白名单清洗；
     *   2) resolve + normalize 后必须 startsWith(root)；
     *   3) 扩展名白名单校验（防伪造扩展名携带路径字符）。
     * DB 查询仍用原始 cameraName（MyBatis 参数化，无注入面）。
     */
    @PostMapping("/image/upload")
    public ResultVO<String> uploadImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam("cameraName") String cameraName) {

        try {
            if (file.isEmpty()) return ResultVO.fail("文件不能为空");
            if (cameraName == null || cameraName.trim().isEmpty()) return ResultVO.fail("cameraName 不能为空");

            String contentType = file.getContentType();
            if (contentType == null || !contentType.startsWith("image/")) return ResultVO.fail("只支持图片文件");

            String ext = getExtension(file.getOriginalFilename());
            if (!ALLOWED_EXTENSIONS.contains(ext)) return ResultVO.fail("不支持的图片格式: " + ext);

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            String fileName = sanitizePathSegment(cameraName) + "_" + timestamp + "_" + UUID.randomUUID().toString().substring(0, 8) + "." + ext;

            Path root = Paths.get(imageDir).toAbsolutePath().normalize();
            if (!Files.exists(root)) Files.createDirectories(root);
            Path filePath = root.resolve(fileName).normalize();
            if (!filePath.startsWith(root)) {
                log.warn("拒绝非法文件路径: cameraName={} fileName={}", cameraName, fileName);
                return ResultVO.fail("非法文件路径");
            }
            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

            String imageUrl = "/api/v1/camera/image/" + fileName;

            // 1. 插入 camera_images 表
            CameraImage image = new CameraImage();
            image.setCameraName(cameraName);
            image.setImagePath(filePath.toString());
            image.setImageUrl(imageUrl);
            image.setFileSize(file.getSize());
            image.setCaptureTime(LocalDateTime.now());
            cameraImageMapper.insert(image);

            // 2. 更新 camera_status 的 latest_image_path（缓存最新一张）
            CameraStatus camera = cameraStatusMapper.selectOne(
                new LambdaQueryWrapper<CameraStatus>().eq(CameraStatus::getCameraName, cameraName)
            );
            if (camera != null) {
                camera.setLatestImagePath(imageUrl);
                cameraStatusMapper.updateById(camera);
            }

            log.info("图片上传成功: {} -> {}", cameraName, imageUrl);
            return ResultVO.success(imageUrl, "上传成功");

        } catch (IOException e) {
            log.error("图片上传失败", e);
            return ResultVO.fail("图片上传失败：" + e.getMessage());
        }
    }

    /**
     * 查询相机所有图片列表
     * GET /api/v1/camera/images/{cameraName}
     */
    @GetMapping("/images/{cameraName}")
    public ResultVO<List<CameraImage>> getImages(
            @PathVariable String cameraName,
            @RequestParam(defaultValue = "5") int limit) {
        return ResultVO.success(cameraImageMapper.findRecentByCamera(cameraName, limit));
    }

    /**
     * 获取相机最新图片
     * GET /api/v1/camera/image/{fileName}
     *
     * SEC-H1：permitAll 匿名可达，读取路径同样必须限制在上传根目录内
     * （对齐 DefectImageServiceImpl.resolveImagePath 的 startsWith(root) 校验）。
     */
    @GetMapping("/image/{fileName}")
    public ResponseEntity<Resource> getImage(@PathVariable String fileName) {
        try {
            if (fileName == null || fileName.trim().isEmpty()) return ResponseEntity.badRequest().build();
            Path root = Paths.get(imageDir).toAbsolutePath().normalize();
            Path filePath = root.resolve(fileName).normalize();
            if (!filePath.startsWith(root)) {
                log.warn("拒绝非法图片读取路径: {}", fileName);
                return ResponseEntity.badRequest().build();
            }
            Resource resource = new FileSystemResource(filePath);
            if (!resource.exists()) return ResponseEntity.notFound().build();

            String ct = Files.probeContentType(filePath);
            if (ct == null) ct = "image/jpeg";

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(ct))
                    .header(HttpHeaders.CACHE_CONTROL, "max-age=3600")
                    .body(resource);
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** 允许的图片扩展名白名单（与 contentType image/* 前缀校验叠加） */
    private static final java.util.Set<String> ALLOWED_EXTENSIONS =
            new java.util.HashSet<>(java.util.Arrays.asList("jpg", "jpeg", "png", "bmp", "gif", "webp"));

    private String getExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) return "jpg";
        String ext = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
        // 扩展名段不允许携带路径字符（防伪造扩展名实现穿越）
        ext = ext.replaceAll("[^a-z0-9]", "");
        return ext.isEmpty() ? "jpg" : ext;
    }

    /** 路径段白名单清洗：对齐 DefectImageServiceImpl#sanitizePathSegment */
    private String sanitizePathSegment(String input) {
        return input == null ? "unknown" : input.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
