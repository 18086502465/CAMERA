package com.wms.defect;

import com.wms.defect.app.ArchiveMonthRange;
import com.wms.defect.infra.repository.DefectImageArchiveRepository;
import com.wms.defect.infra.task.DefectImageArchiveTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 图片月度归档（2026-11-05）单测：
 * ① 月份区间计算边界（默认查询绝不碰月表）
 * ② 归档表名白名单（防注入 / 防误 DROP）
 * ③ 归档任务分批、跨月拆分、失败不中断
 */
class DefectImageArchiveTest {

    // ==================== ① ArchiveMonthRange ====================

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 5, 12, 0);
    private static final LocalDateTime CUTOFF = NOW.minusDays(31);   // 2026-10-05

    @Test
    @DisplayName("无时间范围 → 只用主表（默认查询不碰月表）")
    void noRangeMeansHotOnly() {
        assertTrue(ArchiveMonthRange.months(null, null, CUTOFF).isEmpty());
    }

    @Test
    @DisplayName("范围全在热区 → 只用主表")
    void rangeInsideHotZone() {
        assertTrue(ArchiveMonthRange.months(NOW.minusDays(5), NOW, CUTOFF).isEmpty());
    }

    @Test
    @DisplayName("只给热区内结束时间 → 只用主表")
    void onlyHotEndTime() {
        assertTrue(ArchiveMonthRange.months(null, NOW.minusDays(3), CUTOFF).isEmpty());
    }

    @Test
    @DisplayName("范围完全落在历史 → 返回对应月份（升序）")
    void rangeFullyArchived() {
        List<String> months = ArchiveMonthRange.months(
                LocalDateTime.of(2026, 8, 15, 0, 0),
                LocalDateTime.of(2026, 9, 20, 0, 0), CUTOFF);
        assertEquals(Arrays.asList("202608", "202609"), months);
    }

    @Test
    @DisplayName("跨界线 → 区间裁到热区下界，历史部分按月返回")
    void crossBoundaryClamped() {
        List<String> months = ArchiveMonthRange.months(
                LocalDateTime.of(2026, 9, 10, 0, 0), NOW, CUTOFF);
        assertEquals(Arrays.asList("202609", "202610"), months);
    }

    @Test
    @DisplayName("只给历史结束时间 → 向前回看 5 年（封顶 120 个月）")
    void onlyArchivedEndTime() {
        List<String> months = ArchiveMonthRange.months(
                null, LocalDateTime.of(2026, 9, 1, 0, 0), CUTOFF);
        assertEquals(60, months.size());   // 默认回看 5 年 = 60 个月（MAX_MONTHS=120 仅为上限）
        assertEquals("202609", months.get(months.size() - 1));
    }

    @Test
    @DisplayName("反向区间 → 空（只用主表）")
    void reversedRange() {
        assertTrue(ArchiveMonthRange.months(NOW, NOW.minusDays(90), CUTOFF).isEmpty());
    }

    // ==================== ② 表名白名单 ====================

    @Test
    @DisplayName("合法月表名可构造")
    void validTableName() {
        assertEquals("defect_image_file_hist_202610",
                DefectImageArchiveRepository.monthTable("file", "202610"));
        assertEquals("defect_image_box_hist_202610",
                DefectImageArchiveRepository.monthTable("box", "202610"));
    }

    @Test
    @DisplayName("非法表名（注入 / 错拼）直接抛错")
    void invalidTableName() {
        assertThrows(IllegalArgumentException.class,
                () -> DefectImageArchiveRepository.monthTable("file; DROP TABLE x", "202610"));
        assertThrows(IllegalArgumentException.class,
                () -> DefectImageArchiveRepository.monthTable("file", "2026-10"));
        assertThrows(IllegalArgumentException.class,
                () -> DefectImageArchiveRepository.monthTable("hack", "202610"));
    }

    // ==================== ③ 归档任务分批 ====================

    private static Map<String, Object> row(long id, String month) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("imageId", id);
        m.put("month", month);
        return m;
    }

    private static PlatformTransactionManager txManager() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        return manager;
    }

    @Test
    @DisplayName("分批归档：跨月自动拆分，搬完继续下一批直到为空")
    void archiveInBatchesAcrossMonths() {
        DefectImageArchiveRepository repo = mock(DefectImageArchiveRepository.class);
        when(repo.selectExpiredImages(anyInt(), anyInt()))
                // 第一批跨两个月
                .thenReturn(Arrays.asList(row(1L, "202608"), row(2L, "202609"), row(3L, "202609")))
                // 第二批为空 → 结束
                .thenReturn(Collections.emptyList());
        when(repo.selectExpiredBatches(anyInt(), anyInt())).thenReturn(Collections.emptyList());
        when(repo.moveFileRows(anyString(), anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(1)).size());
        when(repo.moveBoxRows(anyString(), anyList())).thenReturn(3);
        when(repo.deleteFileRows(anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());
        when(repo.countHotFiles()).thenReturn(10L);
        when(repo.oldestHotTime()).thenReturn("2026-11-01 00:00:00");
        when(repo.listExpiredMonthTables(anyString(), anyInt())).thenReturn(Collections.emptyList());

        DefectImageArchiveTask task = new DefectImageArchiveTask(repo, txManager());
        Map<String, Object> stat = task.runOnce();

        // 两个月各建表一次
        verify(repo).ensureMonthTables("202608");
        verify(repo).ensureMonthTables("202609");
        // 8 月 1 行、9 月 2 行分别搬移
        verify(repo).moveFileRows(eq("202608"), eq(Collections.singletonList(1L)));
        verify(repo).moveFileRows(eq("202609"), eq(Arrays.asList(2L, 3L)));
        // 删除与搬移同批（先 box 后 file 由实现顺序保证，这里校验都被调用）
        verify(repo).deleteFileRows(Collections.singletonList(1L));
        verify(repo).deleteFileRows(Arrays.asList(2L, 3L));
        assertEquals(3, stat.get("archivedFiles"));
    }

    @Test
    @DisplayName("单批异常不中断任务，其余批次继续")
    void batchFailureDoesNotAbort() {
        DefectImageArchiveRepository repo = mock(DefectImageArchiveRepository.class);
        when(repo.selectExpiredImages(anyInt(), anyInt()))
                .thenReturn(Arrays.asList(row(1L, "202608"), row(2L, "202609")))
                .thenReturn(Collections.emptyList());
        when(repo.selectExpiredBatches(anyInt(), anyInt())).thenReturn(Collections.emptyList());
        // 8 月失败、9 月成功
        when(repo.moveFileRows(eq("202608"), anyList())).thenThrow(new RuntimeException("boom"));
        when(repo.moveFileRows(eq("202609"), anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(1)).size());
        when(repo.deleteFileRows(anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());
        when(repo.countHotFiles()).thenReturn(1L);
        when(repo.oldestHotTime()).thenReturn(null);
        when(repo.listExpiredMonthTables(anyString(), anyInt())).thenReturn(Collections.emptyList());

        DefectImageArchiveTask task = new DefectImageArchiveTask(repo, txManager());
        Map<String, Object> stat = task.runOnce();

        verify(repo).ensureMonthTables("202609");
        verify(repo).deleteFileRows(Collections.singletonList(2L));
        // 8 月批次失败 → 不应删除该批（同事务回滚语义由 TransactionTemplate 保证）
        verify(repo, never()).deleteFileRows(Collections.singletonList(1L));
        assertEquals(1, stat.get("archivedFiles"), "失败的 8 月不应计入归档行数");
        assertTrue(!((List<?>) stat.get("droppedTables")).contains("defect_image_file_hist_202608"));
    }

    @Test
    @DisplayName("无过期数据时不建表、不搬移")
    void nothingToArchive() {
        DefectImageArchiveRepository repo = mock(DefectImageArchiveRepository.class);
        when(repo.selectExpiredImages(anyInt(), anyInt())).thenReturn(Collections.emptyList());
        when(repo.selectExpiredBatches(anyInt(), anyInt())).thenReturn(Collections.emptyList());
        when(repo.countHotFiles()).thenReturn(0L);
        when(repo.oldestHotTime()).thenReturn(null);
        when(repo.listExpiredMonthTables(anyString(), anyInt())).thenReturn(Collections.emptyList());

        DefectImageArchiveTask task = new DefectImageArchiveTask(repo, txManager());
        Map<String, Object> stat = task.runOnce();

        verify(repo, never()).ensureMonthTables(anyString());
        verify(repo, times(0)).moveFileRows(anyString(), anyList());
        assertEquals(0, stat.get("archivedFiles"));
    }
}
