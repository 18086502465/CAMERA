package com.wms.utils;

import com.baomidou.mybatisplus.generator.FastAutoGenerator;
import com.baomidou.mybatisplus.generator.config.OutputFile;
import com.baomidou.mybatisplus.generator.engine.VelocityTemplateEngine;

import java.util.Collections;

/**
 * Mybatis-Plus 代码自动生成器
 * 直接运行main方法，一键生成：Entity、Mapper、Service、Controller 全套代码
 */
public class CodeGenerator {
    public static void main(String[] args) {
        // ======================== 【需要修改的4处配置 - 必改】 ========================
        String url = "jdbc:mysql://localhost:3306/wms-new?serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=utf8";
        String username = "root"; // 你的数据库账号
        // ★ 2026-11-04 安全改造：不再硬编码数据库口令（会随 JAR 分发）。
        //   改为从环境变量读取：MYSQL_PASSWORD=xxx 运行本生成器。
        String password = System.getenv("MYSQL_PASSWORD");
        if (password == null || password.trim().isEmpty()) {
            System.err.println("请先设置环境变量 MYSQL_PASSWORD，例如：");
            System.err.println("  Windows : set MYSQL_PASSWORD=<你的数据库口令>");
            System.err.println("  Linux   : MYSQL_PASSWORD=<你的数据库口令> mvn exec:java ...");
            return;
        }
        String author = "45344"; // 作者名（生成在注释里）

        // ======================== 【生成代码执行核心】 ========================
        FastAutoGenerator.create(url, username, password)
                // 1. 全局配置
                .globalConfig(builder -> {
                    builder.author(author) // 设置作者
//                            .enableSwagger() // 开启swagger文档（可选）
                            .fileOverride() // 覆盖已生成文件
                            .outputDir(System.getProperty("user.dir") + "/src/main/java"); // 指定输出目录
                })
                // 2. 包配置 (生成的代码包路径，对应你的项目结构)
                .packageConfig(builder -> {
                    builder.parent("com.wms") // 父包名
                            .moduleName("") // 模块名，空即可
                            .entity("entity") // 实体类包名
                            .mapper("mapper") // mapper接口包名
                            .service("service") // service接口包名
                            .serviceImpl("service.impl") // service实现类包名
                            .controller("controller") // controller包名
                            .pathInfo(Collections.singletonMap(OutputFile.xml, System.getProperty("user.dir") + "/src/main/resources/mapper")); // mapper.xml文件输出路径
                })
                // 3. 策略配置 (核心：配置你的表名)
                .strategyConfig(builder -> {
                    // 【修改这里】填写你要生成的表名，多个表用逗号分隔，例如："dim_time_bucket_30min","user","order"
                    builder.addInclude("dim_product_brand")
                           // .addTablePrefix("dim_") // 去掉表名前缀：dim_time_bucket_30min → TimeBucket30min
                            // 实体类策略配置
                            .entityBuilder()
                            .enableLombok() // 开启lombok，实体类自动生成get/set
                            .enableTableFieldAnnotation() // 生成字段注解
                            // controller策略配置
                            .controllerBuilder()
                            .enableRestStyle() // 生成RestController风格的控制器
                            .enableHyphenStyle() // url中驼峰转下划线
                            // mapper策略配置
                            .mapperBuilder()
                            .enableMapperAnnotation() // 生成@Mapper注解
                            .build();
                })
                // 4. 模板引擎配置
                .templateEngine(new VelocityTemplateEngine())
                // 执行生成
                .execute();

        System.out.println("=====================================");
        System.out.println("✅ 数据库代码生成成功！所有文件已自动创建");
        System.out.println("=====================================");
    }
}