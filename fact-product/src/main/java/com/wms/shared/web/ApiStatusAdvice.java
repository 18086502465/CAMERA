package com.wms.shared.web;

import com.wms.shared.common.ApiErrorCode;
import com.wms.shared.common.ResultVO;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 错误码 → HTTP 状态对齐（QUAL-02 / v3 H-5，2026-11-06）。
 *
 * <p><b>解决什么</b>：Controller 里 {@code return ResultVO.of(ApiErrorCode.xxx)} 只写了
 * 业务码，HTTP 状态还得跟着变；如果在每个 Controller 里逐个 {@code ResponseEntity.status(...)}
 * 包一层，93 处存量迟早出现口径漂移。这里用 {@link ResponseBodyAdvice} 在一处收口：
 * <b>响应体是 {@link ResultVO} 且 code 在 {@link ApiErrorCode} 里登记过 → 改 HTTP 状态</b>。
 *
 * <p><b>为什么不会破坏存量</b>：
 * <ul>
 *   <li>只认 {@link ApiErrorCode} 里登记过的 code（40101、42201…）。
 *       存量 {@code ResultVO.fail()} 返回的 500 / 422 <b>不在登记表里</b>，
 *       状态保持原样（HTTP 200），所以 93 处老代码零影响；</li>
 *   <li>返回 {@code ResponseEntity} 的端点（如上传、导出）参数类型不是 ResultVO，
 *       {@link #supports} 直接返回 false，不会二次改状态；</li>
 *   <li>安全异常链路在 {@code GlobalExceptionHandler} 里已原样重抛，
 *       不经过本类 —— 401/403 的 AUTHENTICATION_REQUIRED / ACCESS_DENIED 语义不变。</li>
 * </ul>
 */
@RestControllerAdvice
public class ApiStatusAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return ResultVO.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        if (body instanceof ResultVO) {
            int code = ((ResultVO<?>) body).getCode();
            HttpStatus status = ApiErrorCode.httpStatusOf(code);
            if (status != null) {
                response.setStatusCode(status);
            }
        }
        return body;
    }
}
