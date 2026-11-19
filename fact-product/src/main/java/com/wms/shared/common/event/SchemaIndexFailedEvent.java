package com.wms.shared.common.event;

import java.io.Serializable;

/**
 * 启动期建索引失败事件（共享内核 → analytics 域）。
 *
 * <p><b>为什么需要</b>（2026-11-19 评审 P1-1）：
 * 启动期 {@code ensureSchemaFull()} 会幂等补建索引。普通索引建失败无所谓
 * （性能问题，下次发版还能再建）；但<b>唯一索引</b>建失败意味着
 * 「上传幂等的最后一道防线」缺失 —— 设备端重传会**静默产生重复数据**
 * （同一张图重复入库，缺陷框统计被同步放大）。
 *
 * <p><b>问题</b>：原先失败只记一条 WARN 就放弃 ——
 * <b>不重试、无告警</b>，运维从日志里根本不会注意到。
 * 现场实测：2026-11-19 13:38 部署启动时，
 * 唯一索引 {@code uk_defect_file_camera_file_name} 因残留重复数据而建失败，
 * 只留下一条 WARN —— 幂等防线当时实际处于缺失状态。
 *
 * <p><b>为什么在 shared 定义</b>：shared 是共享内核，<b>不得依赖任何业务域</b>
 * （ArchUnit 铁律②）；而发事件的 {@code defect} 域与落库的 {@code analytics} 域
 * 之间不能直接跨域调用（ArchUnit 铁律①：跨域只走 app）——
 * 所以事件类型定义在 shared，由 analytics 域的监听器落 {@code fact_anomaly_alert}。
 *
 * <p>落库时 {@code line_id / workstation_id} 记 0（启动期拿不到业务维度），
 * 与 {@code BACKUP_FAILED} 等运维类告警同一口径。
 */
public class SchemaIndexFailedEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 表名 */
    private final String tableName;
    /** 索引名 */
    private final String indexName;
    /** 是否唯一索引（唯一索引失败 = 幂等防线缺失，严重级别不同） */
    private final boolean uniqueIndex;
    /** 失败原因 */
    private final String reason;

    public SchemaIndexFailedEvent(String tableName, String indexName, boolean uniqueIndex, String reason) {
        this.tableName = tableName;
        this.indexName = indexName;
        this.uniqueIndex = uniqueIndex;
        this.reason = reason;
    }

    public String getTableName() {
        return tableName;
    }

    public String getIndexName() {
        return indexName;
    }

    public boolean isUniqueIndex() {
        return uniqueIndex;
    }

    public String getReason() {
        return reason;
    }
}
