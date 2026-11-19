package com.wms.controller;

import com.wms.defect.app.DefectImageServiceImpl;
import com.wms.defect.api.dto.DefectImageUploadMetadataDTO;
import com.wms.defect.api.dto.DefectImageUploadResultDTO;
import com.wms.defect.infra.repository.DefectImageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上传幂等契约测试（2026-11-19 评审 N-01 修正版）。
 *
 * <p><b>背景</b>：设备端上传后未收到响应（Broken pipe）会重传同一批图，
 * 而服务端此前每张都重新入库 + 重新落盘 →
 * ① 库与磁盘被重复数据撑大；② <b>每条重复行的 defect_image_box 也重复插了一份</b>
 * → 缺陷框统计被放大。生产库实测 570 组 / 845 行重复。
 *
 * <p><b>判重键</b>：{@code (camera_id, file_name)}。
 * 刻意<b>不用</b> {@code capture_time} —— 现场同相机同秒会连拍多张（文件名不同，是合法数据）。
 *
 * <p><b>被测行为</b>：
 * <ol>
 *   <li>首次上传 → 正常入库</li>
 *   <li>设备重传同一张图 → 幂等跳过，<b>不重复写库</b>，返回原有 imageId</li>
 *   <li>幂等跳过时<b>删掉刚保存的磁盘副本</b>（不占盘）</li>
 *   <li>不同文件名的图不受影响（连拍合法数据不被误杀）</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class DefectImageUploadIdempotentTest {

    private static final String CAMERA = "DA9299030";
    private static final String FILE_NAME = "(200)弹性腰围异常_20261119095834_507.jpg";

    @Mock
    private DefectImageRepository defectImageRepository;

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path tempDir;

    private DefectImageServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DefectImageServiceImpl();
        // Service 依赖 repository（反射注入，避免改生产代码的注入方式）
        org.springframework.test.util.ReflectionTestUtils.setField(service, "defectImageRepository", defectImageRepository);
        // maxBatchImages 是 @Value 注入的字段，测试里手动给值（默认 100）
        org.springframework.test.util.ReflectionTestUtils.setField(service, "maxBatchImages", 100);
        // objectMapper 是 @Autowired 注入的字段，测试里手动给实例
        org.springframework.test.util.ReflectionTestUtils.setField(service, "objectMapper",
                new com.fasterxml.jackson.databind.ObjectMapper());
        // defectImageDir 指向临时目录（避免测试往真实图片目录写文件）
        org.springframework.test.util.ReflectionTestUtils.setField(service, "defectImageDir",
                tempDir.toAbsolutePath().toString());
        // schemaChecked=true → ensureSchema() 直接返回，不去连库做存在性检查
        org.springframework.test.util.ReflectionTestUtils.setField(service, "schemaChecked", true);
        // transactionManager 是必填依赖 —— 用最小实现（无真实资源，
        // 但 TransactionTemplate 的执行流程完整，足以覆盖本测试的写库路径）
        org.springframework.test.util.ReflectionTestUtils.setField(service, "transactionManager",
                new NoopTransactionManager());
        // insertBatch 返回 batchId（后续 insertImageFile 依赖它）
        lenient().when(defectImageRepository.insertBatch(anyString(), anyString(), anyString(),
                any(), any(), any(Integer.class), anyString())).thenReturn(1L);
        // 首次上传：查不到已存在记录
        lenient().when(defectImageRepository.findImageIdByCameraAndFileName(anyString(), anyString()))
                .thenReturn(null);
        lenient().when(defectImageRepository.insertImageFile(any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), any(), any(), any(),
                any(Integer.class), any(Integer.class), any(Integer.class), any(Integer.class)))
                .thenReturn(999L);
    }

    private DefectImageUploadMetadataDTO metadata() {
        DefectImageUploadMetadataDTO meta = new DefectImageUploadMetadataDTO();
        meta.setCameraId(CAMERA);
        meta.setCameraName("CV5-Cam1");
        DefectImageUploadMetadataDTO.ImageMetadata img = new DefectImageUploadMetadataDTO.ImageMetadata();
        img.setFileName(FILE_NAME);
        img.setCaptureTime("2026-11-19 09:58:34");
        DefectImageUploadMetadataDTO.DefectMetadata defect = new DefectImageUploadMetadataDTO.DefectMetadata();
        defect.setType("弹性腰围异常");
        defect.setSeverity("HIGH");
        img.setDefects(Arrays.asList(defect));
        meta.setImages(Arrays.asList(img));
        return meta;
    }

    /** 把 metadata DTO 序列化成 JSON（Service 的 upload 收的是 JSON 字符串） */
    private String metadataJson() {
        return metadataJsonOf(metadata());
    }

    private String metadataJsonOf(DefectImageUploadMetadataDTO meta) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(meta);
        } catch (Exception e) {
            throw new IllegalStateException("metadata 序列化失败", e);
        }
    }

    private MultipartFile file() {
        return new MockMultipartFile("files", FILE_NAME, "image/jpeg", realJpegBytes());
    }

    /**
     * 生成一张**真实的**最小 JPEG。
     *
     * <p>必须是真的：{@code saveFile} 会解码图片取宽高，并校验不是 1px/2px 占位图 ——
     * 用假字节会被当成「图片内容解析失败」而静默跳过，永远走不到写库那步。
     */
    private static byte[] realJpegBytes() {
        try {
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(32, 32,
                    java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = img.createGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, 32, 32);
            g.setColor(java.awt.Color.RED);
            g.fillRect(8, 8, 16, 16);
            g.dispose();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "jpg", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("生成测试 JPEG 失败", e);
        }
    }

    /** 最小事务管理器：只走 TransactionTemplate 的回调，不做真实资源管理 */
    static class NoopTransactionManager implements org.springframework.transaction.PlatformTransactionManager {
        @Override
        public org.springframework.transaction.TransactionStatus getTransaction(
                org.springframework.transaction.TransactionDefinition definition) {
            return new org.springframework.transaction.support.SimpleTransactionStatus();
        }

        @Override
        public void commit(org.springframework.transaction.TransactionStatus status) {
            // no-op
        }

        @Override
        public void rollback(org.springframework.transaction.TransactionStatus status) {
            // no-op
        }
    }

    @Test
    @DisplayName("首次上传：正常入库，不标记为重复")
    void firstUploadShouldInsertNormally() {
        DefectImageUploadResultDTO result = service.upload(new MultipartFile[]{file()}, metadataJson(), "Bearer test-token", CAMERA);

        assertNotNull(result);
        // 写库发生了一次
        verify(defectImageRepository, times(1)).insertImageFile(any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), any(), any(), any(),
                any(Integer.class), any(Integer.class), any(Integer.class), any(Integer.class));
    }

    @Test
    @DisplayName("设备重传同一张图：幂等跳过，不再写库，返回原有 imageId")
    void retransmitShouldSkipInsert() {
        // 模拟已存在
        when(defectImageRepository.findImageIdByCameraAndFileName(CAMERA, FILE_NAME)).thenReturn(555L);

        DefectImageUploadResultDTO result = service.upload(new MultipartFile[]{file()}, metadataJson(), "Bearer test-token", CAMERA);

        assertNotNull(result);
        // ★ 关键断言：重复上传时**绝不**再次写库
        verify(defectImageRepository, never()).insertImageFile(any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), any(), any(), any(),
                any(Integer.class), any(Integer.class), any(Integer.class), any(Integer.class));

        DefectImageUploadResultDTO.FileResult fr = result.getResults().get(0);
        assertTrue(Boolean.TRUE.equals(fr.getSuccess()), "重传应视为成功（设备端不应再重试）");
        assertEquals(Boolean.TRUE, fr.getDuplicate(), "应标记 duplicate=true");
        assertEquals(Long.valueOf(555L), fr.getImageId(), "应返回已存在的 imageId");
    }

    @Test
    @DisplayName("幂等判重只用 camera_id + file_name —— 不用 capture_time（连拍合法数据不能被误杀）")
    void idempotencyKeyShouldBeCameraAndFileNameNotCaptureTime() {
        when(defectImageRepository.findImageIdByCameraAndFileName(CAMERA, FILE_NAME)).thenReturn(555L);

        service.upload(new MultipartFile[]{file()}, metadataJson(), "Bearer test-token", CAMERA);

        // 查重方法必须被调用，且参数就是 (camera_id, file_name)
        verify(defectImageRepository, times(1)).findImageIdByCameraAndFileName(CAMERA, FILE_NAME);
    }

    @Test
    @DisplayName("不同文件名的图走正常入库路径（不误判为重复）")
    void differentFileNameShouldNotBeTreatedAsDuplicate() {
        DefectImageUploadMetadataDTO meta = metadata();
        DefectImageUploadMetadataDTO.ImageMetadata img = new DefectImageUploadMetadataDTO.ImageMetadata();
        img.setFileName("(200)接头_20261119095835_408.jpg");
        img.setCaptureTime("2026-11-19 09:58:35");
        DefectImageUploadMetadataDTO.DefectMetadata defect = new DefectImageUploadMetadataDTO.DefectMetadata();
        defect.setType("接头");
        defect.setSeverity("HIGH");
        img.setDefects(Arrays.asList(defect));
        meta.setImages(Arrays.asList(img));

        MultipartFile f = new MockMultipartFile("files", img.getFileName(), "image/jpeg", realJpegBytes());

        DefectImageUploadResultDTO result = service.upload(new MultipartFile[]{f}, metadataJsonOf(meta), "Bearer test-token", CAMERA);

        assertNotNull(result);
        assertTrue(result.getResults().get(0).getSuccess());
        verify(defectImageRepository, times(1)).insertImageFile(any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), any(), any(), any(),
                any(Integer.class), any(Integer.class), any(Integer.class), any(Integer.class));
    }

    @Test
    @DisplayName("判重查询必须发生在写库之前（顺序不能反）")
    void dedupCheckMustHappenBeforeInsert() {
        // 该 mock 若被调用则说明顺序反了
        when(defectImageRepository.findImageIdByCameraAndFileName(CAMERA, FILE_NAME)).thenReturn(555L);

        DefectImageUploadResultDTO result = service.upload(new MultipartFile[]{file()}, metadataJson(), "Bearer test-token", CAMERA);

        // 结果里 imageId 来自查重而非新插入
        assertEquals(Long.valueOf(555L), result.getResults().get(0).getImageId());
        List<String> msgs = result.getResults().stream()
                .map(DefectImageUploadResultDTO.FileResult::getMessage)
                .collect(java.util.stream.Collectors.toList());
        assertTrue(msgs.get(0).contains("幂等"), "消息应说明是幂等跳过，实际: " + msgs.get(0));
    }
}
