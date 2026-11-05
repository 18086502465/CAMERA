package com.wms.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wms.device.api.ServerMetricsController;
import com.wms.device.app.IServerMetricsService;
import com.wms.device.api.vo.ServerMetricsVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 服务器监控接口契约测试：GET /api/v1/system/server/metrics。
 *
 * <p>不加载 Spring 上下文与数据库，纯 MockMvc standalone。
 *
 * <p><b>重点验证两件事</b>：
 * <ol>
 *   <li>首次采样时磁盘速率必须为 <b>null</b>（不是 0）——
 *       返回 0 会被前端误读为"磁盘完全空闲"，这是方案里明确标注的高风险点。</li>
 *   <li>{@code sampleIntervalMillis=0} 必须原样透出，
 *       前端据此显示"--"而不是显示 0。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class ServerMetricsControllerTest {

    private static final String URL = "/api/v1/system/server/metrics";

    @Mock
    private IServerMetricsService serverMetricsService;

    @InjectMocks
    private ServerMetricsController controller;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("首次采样：磁盘速率为 null 而非 0，sampleIntervalMillis 为 0")
    void firstSampleHasNullDiskRate() throws Exception {
        ServerMetricsVO vo = new ServerMetricsVO();

        ServerMetricsVO.DiskIo io = new ServerMetricsVO.DiskIo();
        // ★ 首次采样没有做差基准 —— 必须是 null
        io.setReadBytesPerSec(null);
        io.setWriteBytesPerSec(null);
        io.setReadOpsPerSec(null);
        io.setWriteOpsPerSec(null);
        io.setReadTotalBytes(123456789L);
        io.setWriteTotalBytes(987654321L);
        io.setSampleIntervalMillis(0L);
        vo.setDiskIo(io);

        ServerMetricsVO.Cpu cpu = new ServerMetricsVO.Cpu();
        cpu.setUsagePercent(null);   // 首次没有上一次的 ticks，CPU 也是 null
        vo.setCpu(cpu);

        ServerMetricsVO.Memory mem = new ServerMetricsVO.Memory();
        mem.setTotalBytes(34359738368L);
        mem.setAvailableBytes(12884901888L);
        mem.setUsedBytes(21474836480L);
        mem.setUsagePercent(62.5);
        vo.setMemory(mem);

        vo.setHistory(Collections.<ServerMetricsVO.HistoryPoint>emptyList());
        vo.setHistorySize(120);
        vo.setSampleCount(1);

        when(serverMetricsService.getMetrics()).thenReturn(vo);

        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.diskIo.readBytesPerSec").doesNotExist())
                .andExpect(jsonPath("$.data.diskIo.writeBytesPerSec").doesNotExist())
                .andExpect(jsonPath("$.data.diskIo.readOpsPerSec").doesNotExist())
                .andExpect(jsonPath("$.data.diskIo.writeOpsPerSec").doesNotExist())
                .andExpect(jsonPath("$.data.diskIo.sampleIntervalMillis").value(0))
                .andExpect(jsonPath("$.data.diskIo.readTotalBytes").value(123456789L))
                .andExpect(jsonPath("$.data.memory.usagePercent").value(62.5))
                .andExpect(jsonPath("$.data.cpu.usagePercent").doesNotExist());
    }

    @Test
    @DisplayName("正常采样：磁盘速率与 IOPS 正确透出")
    void normalSampleHasRates() throws Exception {
        ServerMetricsVO vo = new ServerMetricsVO();

        ServerMetricsVO.DiskIo io = new ServerMetricsVO.DiskIo();
        io.setReadBytesPerSec(1048576.0);
        io.setWriteBytesPerSec(5242880.0);
        io.setReadOpsPerSec(12.0);
        io.setWriteOpsPerSec(86.0);
        io.setReadTotalBytes(174375094784L);
        io.setWriteTotalBytes(178219936768L);
        io.setReadTotalOps(3715855L);
        io.setWriteTotalOps(13804500L);
        io.setSampleIntervalMillis(5000L);
        vo.setDiskIo(io);

        ServerMetricsVO.Cpu cpu = new ServerMetricsVO.Cpu();
        cpu.setUsagePercent(12.5);
        cpu.setLoadAverage1(0.35);
        vo.setCpu(cpu);

        vo.setFileSystems(Collections.<ServerMetricsVO.FileSystemInfo>emptyList());
        vo.setHistory(Collections.<ServerMetricsVO.HistoryPoint>emptyList());
        vo.setHistorySize(120);
        vo.setSampleCount(10);
        vo.setCollectedAt("2026-11-02 18:00:00");

        when(serverMetricsService.getMetrics()).thenReturn(vo);

        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.diskIo.readBytesPerSec").value(1048576.0))
                .andExpect(jsonPath("$.data.diskIo.writeBytesPerSec").value(5242880.0))
                .andExpect(jsonPath("$.data.diskIo.readOpsPerSec").value(12.0))
                .andExpect(jsonPath("$.data.diskIo.writeOpsPerSec").value(86.0))
                .andExpect(jsonPath("$.data.diskIo.sampleIntervalMillis").value(5000))
                .andExpect(jsonPath("$.data.cpu.usagePercent").value(12.5))
                .andExpect(jsonPath("$.data.cpu.loadAverage1").value(0.35))
                .andExpect(jsonPath("$.data.collectedAt").value("2026-11-02 18:00:00"));
    }

    @Test
    @DisplayName("history 参数裁剪历史点数")
    void historyParamTrimsPoints() throws Exception {
        ServerMetricsVO vo = new ServerMetricsVO();

        List<ServerMetricsVO.HistoryPoint> all = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            ServerMetricsVO.HistoryPoint hp = new ServerMetricsVO.HistoryPoint();
            hp.setTimestamp(1762051200000L + i * 5000L);
            hp.setCpuUsagePercent((double) i);
            all.add(hp);
        }
        vo.setHistory(all);
        vo.setHistorySize(120);
        vo.setSampleCount(30);
        vo.setDiskIo(new ServerMetricsVO.DiskIo());

        when(serverMetricsService.getMetrics()).thenReturn(vo);

        mockMvc.perform(get(URL).param("history", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.history.length()").value(5))
                // 保留的是最新的 5 个（timestamp 从 25 开始）
                .andExpect(jsonPath("$.data.history[0].timestamp").value(1762051200000L + 25 * 5000L))
                .andExpect(jsonPath("$.data.history[4].timestamp").value(1762051200000L + 29 * 5000L));
    }

    @Test
    @DisplayName("采集失败：stale 与 errorCode 透出，不抛异常")
    void staleSnapshotIsReturned() throws Exception {
        ServerMetricsVO vo = new ServerMetricsVO();
        vo.setStale(true);
        vo.setErrorCode("OSHI_INIT_FAILED");
        vo.setLastError("OSHI 初始化失败：UnsatisfiedLinkError");
        vo.setHistory(Collections.<ServerMetricsVO.HistoryPoint>emptyList());
        vo.setSampleCount(0);

        when(serverMetricsService.getMetrics()).thenReturn(vo);

        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.stale").value(true))
                .andExpect(jsonPath("$.data.errorCode").value("OSHI_INIT_FAILED"))
                .andExpect(jsonPath("$.data.lastError").exists());
    }

    @Test
    @DisplayName("响应体符合 ResultVO 契约：code / message / data")
    void responseMatchesResultVoContract() throws Exception {
        ServerMetricsVO vo = new ServerMetricsVO();
        vo.setHistory(Collections.<ServerMetricsVO.HistoryPoint>emptyList());
        vo.setDiskIo(new ServerMetricsVO.DiskIo());
        vo.setServer(new ServerMetricsVO.ServerInfo());

        when(serverMetricsService.getMetrics()).thenReturn(vo);

        String body = mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.data").exists())
                .andReturn().getResponse().getContentAsString();

        // 确认没有把内部字段泄漏出去
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("prevDiskCounters"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("systemInfo"));
    }
}
