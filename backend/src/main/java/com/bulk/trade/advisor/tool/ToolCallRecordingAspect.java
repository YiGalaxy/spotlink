package com.bulk.trade.advisor.tool;

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
 * Records every {@code @Tool} invocation into {@link ToolCallRecorder}.
 *
 * <p>An aspect rather than a call inside each tool: the recording is a property
 * of "being a tool", so it belongs with the annotation, not repeated in every
 * method body where one omission silently loses part of the trail.
 *
 * <p>Runs on the calling thread, so the recorder's thread-local slot is the same
 * one the turn opened.
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class ToolCallRecordingAspect {

    private final ObjectMapper objectMapper;

    @Around("@annotation(tool)")
    public Object record(ProceedingJoinPoint joinPoint, Tool tool) throws Throwable {
        String name = tool.name().isBlank()
                ? joinPoint.getSignature().getName()
                : tool.name();
        String input = describeArguments(joinPoint.getArgs());

        try {
            Object result = joinPoint.proceed();
            ToolCallRecorder.record(name, input, String.valueOf(result));
            return result;
        } catch (Throwable failure) {
            // Recorded before rethrowing so a failing tool is visible in the
            // trail rather than appearing never to have run.
            ToolCallRecorder.record(name, input, "失败：" + failure.getMessage());
            throw failure;
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
