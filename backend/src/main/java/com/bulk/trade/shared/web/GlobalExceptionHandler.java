package com.bulk.trade.shared.web;

import com.bulk.trade.shared.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import jakarta.validation.ConstraintViolationException;

import java.util.stream.Collectors;

/**
 * Translates exceptions into {@link ApiResponse}.
 *
 * <p>Business failures return HTTP 200 with a non-zero {@code code} so the
 * frontend interceptor has a single place to handle them. Only authentication
 * and authorisation failures keep their HTTP status, because the frontend needs
 * to distinguish "show a message" from "redirect to login".
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> handleBusiness(BusinessException e) {
        log.warn("Business rule rejected: code={}, message={}", e.getResultCode().getCode(), e.getMessage());
        return ApiResponse.failure(e.getResultCode(), e.getMessage());
    }

    /** @Valid failure on a request body. Reports every offending field at once. */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ApiResponse<Void> handleValidation(BindException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(field -> field.getField() + ": " + defaultMessage(field))
                .collect(Collectors.joining("; "));
        log.warn("Request validation failed: {}", detail);
        return ApiResponse.failure(ResultCode.BAD_REQUEST, detail);
    }

    /** @Validated failure on a method parameter (path / query variable). */
    @ExceptionHandler(ConstraintViolationException.class)
    public ApiResponse<Void> handleConstraintViolation(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining("; "));
        log.warn("Constraint violation: {}", detail);
        return ApiResponse.failure(ResultCode.BAD_REQUEST, detail);
    }

    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    public ApiResponse<Void> handleMalformedRequest(Exception e) {
        log.warn("Malformed request: {}", e.getMessage());
        return ApiResponse.failure(ResultCode.BAD_REQUEST);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ApiResponse<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return ApiResponse.failure(ResultCode.METHOD_NOT_ALLOWED);
    }

    /**
     * Rethrown as a 403 so the security filter chain and the frontend both see a
     * genuine authorisation failure rather than a 200 with an error code.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public void handleAccessDenied(AccessDeniedException e) {
        log.warn("Access denied: {}", e.getMessage());
        // Rethrown, not converted. Returning the envelope here would make a
        // refused request indistinguishable from a successful one at the HTTP
        // level — 200 with an error code inside — and the client's 403 branch
        // would never run. Bucket permissions and the security filter chain
        // should fail the same way, and this way is the one that carries the
        // status.
        throw e;
    }

    /** Last resort. Logs the full trace and hides internals from the caller. */
    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return ApiResponse.failure(ResultCode.INTERNAL_ERROR);
    }

    private String defaultMessage(FieldError field) {
        return field.getDefaultMessage() == null ? "invalid" : field.getDefaultMessage();
    }
}
