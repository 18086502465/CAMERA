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
@MapperScan("com.wms.mapper") // 关键注解：扫描mapper接口包
@EnableScheduling // 开启定时任务
public class TimeBucketApplication {
    public static void main(String[] args) {
        SpringApplication.run(TimeBucketApplication.class, args);
    }
}