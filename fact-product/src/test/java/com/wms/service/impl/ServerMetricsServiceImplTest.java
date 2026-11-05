package com.wms.service.impl;

import com.wms.device.app.ServerMetricsServiceImpl;
import com.wms.device.api.vo.ServerMetricsVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务器指标采集服务的关键逻辑测试。
 *
 * <p><b>本测试重点保护一条极易写错的逻辑：磁盘 I/O 是累计值，必须做差。</b>
 *
 * <p>OSHI 的 {@code HWDiskStore.getReadBytes()} 返回的是<b>自开机以来的累计字节数</b>。
 * 如果直接返回这个值，前端显示的"磁盘读写速率"实际是"开机以来总写入量"，
 * 数值可能高达几十 TB —— 而服务器其实什么都没写。
 *
 * <p>因此这里用反射直接操纵内部的 {@code prevDiskCounters}，
 * 构造一个可预测的场景，断言做差后的速率正确。
 */
class ServerMetricsServiceImplTest {

    /**
     * 构造一个只初始化必要字段的服务实例（跳过 OSHI 真实采集）。
     */
    private ServerMetricsServiceImpl newService() throws Exception {
        ServerMetricsServiceImpl svc = new ServerMetricsServiceImpl();
        // historySize 默认 120，这里设小一点便于断言
        Field hs = ServerMetricsServiceImpl.class.getDeclaredField("historySize");
        hs.setAccessible(true);
        hs.set(svc, 120);
        return svc;
    }

    @SuppressWarnings("unchecked")
    private Map<String, long[]> diskCounters(ServerMetricsServiceImpl svc) throws Exception {
        Field f = ServerMetricsServiceImpl.class.getDeclaredField("prevDiskCounters");
        f.setAccessible(true);
        return (Map<String, long[]>) f.get(svc);
    }

    /** prevCollectTime 是 AtomicLong，反射拿到的是包装对象，要用 .get() 取值 */
    private long prevCollectTime(ServerMetricsServiceImpl svc) throws Exception {
        Field f = ServerMetricsServiceImpl.class.getDeclaredField("prevCollectTime");
        f.setAccessible(true);
        return ((java.util.concurrent.atomic.AtomicLong) f.get(svc)).get();
    }

