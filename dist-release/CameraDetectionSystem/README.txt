产线缺陷在线AI检测系统 —— Windows 版发布包
版本：1.0.0
构建时间：2026-10-31 10:24:22

目录说明：
  app\        后端 JAR（Spring Boot 2.7 可执行包，内含前端静态资源）
  config\     外部配置 application.yml（安装后按现场改数据库口令与 jwt.secret）
  scripts\    安装/启停/自检/备份/清理/巡检脚本（PowerShell）
  sql\        建库脚本四段，必须按 01→02→03→04 顺序执行
  service\    Windows 服务包装器（.NET Framework 4.x）
  jre\        内置 Java 8 运行时（无需另装 JDK）
  docs\       部署方案与问题排查手册
  data\       运行数据与相机上传图片
  logs\       应用日志

部署三步：
  1. 双击安装包，安装向导里选择"工作路径"（建议非系统盘）
  2. 用 scripts\init-database.ps1 初始化数据库（需 MySQL 8）
  3. 打开 http://localhost:8091 ，默认账号 admin / admin123（部署后请改）
