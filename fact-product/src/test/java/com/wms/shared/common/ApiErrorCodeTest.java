package com.wms.shared.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 错误码族契约测试（v3 H-5 / QUAL-02）。
 *
 * <p>守护的核心是**兼容性**：只有本枚举登记过的 code 才会改 HTTP 状态；
 * 存量 93 处 {@code ResultVO.fail} 用的 500/422 必须原样返回 —— 一旦有人
 * 顺手把 500 也登记进来，全站业务失败会瞬间变成 HTTP 500，前端所有
 * 「HTTP 200 + code!==200」的分支全部失效。
 */
class ApiErrorCodeTest {

    @Test
    @DisplayName("会话族 → 401，限流族 → 429，参数/凭据族 → 400，冲突 → 409，上报校验 → 422")
    void familiesMapToHttpStatus() {
        // NEW-03（v5 评审）：凭据错误是「参数不对」，不是「会话过期」——必须 400，
        // 否则前端 401 拦截器会清 token 并把当前页写成回跳来源。
        assertEquals(HttpStatus.BAD_REQUEST, ApiErrorCode.AUTH_INVALID_CREDENTIALS.httpStatus());
        assertEquals(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_TOKEN_INVALID.httpStatus());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ApiErrorCode.AUTH_RATE_LIMITED.httpStatus());
        assertEquals(HttpStatus.BAD_REQUEST, ApiErrorCode.PARAM_INVALID.httpStatus());
        assertEquals(HttpStatus.CONFLICT, ApiErrorCode.CONFLICT_USERNAME_EXISTS.httpStatus());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.UPLOAD_BATCH_SIZE_EXCEEDED.httpStatus());
    }

    @Test
    @DisplayName("存量错误码 500 / 422 未登记 → 不改 HTTP 状态（兼容硬保证）")
    void legacyCodesAreNotMapped() {
        assertNull(ApiErrorCode.httpStatusOf(500), "存量 code=500 必须保持 HTTP 200");
        assertNull(ApiErrorCode.httpStatusOf(422), "存量 code=422 必须保持原状态");
        assertNull(ApiErrorCode.httpStatusOf(200), "成功码不需要映射");
        assertNull(ApiErrorCode.httpStatusOf(0));
    }

    @Test
    @DisplayName("登记过的 code 可反查枚举（监控按 code 聚合用）")
    void codeLookup() {
        assertEquals(ApiErrorCode.AUTH_INVALID_CREDENTIALS, ApiErrorCode.ofCode(40004));
        assertEquals(ApiErrorCode.UPLOAD_METADATA_INVALID, ApiErrorCode.ofCode(42203));
        assertNull(ApiErrorCode.ofCode(99999));
    }

    @Test
    @DisplayName("错误码全局唯一（防止复制粘贴造成歧义）")
    void codesAreUnique() {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (ApiErrorCode value : ApiErrorCode.values()) {
            org.junit.jupiter.api.Assertions.assertTrue(seen.add(value.code()),
                    "错误码重复: " + value.code() + " (" + value.name() + ")");
        }
    }

    @Test
    @DisplayName("ResultVO.of 会同时写 code 与 errorCode，成功响应不带 errorCode")
    void resultVoCarriesErrorCode() {
        ResultVO<String> failed = ResultVO.of(ApiErrorCode.AUTH_INVALID_CREDENTIALS);
        assertEquals(40004, failed.getCode());
        assertEquals("AUTH_INVALID_CREDENTIALS", failed.getErrorCode());
        assertEquals("用户名或密码错误", failed.getMessage());

        ResultVO<String> custom = ResultVO.of(ApiErrorCode.AUTH_RATE_LIMITED, "锁定 5 分钟");
        assertEquals("锁定 5 分钟", custom.getMessage());
        assertEquals("AUTH_RATE_LIMITED", custom.getErrorCode());

        ResultVO<String> ok = ResultVO.success("x");
        assertEquals(200, ok.getCode());
        assertNull(ok.getErrorCode());
    }
}
