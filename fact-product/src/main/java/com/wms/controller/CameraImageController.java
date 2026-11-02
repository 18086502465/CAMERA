package com.wms.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wms.entity.CameraImage;
import com.wms.entity.CameraStatus;
import com.wms.mapper.CameraImageMapper;
import com.wms.mapper.CameraStatusMapper;
import com.wms.vo.ResultVO;
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
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            String fileName = cameraName + "_" + timestamp + "_" + UUID.randomUUID().toString().substring(0, 8) + "." + ext;

            Path uploadPath = Paths.get(imageDir);
            if (!Files.exists(uploadPath)) Files.createDirectories(uploadPath);
            Path filePath = uploadPath.resolve(fileName);
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
     */
    @GetMapping("/image/{fileName}")
    public ResponseEntity<Resource> getImage(@PathVariable String fileName) {
        try {
            Path filePath = Paths.get(imageDir).resolve(fileName).normalize();
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

    private String getExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) return "jpg";
        return fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
    }
}
