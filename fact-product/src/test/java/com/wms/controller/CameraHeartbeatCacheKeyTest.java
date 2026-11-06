package com.wms.controller;

import com.wms.device.api.dto.CameraStatusHeartbeatDTO;
import com.wms.device.app.ICameraStatusService;
import com.wms.device.app.CameraDirectory;
import com.wms.device.domain.model.CameraStatus;
import com.wms.device.infra.mapper.CameraStatusMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 心跳计数缓存的相机身份契约测试（2026-11-06）。
 *
 * <p>背景：现场 {@code camera_name} **跨产线重名**（ZK54 的 CV1_Cam1/DA8119487
 * 与 ZK23 的 CV1_Cam1/DA8119489 同名；CV2_Cam1 同理）。
 * 心跳处理里的计数缓存 {@code statusCache} 原以 {@code camera_name} 为键 →
 * 两台同名相机**共用一个缓存槽位**，导致：
 * <ul>
 *   <li>计数互相覆盖 → 两台相机的累计检测数几乎相同（实测 348952 / 348953）；</li>
 *   <li>互相"变小"→ 误报「计数器重置」（11-05 07:52 一次冒出 7 条）；</li>
 *   <li>一台设备的 resetFlag 把**另一台**的缓存一起清掉。</li>
 * </ul>
 *
 * <p>修复：缓存键改用 {@code camera_code}（心跳的 {@code X-Device-Id} 头），
 * 名称仅在无编号时兜底。
 *
 * <p>本测试冻结：**同名不同编号的相机，计数互不影响**。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CameraHeartbeatCacheKeyTest {

    @Mock
    private ICameraStatusService cameraStatusService;
    @Mock
    private CameraStatusMapper cameraStatusMapper;
    @Mock
    private CameraDirectory cameraDirectory;
    @Mock
    private com.wms.device.infra.repository.CameraDirectoryRepository cameraDirectoryRepository;
    /** 心跳告警经事件发布（DDD P4 解耦跨域依赖） */
    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    /**
     * 直接驱动被测服务（缓存键逻辑在 service 内部，不经过 Controller）。
     * 这里用反射读取私有缓存，验证两台同名相机的槽位互不干扰。
     */
    @Test
    @DisplayName("心跳计数缓存：同名不同编号的相机各占独立槽位")
    void heartbeatCacheKeyIsCodeFirst() throws Exception {
        com.wms.device.app.CameraStatusServiceImpl service =
                new com.wms.device.app.CameraStatusServiceImpl();

        // 注入依赖（@Autowired 字段）
        inject(service, "cameraStatusMapper", cameraStatusMapper);
        inject(service, "cameraDirectoryRepository", cameraDirectoryRepository);
        inject(service, "eventPublisher", eventPublisher);

        // 心跳要求设备已在系统配置中维护（否则抛「设备未在系统配置中维护」）→ mock 出已存在的行
        when(cameraStatusMapper.selectOne(any())).thenAnswer(inv -> {
            CameraStatus s = new CameraStatus();
            s.setId(1L);
            s.setCameraCode("DA8119489");
            s.setCameraName("CV1_Cam1");
            s.setLineId(5);
            s.setWorkstationId(23);
            s.setIsActive(1);
            return s;
        });
        when(cameraStatusMapper.selectList(any())).thenReturn(List.of());

        CameraStatusHeartbeatDTO dto = new CameraStatusHeartbeatDTO();
        dto.setCameraName("CV1_Cam1");     // ★ 两台相机同名
        dto.setRecognizeCount(100);
        dto.setCorrectCount(95);
        dto.setAbnormalCount(5);
        dto.setCameraStatus(1);

        // ZK23 的 CV1_Cam1（DA8119489）
        service.processHeartbeat(dto, "DA8119489");
        // ZK54 的 CV1_Cam1（DA8119487）—— 同名但不同编号
        dto.setRecognizeCount(7);
        service.processHeartbeat(dto, "DA8119487");

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> cache = (java.util.Map<String, Object>) getField(service, "statusCache");
        assertNotNull(cache, "statusCache 字段未找到");

        // ★ 修复前：两个 code 共用 name 槽位 → 只有 1 个条目（后者覆盖前者）
        //   修复后：按 code 分槽 → 2 个条目
        assertEquals(2, cache.size(), "同名相机的计数缓存必须各自独立（修复前会被覆盖成 1 个）");

        // 验证槽位键是 code 形态（而不是 name）
        assertEquals(true, cache.keySet().stream().anyMatch(k -> k.contains("DA8119489")),
                "缓存键应包含 camera_code DA8119489，实际键：" + cache.keySet());
        assertEquals(true, cache.keySet().stream().anyMatch(k -> k.contains("DA8119487")),
                "缓存键应包含 camera_code DA8119487，实际键：" + cache.keySet());

        // 两台相机的识别计数各自独立
        Object c1 = readRecognizeCount(cache, "DA8119489");
        Object c2 = readRecognizeCount(cache, "DA8119487");
        assertEquals(100, ((Number) c1).intValue(), "DA8119489 的 recognizeCount 应为 100");
        assertEquals(7, ((Number) c2).intValue(), "DA8119487 的 recognizeCount 应为 7");
    }

    @Test
    @DisplayName("心跳计数缓存：无 deviceId 时退回按名称，且带前缀不与 code 冲突")
    void heartbeatCacheKeyFallsBackToName() throws Exception {
        com.wms.device.app.CameraStatusServiceImpl service =
                new com.wms.device.app.CameraStatusServiceImpl();
        inject(service, "cameraStatusMapper", cameraStatusMapper);
        inject(service, "cameraDirectoryRepository", cameraDirectoryRepository);
        inject(service, "eventPublisher", eventPublisher);
        when(cameraStatusMapper.selectOne(any())).thenAnswer(inv -> {
            CameraStatus s = new CameraStatus();
            s.setId(1L);
            s.setCameraCode("DA8119489");
            s.setCameraName("CV1_Cam1");
            s.setLineId(5);
            s.setWorkstationId(23);
            s.setIsActive(1);
            return s;
        });
        // 无 deviceId → 走名称兜底，名称必须唯一（selectList 返回 1 行）
        when(cameraStatusMapper.selectList(any())).thenReturn(List.of(namedStatus()));

        CameraStatusHeartbeatDTO dto = new CameraStatusHeartbeatDTO();
        dto.setCameraName("CV1_Cam1");
        dto.setRecognizeCount(50);
        dto.setCameraStatus(1);
        service.processHeartbeat(dto, null);

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> cache = (java.util.Map<String, Object>) getField(service, "statusCache");
        assertEquals(1, cache.size());
        assertEquals(true, cache.keySet().iterator().next().contains("CV1_Cam1"),
                "无编号时应退回按名称建槽，实际键：" + cache.keySet());
    }

    @Test
    @DisplayName("心跳计数缓存：resetFlag 只清自己那一台的槽位")
    void resetFlagOnlyClearsOwnSlot() throws Exception {
        com.wms.device.app.CameraStatusServiceImpl service =
                new com.wms.device.app.CameraStatusServiceImpl();
        inject(service, "cameraStatusMapper", cameraStatusMapper);
        inject(service, "cameraDirectoryRepository", cameraDirectoryRepository);
        inject(service, "eventPublisher", eventPublisher);
        when(cameraStatusMapper.selectOne(any())).thenAnswer(inv -> {
            CameraStatus s = new CameraStatus();
            s.setId(1L);
            s.setCameraCode("DA8119489");
            s.setCameraName("CV1_Cam1");
            s.setLineId(5);
            s.setWorkstationId(23);
            s.setIsActive(1);
            return s;
        });
        when(cameraStatusMapper.selectList(any())).thenReturn(List.of());

        CameraStatusHeartbeatDTO dto = new CameraStatusHeartbeatDTO();
        dto.setCameraName("CV1_Cam1");
        dto.setRecognizeCount(100);
        dto.setCameraStatus(1);
        service.processHeartbeat(dto, "DA8119489");
        dto.setRecognizeCount(200);
        service.processHeartbeat(dto, "DA8119487");

        dto.setResetFlag(true);
        service.processHeartbeat(dto, "DA8119489");

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> cache = (java.util.Map<String, Object>) getField(service, "statusCache");
        // resetFlag 的语义是「清掉本机旧计数，从头记」→ 同一次心跳会重新写入自己的槽位，
        // 所以槽位数仍是 2。★ 关键是**另一台相机的计数既没被清掉、也没被覆盖**。
        assertEquals(2, cache.size(), "resetFlag 不应影响另一台相机的槽位，实际剩 " + cache.size() + " 个");
        assertEquals(200, ((Number) readRecognizeCount(cache, "DA8119487")).intValue(),
                "另一台相机的 recognizeCount 应仍为 200（未被 resetFlag 清掉/覆盖）");
    }

    // ---------------- 工具 ----------------

    /** 名称兜底场景下"名称唯一"的一行配置 */
    private static CameraStatus namedStatus() {
        CameraStatus s = new CameraStatus();
        s.setId(2L);
        s.setCameraCode("DA8119489");
        s.setCameraName("CV1_Cam1");
        s.setLineId(5);
        s.setWorkstationId(23);
        s.setIsActive(1);
        return s;
    }

    private static Object readRecognizeCount(java.util.Map<String, Object> cache, String code) throws Exception {
        for (java.util.Map.Entry<String, Object> e : cache.entrySet()) {
            if (e.getKey().contains(code)) {
                Object v = e.getValue();
                java.lang.reflect.Field f = v.getClass().getDeclaredField("recognizeCount");
                f.setAccessible(true);
                return f.get(v);
            }
        }
        throw new AssertionError("缓存中没有 " + code + " 的槽位：" + cache.keySet());
    }

    private static Object getField(Object target, String name) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static void inject(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
