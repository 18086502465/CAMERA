package com.wms.system;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.wms.device.domain.model.CameraImage;
import com.wms.device.infra.mapper.CameraImageMapper;
import com.wms.system.infra.task.DataCleanupTask;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 裂图回归测试（2026-11-07 v3）。
 *
 * <p>锁住的语义只有一条，但它是不可回退的：<b>{@code camera_images} 的 30 天清理
 * 只删数据库行，绝不能删磁盘文件</b>。
 *
 * <p>为什么：现场实测 {@code camera_images} 与 {@code defect_image_file} 的
 * {@code image_path} 100% 重叠（9696/9696），同一个 jpg 被两张表引用。而
 * {@code defect_image_file} 的记录要留到 730 天（归档任务搬月表 → 月表 730 天 DROP），
 * {@code camera_images} 只留 30 天。若这里删文件，30~730 天之间的历史记录会全部
 * 变成「有记录无文件」，页面查看/导出/追溯一律 404。
 *
 * <p>测试用 Mockito 替身喂数据、H2 提供真实事务管理器（{@code tx()} 内部 new
 * {@code TransactionTemplate}，没法外部注入，所以要走真实事务）。
 */
class DataCleanupTaskImageTest {

    private CameraImageMapper mapper;
    private DataCleanupTask task;
    private File imageFile;

    /**
     * {@code LambdaQueryWrapper.select(CameraImage::getImageId)} 需要 MyBatis-Plus 的
     * 实体元数据缓存（通常由 MyBatis 启动时构建）。纯单测没有容器，必须手动初始化，
     * 否则抛 {@code can not find lambda cache for this entity}。
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, CameraImage.class);
    }

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl("jdbc:h2:mem:cleanupimg;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false");
        ds.setDriverClassName("org.h2.Driver");

        mapper = mock(CameraImageMapper.class);
        task = new DataCleanupTask();
        ReflectionTestUtils.setField(task, "cameraImageMapper", mapper);
        ReflectionTestUtils.setField(task, "transactionManager", new DataSourceTransactionManager(ds));

        imageFile = File.createTempFile("defect-img-", ".jpg");
        Files.write(imageFile.toPath(), "fake-jpeg".getBytes(StandardCharsets.UTF_8));
    }

    private CameraImage row(long id, String path) {
        CameraImage img = new CameraImage();
        img.setImageId(id);
        img.setImagePath(path);
        return img;
    }

    @SuppressWarnings("unchecked")
    private void feed(List<CameraImage> batch) {
        // 第一次返回这批，第二次返回空 —— 让 while 循环自然结束
        when(mapper.selectList(any())).thenReturn(batch).thenReturn(Collections.<CameraImage>emptyList());
        when(mapper.deleteBatchIds(anyList())).thenReturn(batch.size());
    }

    @Test
    @DisplayName("超期行被删除，但磁盘文件必须保留（防裂图）")
    void expiredRowsDeletedButFileKept() {
        assertTrue(imageFile.exists(), "前置条件：测试文件存在");

        List<CameraImage> batch = new ArrayList<CameraImage>();
        batch.add(row(1L, imageFile.getAbsolutePath()));
        feed(batch);

        ReflectionTestUtils.invokeMethod(task, "cleanupExpiredImages", LocalDate.now().minusDays(30));

        verify(mapper, times(1)).deleteBatchIds(anyList());
        assertTrue(imageFile.exists(),
                "文件被删了 —— 这正是 2026-12-03 起批量裂图的成因。" +
                        "camera_images 与 defect_image_file 共用 image_path，记录要留 730 天。");
        imageFile.delete();
    }

    @Test
    @DisplayName("文件已不存在时清理仍正常完成（幂等）")
    void missingFileDoesNotBreakCleanup() {
        File gone = new File(imageFile.getAbsolutePath() + ".not-exist");
        assertFalse(gone.exists());

        List<CameraImage> batch = new ArrayList<CameraImage>();
        batch.add(row(2L, gone.getAbsolutePath()));
        feed(batch);

        // 不抛异常即为通过：旧实现里 file.exists() 为 false 会直接跳过，现在是根本不碰文件系统
        ReflectionTestUtils.invokeMethod(task, "cleanupExpiredImages", LocalDate.now().minusDays(30));

        verify(mapper, times(1)).deleteBatchIds(anyList());
        imageFile.delete();
    }

    @Test
    @DisplayName("空表时不发删除语句")
    void emptyTableDoesNothing() {
        when(mapper.selectList(any())).thenReturn(Collections.<CameraImage>emptyList());

        ReflectionTestUtils.invokeMethod(task, "cleanupExpiredImages", LocalDate.now().minusDays(30));

        verify(mapper, times(0)).deleteBatchIds(anyList());
        imageFile.delete();
    }
}
