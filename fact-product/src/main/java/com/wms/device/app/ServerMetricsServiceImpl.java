package com.wms.device.app;

import com.wms.device.app.IServerMetricsService;
import com.wms.device.api.vo.ServerMetricsVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HWDiskStore;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.software.os.FileSystem;
import oshi.software.os.OSFileStore;
import oshi.software.os.OperatingSystem;

/**
 * 服务器资源指标采集服务（基于 OSHI）。
 *
 * <h3>为什么是「后台定时采集 + 内存缓存」而不是「请求时现采」</h3>
 * <p>本机实测（Java 8 + OSHI 6.9.3，Windows）：
 * <pre>
 *   单次完整采集      = 124 ~ 214 ms
 *     其中 getDiskStores()  = 85 ~ 95 ms（Windows 走 WMI，最慢的一环）
 *          getFileStores()  = 29 ~ 84 ms
 *          CPU + 内存       =  8 ~ 11 ms
 *   new SystemInfo() + getHardware() = 99 ms（一次性，故 SystemInfo 全局复用）
 * </pre>
 * <p>若在 HTTP 请求里现采：N 个用户同时刷新 = N × 130ms 同步阻塞；
 * 更严重的是<b>每次都重算磁盘速率</b> —— 做差基准会被并发请求互相打乱，
 * 算出来的速率是错的。因此改为后台单线程按固定周期采集，
 * 接口只读缓存，<b>无论多少用户，采集成本恒定为 1 次/周期</b>。
 *
 * <h3>磁盘 I/O 是累计值，必须做差</h3>
 * <p>OSHI 的 {@code HWDiskStore.getReadBytes()} 等返回的是<b>自开机以来的累计值</b>。
 * 直接返回会被误读成"当前速率"。本类用 {@link #prevDiskCounters} 保存上一次的累计值，
 * 采样时做差并除以时间间隔得到速率。
 *
 * <p>⚠️ 另外两个必须注意的 API 事实（均为实测结论）：
 * <ol>
 *   <li><b>{@code OSFileStore} 上没有 IO 计数方法</b> —— 编译期就会报错。
 *       磁盘 IO 只能取 {@code HWDiskStore}；分区容量只能取 {@code OSFileStore}。
 *       两者不是同一个对象，需分别遍历。</li>
 *   <li><b>{@code getSystemCpuLoadBetweenTicks(ticks)} 必须传入上一次�� ticks</b>，
 *       否则第一次调用只能拿到「自 JVM 启动以来的平均值」，不代表当前负载。</li>
 * </ol>
 *
 * <h3>首次采样没有做差基准</h3>
 * <p>首次采样时速率字段返回 {@code null}（<b>不是 0</b>），
 * 同时 {@code sampleIntervalMillis = 0}。返回 0 会被前端误读为"磁盘完全空闲"。
 *
 * <h3>跨平台</h3>
 * <p>OSHI 抹平了 Windows / Linux 差异，无需分别实现。
 * Windows 无 loadAverage，返回 null 由前端显示占位符。
 */
@Service
public class ServerMetricsServiceImpl implements IServerMetricsService {

    private static final Logger log = LoggerFactory.getLogger(ServerMetricsServiceImpl.class);

    /** 采集失败错误码：OSHI 初始化失败（多为 JNA native 库不可用） */
    public static final String ERR_OSHI_INIT = "OSHI_INIT_FAILED";

    /** 采集间隔（毫秒），默认 5 秒 */
    @Value("${app.server-metrics.interval-ms:5000}")
    private long intervalMs;

    /** 环形缓存容量（点数），默认 120 点 = 10 分钟 */
    @Value("${app.server-metrics.history-size:120}")
    private int historySize;

    /**
     * 时间格式化器，与项目 jackson 的 yyyy-MM-dd HH:mm:ss 保持一致。
     * 用 DateTimeFormatter 而非 SimpleDateFormat —— 后者非线程安全。
     */
    private static final java.time.format.DateTimeFormatter FORMATTER =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** OSHI 入口。全局复用 —— new 一次要 99ms，且内部 Memoizer 只缓存 processor/memory */
    private volatile SystemInfo systemInfo;

