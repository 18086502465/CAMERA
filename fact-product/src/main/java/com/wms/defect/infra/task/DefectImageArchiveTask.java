package com.wms.defect.infra.task;

import com.wms.defect.infra.repository.DefectImageArchiveRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 缺陷图片月度归档任务（2026-11-05 新增）。
 *
 * <p><b>职责</b>：把主表 {@code defect_image_file/_box/_batch} 中超过
 * {@code app.archive.images.hot-days}（默认 31）天的数据，按月移入
 * {@code defect_image_*_hist_YYYYMM} 月表，使主表体积恒定、页面默认查询
 * 永远是「近一个月」的毫秒级响应。磁盘图片文件<b>不动</b>（保留 2 年，
 * 由 cleanup-defect-images.sh 按日期目录管理）。</p>
 *
 * <p><b>与既有归档先例的差别</b>：{@code DataCleanupTask#archiveAndCleanupProductionData}
 * 是逐行 Java 查询 + insert（30 分钟聚合表 48 行/天/工位，量级小）；
 * 图片表是 10 万行/天级，这里用 {@code INSERT…SELECT} 按批（默认 5000 行）搬移，
 * 每批一个独立事务 —— 避免长事务锁表，失败只回滚该批并继续下一批（幂等可重跑）。</p>
 *
 * <p><b>顺序保证</b>：先 box（子）后 file（父）搬移与删除；批次表在无子图后才搬。
 * 若中途失败，下一轮按主键重查自然补齐，不会产生孤儿（唯一风险是本轮多留一行，
 * 下轮继续处理）。</p>
 */
@Component
public class DefectImageArchiveTask {

    private static final Logger log = LoggerFactory.getLogger(DefectImageArchiveTask.class);

    private final DefectImageArchiveRepository archiveRepository;
    private final TransactionTemplate transactionTemplate;

    /** 主表保留天数 */
    @Value("${app.archive.images.hot-days:31}")
    private int hotDays;

    /** 月表保留天数（超期 DROP，磁盘文件由清理脚本按同一口径删除） */
    @Value("${app.archive.images.retain-days:730}")
    private int retainDays;

    /** 每批行数 */
    @Value("${app.archive.images.batch-size:5000}")
    private int batchSize;

    public DefectImageArchiveTask(DefectImageArchiveRepository archiveRepository,
                                 PlatformTransactionManager transactionManager) {
        this.archiveRepository = archiveRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** 每日 02:30（错开 02:00 的 DataCleanupTask） */
    @Scheduled(cron = "${app.archive.images.cron:0 30 2 * * ?}")
    public void scheduledArchive() {
        try {
            Map<String, Object> stat = runOnce();
            log.info("[图片归档] 完成: {}", stat);
        } catch (Exception e) {
            // 归档失败不影响服务，仅告警；主表会在下一轮继续累积并重试
            log.error("[图片归档] 本轮执行异常（不影响主流程，下一轮重试）: {}", e.getMessage(), e);
        }
    }

    /**
     * 执行一轮归档（供定时任务与管理员手动触发共用）。
     *
     * @return 统计信息（归档行数 / 批次 / 超期表）
     */
    public Map<String, Object> runOnce() {
        long started = System.currentTimeMillis();
        Map<String, Object> stat = new LinkedHashMap<>();
        stat.put("hotDays", hotDays);
        stat.put("batchSize", batchSize);

        int archivedFiles = archiveImages();
        int archivedBatches = archiveBatches();
        List<String> droppedTables = dropExpiredMonths();

        stat.put("archivedFiles", archivedFiles);
        stat.put("archivedBatches", archivedBatches);
        stat.put("droppedTables", droppedTables);
        stat.put("hotFilesLeft", archiveRepository.countHotFiles());
        stat.put("hotOldestTime", archiveRepository.oldestHotTime());
        stat.put("costMs", System.currentTimeMillis() - started);

        // 健康度：主表最老数据超过 hotDays+30 天说明归档连续落后，需要人工介入
        warnIfArchiveLagging();
        return stat;
    }

    /** 图片 + 缺陷框：按批（默认 5000 行）搬移，跨月自动拆分 */
    private int archiveImages() {
        int total = 0;
        int round = 0;
        while (true) {
            List<Map<String, Object>> batch = archiveRepository.selectExpiredImages(batchSize, hotDays);
            if (batch.isEmpty()) {
                break;
            }
            // 按归属月份分组（同一批可能跨月，各自写入对应月表）
            Map<String, List<Long>> byMonth = batch.stream().collect(Collectors.groupingBy(
                    r -> (String) r.get("month"),
                    LinkedHashMap::new,
                    Collectors.mapping(r -> (Long) r.get("imageId"), Collectors.toList())));

            int archivedThisBatch = 0;
            for (Map.Entry<String, List<Long>> entry : byMonth.entrySet()) {
                String month = entry.getKey();
                List<Long> ids = entry.getValue();
                try {
                    // DDL 放在事务外（MySQL DDL 隐式提交，避免与业务事务互相影响）
                    archiveRepository.ensureMonthTables(month);
                    Integer moved = transactionTemplate.execute(status -> {
                        archiveRepository.moveBoxRows(month, ids);
                        archiveRepository.moveFileRows(month, ids);
                        archiveRepository.deleteBoxRows(ids);
                        return archiveRepository.deleteFileRows(ids);
                    });
                    archivedThisBatch += moved == null ? 0 : moved;
                } catch (Exception e) {
                    log.error("[图片归档] 批次失败（month={}, rows={}，下一轮重试）: {}",
                            month, ids.size(), e.getMessage(), e);
                }
            }
            total += archivedThisBatch;
            round++;
            log.info("[图片归档] 第 {} 批: 候选 {} 行, 归档 {} 行, 累计 {}",
                    round, batch.size(), archivedThisBatch, total);
            if (archivedThisBatch == 0) {
                // 全部失败时防止死循环：交给下一轮
                log.warn("[图片归档] 本批全部失败，中止本轮（下一轮重试）");
                break;
            }
        }
        return total;
    }

    /** 批次表：无子图且超期才搬（保住 file.batch_id 引用完整性） */
    private int archiveBatches() {
        int total = 0;
        while (true) {
            List<Map<String, Object>> batch = archiveRepository.selectExpiredBatches(batchSize, hotDays);
            if (batch.isEmpty()) {
                break;
            }
            Map<String, List<Long>> byMonth = batch.stream().collect(Collectors.groupingBy(
                    r -> (String) r.get("month"),
                    LinkedHashMap::new,
                    Collectors.mapping(r -> (Long) r.get("batchId"), Collectors.toList())));
            int movedThisBatch = 0;
            for (Map.Entry<String, List<Long>> entry : byMonth.entrySet()) {
                try {
                    archiveRepository.ensureMonthTables(entry.getKey());
                    Integer moved = transactionTemplate.execute(status -> {
                        int n = archiveRepository.moveBatchRows(entry.getKey(), entry.getValue());
                        archiveRepository.deleteBatchRows(entry.getValue());
                        return n;
                    });
                    movedThisBatch += moved == null ? 0 : moved;
                } catch (Exception e) {
                    log.error("[图片归档] 批次表搬移失败（month={}, rows={}，下一轮重试）: {}",
                            entry.getKey(), entry.getValue().size(), e.getMessage(), e);
                }
            }
            total += movedThisBatch;
            if (movedThisBatch == 0) {
                break;
            }
        }
        return total;
    }

    /** 超过保留期（默认 730 天）的整月表 DROP；磁盘文件由清理脚本按同一口径删除 */
    private List<String> dropExpiredMonths() {
        List<String> dropped = new ArrayList<>();
        for (String kind : new String[]{"file", "box", "batch"}) {
            for (String table : archiveRepository.listExpiredMonthTables(kind, retainDays)) {
                try {
                    archiveRepository.dropMonthTable(table);
                    dropped.add(table);
                    log.info("[图片归档] 超期月表已删除: {}", table);
                } catch (Exception e) {
                    log.error("[图片归档] 删除超期月表失败: {} - {}", table, e.getMessage());
                }
            }
        }
        return dropped;
    }

    private void warnIfArchiveLagging() {
        try {
            String oldest = archiveRepository.oldestHotTime();
            if (oldest == null) {
                return;
            }
            long days = java.time.Duration.between(
                    java.time.LocalDateTime.parse(oldest.replace(' ', 'T')),
                    java.time.LocalDateTime.now()).toDays();
            if (days > hotDays + 30) {
                log.error("[图片归档] 主表最早数据已 {} 天（阈值 {} 天）—— 归档连续落后，请检查任务日志",
                        days, hotDays);
            }
        } catch (Exception e) {
            log.warn("[图片归档] 健康度检查失败: {}", e.getMessage());
        }
    }
}
