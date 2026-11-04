# 服务器部署方案 —— Ubuntu 24.04 LTS（兼容 22.04）

> 目标：在 Ubuntu 服务器上完成**安装、运行、验收、升级、回滚**全流程。
> 适用：产线缺陷在线AI检测系统 v1.0.0（Spring Boot 2.7 + Vue 3 + MySQL 8）
> 配套：Windows 版方案 `docs/部署方案-精简版.md` ｜ 项目现状 `docs/项目现状梳理与服务器部署方案-20261024.md`
> **运维排查：`docs/服务器运行与问题排查手册.md`**

> **✅ 已在真实服务器完成部署验证**：`10.2.77.72`（Hik-Vision）。
> **该机实际系统是 Ubuntu 24.04.5 LTS**（不是 22.04）—— 本方案已按 **24.04 为基准**编写并实测。
> OpenJDK 1.8.0_504 / MySQL 8.0.46 / systemd 255 / 16 GB 内存。
> 从零部署 → 建库 → 启动 → 自检 **21 项全过**；首轮暴露的 5 个真实缺陷已修复，详见**附录 B**。

> **数据库账号**：本方案按现场要求使用 **`root` / `<数据库口令>`**（该机 root 的认证插件是
> `mysql_native_password`，密码直连可用，**无需**改 `auth_socket`）。
> 若换到其他机器，root 可能是 `auth_socket`（只认操作系统身份、不认密码），
> 需按 §3.2 改用专用账号。

---

## 0. 一页速览

```
构建机                        Ubuntu 服务器
─────────────────────        ─────────────────────────────────────────
① npm run build       ──┐
② 拷 dist → static     ├──→ app/time-bucket-demo.jar
③ mvn package         ──┘        ↓
④ mvn test                  tar 打包 ──────→  scp 传包
⑤ 组装发布目录                                    ↓
⑥ 拷 4 个建库脚本                      解压 → ./install.sh
⑦ 拷 Linux 脚本 + systemd unit              ↓
⑧ tar.gz                          建账号 cdsys → 建目录 → 装服务
                                          ↓
                              建库（4 段脚本）→ 改口令 → 启服务 → 自检
```

**服务端只需做 5 件事**：装 MySQL → 解压安装 → 建库 → 改口令/配置 → 跑自检。

> ⚠️ **顺序很重要**：必须**先建库、后启动应用**。
> 应用启动时有 3 处 `@PostConstruct` 会建表/加列，数据库不存在时会直接崩溃。
> `install.sh` 已在检测到数据库缺失时给出明确指引并以退出码 1 结束。

### 目录规划（安装后）

| 路径 | 内容 | 属主 |
|---|---|---|
| `/opt/camera-detection-system/` | 程序（jar / 配置 / 脚本 / 建库SQL） | `cdsys`（只读） |
| `/var/lib/camera-detection-system/` | 运行数据（`logs/`、`data/uploads/`） | `cdsys`（可写） |
| `/etc/default/camera-detection` | **所有可变参数集中在这里** | root（640） |
| `/etc/systemd/system/camera-detection.service` | systemd 服务单元 | root |

> 选 `/var/lib/` 放数据而非 `/opt/` 下的原因：图片与日志会持续增长，需要与程序分离，便于单独挂盘或做清理策略。

---

## 1. 环境要求与改造点

### 1.1 服务器要求

| 项 | 要求 | 说明 |
|---|---|---|
| 操作系统 | Ubuntu 24.04 LTS (noble) x86_64 ｜ 22.04 LTS 亦可 | **实测机为 24.04.5** |
| CPU / 内存 | 2 核 / 4 GB 起 | 堆内存见 §1.3 |
| 磁盘 | ≥ 20 GB | 图片按每天 1000 张 × 10 MB 估算保留期 |
| Java | `openjdk-8-jre-headless` | **已实测 JAR 在 Java 8 上正常启动**（见 §1.2） |
| MySQL | 8.0.x | 官方 APT 源或 Ubuntu 源均可 |
| 时区 | `Asia/Shanghai` | 应用 JDBC 用 `serverTimezone=Asia/Shanghai`，Jackson 用 `GMT+8` |

### 1.2 为什么用 Java 8（已实测）

- JAR 的 class 文件版本为 **major=52（即 Java 8 字节码）**，未使用任何 Java 9+ 特有 API（`List.of`/`Map.of`/`String.isBlank` 等全无）。
- **实测**：用本机 JDK 1.8.0_333 启动该 JAR —— 页面 200、登录 200、dashboard 200，与 Java 17 表现一致。
- **Ubuntu 24.04 的 `openjdk-8-jre-headless` 在 main 仓库自带**（1.8.0_504），直接 `apt install` 即可。
  > 若是 **22.04**，该包在 `universe` 仓库，需先 `sudo add-apt-repository universe`。

> 若现场偏好 Java 11/17 亦可（同样实测通过），只需改 `/etc/default/camera-detection` 的 `JAVA_HOME`。

### 1.3 改造点：改这里，其余自动跟随

