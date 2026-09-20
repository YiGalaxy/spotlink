package com.bulk.trade.advisor.dto;

import com.bulk.trade.advisor.agent.AgentResult;

import java.util.List;

/**
 * Answer plus the reasoning trail behind it.
 *
 * <p>The tool calls are returned to the client on purpose. An answer about
 * platform data is only as trustworthy as the query that produced it, and
 * showing "which tool, with which arguments" is what lets a user check the
 * figure instead of taking it on faith. It is also the fastest way to debug a
 * wrong answer.
 */
public record ChatResponse(
        String answer,
        List<ToolCall> toolCalls,
        int iterations,
        TokenUsage usage
) {

    public record ToolCall(String name, String input, String output) {
    }

    public record TokenUsage(
            long inputTokens,
            long outputTokens,
            long cacheReadTokens,
            long cacheCreationTokens
    ) {
    }

    public static ChatResponse from(AgentResult result) {
        return new ChatResponse(
                result.answer(),
                result.toolInvocations().stream()
                        .map(t -> new ToolCall(t.name(), t.input(), t.output()))
                        .toList(),
                result.iterations(),
                new TokenUsage(result.inputTokens(), result.outputTokens(),
                        result.cacheReadTokens(), result.cacheCreationTokens()));
    }
}
