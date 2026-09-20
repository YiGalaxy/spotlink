package com.bulk.trade.advisor.dto;

import com.bulk.trade.advisor.entity.AdvisorMessage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.extern.slf4j.Slf4j;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * One message as the client sees it — the same shape for a freshly produced
 * answer and for one loaded from history, so the frontend has a single renderer.
 */
@Slf4j
public record MessageView(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String role,
        String content,
        List<ToolCallView> toolCalls,
        Integer iterations,
        TokenUsage usage,
        OffsetDateTime createdAt
) {

    public record ToolCallView(String name, String input, String output) {
    }

    public record TokenUsage(
            long inputTokens,
            long outputTokens,
            long cacheReadTokens,
            long cacheCreationTokens
    ) {
    }

    public static MessageView from(AdvisorMessage entity, ObjectMapper objectMapper) {
        return new MessageView(
                entity.getId(),
                entity.getRole(),
                entity.getContent(),
                parseToolCalls(entity.getToolCalls(), objectMapper),
                entity.getIterations(),
                entity.getInputTokens() == null ? null : new TokenUsage(
                        nullSafe(entity.getInputTokens()),
                        nullSafe(entity.getOutputTokens()),
                        nullSafe(entity.getCacheReadTokens()),
                        nullSafe(entity.getCacheCreationTokens())),
                entity.getCreatedAt());
    }

    /** A turn that has not been persisted yet. */
    public static MessageView ofAssistant(String content,
                                          List<ToolCallView> toolCalls,
                                          int iterations,
                                          TokenUsage usage) {
        return new MessageView(null, AdvisorMessage.Role.ASSISTANT, content,
                toolCalls, iterations, usage, OffsetDateTime.now());
    }

    /**
     * A malformed trail must not break loading a whole conversation, so a parse
     * failure degrades to "no tool calls" and is logged.
     */
    private static List<ToolCallView> parseToolCalls(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<ToolCallView>>() {
            });
        } catch (Exception e) {
            log.warn("Could not parse stored tool-call trail, returning empty list", e);
            return List.of();
        }
    }

    private static long nullSafe(Long value) {
        return value == null ? 0L : value;
    }
}