| 参数 | 默认值 | 改哪里（Ubuntu 版共 3 处） |
|---|---|---|
| 应用端口 | `8091` | `installer/templates/application.yml` ①（`server.port`）<br>`installer/linux/systemd/camera-detection.service` ②（仅当用 nginx 反代时改监听）<br>`installer/linux/scripts/verify.sh` ③（自检端口） |
| 数据库名 | `wms-new` | `installer/templates/application.yml` ①<br>`installer/linux/scripts/init-database.sh` ②（`DATABASE` 默认值） |
| 数据库账号/密码 | `root` / `<数据库口令>` | `installer/templates/application.yml` ① |
| 管理员口令 | `123456` | 见 §4.3（用 `reset-password.sh` 改，不要写进脚本） |
| 安装目录 | `/opt/camera-detection-system` | `installer/linux/scripts/install.sh` ① |
| 工作目录 | `/var/lib/camera-detection-system` | `installer/linux/scripts/install.sh` ① |
| 服务名 | `camera-detection` | `installer/linux/systemd/camera-detection.service` ①<br>`installer/linux/scripts/` 下 4 个脚本 ② |
| JVM 堆 | `-Xms512m -Xmx1024m` | `/etc/default/camera-detection`（运行时改，不用重打包） |

> **运行时改参数不动代码**：改 `/etc/default/camera-detection` → `systemctl restart camera-detection` 即生效。

### 1.4 必改的 2 项（安全）

`/opt/camera-detection-system/config/application.yml`：

```yaml
jwt:
  secret: <改成随机串，≥32字节>      # 默认值是公开的，必须改
spring:
  datasource:
    password: <改成现场密码>          # 默认值是公开的，必须改
```

生成随机 secret：`openssl rand -base64 48`

---

## 2. 构建机操作

```bash
# ① 前端
cd frontend
npm install
npm run build

# ② 前端产物并入后端（两份拷贝，必须同步）
cd ..
rm -rf fact-product/src/main/resources/static/assets
cp -r frontend/dist/assets fact-product/src/main/resources/static/assets
cp frontend/dist/index.html fact-product/src/main/resources/static/index.html

# ③ 后端
cd fact-product
mvn -q -DskipTests clean package

# ④ 契约测试（必跑，8 个用例）
mvn test
```

**一键构建 Linux 发布包**（等价于上面 ①–⑧）：

```bash
bash installer/linux/build-release.sh
```

产出：`dist-release/linux/camera-detection-system-1.0.0.tar.gz`

---

## 3. 服务器操作

### 3.1 装依赖

```bash
sudo apt update

# MySQL 8.0
sudo apt install -y mysql-server

# Java 8 运行时（universe 仓库）
sudo apt install -y openjdk-8-jre-headless

# 辅助工具：htpasswd（改口令用）、curl、tar
sudo apt install -y apache2-utils curl tar

# 确认
java -version && mysql --version
```

### 3.2 MySQL 账号

**本方案按现场要求使用 `root` / `<数据库口令>`。** 该机（`10.2.77.72`）的 root 认证插件是
`mysql_native_password`，**密码直连可用**，无需改动：

```bash
# 确认本机 root 的认证插件
mysql -h 127.0.0.1 -u root -p -N -e "SELECT user,host,plugin FROM mysql.user WHERE user='root';"

# 确认密码能连上
mysql -h 127.0.0.1 -u root -p -e "SELECT 1;"
```

> ⚠️ **换机器时务必先查这一项。** Ubuntu 的 `mysql-server` 包默认把 `root@localhost`
> 设成 **`auth_socket`** —— 只认操作系统身份、**不认密码**。此时本方案用 root 跑应用会连不上，
> 报 `Access denied for user 'root'@'localhost' (using password: YES)`。
> 两种处理方式：
>
> **A. 改成密码认证**（保持用 root）：
> ```bash
> sudo mysql <<'SQL'
> ALTER USER 'root'@'localhost' IDENTIFIED WITH caching_sha2_password BY '<数据库口令>';
> FLUSH PRIVILEGES;
> SQL
> ```
>
> **B. 建专用业务账号**（更安全，权限最小化）：
> ```bash
> sudo mysql <<'SQL'
> CREATE USER 'cds'@'localhost' IDENTIFIED WITH caching_sha2_password BY '改成强口令';
> -- 授权必须带 CREATE/ALTER：应用启动时 3 处 @PostConstruct 会 CREATE TABLE / ALTER TABLE
> GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES
>   ON `wms-new`.* TO 'cds'@'localhost';
> FLUSH PRIVILEGES;
> SQL
> ```
> 然后把 `application.yml` 的 `spring.datasource.username` / `password` 改成 `cds`。
>
> **实测**：`caching_sha2_password` 账号 + `application.yml` 里原样的 JDBC URL
> （含 `allowPublicKeyRetrieval=true&useSSL=false`）经 TCP 连接**成功**；
> 错误密码则正确报 `Access denied ... (using password: YES)`。

**MySQL 只绑本机**（本方案数据库与应用同机）：

```bash
sudo sed -i 's/^bind-address.*/bind-address = 127.0.0.1/' /etc/mysql/mysql.conf.d/mysqld.cnf
sudo systemctl restart mysql
```

### 3.3 传包并安装

```bash
# 从构建机传包
scp dist-release/linux/camera-detection-system-1.0.0.tar.gz user@server:/tmp/

# 服务器上
sudo tar -xzf /tmp/camera-detection-system-1.0.0.tar.gz -C /tmp
cd /tmp/camera-detection-system
sudo ./scripts/install.sh
```

> **若报 `Permission denied`** —— 说明打包时丢了 Unix 执行位（跨平台打包或某些传输工具不保留权限位）。
> 改用 `sudo bash scripts/install.sh` 即可；`install.sh` 内部会自行补齐其他脚本的执行位。

`install.sh` 会自动完成：建 `cdsys` 系统账号 → 建安装/工作目录 → 同步程序文件 →
下发 `/etc/default/camera-detection` → 安装并启用 systemd 服务 → 放行防火墙端口。