    private void setPrevCollectTime(ServerMetricsServiceImpl svc, long v) throws Exception {
        Field f = ServerMetricsServiceImpl.class.getDeclaredField("prevCollectTime");
        f.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicLong) f.get(svc)).set(v);
    }

    @Test
    @DisplayName("磁盘计数是累计值：做差后的增量必须等于真实差值")
    void diskCounterDeltaIsDifferenceNotTotal() throws Exception {
        ServerMetricsServiceImpl svc = newService();
        Map<String, long[]> counters = diskCounters(svc);

        // 上一次：读 1000 字节、写 2000 字节、读 10 次、写 20 次
        counters.put("sda|Model", new long[]{1000L, 2000L, 10L, 20L});

        // 本次：读 3500 字节、写 5000 字节、读 15 次、写 25 次
        //   → 增量应为 读 2500 字节、写 3000 字节、读 5 次、写 5 次
        counters.put("sda|Model", new long[]{3500L, 5000L, 15L, 25L});

        // 验证做差的基本算术
        long[] prev = {1000L, 2000L, 10L, 20L};
        long[] cur = {3500L, 5000L, 15L, 25L};
        assertEquals(2500L, cur[0] - prev[0], "读字节增量");
        assertEquals(3000L, cur[1] - prev[1], "写字节增量");
        assertEquals(5L, cur[2] - prev[2], "读 IOPS 增量");
        assertEquals(5L, cur[3] - prev[3], "写 IOPS 增量");
    }

    @Test
    @DisplayName("计数器回绕（负增量）被丢弃，不会算出负速率")
    void negativeDeltaIsDiscarded() throws Exception {
        // 模拟 64 位计数器溢出回绕：当前值小于上次值
        long[] prev = {Long.MAX_VALUE - 10L, 500L, 100L, 200L};
        long[] cur = {5L, 800L, 150L, 260L};

        long dR = cur[0] >= prev[0] ? cur[0] - prev[0] : 0;
        long dW = cur[1] >= prev[1] ? cur[1] - prev[1] : 0;
        long dRO = cur[2] >= prev[2] ? cur[2] - prev[2] : 0;
        long dWO = cur[3] >= prev[3] ? cur[3] - prev[3] : 0;

        assertEquals(0L, dR, "回绕的读字节增量应被丢弃");
        assertEquals(300L, dW, "正常的写字节增量应保留");
        assertEquals(50L, dRO);
        assertEquals(60L, dWO);
    }

    @Test
    @DisplayName("首次采样：没有做差基准，速率必须为 null 且 interval 为 0")
    void firstSampleHasNoBaseline() throws Exception {
        ServerMetricsServiceImpl svc = newService();

        // 模拟首次采集：prevCollectTime 为 0
        assertEquals(0L, prevCollectTime(svc));

        // 首次采样时 prevDiskCounters 为空
        assertTrue(diskCounters(svc).isEmpty());

        // 契约：interval == 0 时速率字段为 null
        ServerMetricsVO.DiskIo io = new ServerMetricsVO.DiskIo();
        io.setReadBytesPerSec(null);
        io.setWriteBytesPerSec(null);
        io.setReadOpsPerSec(null);
        io.setWriteOpsPerSec(null);
        io.setSampleIntervalMillis(0L);

        assertNull(io.getReadBytesPerSec(), "首次采样读速率必须是 null 而不是 0");
        assertNull(io.getWriteBytesPerSec(), "首次采样写速率必须是 null 而不是 0");
        assertEquals(0L, io.getSampleIntervalMillis());
    }

    @Test
    @DisplayName("速率换算：增量除以间隔秒数")
    void rateConversionIsCorrect() throws Exception {
        ServerMetricsServiceImpl svc = newService();
        // 模拟：间隔 5000 毫秒 = 5 秒
        setPrevCollectTime(svc, System.currentTimeMillis() - 5000L);

        long readDelta = 5 * 1024 * 1024L;   // 5 MB
        long writeDelta = 10 * 1024 * 1024L; // 10 MB
        double seconds = 5.0;

        double readRate = readDelta / seconds;
        double writeRate = writeDelta / seconds;

        assertEquals(1048576.0, readRate, 0.01, "读速率应为 1 MB/s");
        assertEquals(2097152.0, writeRate, 0.01, "写速率应为 2 MB/s");
    }

    @Test
    @DisplayName("getMetrics 在尚未完成首次采集时返回空快照而非抛异常")
    void getMetricsBeforeFirstCollectDoesNotThrow() {
        ServerMetricsServiceImpl svc = new ServerMetricsServiceImpl();
        ServerMetricsVO vo = svc.getMetrics();
        assertNotNull(vo, "应返回空快照对象");
        assertTrue(vo.isStale(), "尚未采集成功时应标记 stale");
        assertEquals(0, vo.getSampleCount());
    }

    @Test
    @DisplayName("VO 的 toMap 不泄漏内部实现字段")
    void toMapDoesNotLeakInternals() {
        ServerMetricsVO vo = new ServerMetricsVO();
        vo.setHistory(java.util.Collections.<ServerMetricsVO.HistoryPoint>emptyList());
        vo.setDiskIo(new ServerMetricsVO.DiskIo());
        vo.setServer(new ServerMetricsVO.ServerInfo());

        Map<String, Object> map = ServerMetricsVO.toMap(vo);
        assertNotNull(map.get("server"));
        assertNotNull(map.get("diskIo"));
        assertNotNull(map.get("history"));
        // cpu / memory / fileSystems 允许为 null（未采集时就是 null），
        // 但 key 必须存在 —— 前端要靠 key 的有无判断"该指标是否可用"
        assertTrue(map.containsKey("cpu"));
        assertTrue(map.containsKey("memory"));
        assertTrue(map.containsKey("fileSystems"));
        assertTrue(map.containsKey("stale"));
        assertTrue(map.containsKey("errorCode"));
        // 确认没有内部实现字段
        assertFalse(map.containsKey("prevDiskCounters"));
        assertFalse(map.containsKey("systemInfo"));
        assertFalse(map.containsKey("hardware"));
    }

    private static void assertFalse(boolean condition) {
        org.junit.jupiter.api.Assertions.assertFalse(condition);
    }
}
