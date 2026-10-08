package com.spotlink.shared.exception;

import com.spotlink.shared.web.ResultCode;
import lombok.Getter;

/**
 * 当某条业务规则被违反时抛出。
 *
 * <p>业务失败是预期之内的结果，不是 bug：它们由 {@code GlobalExceptionHandler} 接住，
 * 翻译成一个 {@code ResultCode}，再以 HTTP 200 返回，好让前端把消息展示出来。
 * 也正因如此，堆栈跟踪是被刻意压掉的。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ResultCode resultCode;

    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage(), null, false, false);
        this.resultCode = resultCode;
    }

    public BusinessException(ResultCode resultCode, String message) {
        super(message, null, false, false);
        this.resultCode = resultCode;
    }

    public static BusinessException of(ResultCode resultCode) {
        return new BusinessException(resultCode);
    }

    public static BusinessException of(ResultCode resultCode, String message) {
        return new BusinessException(resultCode, message);
    }

    /** 当 {@code condition} 为真时抛出。让守卫子句保持一行。 */
    public static void throwIf(boolean condition, ResultCode resultCode) {
        if (condition) {
            throw new BusinessException(resultCode);
        }
    }

    public static void throwIf(boolean condition, ResultCode resultCode, String message) {
        if (condition) {
            throw new BusinessException(resultCode, message);
        }
    }
}