自定义目录：

```bash
sudo ./scripts/install.sh --work-dir /data/camera-detection --port 8091
```

### 3.4 建库（4 段脚本，按序）

```bash
sudo /opt/camera-detection-system/scripts/init-database.sh
```

脚本内部等价于：

```bash
SQL=/opt/camera-detection-system/sql
mysql -h 127.0.0.1 -u cds -p --default-character-set=utf8mb4 \
  -e 'CREATE DATABASE IF NOT EXISTS `wms-new` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;'

mysql -h 127.0.0.1 -u cds -p --default-character-set=utf8mb4 wms-new < "$SQL/01_init_schema_nodb.sql"
mysql -h 127.0.0.1 -u cds -p --default-character-set=utf8mb4 wms-new < "$SQL/02_final_schema_p0.sql"
mysql -h 127.0.0.1 -u cds -p --default-character-set=utf8mb4 wms-new < "$SQL/03_system_settings.sql"
mysql -h 127.0.0.1 -u cds -p --default-character-set=utf8mb4 wms-new < "$SQL/04_align_entity_columns.sql"
```

**表数应为 34**（应用首次启动后运行时自建 4 张 → 38）。

> ⚠️ **bash 的两个陷阱（均已实测）**
>
> | 陷阱 | 现象 | 正确写法 |
> |---|---|---|
> | 反引号 = 命令替换 | 库名带连字符必须写反引号，但 `"...\`wms-new\`..."` 会被 bash 当命令执行，报 `wms-new: command not found`，SQL 变成 `CREATE DATABASE ;` | 整条 SQL 用**单引号**包裹，或反斜杠转义 `` \` ``。`init-database.sh` 已用双引号+转义写法处理 |
> | `mysql < file` 的编码 | —— | **bash 的 `<` 是字节流重定向，中文不会乱码**（Windows PowerShell 管道会按 GBK 重编码，两者行为相反）|
>
> > 实测：用 `mysql < file` 导入后，`dim_product.product_name` = `产品A`、
> > `dim_factory.factory_name` = `杭州工厂`、`system.name` = `产线缺陷在线AI检测系统`，中文完好。
>
> ⚠️ `02` 会 DROP 并重建 21 张表，**只适用于空库**。已有数据的库升级见 §6。
>
> ⚠️ **交付包里的旧 `wms-new.sql` 不要用** —— 缺 15 张 P0 表，设备上报接口会 500。

### 3.5 改配置（2 行）

```bash
sudo vim /opt/camera-detection-system/config/application.yml
```

改 `jwt.secret` 与 `spring.datasource.password`（见 §1.4）；数据库账号改为 §3.2 建的 `cds`。

### 3.6 改管理员口令

```bash
sudo MYSQL_PASSWORD='<数据库密码>' \
  /opt/camera-detection-system/scripts/reset-password.sh --password '现场强口令'
```

> 该脚本用 `htpasswd -bnBC 10`（或退回 `python3` 的 `crypt` 模块，Ubuntu 自带，无需联网装包）
> 现场生成 BCrypt 哈希，**不把哈希写进脚本或日志**；并把账号/口令记入
> `/etc/default/camera-detection`（权限 640），使 `verify.sh` 能自动登录自检。
>
> 部署后**必须执行**，否则 `admin/123456` 是公开的默认口令。
>
> 脚本会把新口令记入 `/etc/default/camera-detection`（640 root），供 `verify.sh` 自检时自动登录。
> **2026-10-27 修复**：原实现用两条独立的 `grep -v` 分别过滤 `ADMIN_USER` 与 `ADMIN_PASSWORD`，
> 而两者都读原文件 —— 第一个保留了旧的 `ADMIN_PASSWORD` 行，导致**每改一次口令就多留一条旧条目**。
> 已改为一条 `grep -v -e "^ADMIN_USER=" -e "^ADMIN_PASSWORD="` 同时过滤，实测连跑两次后各只剩 1 条。

### 3.7 启动与放行

```bash
sudo systemctl restart camera-detection
sudo systemctl status camera-detection --no-pager

