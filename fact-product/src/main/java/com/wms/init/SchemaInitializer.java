package com.wms.init;

import com.wms.analytics.infra.repository.AnalyticsAggregationRepository;
import com.wms.defect.infra.repository.DefectImageRepository;
import com.wms.system.infra.repository.SystemSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * DDD P5：建表 / 补列 / 补索引的统一启动期入口（Schema 收敛）。
 *
 * <p>此前散布在三处：</p>
 * <ul>
 *   <li>system：{@code SystemSettingsController#@PostConstruct}（建设置表 + 种子数据 + 修复 + 保留策略）；</li>
 *   <li>defect：{@code DefectImageServiceImpl#@PostConstruct}（建图片三表 + 补 5 列 + 补索引）；</li>
 *   <li>analytics：请求路径惰性建表（聚合配置表 / 两张日聚合表 / 聚合任务表）。</li>
 * </ul>
 *
 * <p>收敛原则：</p>
 * <ul>
 *   <li><b>SQL 文本不搬家</b> —— 仍按表所属域留在各域 Repository（P3 铁律 4），
 *       本类只做统一调度与失败告警；</li>
 *   <li><b>INFORMATION_SCHEMA 判断语义保留</b> —— 各 repository 的 ensure* 方法内部
 *       均为「表在则不执行 DDL」，幂等可重入；</li>
 *   <li><b>失败不阻断启动</b> —— 与原行为一致（原来请求路径也会再次尝试并抛错，
 *       现在启动日志 ERROR 告警 + 请求路径守卫仍在，请求时行为不变）；</li>
 *   <li><b>请求路径守卫保留</b> —— 缺陷图片通道的限频自愈（5 分钟缓存）、
 *       系统设置页的 ensureSettingTable 幂等守卫均不动，表运行期被删仍可自愈。</li>
 * </ul>
 */
@Component
public class SchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    @Autowired
    private SystemSettingsRepository systemSettingsRepository;

    @Autowired
    private DefectImageRepository defectImageRepository;

    @Autowired
    private AnalyticsAggregationRepository analyticsAggregationRepository;

    /**
     * 启动期按域依次保证表结构就绪。顺序无依赖（各 ensure 幂等），
     * 仅按「设置 → 缺陷图片 → 统计聚合」的业务链排列便于阅读日志。
     */
    @PostConstruct
    public void initialize() {
        ensureSystem();
        ensureDefect();
        ensureAnalytics();
    }

    /** 原样搬运自 SystemSettingsController#@PostConstruct init()（2026-11-05 P5 收敛） */
    private void ensureSystem() {
        try {
            systemSettingsRepository.ensureSettingTable();
            systemSettingsRepository.seedDefaultSettings();
            systemSettingsRepository.repairCorruptedDefaultSettings();
            systemSettingsRepository.seedDefaultRetentionPolicies();
        } catch (Exception e) {
            log.error("[SchemaInitializer] system 域表结构/种子数据保证失败（服务继续启动，设置页请求时仍会重试）: {}",
                    e.getMessage(), e);
        }
    }

    /** 原样搬运自 DefectImageServiceImpl#@PostConstruct init() 强制完整检查（2026-11-05 P5 收敛） */
    private void ensureDefect() {
        try {
            defectImageRepository.ensureSchemaFull();
        } catch (Exception e) {
            log.error("[SchemaInitializer] defect 域表结构保证失败（服务继续启动，上传通道限频自愈守卫仍在）: {}",
                    e.getMessage(), e);
        }
    }

    /** analytics 三组聚合表（原请求路径惰性建表改为启动期执行，repository 内部幂等） */
    private void ensureAnalytics() {
        try {
            analyticsAggregationRepository.ensureAggregationScheduleTable();
            analyticsAggregationRepository.ensureDailyAggregationTables();
            analyticsAggregationRepository.ensureAggregationJobTable();
        } catch (Exception e) {
            log.error("[SchemaInitializer] analytics 域聚合表保证失败（服务继续启动，聚合任务会自行重试建表）: {}",
                    e.getMessage(), e);
        }
    }
}