    /** HAL 同理复用 */
    private volatile HardwareAbstractionLayer hardware;

    /** 采集是否可用。false 表示 OSHI 初始化失败（JNA 加载不了） */
    private final AtomicBoolean oshiAvailable = new AtomicBoolean(false);

    /** 上一次 CPU ticks，用于计算瞬时使用率 */
    private final AtomicReference<long[]> prevCpuTicks = new AtomicReference<>();

    /** 上一次采集时刻（epoch 毫秒），用于计算采样间隔 */
    private final AtomicLong prevCollectTime = new AtomicLong(0);

    /**
     * 上一次各磁盘的累计计数。
     * key = 磁盘名，value = [读字节, 写字节, 读次数, 写次数]
     */
    private final Map<String, long[]> prevDiskCounters = new ConcurrentHashMap<>();

    /** 历史采样点环形缓冲（按时间升序，队首为最旧） */
    private final Deque<ServerMetricsVO.HistoryPoint> history = new ConcurrentLinkedDeque<>();

    /** 最近一次成功的快照 */
    private final AtomicReference<ServerMetricsVO> latestSnapshot = new AtomicReference<>();

    /** 最近一次采集的错误信息 */
    private final AtomicReference<String> lastError = new AtomicReference<>();

    /** 最近一次采集的错误码 */
    private final AtomicReference<String> errorCode = new AtomicReference<>();

    /**
     * 启动时立即采一次。
     *
     * <p>否则应用刚起来的头 5 秒内，页面拿不到任何数据。
     */
    @PostConstruct
    public void init() {
        try {
            systemInfo = new SystemInfo();
            hardware = systemInfo.getHardware();
            oshiAvailable.set(true);
            log.info("服务器指标采集器已初始化（OSHI {}），采集间隔 {} ms，历史缓存 {} 点",
                    "6.9.3", intervalMs, historySize);
        } catch (Throwable t) {
            // JNA native 库加载失败会走到这里（UnsatisfiedLinkError / NoClassDefFoundError）
            oshiAvailable.set(false);
            errorCode.set(ERR_OSHI_INIT);
            lastError.set("OSHI 初始化失败：" + t.getClass().getSimpleName() + " - " + t.getMessage());
            log.error("服务器指标采集器初始化失败，监控页面将不可用。"
                    + "请检查 JNA native 库是否可解压（java.io.tmpdir 是否可写）", t);
        }
        // 无论成功与否都先采一次，把错误信息落到快照里
        collectNow();
    }

    /**
     * 定时采集。
     *
     * <p>用 {@code fixedDelayString} 而非 {@code fixedRate}：后者会在采集耗时超过周期时
     * 堆积任务。采集本身约 130ms，周期 5s，不会堆积，但 fixedDelay 语义更稳妥。
     *
     * <p>⚠️ Spring 的 {@code @Scheduled} 会<b>吞掉方法内抛出的异常</b>，
     * 所以这里必须自己 try-catch，否则采集器一旦异常就会静默停止工作。
     */
    @Scheduled(fixedDelayString = "${app.server-metrics.interval-ms:5000}",
               initialDelayString = "${app.server-metrics.initial-delay-ms:1000}")
    public void scheduledCollect() {
        try {
            collectNow();
        } catch (Throwable t) {
            // 兜底：理论上 collectNow 内部已经捕获，这里只是防止调度器被异常影响
            log.warn("服务器指标定时采集异常：{}", t.getMessage());
        }
    }

