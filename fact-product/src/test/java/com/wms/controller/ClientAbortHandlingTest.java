package com.wms.controller;

import com.wms.shared.common.event.SchemaIndexFailedEvent;
import com.wms.shared.common.event.UploadFailedEvent;
import com.wms.shared.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 客户端断连处理契约测试（2026-11-19 评审 P1-2）。
 *
 * <p><b>背景</b>：客户端断连（{@code ClientAbortException: Broken pipe}）的异常
 * 走的是兜底 {@code handleException}，而那里<b>只 log.error、没有发布事件</b>
 * → 这类请求**从来没有过告警**（现场近 24h 10 条断连，告警表里 0 条）。
 *
 * <p><b>关键约束</b>：客户端已断连，往 socket 写任何字节都必然再次抛 Broken pipe
 * → 处理器<b>不能写响应</b>（现场日志里的
 * {@code Failure in @ExceptionHandler ... Broken pipe} 就是这么来的，
 * 它会掩盖原始异常并打断处理链）。
 *
 * <p>本测试冻结：断连 → ①发事件（告警能落库）②不写响应（不产生二次异常）。
 */
@ExtendWith(MockitoExtension.class)
class ClientAbortHandlingTest {

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private GlobalExceptionHandler handler;

    /** 模拟设备端上传中途断连的 Controller */
    @RestController
    static class AbortController {
        @GetMapping("/abort")
        public String abortDirect() throws IOException {
            throw new org.apache.catalina.connector.ClientAbortException("Broken pipe");
        }

        @GetMapping("/abort-wrapped")
        public String abortWrapped() throws IOException {
            throw new IOException("wrapped",
                    new org.apache.catalina.connector.ClientAbortException("Broken pipe"));
        }
    }

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        ReflectionTestUtils.setField(handler, "eventPublisher", eventPublisher);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AbortController())
                .setControllerAdvice(handler)
                .build();
        this.mockMvc = mockMvc;
    }

    private MockMvc mockMvc;

    /** 直接抛 ClientAbortException */
    @Test
    @DisplayName("直接 ClientAbortException → 发事件（告警能落库）+ 不写响应")
    void directClientAbortShouldPublishEventAndNotWriteBody() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/abort")
                        .header("X-Device-Id", "DA4733319"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

        ArgumentCaptor<Object> evt = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(1)).publishEvent(evt.capture());

        Object event = evt.getValue();
        assertTrue(event instanceof UploadFailedEvent, "应发布 UploadFailedEvent，实际：" + event);
        UploadFailedEvent uf = (UploadFailedEvent) event;
        assertEquals("DA4733319", uf.getDeviceId(), "事件应带设备号");
        assertEquals("ClientAbortException", uf.getErrorType(), "事件应标明异常类型");
        assertTrue(uf.getReason().contains("客户端断开连接"), "事件原因应说明是断连：" + uf.getReason());
    }

    /** 被包装的 ClientAbortException（cause 链里） */
    @Test
    @DisplayName("被包装的 ClientAbortException → 同样发事件（cause 链识别）")
    void wrappedClientAbortShouldAlsoPublishEvent() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/abort-wrapped"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

        ArgumentCaptor<Object> evt = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(1)).publishEvent(evt.capture());
        assertTrue(evt.getValue() instanceof UploadFailedEvent,
                "包装后的断连也应发 UploadFailedEvent，实际：" + evt.getValue());
    }

    /** 普通异常不能被误判成断连 */
    @Test
    @DisplayName("普通异常不走断连分支（不误发 UploadFailedEvent）")
    void ordinaryExceptionShouldNotPublishUploadEvent() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new OrdinaryController())
                .setControllerAdvice(handler).build();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/boom"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isInternalServerError());

        verify(eventPublisher, never()).publishEvent(any());
    }

    @RestController
    static class OrdinaryController {
        @GetMapping("/boom")
        public String boom() {
            throw new IllegalStateException("普通故障");
        }
    }

    /** 事件发布器为空时不能抛异常（保底） */
    @Test
    @DisplayName("事件发布器为 null 时也不抛异常（保底，不影响服务）")
    void nullPublisherShouldNotBreak() throws Exception {
        ReflectionTestUtils.setField(handler, "eventPublisher", null);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/abort"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        // 不抛异常即通过
        assertNotNull(handler);
    }
}
