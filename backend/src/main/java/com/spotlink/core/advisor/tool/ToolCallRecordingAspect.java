package com.spotlink.advisor.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 把每一次 {@code @Tool} 调用记录进 {@link ToolCallRecorder}。
 *
 * <p>做成切面，而不是在每个工具里调一次：记录是「身为一个工具」的属性，所以它该跟注解
 * 待在一起，而不是在每个方法体里重复一遍——漏掉一处，轨迹就会悄无声息地缺一段。
 *
 * <p>在调用线程上运行，所以记录器的线程局部槽位，就是本次回合打开的那一个。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class ToolCallRecordingAspect {

    private final ObjectMapper objectMapper;

    @Around("@annotation(tool)")
    public Object record(ProceedingJoinPoint joinPoint, Tool tool) throws Throwable {
        ToolCallRecorder.beforeCall();
        if (Arrays.stream(joinPoint.getArgs()).anyMatch(value -> value instanceof String text && text.length() > 4000)) {
            throw com.spotlink.shared.exception.BusinessException.of(com.spotlink.shared.web.ResultCode.BAD_REQUEST,
                    "查询参数过长，请缩小查询范围");
        }
        String name = tool.name().isBlank()
                ? joinPoint.getSignature().getName()
                : tool.name();
        String input = describeArguments(joinPoint.getArgs());

        try {
            Object result = joinPoint.proceed();
            ToolCallRecorder.record(name, input, String.valueOf(result));
            // 模型上下文也限制体积，不能仅限制保存的轨迹。
            if (result instanceof String text && text.length() > 8000) return text.substring(0, 8000) + "\n（查询结果过多，请缩小范围）";
            return result;
        } catch (Throwable failure) {
            // 在重新抛出之前先记录，这样一个失败的工具在轨迹里是看得见的，
            // 而不是显得从未运行过。
            ToolCallRecorder.record(name, input, "查询失败，请缩小范围或检查权限后重试");
            if (failure instanceof com.spotlink.shared.exception.BusinessException) throw failure;
            // SQL/连接异常不能通过工具错误反馈泄漏给模型或客户端。
            throw com.spotlink.shared.exception.BusinessException.of(com.spotlink.shared.web.ResultCode.ADVISOR_UNAVAILABLE,
                    "平台查询暂时不可用，请稍后重试");
        }
    }

    private String describeArguments(Object[] arguments) {
        if (arguments == null || arguments.length == 0) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(
                    arguments.length == 1 ? arguments[0] : arguments);
        } catch (Exception e) {
            log.debug("Could not serialise tool arguments", e);
            return Arrays.toString(arguments);
        }
    }
}