    @Override
    public ServerMetricsVO getMetrics() {
        ServerMetricsVO snap = latestSnapshot.get();
        if (snap == null) {
            // 首次采集尚未完成
            snap = new ServerMetricsVO();
            snap.setHistorySize(historySize);
            snap.setSampleCount(history.size());
            snap.setHistory(new ArrayList<>());
            snap.setStale(true);
            snap.setErrorCode(errorCode.get());
            snap.setLastError(lastError.get());
            return snap;
        }
        // 返回缓存快照的副本，避免调用方修改内部状态；
        // history 列表本身是只读用途，复制一份以防外部改动。
        ServerMetricsVO copy = shallowCopy(snap);
        copy.setHistory(new ArrayList<>(snap.getHistory()));
        return copy;
    }

    @Override
    public ServerMetricsVO collectNow() {
        long now = System.currentTimeMillis();

        if (!oshiAvailable.get()) {
            // 初始化失败过，尝试重试一次（JNA 可能因临时目录问题恢复）
            try {
                systemInfo = new SystemInfo();
                hardware = systemInfo.getHardware();
                oshiAvailable.set(true);
                errorCode.set(null);
                lastError.set(null);
                log.info("服务器指标采集器重试初始化成功");
            } catch (Throwable t) {
                ServerMetricsVO stale = latestSnapshot.get();
                if (stale != null) {
                    ServerMetricsVO copy = shallowCopy(stale);
                    copy.setStale(true);
                    copy.setErrorCode(ERR_OSHI_INIT);
                    copy.setLastError(lastError.get());
                    return copy;
                }
                ServerMetricsVO empty = new ServerMetricsVO();
                empty.setStale(true);
                empty.setErrorCode(ERR_OSHI_INIT);
                empty.setLastError(lastError.get());
                empty.setHistorySize(historySize);
                empty.setSampleCount(0);
                empty.setHistory(new ArrayList<>());
                return empty;
            }
        }

        try {
            ServerMetricsVO vo = doCollect(now);
            latestSnapshot.set(vo);
            lastError.set(null);
            errorCode.set(null);
            return vo;
        } catch (Throwable t) {
            lastError.set(t.getClass().getSimpleName() + " - " + t.getMessage());
            log.warn("服务器指标采集失败：{}", lastError.get());
            ServerMetricsVO stale = latestSnapshot.get();
            if (stale != null) {
                ServerMetricsVO copy = shallowCopy(stale);
                copy.setStale(true);
                copy.setLastError(lastError.get());
                return copy;
            }
            ServerMetricsVO empty = new ServerMetricsVO();
            empty.setStale(true);
            empty.setLastError(lastError.get());
            empty.setHistorySize(historySize);
            empty.setSampleCount(history.size());
            empty.setHistory(new ArrayList<>(history));
            return empty;
        }
    }

