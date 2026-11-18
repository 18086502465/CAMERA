package com.wms.shared.web;

import com.wms.shared.common.ResultVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器（QUAL-01，2026-11-06）。
 *
 * <p>此前全仓没有任何 {@code @ControllerAdvice}：未捕获异常一律走 Spring 默认
 * {@code /error}，返回 {@code {timestamp,status,error,path}} —— 前端读
 * {@code res.data.code} 得到 undefined，页面只能显示“操作失败”，排查全靠翻日志
 * （BUG-01 的 SQL 1054 就是这样以“未知错误”呈现的）。
 *
 * <p>本类把所有未捕获异常统一成 {@link ResultVO} 契约，同时：
 * <ul>
 *   <li><b>不吞安全异常</b>：{@link AccessDeniedException} / {@link AuthenticationException}
 *       原样重抛，交回 {@code ExceptionTranslationFilter} —— 401/403 分派链路
 *       （2026-10 改造，勿回退）依赖它返回 AUTHENTICATION_REQUIRED / ACCESS_DENIED，
 *       在这里接住会把 403 变成 500，毁掉整套前端跳转逻辑；</li>
 *   <li>HTTP 状态与错误类别对齐：参数类 400、数据访问 500、其余 500。</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 事件发布器（上传失败告警用；可为空 → 跳过发事件，不影响上传响应） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    /** 参数/状态不合法（业务代码大量使用 IllegalArgumentException 表达入参错误） */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ResultVO<Void>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ResultVO.fail("参数错误: " + e.getMessage()));
    }

    /** 请求体不可解析（JSON 格式错、类型不匹配等） */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ResultVO<Void>> handleUnreadable(org.springframework.http.converter.HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ResultVO.fail("请求体格式错误"));
    }

    /** 必填参数缺失 / 类型错误 */
    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<ResultVO<Void>> handleMissingParam(org.springframework.web.bind.MissingServletRequestParameterException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ResultVO.fail("缺少必要参数: " + e.getParameterName()));
    }

    /** 数据库访问异常：统一 500 + 脱敏文案，详情只进日志 */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ResultVO<Void>> handleDataAccess(DataAccessException e) {
        log.error("数据库访问异常: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ResultVO.fail("数据访问异常，请稍后重试或联系管理员"));
    }

    /**
     * 设备上传失败（multipart 解析异常 / 客户端断流）。
     *
     * <p>★ 2026-11-18 评审 M-04：这类失败<b>只进 journald，现场完全无感知</b> ——
     * 失败的那张图既没入库也没告警，设备端若不重试就永久丢失。
     * 现场近 24h 实测 39 条 multipart 解析失败 + 19 条 Broken pipe。
     *
     * <p>为什么在这里而不是 Controller：multipart 解析发生在 <b>Spring 参数绑定阶段</b>，
     * 请求还没进 Controller 方法体，Controller 的 try/catch 捕获不到。
     *
     * <p>事件类型定义在 shared（共享内核不得依赖业务域，ArchUnit 铁律②），
     * 由 analytics 域的监听器落 {@code fact_anomaly_alert}。
     */
    @ExceptionHandler(org.springframework.web.multipart.MultipartException.class)
    public ResponseEntity<ResultVO<Void>> handleMultipart(org.springframework.web.multipart.MultipartException e,
                                                          javax.servlet.http.HttpServletRequest request) {
        String deviceId = request == null ? null : request.getHeader("X-Device-Id");
        String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        log.error("设备上传失败（multipart 解析异常）deviceId={} reason={}", deviceId, reason, e);
        publishUploadFailed(deviceId, reason, e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ResultVO.fail("上传失败，请重试该批次"));
    }

    /** 发布上传失败事件（发布失败不影响主流程 —— 原始异常已在 log.error 落盘） */
    private void publishUploadFailed(String deviceId, String reason, String errorType) {
        if (eventPublisher == null) {
            return;
        }
        try {
            eventPublisher.publishEvent(new com.wms.shared.common.event.UploadFailedEvent(deviceId, reason, errorType));
        } catch (Exception ex) {
            log.warn("发布上传失败告警事件异常（不影响上传响应）: {}", ex.getMessage());
        }
    }

    /**
     * 兜底：所有未捕获异常统一 ResultVO。
     * 安全异常必须原样重抛（见类注释），绝不能在这里落成 500。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ResultVO<Void>> handleException(Exception e) throws Exception {
        if (e instanceof AccessDeniedException || e instanceof AuthenticationException) {
            throw e; // 交回 ExceptionTranslationFilter → 401/403 语义
        }
        log.error("未处理异常: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ResultVO.fail("系统异常，请稍后重试或联系管理员"));
    }
}
