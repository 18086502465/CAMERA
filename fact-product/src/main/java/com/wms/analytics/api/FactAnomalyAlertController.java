package com.wms.analytics.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wms.analytics.domain.model.FactAnomalyAlert;
import com.wms.analytics.app.IFactAnomalyAlertService;
import com.wms.shared.common.ResultVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 异常告警查询/处理。
 *
 * <p><b>v2 P0-1（2026-11-07）：读取端不再被相机告警淹没。</b>
 * 原实现是单一查询 {@code ORDER BY create_time DESC LIMIT 20}，现场实测
 * 85 条未恢复告警里 78 条是同一类 {@code TEMPERATURE_HIGH}，结果 7 条
 * {@code CLEANUP_FAILED}（HIGH）只有 3 条能挤进前 20，其余永久不可见。
 * 现在拆两段查询后再合并：
 * <ul>
 *   <li>运维类告警（{@link #OPS_ALERT_TYPES}）：独立查询、独立窗口，
 *       永远占前排，不与相机业务告警抢名额；</li>
 *   <li>业务告警：级别优先排序（CRITICAL &gt; HIGH &gt; MEDIUM &gt; WARNING &gt; LOW &gt; INFO），
 *       同级再按时间倒序 —— 保证「新的 HIGH」一定排在「旧的 WARNING」前面。</li>
 * </ul>
 * 返回体保持向后兼容：{@code alerts} 仍是全部可见告警（运维类在前），
 * {@code total} 仍是其长度；新增 {@code opsCount} / {@code businessCount} / {@code opsAlerts}。
 */
@RestController
@RequestMapping("/api/alerts")
public class FactAnomalyAlertController {

    /**
     * 运维类告警类型：由脚本（备份 / 清理）写入，与相机业务告警不是同一类问题。
     * 它们低频、高危，一旦产生就必须立刻可见，因此不允许被相机高频告警挤掉。
     *
     * <p>新增脚本告警类型时**必须同步这里**，否则该类型会被当成业务告警按级别排序，
     * 在相机告警密集时重新沉底。
     */
    private static final Set<String> OPS_ALERT_TYPES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "BACKUP_FAILED",
                    "CLEANUP_FAILED",
                    "ARCHIVE_FAILED",
                    "DISK_USAGE_HIGH",
                    // 2026-11-07：容量预测告警（按 REAL 相机实际日增推算可写天数）。
                    // 与 DISK_USAGE_HIGH 分开：那是「已经到 85%」，这是「还能写多少天」——
                    // 扩盘要走采购/停机流程，需要半年提前量，而百分比给不出提前量。
                    "DISK_CAPACITY_LOW",
                    "DISK_FULL")));

    /** 运维类告警最多返回条数（低频，20 条足够覆盖全部历史积压）。 */
    private static final int OPS_LIMIT = 20;

    /** 业务告警最多返回条数（与原行为一致）。 */
    private static final int BUSINESS_LIMIT = 20;

    /**
     * 级别优先排序表达式。
     *
     * <p>{@code FIELD()} 对枚举外的值返回 0，而 0 会排在最前 —— 用
     * {@code NULLIF(...,0)} 把它转成 NULL、再由 {@code COALESCE} 兜到 99（排最后），
     * 避免出现「级别写错的告警反而置顶」。
     */
    private static final String LEVEL_ORDER =
            "COALESCE(NULLIF(FIELD(alert_level,'CRITICAL','HIGH','MEDIUM','WARNING','LOW','INFO'),0),99)";

    @Autowired
    private IFactAnomalyAlertService alertService;

    @GetMapping("/active")
    public ResultVO<Map<String, Object>> getActiveAlerts() {
        List<FactAnomalyAlert> ops = queryUnresolved(true, OPS_LIMIT);
        List<FactAnomalyAlert> business = queryUnresolved(false, BUSINESS_LIMIT);

        List<Map<String, Object>> opsAlerts = ops.stream()
                .map(a -> toMap(a, true)).collect(Collectors.toList());
        List<Map<String, Object>> businessAlerts = business.stream()
                .map(a -> toMap(a, false)).collect(Collectors.toList());

        List<Map<String, Object>> merged = new ArrayList<>(opsAlerts.size() + businessAlerts.size());
        merged.addAll(opsAlerts);
        merged.addAll(businessAlerts);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("alerts", merged);
        result.put("total", merged.size());
        result.put("opsCount", opsAlerts.size());
        result.put("businessCount", businessAlerts.size());
        result.put("opsAlerts", opsAlerts);
        return ResultVO.success(result);
    }

    /**
     * 查未恢复告警。
     *
     * @param opsOnly {@code true} 只查运维类；{@code false} 只查非运维类（含 alert_type 为空的旧数据）
     */
    private List<FactAnomalyAlert> queryUnresolved(boolean opsOnly, int limit) {
        LambdaQueryWrapper<FactAnomalyAlert> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FactAnomalyAlert::getIsResolved, 0);
        if (opsOnly) {
            wrapper.in(FactAnomalyAlert::getAlertType, OPS_ALERT_TYPES);
        } else {
            // 旧数据 alert_type 可能为 NULL（该列是 2026-11 的 04 对齐脚本补的），
            // 不能用 NOT IN 直接过滤 —— SQL 里 NULL NOT IN (...) 恒为 UNKNOWN，会把它们全丢掉。
            wrapper.and(w -> w.isNull(FactAnomalyAlert::getAlertType)
                    .or().notIn(FactAnomalyAlert::getAlertType, OPS_ALERT_TYPES));
        }
        // 用 last() 承载 ORDER BY + LIMIT：LambdaQueryWrapper 无法表达 FIELD() 排序。
        // create_time 为 NULL 的历史行用 alert_id 兜底，保证分页边界稳定。
        wrapper.last("ORDER BY " + LEVEL_ORDER + ", create_time DESC, alert_id DESC LIMIT " + limit);
        return alertService.list(wrapper);
    }

    private Map<String, Object> toMap(FactAnomalyAlert a, boolean ops) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getAlertId());
        m.put("type", a.getAlertType() != null ? a.getAlertType() : "anomaly");
        m.put("level", a.getAlertLevel() != null ? a.getAlertLevel() : "WARNING");
        m.put("title", a.getAlertTitle() != null ? a.getAlertTitle() : "告警");
        m.put("message", a.getAlertMessage() != null ? a.getAlertMessage() : "");
        m.put("lineId", a.getLineId());
        m.put("workstationId", a.getWorkstationId());
        m.put("createTime", a.getCreateTime() != null ? a.getCreateTime().toString() : "");
        m.put("ops", ops);
        return m;
    }

    @PostMapping("/{id}/resolve")
    public ResultVO<String> resolveAlert(@PathVariable Long id) {
        FactAnomalyAlert alert = alertService.getById(id);
        if (alert != null) {
            alert.setIsResolved(1);
            alert.setResolvedAt(LocalDateTime.now());
            alertService.updateById(alert);
            return ResultVO.success("已解决");
        }
        return ResultVO.success("告警不存在");
    }
}