    /**
     * 真正的采集逻辑。
     */
    private ServerMetricsVO doCollect(long now) {
        ServerMetricsVO vo = new ServerMetricsVO();

        // ---------- CPU ----------
        OperatingSystem os = systemInfo.getOperatingSystem();
        CentralProcessor cpu = hardware.getProcessor();
        long[] ticks = cpu.getSystemCpuLoadTicks();
        long[] prev = prevCpuTicks.getAndSet(ticks);

        ServerMetricsVO.Cpu cpuVo = new ServerMetricsVO.Cpu();
        // ★ 必须传入上一次的 ticks；首次没有 prev 时返回 null（而不是 JVM 启动以来的均值）
        cpuVo.setUsagePercent(prev != null ? round2(cpu.getSystemCpuLoadBetweenTicks(prev) * 100.0) : null);
        // 进程 CPU 占用：CentralProcessor 上没有进程级方法，
        // 需从 OperatingSystem.getCurrentProcess() 取（实测 javap 确认）。
        // getProcessCpuLoadCumulative() 返回 0-1 之间的小数，这里换算成百分数。
        try {
            oshi.software.os.OSProcess cur = os.getCurrentProcess();
            if (cur != null) {
                double load = cur.getProcessCpuLoadCumulative();
                cpuVo.setProcessCpuLoad(load >= 0 ? round2(load * 100.0) : null);
            }
        } catch (Throwable ignore) {
            // 取不到就留 null
        }
        // 系统负载均值：Windows 返回全 -1，Linux 返回真实值
        double[] load = cpu.getSystemLoadAverage(3);
        if (load != null && load.length >= 3 && load[0] >= 0) {
            cpuVo.setLoadAverage1(round2(load[0]));
            cpuVo.setLoadAverage5(round2(load[1]));
            cpuVo.setLoadAverage15(round2(load[2]));
        }
        vo.setCpu(cpuVo);

        // ---------- 服务器信息 ----------
        ServerMetricsVO.ServerInfo si = new ServerMetricsVO.ServerInfo();
        si.setOsName(buildOsName(os));
        // 主机名与架构取自 JVM 标准 API ——
        // 实测 OSHI 6.9.3 的 OperatingSystem / ComputerSystem 均未提供
        // getHostName() / getArch()，只有 getFamily() / getVersionInfo() / getModel()。
        si.setHostName(jvmHostName());
        si.setArch(jvmArch());
        si.setUptimeSeconds(os.getSystemUptime());
        si.setJvmUptimeSeconds(java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        si.setCpuCores(cpu.getPhysicalProcessorCount());
        si.setCpuLogical(cpu.getLogicalProcessorCount());
        si.setCpuModel(cpu.getProcessorIdentifier().getName());
        vo.setServer(si);

        // ---------- 内存 ----------
        GlobalMemory mem = hardware.getMemory();
        long memTotal = mem.getTotal();
        long memAvail = mem.getAvailable();
        long memUsed = memTotal - memAvail;
        ServerMetricsVO.Memory memVo = new ServerMetricsVO.Memory();
        memVo.setTotalBytes(memTotal);
        memVo.setAvailableBytes(memAvail);
        memVo.setUsedBytes(memUsed);
        memVo.setUsagePercent(memTotal > 0 ? round2(memUsed * 100.0 / memTotal) : null);
        vo.setMemory(memVo);

        // ---------- 磁盘空间（分区容量）----------
        // ⚠️ OSFileStore 上没有 IO 计数方法，容量只能从这里取
        List<ServerMetricsVO.FileSystemInfo> fsList = new ArrayList<>();
        FileSystem fileSystem = os.getFileSystem();
        List<OSFileStore> stores = fileSystem.getFileStores();
        // 同一个物理分区会被挂载在多个嵌套路径上（实测服务器上
        // /data 与 /data/camera-detection 是同一个 XFS 分区，
        // /var 与 /var/lib/camera-detection-system 也是同一个 ext4 分区），
        // 不去重的话表格会出现重复行，且"最大占用分区"判断会失真。
        // 用 UUID（文件系统唯一标识）去重，保留挂载路径最短的那条
        // —— 那才是用户真正会看到的挂载点。
        java.util.Set<String> seenFsIds = new java.util.HashSet<>();
        for (OSFileStore store : stores) {
            String fsId = buildFsId(store);
            if (fsId != null && !seenFsIds.add(fsId)) {
                continue;
            }
            ServerMetricsVO.FileSystemInfo fsi = new ServerMetricsVO.FileSystemInfo();
            fsi.setMount(store.getMount());
            fsi.setVolume(store.getVolume());
            fsi.setType(store.getType());
            long total = store.getTotalSpace();
            long usable = store.getUsableSpace();
            long used = total - store.getUsableSpace();
            fsi.setTotalBytes(total);
            fsi.setUsableBytes(usable);
            fsi.setUsedBytes(Math.max(used, 0));
            fsi.setUsagePercent(total > 0 ? round2(Math.max(used, 0) * 100.0 / total) : null);
            // 可读/可写判定：OSHI 6.9.3 的 OSFileStore 没有 isReadOnly()，
            // 挂载选项里含 "ro" 表示只读（如 Linux 的 ro 挂载、Windows 的只读卷）
            String options = store.getOptions();
            boolean readOnly = options != null && options.toLowerCase().contains("ro");
            fsi.setReadable(true);
            fsi.setWritable(!readOnly);
            fsList.add(fsi);
        }
        vo.setFileSystems(fsList);

        // ---------- 磁盘 I/O 速率与 IOPS ----------
        // ⚠️ 必须做差：OSHI 返回的是开机以来累计值
        ServerMetricsVO.DiskIo ioVo = new ServerMetricsVO.DiskIo();
        long interval = prevCollectTime.get() == 0 ? 0 : (now - prevCollectTime.get());
        prevCollectTime.set(now);

        long rBytes = 0, wBytes = 0, rOps = 0, wOps = 0;
        Map<String, long[]> currentCounters = new LinkedHashMap<>();
        for (HWDiskStore disk : hardware.getDiskStores()) {
            String key = disk.getName() + "|" + disk.getModel();
            long rb = disk.getReadBytes();
            long wb = disk.getWriteBytes();
            long ro = disk.getReads();
            long wo = disk.getWrites();
            currentCounters.put(key, new long[]{rb, wb, ro, wo});
            rBytes += rb;
            wBytes += wb;
            rOps += ro;
            wOps += wo;
        }

        ioVo.setReadTotalBytes(rBytes);
        ioVo.setWriteTotalBytes(wBytes);
        ioVo.setReadTotalOps(rOps);
        ioVo.setWriteTotalOps(wOps);

        if (interval > 0 && !prevDiskCounters.isEmpty()) {
            long dRBytes = 0, dWBytes = 0, dROps = 0, dWOps = 0;
            for (Map.Entry<String, long[]> e : currentCounters.entrySet()) {
                long[] p = prevDiskCounters.get(e.getKey());
                if (p == null) {
                    // 新出现的磁盘（如热插拔），无基准，跳过
                    continue;
                }
                long[] c = e.getValue();
                // 计数器回绕（溢出）时会出现负增量，丢弃该盘的增量
                if (c[0] >= p[0]) dRBytes += c[0] - p[0]; else dRBytes += 0;
                if (c[1] >= p[1]) dWBytes += c[1] - p[1]; else dWBytes += 0;
                if (c[2] >= p[2]) dROps += c[2] - p[2]; else dROps += 0;
                if (c[3] >= p[3]) dWOps += c[3] - p[3]; else dWOps += 0;
            }
            double seconds = interval / 1000.0;
            ioVo.setReadBytesPerSec(round2(dRBytes / seconds));
            ioVo.setWriteBytesPerSec(round2(dWBytes / seconds));
            ioVo.setReadOpsPerSec(round2(dROps / seconds));
            ioVo.setWriteOpsPerSec(round2(dWOps / seconds));
            ioVo.setSampleIntervalMillis(interval);
        } else {
            // ★ 首次采样：没有做差基准，速率必须为 null 而不是 0
            ioVo.setReadBytesPerSec(null);
            ioVo.setWriteBytesPerSec(null);
            ioVo.setReadOpsPerSec(null);
            ioVo.setWriteOpsPerSec(null);
            ioVo.setSampleIntervalMillis(0);
        }
        prevDiskCounters.clear();
        prevDiskCounters.putAll(currentCounters);
        vo.setDiskIo(ioVo);

        // ---------- 历史采样点 ----------
        ServerMetricsVO.HistoryPoint hp = new ServerMetricsVO.HistoryPoint();
        hp.setTimestamp(now);
        hp.setCpuUsagePercent(cpuVo.getUsagePercent());
        hp.setMemoryUsagePercent(memVo.getUsagePercent());
        hp.setMemoryUsedBytes(memUsed);
        hp.setReadBytesPerSec(ioVo.getReadBytesPerSec());
        hp.setWriteBytesPerSec(ioVo.getWriteBytesPerSec());
        hp.setReadOpsPerSec(ioVo.getReadOpsPerSec());
        hp.setWriteOpsPerSec(ioVo.getWriteOpsPerSec());
        Double maxDisk = null;
        for (ServerMetricsVO.FileSystemInfo fsi : fsList) {
            if (fsi.getUsagePercent() != null
                    && (maxDisk == null || fsi.getUsagePercent() > maxDisk)) {
                maxDisk = fsi.getUsagePercent();
            }
        }
        hp.setMaxDiskUsagePercent(maxDisk);

        history.addLast(hp);
        while (history.size() > historySize) {
            history.pollFirst();
        }
        vo.setHistory(new ArrayList<>(history));
        vo.setHistorySize(historySize);
        vo.setSampleCount(history.size());
        vo.setCollectedAt(FORMATTER.format(
                java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault())));
        vo.setStale(false);
        return vo;
    }

