package com.spotlink.advisor.dto;

import com.spotlink.advisor.entity.AdvisorMessage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.extern.slf4j.Slf4j;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 客户端看到的一条消息——刚生成的回答和从历史里读出来的回答是同一个形状，
 * 这样前端只需要一个渲染器。
 */
@Slf4j
public record MessageView(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String role,
        String content,
        List<AdvisorProductReference> products,
        List<AdvisorKnowledgeReference> knowledge,
        List<ToolCallView> toolCalls,
        Integer iterations,
        TokenUsage usage,
        OffsetDateTime createdAt,
        String engine,
        String runId
) {

    public record ToolCallView(String name, String input, String output) {
    }

    public record TokenUsage(
            Long inputTokens,
            Long outputTokens,
            Long cacheReadTokens,
            Long cacheCreationTokens
    ) {
    }

    public static MessageView from(AdvisorMessage entity, ObjectMapper objectMapper) {
        return new MessageView(
                entity.getId(),
                entity.getRole(),
                entity.getContent(),
                parseProducts(entity.getProductsJson(), objectMapper),
                parseKnowledge(entity.getKnowledgeJson(), objectMapper),
                parseToolCalls(entity.getToolCalls(), objectMapper),
                entity.getIterations(),
                entity.getInputTokens() == null ? null : new TokenUsage(
                        entity.getInputTokens(), entity.getOutputTokens(), entity.getCacheReadTokens(), entity.getCacheCreationTokens()),
                entity.getCreatedAt(), entity.getEngine(), entity.getRunId());
    }

    /** 一个尚未落库的回合。 */
    public static MessageView ofAssistant(String content,
                                          List<ToolCallView> toolCalls,
                                          int iterations,
                                          TokenUsage usage) {
        return new MessageView(null, AdvisorMessage.Role.ASSISTANT, content, List.of(), List.of(),
                toolCalls, iterations, usage, OffsetDateTime.now(), "spring-ai", null);
    }

    private static List<AdvisorProductReference> parseProducts(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) return List.of();
        try { return mapper.readValue(json, new TypeReference<List<AdvisorProductReference>>() {}); }
        catch (Exception e) { return List.of(); }
    }
    private static List<AdvisorKnowledgeReference> parseKnowledge(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) return List.of();
        try { return mapper.readValue(json, new TypeReference<List<AdvisorKnowledgeReference>>() {}); }
        catch (Exception e) { return List.of(); }
    }

    /**
     * 一段格式错乱的轨迹不能把整个会话的加载搞坏，所以解析失败降级为
     * 「没有工具调用」，并记日志。
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

}
