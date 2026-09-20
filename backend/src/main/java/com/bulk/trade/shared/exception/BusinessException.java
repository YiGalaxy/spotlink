package com.bulk.trade.shared.exception;

import com.bulk.trade.shared.web.ResultCode;
import lombok.Getter;

/**
 * Thrown when a business rule is violated.
 *
 * <p>Business failures are expected outcomes, not bugs: they are caught by
 * {@code GlobalExceptionHandler}, translated into a {@code ResultCode}, and
 * returned with HTTP 200 so the frontend can show the message. Stack traces
 * are therefore suppressed on purpose.
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

    /** Throws when {@code condition} is true. Keeps guard clauses to one line. */
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