# 防火墙（install.sh 已自动做，此处为手工补做）
sudo ufw allow 8091/tcp
```

常用命令：

```bash
/opt/camera-detection-system/scripts/service.sh status   # 状态
/opt/camera-detection-system/scripts/service.sh logs     # 跟踪日志
/opt/camera-detection-system/scripts/service.sh restart  # 重启
```

### 3.8（可选）Nginx 反向代理

若需要 80 端口访问或 HTTPS：

```bash
sudo apt install -y nginx
sudo tee /etc/nginx/conf.d/camera-detection.conf > /dev/null <<'EOF'
server {
    listen 80;
    server_name _;

    # 缺陷图片单批最多 100 张、单张 10MB、整批上限 1GB
    client_max_body_size 1024m;
    client_body_timeout  300s;
    proxy_read_timeout   300s;

    location / {
        proxy_pass         http://127.0.0.1:8091;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
    }
}
EOF
sudo nginx -t && sudo systemctl reload nginx
```

> `client_max_body_size` 必须配够 —— nginx 默认仅 1MB，图片上传会被 nginx 拦成 413，
> 表现为"设备上报失败"而非应用报错。

---

## 4. 验收

### 4.1 一键自检（20 项）

```bash
sudo /opt/camera-detection-system/scripts/verify.sh
```

覆盖：Java/客户端版本 → 程序文件 → 工作目录权限 → systemd 状态 → 端口监听 →
页面/SPA深链/登录/看板 → 心跳/批量上报/未映射拒绝/图片超量拒绝。失败退出码 1。

> ⚠️ **恢复演练时的 MySQL 认证**（2026-10-31 实测）：
> `sudo mysql` 免密只在 root 的插件是 `auth_socket` 时成立；
> 若已改成 `mysql_native_password`，socket 免密会报
> `Access denied ... (using password: NO)`。
> **统一写法**：`export MYSQL_PWD='口令'` 后走 `mysql -h127.0.0.1 -uroot`。
> 详见《服务器运行与问题排查手册》§7.2。

### 4.2 功能基线（逐条核对）

```bash
B=http://127.0.0.1:8091
```

| # | 命令 | 期望 |
|---|---|---|
| 1 | `curl -s -o /dev/null -w "%{http_code}" $B/` | `200` |
| 2 | `curl -s -o /dev/null -w "%{http_code}" $B/system/config` | `200`（SPA 深链可刷新） |
| 3 | `curl -s -X POST -H "Content-Type: application/json" -d '{"username":"admin","password":"现场口令"}' $B/api/auth/login` | `code:200` + token |
| 4 | `curl -s $B/api/stats/dashboard-overview` | `code:200`（**这条验证 `04` 补列是否生效**） |
| 5 | 心跳（见下方命令） | `code:200` |
| 6 | 批量上报（`sourceCode:"p0"`） | `code:200` `accepted:true` |
| 7 | 上条重发（同 `batchId`） | `code:200` `duplicate:true` |
| 8 | 上条把 `sourceCode` 改成 `__NOT_MAPPED__` | `code:422` `UNMAPPED` |
| 9 | 图片上传带 `X-Image-Count: 101` | `413` |

```bash
# 5 心跳
curl -s -X POST -H "Content-Type: application/json" \
  -H "X-Device-Id: CAM-001" -H "Authorization: Bearer dev-token" \
  -d '{"cameraName":"c","cameraCode":"CAM-001","cameraStatus":1}' \
  $B/api/v1/camera/status/heartbeat

# 6 批量上报
curl -s -X POST -H "Content-Type: application/json" \
  -H "X-Device-Id: CAM-001" -H "X-Batch-Id: T1" -H "Authorization: Bearer dev-token" \
  -d '{"schemaVersion":"2.0","batchId":"T1","cameraCode":"CAM-001","productName":"产品A","reportTime":"2026-01-01 00:00:00","counterMode":"INCREMENTAL","timeRange":{"startTime":"2026-01-01 00:00:00","endTime":"2026-01-01 00:30:00"},"summary":{"totalCount":10,"okCount":10,"ngCount":0},"defects":[]}' \
  $B/api/v1/camera/inspection/batch-upload
```

> **注意** `schemaVersion` 实测**只接受 `"2.0"`**（DTO 注释与契约测试里写的 `1.0` 是错的）。
> `sourceCode` **大小写敏感**，必须与 `cfg_camera_defect_mapping.source_code` 完全一致，有效值形如 `p0`、`p1`。

### 4.3 设备侧 4 项确认

1. `cfg_camera_token` 里有该相机的 token
2. `dim_camera` 里有该相机记录（`camera_code`/`device_id`/所属产线工位）
3. `cfg_camera_defect_mapping` 里配了「相机 + 来源编码 → 缺陷类型」
4. 相机上报的 `sourceCode` 与映射表 `source_code` **完全一致**

### 4.4 数据迁移注意事项（若从 Windows 迁旧库）

数据库里的 `defect_image_file.image_path` / `camera_images.image_path` 存的是**绝对路径**（形如 `D:\45344\runtime\uploads\...`）。迁移到 Linux 后：

- **图片显示走相对路径**：应用先按 `defect-image.upload.dir` + URL 相对路径去找，能找到就直接返回 —— **只要把图片目录原样拷到新工作目录即可显示**。
- `image_path` 列里的 Windows 路径仅作兜底，Linux 上必然不存在，**不影响显示**，但历史数据的定时清理任务（`DataCleanupTask`）按该列删文件会失效（不影响业务）。

```bash
# 迁移历史图片（Windows → Linux）
rsync -avz /path/to/runtime/data/uploads/ \
      root@server:/var/lib/camera-detection-system/data/uploads/
```

---

## 5. 验收通过后的日常运维

```bash
# 状态
/opt/camera-detection-system/scripts/service.sh status

# 跟踪日志（JVM stdout/stderr）
/opt/camera-detection-system/scripts/service.sh logs

# 业务日志
tail -f /var/lib/camera-detection-system/logs/camera-detection-system.log

