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
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.validation.ConstraintViolationException;

import java.util.stream.Collectors;

/**
 * 把异常翻译成 {@link ApiResponse}。
 *
 * <p>业务失败返回 HTTP 200 加一个非零的 {@code code}，这样前端的拦截器就只有一个地方
 * 需要处理它们。**只有认证和授权失败保留各自的 HTTP 状态码**，因为前端必须能区分
 * 「弹一条提示」和「跳转到登录页」。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> handleBusiness(BusinessException e) {
        log.warn("Business rule rejected: code={}, message={}", e.getResultCode().getCode(), e.getMessage());
        return ApiResponse.failure(e.getResultCode(), e.getMessage());
    }

    /** 请求体上的 @Valid 失败。一次报出所有有问题的字段。 */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ApiResponse<Void> handleValidation(BindException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(field -> field.getField() + ": " + defaultMessage(field))
                .collect(Collectors.joining("; "));
        log.warn("Request validation failed: {}", detail);
        return ApiResponse.failure(ResultCode.BAD_REQUEST, detail);
    }

    /** 方法参数（路径 / 查询变量）上的 @Validated 失败。 */
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
     * 重新抛出去，让它成为一个 403，这样安全过滤器链和前端看到的都是一个真正的授权
     * 失败，而不是一个带着错误码的 200。
     */
    @ExceptionHandler(AccessDeniedException.class)
    public void handleAccessDenied(AccessDeniedException e) {
        log.warn("Access denied: {}", e.getMessage());
        // 抛出，而不是转换。在这里返回那个响应包，会让一个被拒绝的请求在 HTTP 层面上
        // 和一个成功的请求无从区分——200，里面裹着一个错误码——而客户端那个 403 分支
        // 永远不会被执行。方法级权限和安全过滤器链应该以同一种方式失败，
        // 而这一种是带着状态码的那种。
        throw e;
    }

    /**
     * 一个没有对应处理器的路径。
     *
     * <p>Spring 6 把「没有路由匹配」也抛成异常，于是它会落进最后那道兜底：一条 ERROR
     * 级别的完整堆栈，对调用方则是一句「系统繁忙」。一个拼错的 URL 不是系统故障，
     * 它该得到一句说得清的话，日志里也不该为它留一份堆栈。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ApiResponse<Void> handleNoResource(NoResourceFoundException e) {
        log.warn("No handler for {}", e.getResourcePath());
        return ApiResponse.failure(ResultCode.NOT_FOUND);
    }

    /** 最后一道兜底。记下完整堆栈，对调用方隐藏内部细节。 */
    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return ApiResponse.failure(ResultCode.INTERNAL_ERROR);
    }

    private String defaultMessage(FieldError field) {
        return field.getDefaultMessage() == null ? "invalid" : field.getDefaultMessage();
    }
}
