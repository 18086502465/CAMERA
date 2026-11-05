package com.wms;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 项目启动类
 * @MapperScan 扫描所有Mapper接口的包，必须配置
 */
@SpringBootApplication
@MapperScan({
        "com.wms.iam.infra.mapper",
        "com.wms.masterdata.infra.mapper",
        "com.wms.device.infra.mapper",
        "com.wms.defect.infra.mapper",
        "com.wms.inspection.infra.mapper",
        "com.wms.analytics.infra.mapper",
        "com.wms.system.infra.mapper"
}) // DDD 拆分：按域扫描 mapper // 关键注解：扫描mapper接口包
@EnableScheduling // 开启定时任务
public class TimeBucketApplication {
    public static void main(String[] args) {
        SpringApplication.run(TimeBucketApplication.class, args);
    }
}