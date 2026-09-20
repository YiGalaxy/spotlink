package com.bulk.trade.shared.web;

import lombok.Getter;

/**
 * 每个 REST 接口都返回的统一响应包。
 *
 * <p>所有响应共用一个形状，前端就只需要一个拦截器：它检查 {@code code == 0}，
 * 否则把 {@code message} 显示出来。
 *
 * @param <T> 载荷类型
 */
@Getter
public class ApiResponse<T> {

    /** 0 表示成功；其他任何值都是业务或系统错误。 */
    private final int code;
    private final String message;
    private final T data;
    private final long timestamp;

    private ApiResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data);
    }

    public static <T> ApiResponse<T> success() {
        return success(null);
    }

    public static <T> ApiResponse<T> failure(ResultCode resultCode) {
        return new ApiResponse<>(resultCode.getCode(), resultCode.getMessage(), null);
    }

    public static <T> ApiResponse<T> failure(ResultCode resultCode, String message) {
        return new ApiResponse<>(resultCode.getCode(), message, null);
    }
}
