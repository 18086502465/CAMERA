package com.wms.task;

import com.wms.entity.*;
import com.wms.mapper.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

/**
 * 数据清理定时任务
 */
@Component
public class DataCleanupTask {

    @Autowired
    private CameraImageMapper cameraImageMapper;

    @Autowired
    private FactProduction30minMapper factProduction30minMapper;

    @Autowired
    private FactProduction30minArchiveMapper factProduction30minArchiveMapper;

    @Autowired
    private SysOperationLogMapper sysOperationLogMapper;

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
     *
     * <p><b>注意</b>：本方法带 {@code @Transactional}，任何一步抛异常会**整体回滚**
     * （含生产数据归档与操作日志清理）。因此修复时必须确保三步都能正常执行。
     */
    @Scheduled(cron = "${app.cleanup.cron:0 0 2 * * ?}")
    @Transactional(rollbackFor = Exception.class)
    public void cleanupExpiredData() {
        LocalDate now = LocalDate.now();
        cleanupExpiredImages(now.minusDays(30));
        archiveAndCleanupProductionData(now.minusMonths(3));
        cleanupOperationLogs(now.minusMonths(6));
    }

    private void cleanupExpiredImages(LocalDate deadline) {
        LocalDateTime deadlineTime = deadline.atTime(LocalTime.MAX);
        LambdaQueryWrapper<CameraImage> wrapper = new LambdaQueryWrapper<>();
        wrapper.le(CameraImage::getCaptureTime, deadlineTime);
        List<CameraImage> expiredImages = cameraImageMapper.selectList(wrapper);

        for (CameraImage img : expiredImages) {
            File file = new File(img.getImagePath());
            if (file.exists()) {
                file.delete();
            }
        }
        cameraImageMapper.delete(wrapper);
    }

    private void archiveAndCleanupProductionData(LocalDate deadline) {
        LambdaQueryWrapper<FactProduction30min> wrapper = new LambdaQueryWrapper<>();
        wrapper.le(FactProduction30min::getBucketDate, deadline);
        List<FactProduction30min> expiredData = factProduction30minMapper.selectList(wrapper);

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
    }

    private void cleanupOperationLogs(LocalDate deadline) {
        Date deadlineDate = Date.from(deadline.atTime(LocalTime.MAX).atZone(ZoneId.systemDefault()).toInstant());
        LambdaQueryWrapper<SysOperationLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.le(SysOperationLog::getOperationTime, deadlineDate);
        sysOperationLogMapper.delete(wrapper);
    }

    private String calculateEndTime(LocalDate bucketDate, Integer bucketIndex) {
        int hour = bucketIndex * 30 / 60;
        int min  = bucketIndex * 30 % 60;
        return String.format("%s %02d:%02d:00", bucketDate.toString(), hour, min);
    }
}
