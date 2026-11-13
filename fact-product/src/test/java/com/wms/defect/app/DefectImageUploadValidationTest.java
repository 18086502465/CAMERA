package com.wms.defect.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wms.device.infra.mapper.CameraImageMapper;
import com.wms.device.infra.mapper.CameraStatusMapper;
import com.wms.defect.infra.repository.DefectImageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 图片上传主通道（{@code POST /api/v1/defect-images/upload}）的前置校验测试。
 *
 * <p>现场 4.4 万张图走这条通道，但此前只有 Controller 层的形状测试，Service 的校验
 * 语义没有断言。本测试覆盖<b>落盘之前</b>的全部拒绝路径——这些路径一旦被放松，坏数据
 * 会直接进库污染统计与良率口径。</p>
 *
 * <p>不覆盖：逐文件落盘、部分成功语义、缺陷框写入（依赖文件系统与多张表，留待集成测试）。</p>
 */
@ExtendWith(MockitoExtension.class)
class DefectImageUploadValidationTest {

    @Mock
    private DefectImageRepository defectImageRepository;

    @Mock
    private CameraImageMapper cameraImageMapper;

    @Mock
    private CameraStatusMapper cameraStatusMapper;

    @Mock
    private PlatformTransactionManager transactionManager;

    @InjectMocks
    private DefectImageServiceImpl service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String AUTH = "Bearer any-token";
    private static final String DEVICE = "CAM-ZK23-01";

    @BeforeEach
    void setUp() {
        // objectMapper / maxBatchImages 是 @Autowired / @Value 字段，
        // @InjectMocks 不会给默认值——不显式设置会导致 maxBatchImages=0，
        // 让「超过 0 张」抢先触发，掩盖真正的被测分支。
        ReflectionTestUtils.setField(service, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(service, "maxBatchImages", 100);
        ReflectionTestUtils.setField(service, "defectImageDir", System.getProperty("java.io.tmpdir") + "/cds-test-uploads/");
    }

    // ------------------------------------------------------------------
    // 请求头与文件数组
    // ------------------------------------------------------------------

    @Test
    @DisplayName("缺 Authorization → 拒绝")
    void missingAuthorization() {
        assertMessageContains(() -> service.upload(files("a.jpg"), metadata("a.jpg"), null, DEVICE),
                "缺少认证令牌");
    }

    @Test
    @DisplayName("Authorization 不以 Bearer 开头 → 拒绝")
    void nonBearerAuthorization() {
        assertMessageContains(() -> service.upload(files("a.jpg"), metadata("a.jpg"), "Basic abc", DEVICE),
                "缺少认证令牌");
    }

    @Test
    @DisplayName("缺 X-Device-Id → 拒绝")
    void missingDeviceId() {
        assertMessageContains(() -> service.upload(files("a.jpg"), metadata("a.jpg"), AUTH, " "),
                "缺少设备ID");
    }

    @Test
    @DisplayName("files 为空数组 → 拒绝")
    void emptyFiles() {
        assertMessageContains(() -> service.upload(new MultipartFile[0], metadata("a.jpg"), AUTH, DEVICE),
                "files 不能为空");
    }

    @Test
    @DisplayName("files 全为空文件 → 拒绝（size=0 视为未传）")
    void allFilesEmpty() {
        MultipartFile empty = new MockMultipartFile("files", "a.jpg", "image/jpeg", new byte[0]);
        assertMessageContains(() -> service.upload(new MultipartFile[]{empty}, metadata("a.jpg"), AUTH, DEVICE),
                "files 不能为空");
    }

    @Test
    @DisplayName("files 超过 max-batch-images（100）→ 拒绝并提示拆批")
    void tooManyFiles() {
        MultipartFile[] many = new MultipartFile[101];
        for (int i = 0; i < many.length; i++) {
            many[i] = new MockMultipartFile("files", "f" + i + ".jpg", "image/jpeg", new byte[]{1});
        }
        assertMessageContains(() -> service.upload(many, metadata("f0.jpg"), AUTH, DEVICE),
                "单批次最多支持 100 张");
    }

    @Test
    @DisplayName("metadata 为空 → 拒绝")
    void blankMetadata() {
        assertMessageContains(() -> service.upload(files("a.jpg"), "  ", AUTH, DEVICE),
                "metadata 不能为空");
    }

    // ------------------------------------------------------------------
    // metadata 内容校验
    // ------------------------------------------------------------------

    @Test
    @DisplayName("metadata 非法 JSON → 拒绝并提示解析失败")
    void invalidMetadataJson() {
        assertMessageContains(() -> service.upload(files("a.jpg"), "{not-json", AUTH, DEVICE),
                "metadata JSON 解析失败");
    }

    @Test
    @DisplayName("metadata.cameraId 为空 → 拒绝（相机身份是硬要求）")
    void missingCameraId() {
        String json = jsonOf("", "a.jpg");
        assertMessageContains(() -> service.upload(files("a.jpg"), json, AUTH, DEVICE),
                "metadata.cameraId 不能为空");
    }

    @Test
    @DisplayName("metadata.images 为空数组 → 拒绝")
    void emptyImages() {
        String json = "{ \"cameraId\": \"CAM-ZK23-01\", \"images\": [] }";
        assertMessageContains(() -> service.upload(files("a.jpg"), json, AUTH, DEVICE),
                "metadata.images 不能为空");
    }

    @Test
    @DisplayName("metadata 声明的图片数超过上限 → 拒绝")
    void tooManyMetadataImages() {
        StringBuilder sb = new StringBuilder("{ \"cameraId\": \"CAM-ZK23-01\", \"images\": [");
        for (int i = 0; i < 101; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"fileName\":\"f").append(i).append(".jpg\"}");
        }
        sb.append("] }");
        assertMessageContains(() -> service.upload(files("f0.jpg"), sb.toString(), AUTH, DEVICE),
                "单批次最多支持 100 张");
    }