# 改 JVM 堆 / 路径等（改完重启即生效，不用重打包）
sudo vim /etc/default/camera-detection
sudo systemctl restart camera-detection
```

### 日志轮转（建议配置）

```bash
sudo tee /etc/logrotate.d/camera-detection > /dev/null <<'EOF'
/var/lib/camera-detection-system/logs/*.log {
    daily
    rotate 30
    compress
    delaycompress
    missingok
    notifempty
    copytruncate
    su cdsys cdsys
}
EOF
sudo logrotate -d /etc/logrotate.d/camera-detection
```

> 用 `copytruncate` 而不是 `create`：应用持有日志文件句柄，不 truncate 的话空间不会释放。

---

## 6. 升级 / 回滚

```bash
# 变量
MYSQL_HOST=127.0.0.1; MYSQL_USER=cds; MYSQL_PASSWORD='***'
MYSQLD=/usr/bin/mysqldump
DB='wms-new'
STAMP=$(date +%Y%m%d_%H%M%S)

# ① 备份数据库（--result-file 让 mysqldump 自己写文件，避免编码问题）
sudo mkdir -p /var/backups/camera-detection
MYSQL_PWD="$MYSQL_PASSWORD" $MYSQLD -h "$MYSQL_HOST" -u "$MYSQL_USER" \
  --single-transaction --routines --hex-blob \
  --result-file=/var/backups/camera-detection/${DB}_${STAMP}.sql "$DB"

# ② 备份程序
sudo cp /opt/camera-detection-system/app/time-bucket-demo.jar \
        /var/backups/camera-detection/time-bucket-demo.jar.${STAMP}.bak

# ③ 停服务 → 换包 → 起服务
sudo systemctl stop camera-detection
sudo cp /tmp/camera-detection-system/app/time-bucket-demo.jar \
        /opt/camera-detection-system/app/
sudo systemctl start camera-detection
sudo /opt/camera-detection-system/scripts/verify.sh

# ④ 回滚
sudo systemctl stop camera-detection
sudo cp /var/backups/camera-detection/time-bucket-demo.jar.${STAMP}.bak \
        /opt/camera-detection-system/app/time-bucket-demo.jar
# 仅当库结构也需回退时执行（会覆盖数据，慎用）
# MYSQL_PWD="$MYSQL_PASSWORD" mysql -h "$MYSQL_HOST" -u "$MYSQL_USER" \
#   --default-character-set=utf8mb4 "$DB" < /var/backups/camera-detection/${DB}_${STAMP}.sql
sudo systemctl start camera-detection
```

> ⚠️ **库结构变更绝不能跑全量 `02`**（会 DROP 并重建 21 张表、清空数据）。
> 增量变更只跑新增的 DDL，且**先在测试库演练**。

### 卸载

```bash
sudo systemctl disable --now camera-detection
sudo rm /etc/systemd/system/camera-detection.service
sudo systemctl daemon-reload
sudo rm -rf /opt/camera-detection-system /var/lib/camera-detection-system
sudo rm /etc/default/camera-detection
sudo userdel cdsys && sudo groupdel cdsys
```

---

## 7. 故障速查

| 现象 | 原因 | 处理 |
|---|---|---|
| **登录永远"用户名或密码错误"** | `sys_user.password_hash` 为 NULL —— 建库时 `04` 脚本先 DROP 了存哈希的 `password` 列 | **已修复**：仓库内 `04_align_entity_columns.sql` 已加 `UPDATE ... SET password_hash = password` 搬运哈希。若现场库仍是旧脚本，手工执行该 UPDATE 后重新导入 |
| **服务 status=127 起不来** | `/etc/default/camera-detection` 里 `JAVA_OPTS` 未加引号，bash source 时空格截断 | **已修复**：`install.sh` 现在写 `JAVA_OPTS="-Xms512m -Xmx1024m"`。现场可手工补引号后 `systemctl restart` |
| **`Start request repeated too quickly`** | systemd `StartLimitBurst=3` 触发，失败 3 次后拒绝再启动 | `systemctl reset-failed camera-detection && systemctl restart camera-detection`（脚本已内置） |
| **自检报"systemd 服务 未安装"但服务明明在跑** | `systemctl list-unit-files \| grep -q` 遇 `set -o pipefail` 误判 | **已修复**：改用 `grep -c` 计数比较。现场可直接更新 `verify.sh` |
| 批量上报 500 `Table 'dim_camera' doesn't exist` | 用了旧 `wms-new.sql` 建库 | 按 §3.4 重跑 4 段脚本 |
| 启动即退 `Table 'dim_time_bucket_30min' doesn't exist` | 建库过程中应用已启动（建库与应用并发） | **先建库、后启动**；日志见 `journalctl -u camera-detection` |
| dashboard 500 `Unknown column 'brand_id'` | `fact_production_30min` 缺列 | 跑 `04_align_entity_columns.sql` |
| 应用连不上数据库 `Access denied` | 该机 root 若为 `auth_socket`（Ubuntu 默认）则不认密码 | 改用 `mysql_native_password` / `caching_sha2_password` 的账号，或见 §3.2 建专用账号 |
| 应用连不上数据库 `Public Key Retrieval is not allowed` | 账号是 `caching_sha2_password` 但 URL 缺参数 | 用 `application.yml` 里含 `allowPublicKeyRetrieval=true` 的 URL |
| 服务起不来 | 找不到 java | `cat /etc/default/camera-detection` 看 `JAVA_HOME`；`systemctl status camera-detection` |
| 服务起不来 | 工作目录不可写 | `chown -R cdsys:cdsys /var/lib/camera-detection-system` |
| 端口被占 | 8091 冲突 | `ss -tlnp \| grep 8091` |
| 设备上传 413（nginx 下） | nginx `client_max_body_size` 默认 1MB | 按 §3.8 调大到 1024m |
| 图片传了但页面不显示 | 筛选器过滤 / 文件名与 metadata 不符 / 映射缺失 | 逐项核对；迁移场景见 §4.4 |
| 页面 403 | 路径不在白名单 | 查 `SecurityConfig` |
| 节假日/用户/公司页 404 | 前端复数 ↔ 后端单数 | 统一 URL 后同步白名单（见 §9） |
| 自检登录项失败但服务正常 | 现场已改口令，自检脚本仍用默认 `123456` | `sudo -E VERIFY_PASSWORD='实际口令' ./verify.sh`，或用 `reset-password.sh` 重设后自动记录 |

**日志位置**：
- JVM stdout/stderr → `journalctl -u camera-detection`
- 业务日志 → `/var/lib/camera-detection-system/logs/camera-detection-system.log`

---

## 8. 改造指引：怎么改成自己的样子

| 想改成什么 | 改哪里 | 怎么验证 |
|---|---|---|
| **换端口** | `installer/templates/application.yml` 的 `server.port`<br>+ `installer/linux/scripts/verify.sh` 的 `PORT` | 改完重启，`ss -tlnp \| grep <新端口>` 有监听；`verify.sh` 显示"TCP <新端口> 监听 通过" |
| **换数据库名** | `installer/templates/application.yml` 的 `spring.datasource.url`<br>+ `init-database.sh` 的 `DATABASE` | 建库后 `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='<新库名>'` 应为 34 |
| **换数据库账号** | `application.yml` 的 `username`/`password`<br>+ MySQL 侧 `GRANT ... ON \`<新库>\`.*` | `mysql -h 127.0.0.1 -u <新账号> -p -e "SELECT 1"` 通；看板接口 200 |
| **换安装/工作目录** | `installer/linux/scripts/install.sh` 的 `INSTALL_DIR`/`WORK_DIR` | 安装后 `ls <新目录>/app/` 有 jar；`<新工作目录>/logs/` 有日志 |
| **换服务名** | `installer/linux/systemd/camera-detection.service` 的 `ExecStart`/`Description`<br>+ `installer/linux/scripts/` 下 4 个脚本的 `SERVICE_NAME` | `systemctl status <新名>` 正常；`service.sh status` 有输出 |
| **换 JVM 堆** | `/etc/default/camera-detection` 的 `JAVA_OPTS` | 重启后 `systemctl show camera-detection -p MemoryMax`；或看应用是否 OOM |
| **加一个设备接口** | `controller/` 新增类 + `docs/api/接口冻结契约-*.md` 补条款<br>+ `src/test/java/com/wms/controller/` 加契约测试 | `mvn test` 必须全绿 |
| **加一个页面** | `frontend/src/views/` 加组件 + `frontend/src/router/index.js` 注册路由<br>+ `frontend/src/api/` 加接口模块 | `npm run build` 通过；`curl` 访问新路由返回 200 |
| **改前端接口地址** | `frontend/src/api/*.js` 里的 url | 注意 **url 不要带 `/api` 前缀**（`request.js` 的 baseURL 已有） |
| **改数据库结构** | 改 `fact-product/src/main/resources/sql/deploy/04_align_entity_columns.sql`<br>**同时改实体类** | 建完库后 `mvn test` + 启动 + 跑 §4.2 的 9 条基线 |

### 改造时的三条硬约束

1. **实体类与表列必须一致**。新增列要同时改 Java 实体和 `04_align_entity_columns.sql`，否则接口 500（`Unknown column`）。
2. **`02_final_schema_p0.sql` 会清空 21 张表**。生产库升级绝不能跑它，只能跑增量 DDL。
3. **改 `installer/` 即可，别改 `dist-release/`**。后者是打包产物，下次构建会覆盖。

---

## 9. 上线前必须知道的三件事

1. **后端鉴权实际不生效** —— `JwtRequestFilter` 只解析 token 不写入 `SecurityContextHolder`，实测**无 token 也能读到 `/api/cameras` 的真实数据**。上线前必须修复，否则任何人都能拉业务数据。
2. **前端有 21 个接口地址与后端对不上**（`/api/holidays` vs `/api/holiday` 等）—— 节假日、用户、公司、品牌、缺陷分类等管理页会 404/403。上线前需统一。
3. **默认口令必须改** —— 建库种子账号为 `admin/123456`，部署后立即执行 §3.6。

完整清单见 `docs/项目现状梳理与服务器部署方案-20261024.md` §5。

---

## 附录 A：Windows 版 → Ubuntu 版 对照

| 能力 | Windows 版 | Ubuntu 版 | 备注 |
|---|---|---|---|
| 服务托管 | C# 服务包装器 `CameraDetectionService.exe`（需 .NET 运行时） | **systemd**（`camera-detection.service`） | Linux 版用 `run.sh` 启动器拼装参数，替代包装器 |
| 安装 | Inno Setup `.exe` 安装向导 | `tar.gz` + `install.sh` | 无图形向导，参数化安装 |
| JRE | 内置 `jre/` 目录（Windows 版，含 `.dll`） | `apt install openjdk-8-jre-headless` | **Windows 的 JRE 不能在 Linux 用**；`run.sh` 仍支持内置 `jre/bin/java` 优先 |
| 启停 | `start-service.ps1` / `stop-service.ps1` | `systemctl` / `service.sh` | |
| 自检 | `verify-installation.ps1` | `verify.sh` | 检查项从 9 项扩到 20 项 |
| 建库 | `init-database.ps1`（单文件，指向已废弃的 `wms-new.sql`） | `init-database.sh`（4 段式，脚本名与用途已对齐） | Windows 版该脚本在本方案下不适用 |
| 改口令 | 需手工算 BCrypt | `reset-password.sh`（用 `htpasswd` 现场生成） | |
| 打包 | `ISCC.exe` 打 `.exe` | `build-release.sh` 打 `.tar.gz` | |
| 参数集中处 | 散落在 `application.yml` + `workdir.txt` | **`/etc/default/camera-detection` 单一文件** | 改参数不用动 unit 文件 |

## 附录 B：已实测结论汇总

> **本节区分「已在真实服务器验证」与「仅本地推演」两类结论。**
> 实测环境：`10.2.77.72`（Hik-Vision），**Ubuntu 24.04.5 LTS**、OpenJDK 1.8.0_504、
> MySQL 8.0.46、systemd 255、16 GB 内存。

### 已在真实服务器（10.2.77.72）验证通过

| 项 | 结论 |
|---|---|
| 全新部署全流程 | 清空环境（删服务/目录/账号/库）后按 §3 逐步执行：解压 → `install.sh` → `init-database.sh` → `service.sh restart` → `verify.sh` **21 项全过** |
| 建库 | 4 段脚本 → 34 张表、零报错；应用启动后运行时自建 4 张 → **38 张** |
| 中文完整性 | `产品A` / `杭州工厂` / `杭州1号产线` / `产线缺陷在线AI检测系统` 全部正确（`mysql < file` 不乱码） |
| 登录 | `admin` 用新口令登录返回 `code:200` + token；旧口令正确被拒 |
| 功能基线 9 条 | 页面 200 / SPA 深链 200 / 登录 200 / dashboard 200 / 心跳 200 / 批量上报 200 / 幂等重放 200 / 未映射 422 / 图片超量 413 |
| 图片上传 | multipart 上传返回 `success:true`，文件落盘到 `data/uploads/defect-images/20261026/CAM-001/`，DB 记录含宽高；再按 `image_url` 取回 HTTP 200 `image/png` |
| 服务自愈 | `kill -9` JVM 后 systemd 自动拉起，**约 11 秒恢复**，页面回到 200 |
| 外部访问 | `http://10.2.77.72:8091/` 返回 200，UFW 已放行 8091/tcp |
| 运行账号 | 进程属主 `cdsys`（非 root） |
| MySQL 暴露面 | `bind-address = 127.0.0.1`，3306 未对外 |
| JAR 字节码 | major=52（Java 8），在 OpenJDK 1.8.0_504 上启动正常 |
| `caching_sha2_password` | 该机 root 用的是 `mysql_native_password`（**不是** Ubuntu 默认的 `auth_socket`），密码登录直连可用 |

### 上机首轮暴露并已修复的 5 个真实缺陷

| # | 现象 | 根因 | 修复 |
|---|---|---|---|
| 1 | 服务 `status=127` 起不来 | `install.sh` 写 `JAVA_OPTS=-Xms512m -Xmx1024m` **未加引号**；`run.sh` 用 bash source 该文件，空格被截断成两个词 → `-Xmx1024m: command not found` | 配置值加双引号；`run.sh` 改为「子 shell 试跑 + 告警 + `${VAR:-默认}` 兜底」，坏配置降级而非崩溃 |
| 2 | systemd 报 `Unknown key name 'StartLimitIntervalSec' in section 'Service'` | 该键必须写在 `[Unit]` 段 | 移到 `[Unit]` 段 |
| 3 | systemd 报 `Invalid URL, ignoring: ...部署方案-Ubuntu22.04.md` | `Documentation=` 指向含中文的文件名 | 去掉该行（文档路径会变，写死反而脆弱） |
| 4 | `install.sh` 误报「✓ 服务已启动」 | `systemctl restart` 只要**排队成功**就返回 0，真正启动失败是异步的 | 启动后轮询 `is-active` 并二次确认；仍失败则检测数据库是否存在，分别给出「先建库」或「看日志」的指引，退出码 1 |
| 5 | 自检误报「systemd 服务 未安装」 | `systemctl list-unit-files \| grep -q ...` —— `grep -q` 命中即退出，`systemctl` 收到 SIGPIPE 返回非 0，配合 `set -o pipefail` 整条管道判为失败 | 改用 `grep -c` 取计数再比较；`install.sh` 里 `ufw status \| grep -q` 同理修正 |

### 第二轮排查（深挖日志与图片存储）又发现 3 个真实缺陷 —— 均已修

| # | 现象 | 根因 | 修复 |
|---|---|---|---|
| 6 | **journald 几小时涨到 243MB**，业务日志文件暴涨 | JAR 内置的**开发版** `application.yml` 带 `mybatis-plus.configuration.log-impl: StdOutImpl` 与 `logging.level.com.wms.mapper: DEBUG`，而外部生产配置**没覆盖它们** —— 配置按 key 合并，省略的 key 仍取内部值；且 logback 取**最具体**的 logger，`com.wms.mapper` 比 `com.wms` 更具体，只写 `com.wms: INFO` 压不住 | 生产模板补 `log-impl: Slf4jImpl` + `com.wms.mapper: INFO`。**实测：同样 20 次请求，journald 新增从 19666 行降到 40 行，SQL 打印从 2835 条降到 0 条** |
| 7 | **磁盘上的缺陷图片只增不减** | 两处叠加：① `camera_images` 缺 `update_time` → 写入被 catch 吞掉只打 WARN，表恒为空；② `CameraImage` 的 `@TableId` 写成 `image_id`，而表主键是 `id` → 清理任务的 SELECT 报错，`@Transactional` 把整个清理任务回滚 | ① `04_align_entity_columns.sql` 补 `update_time`；② 实体主键改为默认映射（走 `id`）、mapper 的 `ORDER BY image_id` 改 `id`。**实测：上传后 `camera_images` 正常落库，过期记录的 SELECT/DELETE 均正常** |
| 8 | `fact_production_info` / `product_detection_history` / `fact_anomaly_alert` 写入抛 SQLSyntaxError | 实体映射的 10 个列在表中不存在（如 `workstation_id`、`update_time`、`line_id`） | `04_align_entity_columns.sql` 一并补齐 |

> 另注：`sys_data_retention_policy` 表存了 5 条保留策略，但**代码从不读它** ——
> 真正的清理是 `DataCleanupTask` 里硬编码的三条（`camera_images` 30 天、
> `fact_production_30min` 归档 3 个月、`sys_operation_log` 6 个月）。
> **新版缺陷图片表 `defect_image_file` 及其磁盘文件没有任何定时任务清理**，
> 已在排查手册 §3.5 给出 cron 兜底方案，并随 `install.sh` 一并安装。

### 另一个运维陷阱（已写进脚本）

systemd 的 `StartLimitBurst=3` 在连续失败 3 次后会进入 **`Start request repeated too quickly`** 状态 —— 此时即使数据库已建好，`systemctl restart` 也会被直接拒绝，必须先 `systemctl reset-failed`。
`install.sh` / `service.sh restart` / `init-database.sh` 的提示里都已加入 `reset-failed`。

### 未能验证 / 需注意

- 方案最初按 Ubuntu 22.04 编写，**实测机是 24.04.5 LTS**，现文档已改以 24.04 为基准。
  两者在 systemd（249 vs 255）、MySQL（8.0.36 vs 8.0.46）上有版本差异，脚本按两者通用写法编写。
- 若目标机确为 **22.04**：`openjdk-8-jre-headless` 在 universe 仓库，需先 `sudo add-apt-repository universe`。
- **未做**：多实例/集群、nginx 反代（配置已给出但未实测）、磁盘写满的故障演练、备份回灌的端到端演练。

---

## 附录 C：容量、存储与备份（2026-10-26 架构评审后落地）

> 详细的现状分析、方案对比与风险见 `docs/架构评审报告.md`。
> 本节只列**运维时需要知道的最终形态**。

### C.1 磁盘分工

| 分区 | 文件系统 | 容量 | 用途 |
|---|---|---|---|
| `/` | ext4 | 40 GB | 系统 |
| `/var` | ext4 | 50 GB | **MySQL 数据目录**（`/var/lib/mysql`）、业务日志 |
| **`/data`** | **XFS** | **500 GB** | **缺陷图片 + 数据库备份** |

> ⚠️ **MySQL 的 datadir 在 `/var`** —— 数据库增长会占用 `/var`。
> 库长到 2.6 GB 后建议把 `innodb_buffer_pool_size` 从 128 MB 调到 512 MB。

### C.2 图片存储

```
/data/camera-detection/images/defect-images/{yyyyMMdd}/{cameraId}/{HHmmssSSS}_{uuid8}.{ext}
```

- 配置项：`application.yml` 的 `defect-image.upload.dir`
- **目录必须由 root 建好并 chown 给 `cdsys`** —— `/data` 本身是 755 root:root，
  `cdsys` 无法在其下创建子目录
- **改这个路径后必须同步改 systemd 的 `ReadWritePaths`**，否则
  `ProtectSystem=full` 会拦截写入，表现为「上传返回成功但文件不落盘」

### C.3 保留期与清理

| 数据 | 保留 | 由谁清理 |
|---|---|---|
| `defect_image_file` / `_box` | **7 天** | `cleanup-defect-images.sh`（cron 03:30） |
| `defect_image_batch` | 30 天 | 同上 |
| `camera_images`（兼容表） | 7 / 30 天 | 同上 + 应用内 `DataCleanupTask`（02:00） |
| `fact_production_30min` | 3 个月 | 应用内 `DataCleanupTask`（归档到 `fact_production_30min_archive`） |
| `sys_operation_log` | 6 个月 | 应用内 `DataCleanupTask` |
| `agg_*` / `fact_inspection_*` | 永久 | 不清理（看板数据源，量小） |

> **清理顺序必须是「先删库记录、再删磁盘文件」** ——
> 反过来会产生孤儿记录（记录在、文件没了），前端显示破图且记录永不复收。

### C.4 备份

| 项 | 值 |
|---|---|
| 方式 | `mysqldump --single-transaction --quick` + `zstd -3`（不锁表） |
| 周期 | 每日 **01:00**（早于 02:00 的清理任务，备份的是清理前状态） |
| 保留 | 14 份 |
| 位置 | `/data/camera-detection/backup/db/full/` |
| 校验 | 每个文件带 `.sha256` |
| 增量 | 依赖 MySQL binlog（ROW 格式，保留 30 天），可用 `mysqlbinlog` 做时间点恢复 |
| 异机 | `OFFSITE_DIR` 指向的目录（**当前仍是同一物理盘，需改为另一台机器**） |

```bash
# 查看定时器
systemctl list-timers camera-detection-backup.timer

# 手动触发一次
sudo systemctl start camera-detection-backup.service

# 查看日志
journalctl -u camera-detection-backup.service -n 30

# 恢复演练（务必每月做一次）
zstd -dc /data/camera-detection/backup/db/full/xxx.sql.zst | mysql -u root cds_restore_test
```

### C.5 磁盘水位监控

`/usr/local/bin/check-disk-usage.sh` 每 30 分钟检查 `/var` 与 `/data`，
超过 85% 写 `logger` + `/var/log/camera-detection-disk-alert.log`，退出码 1。

> 按 350 KB/张、144000 张/天 计算，`/data` 约 10 天写满（7 天保留期下可稳定运行）。
> **上线首日必须用真实图片复核单张均寸** —— 实测区间为 88 KB ~ 6 MB，跨 70 倍。