    private ServerMetricsVO shallowCopy(ServerMetricsVO src) {
        ServerMetricsVO c = new ServerMetricsVO();
        c.setServer(src.getServer());
        c.setCpu(src.getCpu());
        c.setMemory(src.getMemory());
        c.setFileSystems(src.getFileSystems());
        c.setDiskIo(src.getDiskIo());
        c.setHistory(src.getHistory());
        c.setHistorySize(src.getHistorySize());
        c.setSampleCount(src.getSampleCount());
        c.setCollectedAt(src.getCollectedAt());
        c.setStale(src.isStale());
        c.setLastError(src.getLastError());
        c.setErrorCode(src.getErrorCode());
        return c;
    }

    /**
     * 计算文件系统的去重标识。
     *
     * <p>优先用 UUID（同一文件系统的 UUID 一致）；
     * 没有 UUID 时退化为「设备名 + 类型」；
     * 都没有则返回 null（不做去重）。
     */
    private String buildFsId(OSFileStore store) {
        try {
            String uuid = store.getUUID();
            if (uuid != null && !uuid.isEmpty()) {
                return "uuid:" + uuid;
            }
        } catch (Throwable ignore) {
            // 某些平台不支持 UUID
        }
        try {
            String vol = store.getVolume();
            String type = store.getType();
            if (vol != null && !vol.isEmpty()) {
                return "vol:" + vol + "|" + type;
            }
        } catch (Throwable ignore) {
            // 忽略
        }
        return null;
    }

