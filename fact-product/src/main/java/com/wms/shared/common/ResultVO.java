
package com.wms.shared.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一返回结果VO
 */
@Data
public class ResultVO<T> implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 状态码：200-成功，500-失败（存量语义）。
     * <p>QUAL-02 起新增 {@link ApiErrorCode} 错误码族（40xxx/401xx/409xx/422xx/429xx/500xx），
     * 由 {@code ApiStatusAdvice} 映射为对应的 HTTP 状态。存量 93 处仍返回 500，未迁移前行为不变。
     */
    private int code;

    /**
     * 机器可读错误标识（等于 {@link ApiErrorCode} 的枚举名，如 {@code AUTH_INVALID_CREDENTIALS}）。
     * <p>成功响应为 {@code null}；未迁移的存量错误也为 {@code null}（不代表成功）。
     * 用途：监控按此聚合告警，前端无需再靠中文 message 字符串匹配。
     */
    private String errorCode;

    /**
     * 消息
     */
    private String message;

    /**
     * 数据
     */
    private T data;

    /**
     * 成功返回
     */
    public static <T> ResultVO<T> success(T data, String message) {
        ResultVO<T> result = new ResultVO<>();
        result.setCode(200);
        result.setMessage(message);
        result.setData(data);
        return result;
    }

    /**
     * 成功返回（默认消息）
     */
    public static <T> ResultVO<T> success(T data) {
        return success(data, "操作成功");
    }

    /**
     * 失败返回
     */
    public static <T> ResultVO<T> fail(String message) {
        ResultVO<T> result = new ResultVO<>();
        result.setCode(500);
        result.setMessage(message);
        result.setData(null);
        return result;
    }

    /**
     * 失败返回（带数据）
     */
    public static <T> ResultVO<T> fail(String message, T data) {
        ResultVO<T> result = new ResultVO<>();
        result.setCode(500);
        result.setMessage(message);
        result.setData(data);
        return result;
    }

    /**
     * 自定义业务状态码失败返回。
     * 设备上报类接口需要返回 422 等明确业务校验码时使用。
     */
    public static <T> ResultVO<T> fail(int code, String message, T data) {
        ResultVO<T> result = new ResultVO<>();
        result.setCode(code);
        result.setMessage(message);
        result.setData(data);
        return result;
    }

    /**
     * 错误返回（error别名，兼容Controller调用）
     */
    public static <T> ResultVO<T> error(String message) {
        return fail(message);
    }

    /**
     * 错误返回（带数据）
     */
    public static <T> ResultVO<T> error(String message, T data) {
        return fail(message, data);
    }

    // ==================== QUAL-02 错误码族（HTTP 状态由 ApiStatusAdvice 对齐） ====================

    /** 按错误码族返回失败（使用默认文案） */
    public static <T> ResultVO<T> of(ApiErrorCode error) {
        return of(error, error == null ? null : error.defaultMessage(), null);
    }

    /** 按错误码族返回失败（自定义文案，仍带机器可读 errorCode） */
    public static <T> ResultVO<T> of(ApiErrorCode error, String message) {
        return of(error, message, null);
    }

    /** 按错误码族返回失败（自定义文案 + 数据） */
    public static <T> ResultVO<T> of(ApiErrorCode error, String message, T data) {
        ResultVO<T> result = new ResultVO<>();
        result.setCode(error == null ? 500 : error.code());
        result.setErrorCode(error == null ? null : error.name());
        result.setMessage(message == null ? (error == null ? "操作失败" : error.defaultMessage()) : message);
        result.setData(data);
        return result;
    }

    /**
     * 链式补充机器可读错误标识（用于「HTTP 状态与 code 已定、只缺 errorCode」的场景，
     * 例如设备上报通道要保持 {@code code=422} 不变、同时提供可聚合的 errorCode）。
     */
    public ResultVO<T> withErrorCode(String errorCode) {
        this.errorCode = errorCode;
        return this;
    }
}
