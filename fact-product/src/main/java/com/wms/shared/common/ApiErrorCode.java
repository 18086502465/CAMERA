package com.wms.shared.common;

import org.springframework.http.HttpStatus;

/**
 * 业务错误码族（QUAL-02 / v3 H-5，2026-11-06）。
 *
 * <p><b>为什么要做</b>：改造前全仓 93 处业务失败都是
 * {@code HTTP 200 + body.code=500} —— 网关、Nginx 访问日志、APM 只能看到 200，
 * 无法按状态码分类告警；前端也只能靠 message 字符串匹配来区分“账号锁定”
 * 与“口令错误”。QUAL-01 引入 {@code GlobalExceptionHandler}（400/500）之后，
 * 同一系统里并存两套错误语义，越往后越难收敛。
 *
 * <p><b>编码规则</b>（三位族 + 两位序号）：
 * <table border="1">
 *   <tr><th>族</th><th>含义</th><th>HTTP</th></tr>
 *   <tr><td>400xx</td><td>参数校验（入参缺失、格式错误、强度不足）</td><td>400</td></tr>
 *   <tr><td>401xx</td><td>凭据/会话（口令错误、令牌失效、账号禁用）</td><td>401</td></tr>
 *   <tr><td>409xx</td><td>资源冲突（用户名已存在等）</td><td>409</td></tr>
 *   <tr><td>422xx</td><td>设备上报校验（批次超限、文件名不匹配、metadata 非法）</td><td>422</td></tr>
 *   <tr><td>429xx</td><td>限流/锁定</td><td>429</td></tr>
 *   <tr><td>500xx</td><td>服务端内部错误</td><td>500</td></tr>
 * </table>
 *
 * <p><b>迁移策略（渐进，不破坏存量）</b>：
 * <ul>
 *   <li>{@link ResultVO#fail(String)} 等<b>老方法保持原样</b>（HTTP 200 + code 500），
 *       93 处存量按业务分批迁移，不在此次一次性改完；</li>
 *   <li>新增/高频路径（登录、注册、上传、/me）改用 {@link ResultVO#of(ApiErrorCode)}，
 *       由 {@code ApiStatusAdvice} 统一把 code 映射成 HTTP 状态；</li>
 *   <li><b>只有</b>本枚举里登记过的 code 才会改 HTTP 状态，未登记的按原样返回 ——
 *       这是“存量不动”的硬保证。</li>
 * </ul>
 *
 * <p>设备上报通道（{@code /api/v1/defect-images/upload}）例外：
 * 现场设备固件可能按 {@code body.code == 422/500} 判断重试策略，因此<b>只补
 * {@code errorCode} 字符串、不改 {@code code} 数值</b>（HTTP 状态本已对齐）。
 */
public enum ApiErrorCode {

    // ==================== 400xx 参数校验 ====================
    PARAM_INVALID(40001, HttpStatus.BAD_REQUEST, "请求参数不合法"),
    PARAM_WEAK_PASSWORD(40002, HttpStatus.BAD_REQUEST, "口令强度不足"),
    PARAM_MISSING(40003, HttpStatus.BAD_REQUEST, "缺少必要参数"),
    /**
     * 凭据错误（用户名/口令不对）。
     *
     * <p><b>为什么不是 401（NEW-03，2026-11-06 v5 评审）</b>：401 的语义是
     * 「没有有效会话」，前端 {@code utils/request.js} 对**任何** 401 都会
     * 清 token + 写登录上下文 + 跳登录页。而登录接口本身返回 401 时，
     * 拦截器会把「当前页」当成「被踢出前的来源页」写进 sessionStorage，
     * 用户下次登录成功就被送回这个页（实测落在 {@code /camera-status}），
     * 原本想去的地方丢了。
     * 凭据错误是「这次请求的参数不对」，不是「会话过期」，用 400 才是对的语义。
     */
    AUTH_INVALID_CREDENTIALS(40004, HttpStatus.BAD_REQUEST, "用户名或密码错误"),

    // ==================== 401xx 凭据 / 会话 ====================
    AUTH_TOKEN_INVALID(40103, HttpStatus.UNAUTHORIZED, "登录状态已失效，请重新登录"),
    AUTH_ACCOUNT_DISABLED(40104, HttpStatus.UNAUTHORIZED, "用户已被禁用"),
    AUTH_DEVICE_UNAUTHORIZED(40105, HttpStatus.UNAUTHORIZED, "设备认证信息缺失或无效"),

    // ==================== 409xx 资源冲突 ====================
    CONFLICT_USERNAME_EXISTS(40901, HttpStatus.CONFLICT, "用户名已存在"),

    // ==================== 422xx 设备上报校验 ====================
    UPLOAD_BATCH_SIZE_EXCEEDED(42201, HttpStatus.UNPROCESSABLE_ENTITY, "单批次图片数量超过上限"),
    UPLOAD_FILE_NAME_MISMATCH(42202, HttpStatus.UNPROCESSABLE_ENTITY, "metadata 文件名与上传文件不一致"),
    UPLOAD_METADATA_INVALID(42203, HttpStatus.UNPROCESSABLE_ENTITY, "metadata 不符合上报协议"),

    // ==================== 429xx 限流 ====================
    AUTH_RATE_LIMITED(42901, HttpStatus.TOO_MANY_REQUESTS, "操作过于频繁，请稍后再试"),

    // ==================== 500xx 内部错误 ====================
    SERVER_ERROR(50001, HttpStatus.INTERNAL_SERVER_ERROR, "系统异常，请稍后重试");

    private final int code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ApiErrorCode(int code, HttpStatus httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public int code() {
        return code;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    /**
     * code → HTTP 状态。
     *
     * @return 未登记的 code 返回 {@code null}（调用方保持原状态，存量错误码不受影响）
     */
    public static HttpStatus httpStatusOf(int code) {
        for (ApiErrorCode value : values()) {
            if (value.code == code) {
                return value.httpStatus;
            }
        }
        return null;
    }

    /** code → 枚举（未登记返回 {@code null}） */
    public static ApiErrorCode ofCode(int code) {
        for (ApiErrorCode value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return null;
    }
}