    @Test
    @DisplayName("metadata.images[].fileName 为空 → 拒绝")
    void blankImageFileName() {
        String json = jsonOf("CAM-ZK23-01", " ");
        assertMessageContains(() -> service.upload(files("a.jpg"), json, AUTH, DEVICE),
                "metadata.images[].fileName 不能为空");
    }

    // ------------------------------------------------------------------
    // 文件名映射：metadata 与 multipart 必须一一对应
    // ------------------------------------------------------------------

    @Test
    @DisplayName("metadata 内文件名重复 → 拒绝")
    void duplicateMetadataNames() {
        StringBuilder sb = new StringBuilder("{ \"cameraId\": \"CAM-ZK23-01\", \"images\": [");
        sb.append("{\"fileName\":\"a.jpg\"},{\"fileName\":\"a.jpg\"}] }");
        assertMessageContains(() -> service.upload(files("a.jpg"), sb.toString(), AUTH, DEVICE),
                "存在重复文件名");
    }

    @Test
    @DisplayName("multipart 内文件名重复 → 拒绝")
    void duplicateUploadNames() {
        MultipartFile[] dup = new MultipartFile[]{
                new MockMultipartFile("files", "a.jpg", "image/jpeg", new byte[]{1}),
                new MockMultipartFile("files", "a.jpg", "image/jpeg", new byte[]{2})
        };
        assertMessageContains(() -> service.upload(dup, metadata("a.jpg"), AUTH, DEVICE),
                "multipart files 存在重复文件名");
    }

    @Test
    @DisplayName("metadata 声明了但未上传 → 拒绝并报缺失文件名")
    void missingUploadedFile() {
        String json = "{ \"cameraId\": \"CAM-ZK23-01\", \"images\": [{\"fileName\":\"a.jpg\"},{\"fileName\":\"b.jpg\"}] }";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.upload(files("a.jpg"), json, AUTH, DEVICE));
        assertTrue(ex.getMessage().contains("一一对应"), ex.getMessage());
        assertTrue(ex.getMessage().contains("b.jpg"), "必须指出缺失的文件名: " + ex.getMessage());
    }

    @Test
    @DisplayName("上传了但 metadata 未声明 → 拒绝并报多余文件名")
    void extraUploadedFile() {
        MultipartFile[] two = new MultipartFile[]{
                new MockMultipartFile("files", "a.jpg", "image/jpeg", new byte[]{1}),
                new MockMultipartFile("files", "extra.jpg", "image/jpeg", new byte[]{2})
        };
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.upload(two, metadata("a.jpg"), AUTH, DEVICE));
        assertTrue(ex.getMessage().contains("一一对应"), ex.getMessage());
        assertTrue(ex.getMessage().contains("extra.jpg"), "必须指出多余的文件名: " + ex.getMessage());
    }

    @Test
    @DisplayName("文件名大小写不影响匹配（a.JPG 与 a.jpg 视为同一文件）")
    void fileNameMatchIsCaseInsensitive() {
        MultipartFile[] upper = new MultipartFile[]{
                new MockMultipartFile("files", "A.JPG", "image/jpeg", new byte[]{1})
        };
        // 通过文件名校验后会继续往下（insertBatch 等被 mock 返回 null），
        // 因此这里只断言"没有因文件名不匹配而拒绝"。
        try {
            service.upload(upper, metadata("a.jpg"), AUTH, DEVICE);
        } catch (IllegalArgumentException e) {
            assertFalse(e.getMessage().contains("一一对应"),
                    "大小写不同不应触发文件名不匹配: " + e.getMessage());
        } catch (Exception other) {
            // 落库阶段的其它异常（mock 未打桩）与本用例无关
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private void assertMessageContains(Executable action, String expectedFragment) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, action);
        assertTrue(ex.getMessage() != null && ex.getMessage().contains(expectedFragment),
                "期望消息含「" + expectedFragment + "」，实际：" + ex.getMessage());
    }

    @FunctionalInterface
    private interface Executable extends org.junit.jupiter.api.function.Executable {
    }

    private MultipartFile[] files(String... names) {
        MultipartFile[] arr = new MultipartFile[names.length];
        for (int i = 0; i < names.length; i++) {
            arr[i] = new MockMultipartFile("files", names[i], "image/jpeg", new byte[]{1, 2, 3});
        }
        return arr;
    }

    /** 单图 metadata（可指定 cameraId 与文件名）。 */
    private String metadata(String fileName) {
        return jsonOf(DEVICE, fileName);
    }

    private String jsonOf(String cameraId, String fileName) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("cameraId", cameraId);
        Map<String, Object> img = new LinkedHashMap<>();
        img.put("fileName", fileName);
        root.put("images", Collections.singletonList(img));
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
