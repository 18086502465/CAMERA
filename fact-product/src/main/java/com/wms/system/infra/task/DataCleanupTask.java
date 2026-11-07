package com.wms.system.infra.task;

import com.wms.analytics.domain.model.FactProduction30min;
import com.wms.analytics.domain.model.FactProduction30minArchive;
import com.wms.device.domain.model.CameraImage;
import com.wms.iam.domain.model.SysOperationLog;
import com.wms.analytics.infra.mapper.FactProduction30minArchiveMapper;
import com.wms.analytics.infra.mapper.FactProduction30minMapper;
import com.wms.device.infra.mapper.CameraImageMapper;
import com.wms.iam.infra.mapper.SysOperationLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 数据清理定时任务。
 *
 * <p><b>2026-11-07 重构（P0-3，评审见 docs/备份与清理链路评审与优化方案-20261107.md）</b>：
 * <ul>
 *   <li><b>三步拆成三个独立事务</b>：原实现整体 {@code @Transactional}，任何一步抛异常
 *       全部回滚（"操作日志清理失败"连累"图片清理"），故障面被放大。各步现在独立提交、
 *       独立 try/catch，失败只跳过该步并留 ERROR 日志，下一轮自动补齐（三步均幂等）。</li>
 *   <li><b>图片清理改为「先删行、后删文件」</b>：原来先 {@code file.delete()} 再删行且同事务，
 *       删行失败回滚后文件已不可恢复 → 行指向已删文件（裂图）。反过来最坏只留孤儿文件，
 *       由磁盘清理脚本按日期目录最终兜底回收。</li>
 *   <li><b>图片清理分批</b>：原实现一次 {@code selectList} 全量载入内存再逐个删，
 *       表大时 OOM / 长事务；现在按 {@value #IMAGE_BATCH_SIZE} 行/批循环。</li>
 * </ul>
 */
@Component
public class DataCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(DataCleanupTask.class);

    /** 图片清理每批行数 */
    private static final int IMAGE_BATCH_SIZE = 1000;

    @Autowired
    private CameraImageMapper cameraImageMapper;

    @Autowired
    private FactProduction30minMapper factProduction30minMapper;

    @Autowired
    private FactProduction30minArchiveMapper factProduction30minArchiveMapper;

    @Autowired
    private SysOperationLogMapper sysOperationLogMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * 数据清理定时任务。
     *
     * <p>cron 表达式走配置占位符 {@code ${app.cleanup.cron:0 0 2 * * ?}}，
     * 默认仍是每天 02:00。这样做的好处：
     * <ul>
     *   <li>运维可在 application.yml 或环境变量里调整执行时间，**无需重新打包**；</li>
     *   <li>排查问题时可临时改成高频（如 {@code 0 * * * * ?} 每分钟）快速验证清理链路，
     *       验证完再改回 —— 这正是 2026-10-27 定位
     *       {@code Unknown column 'image_id'} 那类问题时最需要的能力。</li>
     * </ul>
     */
    @Scheduled(cron = "${app.cleanup.cron:0 0 2 * * ?}")
    public void cleanupExpiredData() {
        LocalDate now = LocalDate.now();
        runStep("camera_images 清理", () -> cleanupExpiredImages(now.minusDays(30)));
        runStep("30min聚合归档", () -> archiveAndCleanupProductionData(now.minusMonths(3)));
        runStep("操作日志清理", () -> cleanupOperationLogs(now.minusMonths(6)));
    }

    /** 单步执行包装：任何一步失败只跳过该步并留 ERROR 痕迹，不连累其余步骤 */
    private void runStep(String name, Runnable step) {
        try {
            step.run();
        } catch (Exception e) {
            log.error("[数据清理] {} 失败（本轮跳过，下一轮自动重试）: {}", name, e.getMessage(), e);
        }
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    /**
     * camera_images 兼容表清理：先删行（事务内）、后删文件（尽力而为），按批循环。
     *
     * <p>删行失败最坏留孤儿文件（无害，磁盘脚本按 730 天日期目录兜底回收）；
     * 删文件失败只 WARN —— 行已删，该文件成为孤儿，同样由磁盘脚本回收。
     */
    private void cleanupExpiredImages(LocalDate deadline) {
        LocalDateTime deadlineTime = deadline.atTime(LocalTime.MAX);
        int totalRows = 0;
        int failedFiles = 0;
        while (true) {
            // 分批取（只取主键与路径两列）：一次全量 selectList 在表大时会占满内存
            LambdaQueryWrapper<CameraImage> wrapper = new LambdaQueryWrapper<>();
            wrapper.select(CameraImage::getImageId, CameraImage::getImagePath)
                   .le(CameraImage::getCaptureTime, deadlineTime)
                   .last("LIMIT " + IMAGE_BATCH_SIZE);
            List<CameraImage> batch = cameraImageMapper.selectList(wrapper);
            if (batch.isEmpty()) {
                break;
            }
            List<Long> ids = batch.stream().map(CameraImage::getImageId).collect(Collectors.toList());
            Integer deleted = tx().execute(status -> cameraImageMapper.deleteBatchIds(ids));
            if (deleted == null || deleted == 0) {
                // 同一批行删不动还继续循环就是死循环：中止本轮，交给下一轮
                log.error("[数据清理] camera_images 批次删除 0 行（{} 行候选），中止本轮", ids.size());
                break;
            }
            totalRows += deleted;
            for (CameraImage img : batch) {
                File file = new File(img.getImagePath());
                if (file.exists() && !file.delete()) {
                    failedFiles++;
                    log.warn("[数据清理] camera_images 文件删除失败（留待磁盘脚本兜底）: {}",
                            img.getImagePath());
                }
            }
            if (batch.size() < IMAGE_BATCH_SIZE) {
                break;
            }
        }
        log.info("[数据清理] camera_images 清理完成: 删除 {} 行, 文件删除失败 {} 个", totalRows, failedFiles);
    }

    /** 30min 聚合数据归档（>3 个月搬入 archive 表后删源）。量级小（48 行/天/工位），单事务整搬 */
    private void archiveAndCleanupProductionData(LocalDate deadline) {
        tx().executeWithoutResult(status -> {
            LambdaQueryWrapper<FactProduction30min> wrapper = new LambdaQueryWrapper<>();
            wrapper.le(FactProduction30min::getBucketDate, deadline);
            List<FactProduction30min> expiredData = factProduction30minMapper.selectList(wrapper);
            if (expiredData.isEmpty()) {
                return;
            }
            for (FactProduction30min data : expiredData) {
                FactProduction30minArchive archive = new FactProduction30minArchive();
                archive.setLineId(data.getLineId());
                archive.setWorkstationId(data.getWorkstationId());
                archive.setBucketDate(Date.from(data.getBucketDate().atStartOfDay(ZoneId.systemDefault()).toInstant()));
                archive.setBucketIndex(data.getBucketIndex());
                String startTimeStr = data.getBucketDate().toString() + " " +
                        String.format("%02d", data.getBucketIndex() * 30 / 60) + ":" +
                        String.format("%02d", data.getBucketIndex() * 30 % 60) + ":00";
                archive.setStartTime(startTimeStr);
                archive.setEndTime(calculateEndTime(data.getBucketDate(), data.getBucketIndex()));
                archive.setTotalCount(data.getTotalCount());
                archive.setOkCount(data.getOkCount());
                archive.setNgCount(data.getNgCount());
                double yield = (data.getTotalCount() == null || data.getTotalCount() == 0) ? 0.0 :
                        (double) data.getOkCount() / data.getTotalCount();
                archive.setYieldRate(yield);
                archive.setArchiveTime(new Date());
                factProduction30minArchiveMapper.insert(archive);
            }
            factProduction30minMapper.delete(wrapper);
            log.info("[数据清理] 30min聚合归档完成: {} 行", expiredData.size());
        });
    }

    /** 操作日志清理（>6 个月），单事务 */
    private void cleanupOperationLogs(LocalDate deadline) {
        Date deadlineDate = Date.from(deadline.atTime(LocalTime.MAX).atZone(ZoneId.systemDefault()).toInstant());
        LambdaQueryWrapper<SysOperationLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.le(SysOperationLog::getOperationTime, deadlineDate);
        Integer deleted = tx().execute(status -> sysOperationLogMapper.delete(wrapper));
        log.info("[数据清理] 操作日志清理完成: {} 行", deleted == null ? 0 : deleted);
    }

    private String calculateEndTime(LocalDate bucketDate, Integer bucketIndex) {
        int hour = bucketIndex * 30 / 60;
        int min  = bucketIndex * 30 % 60;
        return String.format("%s %02d:%02d:00", bucketDate.toString(), hour, min);
    }
}
