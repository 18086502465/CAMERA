package com.wms.analytics.infra.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「INSERT 后取 LAST_INSERT_ID()」必须处于事务内的契约测试
 * （2026-11-24 深度评审 H2）。
 *
 * <p>背景：MySQL 的 {@code LAST_INSERT_ID()} 是<b>连接级</b>状态；
 * 无事务时两次 JdbcTemplate 调用各自借还连接，并发下取到别的连接的
 * 自增值 → 看板/组件/聚合任务 ID 串号（组件挂错 dashboardId、任务永久 RUNNING）。
 * 原注释宣称「DataSourceUtils 绑定保证同连接」只在事务内成立 —— 该错误认知已修正。
 *
 * <p>本测试冻结：全仓所有调用 {@code LAST_INSERT_ID()} 的方法
 * （已知集合见 {@link #TRANSACTIONAL_REQUIRED}）必须标注 {@code @Transactional}。
 * 新增同类方法时请同步加入集合 —— 这是机器门禁，防止「靠人记住」再次失效。
 */
class LastInsertIdTransactionContractTest {

    /** INSERT 后取自增 ID 的全部方法（文件:行号见各方法 H2 注释） */
    private static final List<ClassAndMethod> TRANSACTIONAL_REQUIRED = Arrays.asList(
            new ClassAndMethod(AnalyticsAggregationRepository.class, "createAggregationJob"),
            new ClassAndMethod(AnalyticsDashboardRepository.class, "insertDashboard"),
            new ClassAndMethod(AnalyticsDashboardRepository.class, "copyDashboard"),
            new ClassAndMethod(AnalyticsDashboardRepository.class, "insertWidget"),
            new ClassAndMethod(AnalyticsDashboardRepository.class, "insertDefaultDashboard")
    );

    private static final class ClassAndMethod {
        final Class<?> clazz;
        final String method;
        ClassAndMethod(Class<?> clazz, String method) { this.clazz = clazz; this.method = method; }
    }

    @Test
    @DisplayName("所有取 LAST_INSERT_ID 的方法必须 @Transactional（并发下同连接才可靠）")
    void lastInsertIdMethodsMustBeTransactional() {
        for (ClassAndMethod cm : TRANSACTIONAL_REQUIRED) {
            Method found = null;
            for (Method m : cm.clazz.getDeclaredMethods()) {
                if (m.getName().equals(cm.method)) { found = m; break; }
            }
            assertTrue(found != null,
                    cm.clazz.getSimpleName() + "#" + cm.method + " 不存在 —— 方法被改名/删除时请同步维护本契约清单");
            assertTrue(found.isAnnotationPresent(Transactional.class),
                    cm.clazz.getSimpleName() + "#" + cm.method
                            + " 缺少 @Transactional：LAST_INSERT_ID() 是连接级状态，"
                            + "无事务时两次 JdbcTemplate 调用可能取到不同连接 → 并发下 ID 串号");
        }
    }
}
