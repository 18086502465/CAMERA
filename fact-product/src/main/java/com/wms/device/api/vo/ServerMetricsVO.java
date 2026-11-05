package com.wms.device.api.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 服务器资源指标响应对象。
 *
 * <p>对应接口：{@code GET /api/v1/system/server/metrics}。
 *
 * <p><b>设计要点</b>（2026-11-02 方案）：
 * <ul>
 *   <li>磁盘 I/O 的读写字节数与 IOPS 在 OSHI 里是<b>累计值</b>，
 *       必须与上一次采样做差再除以时间间隔才能得到速率。
 *       本类因此同时返回「速率」与「累计总量」两组字段，
 *       并用 {@code sampleIntervalMillis} 标明本次速率对应的采样间隔。</li>
 *   <li><b>首次采样没有做差基准</b>，此时速率必须为 {@code null} 而不是 0 ——
 *       返回 0 会被前端误读为「磁盘完全空闲」。</li>
 * </ul>
 */
@Data
public class ServerMetricsVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 服务器基本信息 */
    private ServerInfo server;

    /** CPU 指标 */
    private Cpu cpu;

    /** 内存指标 */
    private Memory memory;

    /** 各文件系统/分区容量 */
    private List<FileSystemInfo> fileSystems;

    /** 磁盘 I/O 速率与累计量 */
    private DiskIo diskIo;

    /** 内存缓存的历史采样点（按时间升序） */
    private List<HistoryPoint> history;

    /** 环形缓存容量 */
    private int historySize;

    /** 当前有效历史点数 */
    private int sampleCount;

    /** 本次快照的采集时刻 */
    private String collectedAt;

    /** 最近一次采集是否失败（true 表示本次读到的是上一次成功值） */
    private boolean stale;

    /** 最近一次采集的错误信息 */
    private String lastError;

    /** 采集错误码，如 OSHI_INIT_FAILED */
    private String errorCode;

    @Data
    public static class ServerInfo implements Serializable {
        private static final long serialVersionUID = 1L;
        /** 操作系统描述 */
        private String osName;
        /** 主机名 */
        private String hostName;
        /** CPU 架构 */
        private String arch;
        /** 系统运行时长（秒） */
        private long uptimeSeconds;
        /** JVM 运行时长（秒） */
        private long jvmUptimeSeconds;
        /** 物理核心数 */
        private int cpuCores;
        /** 逻辑处理器数 */
        private int cpuLogical;
        /** CPU 型号 */
        private String cpuModel;
    }

    @Data
    public static class Cpu implements Serializable {
        private static final long serialVersionUID = 1L;
        /** 系统整体 CPU 使用率（0-100） */
        private Double usagePercent;
        /** 1 分钟平均负载（Windows 为 null） */
        private Double loadAverage1;
        /** 5 分钟平均负载 */
        private Double loadAverage5;
        /** 15 分钟平均负载 */
        private Double loadAverage15;
        /** 当前 JVM 进程的 CPU 占用（0-1 之间的小数） */
        private Double processCpuLoad;
    }

    @Data
    public static class Memory implements Serializable {
        private static final long serialVersionUID = 1L;
        /** 物理内存总量（字节） */
        private long totalBytes;
        /** 可用内存（字节） */
        private long availableBytes;
        /** 已用内存（字节） */
        private long usedBytes;
        /** 使用率（0-100） */
        private Double usagePercent;
    }

    @Data
    public static class FileSystemInfo implements Serializable {
        private static final long serialVersionUID = 1L;
        /** 挂载点（Linux 如 /data；Windows 如 C:\） */
        private String mount;
        /** 卷标 */
        private String volume;
        /** 文件系统类型（ext4 / xfs / NTFS） */
        private String type;
        /** 总量（字节） */
        private long totalBytes;
        /** 可用空间（字节） */
        private long usableBytes;
        /** 已用空间（字节） */
        private long usedBytes;
        /** 使用率（0-100） */
        private Double usagePercent;
        /** 是否可读 */
        private boolean readable;
        /** 是否可写 */
        private boolean writable;
    }

    @Data
    public static class DiskIo implements Serializable {
        private static final long serialVersionUID = 1L;

        /** 读速率（字节/秒）。首次采样为 null（无做差基准） */
        private Double readBytesPerSec;
        /** 写速率（字节/秒）。首次采样为 null */
        private Double writeBytesPerSec;
        /** 读 IOPS。首次采样为 null */
        private Double readOpsPerSec;
        /** 写 IOPS。首次采样为 null */
        private Double writeOpsPerSec;

        /** 自开机以来的累计读取字节数 */
        private long readTotalBytes;
        /** 自开机以来的累计写入字节数 */
        private long writeTotalBytes;
        /** 自开机以来的累计读操作数 */
        private long readTotalOps;
        /** 自开机以来的累计写操作数 */
        private long writeTotalOps;

        /** 本次速率对应的采样间隔（毫秒）。0 表示首次采样、无速率 */
        private long sampleIntervalMillis;
    }

    /**
     * 历史采样点。字段与当前快照一一对应，便于前端直接喂给 ECharts。
     */
    @Data
    public static class HistoryPoint implements Serializable {
        private static final long serialVersionUID = 1L;
        /** 采样时刻（epoch 毫秒） */
        private long timestamp;
        /** CPU 使用率 */
        private Double cpuUsagePercent;
        /** 内存使用率 */
        private Double memoryUsagePercent;
        /** 已用内存（字节） */
        private Long memoryUsedBytes;
        /** 磁盘读速率（字节/秒） */
        private Double readBytesPerSec;
        /** 磁盘写速率（字节/秒） */
        private Double writeBytesPerSec;
        /** 读 IOPS */
        private Double readOpsPerSec;
        /** 写 IOPS */
        private Double writeOpsPerSec;
        /** 磁盘最大分区使用率 */
        private Double maxDiskUsagePercent;
    }

    /**
     * 快照容器。用 Map 承载是为了与项目内其它接口
     * （如 StatsController 的 {@code ResultVO<Map<String,Object>>}）保持一致。
     */
    public static Map<String, Object> toMap(ServerMetricsVO vo) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("server", vo.getServer());
        map.put("cpu", vo.getCpu());
        map.put("memory", vo.getMemory());
        map.put("fileSystems", vo.getFileSystems());
        map.put("diskIo", vo.getDiskIo());
        map.put("history", vo.getHistory());
        map.put("historySize", vo.getHistorySize());
        map.put("sampleCount", vo.getSampleCount());
        map.put("collectedAt", vo.getCollectedAt());
        map.put("stale", vo.isStale());
        map.put("lastError", vo.getLastError());
        map.put("errorCode", vo.getErrorCode());
        return map;
    }
}
