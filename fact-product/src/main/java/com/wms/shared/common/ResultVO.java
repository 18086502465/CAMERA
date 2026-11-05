
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
     * 状态码：200-成功，500-失败
     */
    private int code;

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
}
