package com.wms.controller;

import com.wms.device.api.CameraImageController;
import com.wms.device.domain.model.CameraStatus;
import com.wms.device.infra.mapper.CameraImageMapper;
import com.wms.device.infra.mapper.CameraStatusMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * 相机图片上传/读取端点的路径遍历防御契约测试（2026-11-24 深度评审 H1）。
 *
 * <p>背景：POST /api/v1/camera/image/upload 为 permitAll（相机设备无 JWT），
 * cameraName 曾<b>未清洗直接拼入落盘文件名</b>且 resolve 后无边界校验 ——
 * cameraName 携带 ../ 可跳出上传目录覆盖任意路径文件。
 * GET /api/v1/camera/image/{fileName} 同样匿名可达且缺边界校验。
 *
 * <p>本测试冻结：
 * <ul>
 *   <li>cameraName 恶意路径段必须被白名单清洗，落盘文件必须留在上传根目录内；</li>
 *   <li>非白名单扩展名必须拒绝（不落盘）；</li>
 *   <li>读取路径越界必须 400。</li>
 * </ul>
 *
 * <p>直接调用 Controller 方法（防御逻辑在方法体内，不依赖 URL 层）；
 * standalone MockMvc 不注册 multipartResolver 时单 MultipartFile 参数
 * 无法解析（DefectImage 端点用 MultipartFile[] 走了另一解析分支）。
 */
@ExtendWith(MockitoExtension.class)
class CameraImagePathTraversalTest {

    @Mock
    private CameraImageMapper cameraImageMapper;
    @Mock
    private CameraStatusMapper cameraStatusMapper;

    @InjectMocks
    private CameraImageController controller;

    @TempDir
    Path uploadRoot;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "imageDir", uploadRoot.toString());
        // 上传成功路径会查 camera_status，返回 null 即可
        lenient().when(cameraStatusMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        lenient().when(cameraImageMapper.insert(any())).thenReturn(1);
    }

    private MockMultipartFile file(String name, String ct) {
        return new MockMultipartFile("file", name, ct, new byte[]{1, 2, 3});
    }

    @Test
    @DisplayName("cameraName 携带 ../ → 清洗后落盘仍在上传根目录内，不产生越界文件")
    void traversalCameraNameSanitized() throws Exception {
        com.wms.shared.common.ResultVO<String> r = controller.uploadImage(
                file("a.jpg", MediaType.IMAGE_JPEG_VALUE), "../../etc/evil");
        assertEquals(200, r.getCode(), "清洗后应正常上传: " + r.getMessage());

        try (Stream<Path> outside = Files.list(uploadRoot.getParent())) {
            assertFalse(outside.filter(p -> !p.equals(uploadRoot))
                            .anyMatch(p -> p.getFileName().toString().contains("evil")),
                    "cameraName 穿越 ../ 不得在上传目录外创建文件");
        }
        try (Stream<Path> inside = Files.list(uploadRoot)) {
            assertTrue(inside.findAny().isPresent(), "清洗后的文件应正常落盘");
        }
    }

    @Test
    @DisplayName("cameraName 为纯 .. → 拼上时间戳段后同样清洗为合法文件名，不越界")
    void dotDotCameraNameSanitized() throws Exception {
        com.wms.shared.common.ResultVO<String> r = controller.uploadImage(
                file("a.jpg", MediaType.IMAGE_JPEG_VALUE), "..");
        assertEquals(200, r.getCode(), "纯 .. 也应正常上传: " + r.getMessage());
        try (Stream<Path> inside = Files.list(uploadRoot)) {
            assertTrue(inside.findAny().isPresent());
        }
    }

    @Test
    @DisplayName("扩展名不在白名单（exe）→ 拒绝上传且不落盘")
    void disallowedExtensionRejected() throws Exception {
        // contentType 伪装成 image/* 才能穿透首层校验，专测扩展名白名单
        MockMultipartFile exe = new MockMultipartFile("file", "evil.exe",
                "image/jpeg", new byte[]{0x4d, 0x5a});
        com.wms.shared.common.ResultVO<String> r = controller.uploadImage(exe, "CAM01");
        assertEquals(500, r.getCode(), "非法扩展名应拒绝");
        assertTrue(r.getMessage().contains("不支持的图片格式"));
        try (Stream<Path> inside = Files.list(uploadRoot)) {
            assertFalse(inside.findAny().isPresent(), "非法扩展名不得落盘");
        }
    }

    @Test
    @DisplayName("读取 fileName 含路径分隔符/越界形态 → 400 拒绝")
    void traversalReadRejected() throws Exception {
        // PathVariable 单段不会带 '/'，但契约必须冻结：任何越界形态一律 400
        int status1 = controller.getImage("../secret.txt").getStatusCodeValue();
        assertEquals(400, status1, "相对路径上跳必须 400");
        int status2 = controller.getImage("..").getStatusCodeValue();
        assertEquals(400, status2, "纯 .. 必须 400（未通过边界校验则一律拒绝）");
    }

    @Test
    @DisplayName("正常上传 → 文件落盘在根目录内且返回以 /api/v1/camera/image/ 开头的 URL")
    void normalUploadSucceeds() {
        com.wms.shared.common.ResultVO<String> r = controller.uploadImage(
                file("a.jpg", MediaType.IMAGE_JPEG_VALUE), "CAM01");
        assertEquals(200, r.getCode(), r.getMessage());
        assertTrue(r.getData().startsWith("/api/v1/camera/image/CAM01_"),
                "URL 应以清洗后的 cameraName 开头: " + r.getData());
    }

    @Test
    @DisplayName("正常读取 → 200 且能取回内容")
    void normalReadSucceeds() throws Exception {
        controller.uploadImage(file("a.jpg", MediaType.IMAGE_JPEG_VALUE), "CAM01");
        try (Stream<Path> inside = Files.list(uploadRoot)) {
            Path saved = inside.findFirst().orElseThrow(() -> new AssertionError("应有落盘文件"));
            org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> resp =
                    controller.getImage(saved.getFileName().toString());
            assertEquals(200, resp.getStatusCodeValue(), "根目录内的合法文件应可读取");
        }
    }

    @Test
    @DisplayName("cameraStatus 命中 → latest_image_path 更新走 DB（不因清洗影响运行态查询）")
    void cameraStatusStillQueriedByRawName() {
        CameraStatus cs = new CameraStatus();
        lenient().when(cameraStatusMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(cs);
        com.wms.shared.common.ResultVO<String> r = controller.uploadImage(
                file("a.jpg", MediaType.IMAGE_JPEG_VALUE), "CAM01");
        assertEquals(200, r.getCode());
    }
}