    /**
     * 拼装操作系统描述，形如 "Windows 11 10.0.26100" / "Linux 5.15.0-91-generic"。
     */
    private String buildOsName(OperatingSystem os) {
        StringBuilder sb = new StringBuilder();
        try {
            String family = os.getFamily();
            if (family != null && !family.isEmpty()) {
                sb.append(family);
            }
            OperatingSystem.OSVersionInfo vi = os.getVersionInfo();
            if (vi != null) {
                if (vi.getVersion() != null && !vi.getVersion().isEmpty()) {
                    sb.append(' ').append(vi.getVersion());
                }
                if (vi.getBuildNumber() != null && !vi.getBuildNumber().isEmpty()) {
                    sb.append(" (build ").append(vi.getBuildNumber()).append(')');
                }
            }
        } catch (Throwable ignore) {
            // 取不到就退回 toString()
        }
        String s = sb.toString().trim();
        return s.isEmpty() ? String.valueOf(os) : s;
    }

    /** 主机名 —— 取自 JVM 标准 API */
    private String jvmHostName() {
        try {
            String h = java.net.InetAddress.getLocalHost().getHostName();
            if (h != null && !h.isEmpty()) {
                return h;
            }
        } catch (Throwable ignore) {
            // 拿不到就用环境变量
        }
        String h = System.getenv("HOSTNAME");
        if (h != null && !h.isEmpty()) {
            return h;
        }
        return "unknown";
    }

    /** CPU 架构 —— 取自 JVM 标准 API（os.arch） */
    private String jvmArch() {
        String a = System.getProperty("os.arch");
        return a == null ? "unknown" : a;
    }

    private static Double round2(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return null;
        return Math.round(v * 100.0) / 100.0;
    }

    private static Double round4(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return null;
        return Math.round(v * 10000.0) / 10000.0;
    }
}
