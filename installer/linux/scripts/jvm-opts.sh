#!/usr/bin/env bash
# ==============================================================================
# JVM 参数生成 —— **单一真源**
#
# ★ 2026-11-19 评审（服务器日志深度分析 → JVM 调优）：
#   此前 JVM 参数散在 **4 处**各写一份（install.sh / deploy.sh / run.sh /
#   migrate-to-main-app.sh），改一处忘另一处就会导致"现场跑的和仓库写的不一样"。
#   本文件是唯一真源，上述 4 处统一 source 它。
#
# ---- 为什么按这些参数来（依据本程序的性质）--------------------------------
#   ① **Spring Boot 2.7 + Tomcat，单机 16GB 独占部署**（非容器、非集群）
#      → 堆可占物理内存的 1/4，不必像容器那样按 cgroup 限制保守取值；
#      → 但堆外（Metaspace / CodeCache / 线程栈 / DirectMemory）**必须显式设上限**，
#        否则 Java 8 默认无上限，元空间泄漏会一路吃到 OOM。
#   ② **主负载 = 图片上传（multipart）+ 统计聚合查询 + 5s 心跳**
#      → 上传与看板对延迟敏感（设备端超时断连会触发重传 → 重复数据）；
#        → 用 **G1GC** 而非 Java 8 默认的 ParallelGC：后者吞吐优先但 Full GC
#          停顿可能到秒级，凌晨归档任务一卡就波及白天的上传。
#   ③ **凌晨 00:05 日报物化 / 02:00 通用清理 / 02:30 图片归档**是堆峰值时段
#      → 给足堆（mem/4），并用 `-XX:InitiatingHeapOccupancyPercent` 让 G1 提前
#        进入并发标记，避免峰值时退化成 Full GC。
#   ④ **必须开 GC 日志**：本服务 `PrivateTmp=true`（安全加固），
#      外部 `jstat`/`jcmd` **attach 不上**（服务私有 /tmp 命名空间），
#      → 没有 GC 日志就等于**完全没有堆诊断能力**（现场实测 jstat 报 not found）。
#   ⑤ **OOM 必须能事后分析**：`-XX:+HeapDumpOnOutOfMemoryError` + 落数据盘，
#      再 `-XX:+ExitOnOutOfMemoryError` 让它快速失败、由 systemd 拉起，
#      而不是半死不活地吊着（吊着的话设备端会一直重传）。
#
# ---- 用法 ------------------------------------------------------------------
#   source "$(dirname "$0")/jvm-opts.sh"
#   JAVA_OPTS="$(jvm_opts_for_memory "$(awk '/MemTotal/{print int($2/1024)}' /proc/meminfo)")"
#
#   可用环境变量覆盖：
#     CAMERA_HEAP_MB   堆上限（MB）。不设则按物理内存的 1/4 计算。
#     CAMERA_LOG_BASE  日志根目录（默认 /main/app/logs）
#     CAMERA_DATA_BASE 数据根目录（默认 /main/app/data）
# ==============================================================================

# 堆上限：物理内存的 1/4，下限 1024MB（512MB 对本程序偏小），
# 上限 4096MB（再大对 16GB 机器没有收益，反而压缩堆外与 OS 的余量）。
jvm_heap_mb() {
    local mem_mb="${1:-0}"
    local heap
    if [[ -n "${CAMERA_HEAP_MB:-}" ]]; then
        heap="${CAMERA_HEAP_MB}"
    else
        heap=$(( mem_mb / 4 ))
    fi
    [[ "$heap" -gt 4096 ]] && heap=4096
    [[ "$heap" -lt 1024 ]] && heap=1024
    echo "$heap"
}

# 生成完整的 JAVA_OPTS
jvm_opts_for_memory() {
    local mem_mb="${1:-0}"
    local log_base="${CAMERA_LOG_BASE:-/main/app/logs}"
    local data_base="${CAMERA_DATA_BASE:-/main/app/data}"
    local heap_mb
    heap_mb="$(jvm_heap_mb "$mem_mb")"

    # GC 日志与 dump 目录必须先存在（服务以 cdsys 运行，目录属主要对）
    local gc_dir="${log_base}/gc"
    local dump_dir="${data_base}/dump"
    mkdir -p "$gc_dir" "$dump_dir" 2>/dev/null || true
    chmod 0755 "$gc_dir" "$dump_dir" 2>/dev/null || true

    # Xms 保持 1G：启动快，堆按需增长（Xms=Xmx 会让启动即占满物理内存）
    cat <<EOF
-Xms1024m -Xmx${heap_mb}m
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200
-XX:G1HeapRegionSize=4m
-XX:InitiatingHeapOccupancyPercent=40
-XX:MaxMetaspaceSize=256m
-XX:ReservedCodeCacheSize=256m
-XX:MaxDirectMemorySize=256m
-Xss512k
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=${dump_dir}
-XX:+ExitOnOutOfMemoryError
-XX:+PrintGCDetails
-XX:+PrintGCDateStamps
-XX:+PrintGCApplicationStoppedTime
-Xloggc:${gc_dir}/gc.log
-XX:+UseGCLogFileRotation
-XX:NumberOfGCLogFiles=10
-XX:GCLogFileSize=20M
EOF
}
