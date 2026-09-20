package com.bulk.trade.shared.web;

import lombok.Getter;

/**
 * Uniform response envelope returned by every REST endpoint.
 *
 * <p>Having one shape for all responses means the frontend needs exactly one
 * interceptor: it checks {@code code == 0}, shows {@code message} otherwise.
 *
 * @param <T> payload type
 */
@Getter
public class ApiResponse<T> {

    /** 0 means success; anything else is a business or system error. */
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
