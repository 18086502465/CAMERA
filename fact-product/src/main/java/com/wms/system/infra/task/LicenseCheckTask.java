package com.wms.system.infra.task;

import com.wms.shared.license.LicenseState;
import com.wms.system.app.LicenseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 授权巡检：每小时复核一次，状态变化时留痕。
 *
 * <p>为什么还要定时任务：{@link LicenseService#getState()} 虽然每次调用都按
 * 「今天」重算，但**没人访问就没人发现过期**。产线夜里跑着，早上运维来看
 * 日志却发现「昨天就过期了」，等于没有告警。这里每小时落一条，保证
 * 「到期当天」一定有日志可查。
 *
 * <p>只在<b>状态变化</b>时打 INFO/WARN，避免每小时一条把日志刷成背景噪音。
 */
@Component
@Slf4j
public class LicenseCheckTask {

    @Autowired
    private LicenseService licenseService;

    /** 上次记录的状态，用于抑制重复日志。 */
    private volatile String lastLogged;

    /** 每小时 05 分执行（错开 02:00 数据清理与 03:30 图片清理）。 */
    @Scheduled(cron = "0 5 * * * ?")
    public void check() {
        try {
            LicenseState s = licenseService.getState();
            String now = s.getStatus().name();
            if (now.equals(lastLogged)) {
                return;
            }
            lastLogged = now;
            if (s.getStatus().isProblem()) {
                log.warn("授权巡检: status={}, message={}, daysLeft={}, writeBlocked={}",
                        s.getStatus(), s.getMessage(), s.getDaysLeft(), s.isWriteBlocked());
            } else {
                log.info("授权巡检: status={}, message={}, daysLeft={}",
                        s.getStatus(), s.getMessage(), s.getDaysLeft());
            }
        } catch (Exception e) {
            // 巡检自身绝不能影响业务
            log.error("授权巡检异常: {}", e.getMessage(), e);
        }
    }
}
