package com.wms.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DDD 架构守护（方案 C6 已确认）：固化六域依赖铁律，防止重构成果回退。
 *
 * <p>三条铁律（与《重构方案-后端DDD拆分-20261104.md》一致）：</p>
 * <ol>
 *   <li><b>跨域只走 app 接口</b> —— 业务域类不得依赖其他域的 {@code infra} 层
 *       （Repository/Mapper 是各域私有实现）。存量 5 处已冻结在 FROZEN_CROSS_DOMAIN，
 *       <b>只允许清零、不允许新增</b>：任何新违规直接测试失败。</li>
 *   <li><b>shared 零业务依赖</b> —— 共享内核不得反向依赖任何业务域。</li>
 *   <li><b>domain/model 纯净</b> —— 实体层不得依赖 api/app/infra 层。</li>
 * </ol>
 *
 * <p>设计上的合法例外（不在任何规则约束内）：</p>
 * <ul>
 *   <li>{@code com.wms.init.SchemaInitializer} —— P5 统一启动期建表入口，
 *       需要跨域调度各域 Repository；</li>
 *   <li>{@code com.wms.TimeBucketApplication} —— 装配根，@MapperScan 需要列举各域。</li>
 * </ul>
 */
@AnalyzeClasses(packages = "com.wms", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final List<String> DOMAINS = Arrays.asList(
            "iam", "masterdata", "device", "defect", "inspection", "analytics", "system");

    /**
     * 跨域 infra 存量违规冻结清单（P6 后续任务：接口化清零后逐项移除）。
     * 格式 = "违规类全名 -> 被依赖类全名"。
     */
    private static final Set<String> FROZEN_CROSS_DOMAIN = new HashSet<>(Arrays.asList(
            "com.wms.defect.app.DefectImageServiceImpl -> com.wms.device.infra.mapper.CameraImageMapper",
            "com.wms.defect.app.DefectImageServiceImpl -> com.wms.device.infra.mapper.CameraStatusMapper",
            "com.wms.analytics.api.CameraDashboardController -> com.wms.device.infra.mapper.CameraStatusMapper",
            "com.wms.system.api.ConfigImportController -> com.wms.device.infra.mapper.CameraStatusMapper",
            "com.wms.system.api.ConfigImportController -> com.wms.masterdata.infra.mapper.DimFactoryMapper",
            "com.wms.system.api.ConfigImportController -> com.wms.masterdata.infra.mapper.DimProductionLineMapper",
            "com.wms.system.api.ConfigImportController -> com.wms.masterdata.infra.mapper.DimWorkstationMapper",
            "com.wms.system.infra.task.DataCleanupTask -> com.wms.analytics.infra.mapper.FactProduction30minMapper",
            "com.wms.system.infra.task.DataCleanupTask -> com.wms.analytics.infra.mapper.FactProduction30minArchiveMapper",
            "com.wms.system.infra.task.DataCleanupTask -> com.wms.device.infra.mapper.CameraImageMapper",
            "com.wms.system.infra.task.DataCleanupTask -> com.wms.device.infra.mapper.CameraStatusMapper",
            "com.wms.system.infra.task.DataCleanupTask -> com.wms.iam.infra.mapper.SysOperationLogMapper",
            "com.wms.system.infra.task.InitTimeBucketRunner -> com.wms.analytics.infra.mapper.DimTimeBucket30minMapper"));

    private static String domainOf(String packageName) {
        for (String d : DOMAINS) {
            if (packageName.equals("com.wms." + d) || packageName.startsWith("com.wms." + d + ".")) {
                return d;
            }
        }
        return null;
    }

    /** 铁律①：跨域不得依赖他人 infra（冻结存量，只许清零不许新增） */
    @ArchTest
    static void crossDomainMustNotDependOnOtherDomainInfra(JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        for (JavaClass clazz : classes) {
            String fromDom = domainOf(clazz.getPackageName());
            if (fromDom == null) {
                continue;   // init / 装配根等设计例外
            }
            clazz.getDirectDependenciesFromSelf().forEach(dep -> {
                JavaClass target = dep.getTargetClass();
                String toDom = domainOf(target.getPackageName());
                if (toDom != null && !toDom.equals(fromDom)
                        && target.getPackageName().contains(".infra.")) {
                    String key = clazz.getName() + " -> " + target.getName();
                    if (!FROZEN_CROSS_DOMAIN.contains(key)) {
                        violations.add(key + "（位置: " + dep.getDescription() + "）");
                    }
                }
            });
        }
        assertTrue(violations.isEmpty(),
                "发现新增的跨域 infra 依赖（铁律①：跨域只走 app 接口，禁止新增）:\n  "
                        + String.join("\n  ", violations));
    }

    /** 铁律②：shared 共享内核零业务域依赖 */
    @ArchTest
    static final ArchRule sharedMustNotDependOnDomains =
            ArchRuleDefinition.noClasses()
                    .that().resideInAPackage("com.wms.shared..")
                    .should().dependOnClassesThat(new DescribedPredicate<JavaClass>(" reside in a business domain") {
                        @Override
                        public boolean test(JavaClass input) {
                            return domainOf(input.getPackageName()) != null;
                        }
                    })
                    .because("共享内核是所有域的底座，反向依赖会造成循环（铁律②）");

    /** 铁律③：domain/model 实体层纯净，不依赖 api/app/infra */
    @ArchTest
    static final ArchRule domainModelMustStayPure =
            ArchRuleDefinition.noClasses()
                    .that().resideInAPackage("..domain.model..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..api..", "..app..", "..infra..")
                    .because("实体是领域的被动模型，依赖实现层会造成反向耦合（铁律③）");
}
