package com.bulk.trade.advisor.agent;

import java.util.List;

/**
 * Outcome of one agent run.
 *
 * <p>Token counters are carried out of the loop rather than discarded: they are
 * what per-tenant cost accounting and the "is prompt caching actually working"
 * check are built on.
 */
public record AgentResult(
        String answer,
        List<ToolInvocation> toolInvocations,
        int iterations,
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        long cacheCreationTokens
) {

    /** One tool call, kept for the audit trail. */
    public record ToolInvocation(String name, String input, String output) {
    }

    public static AgentResult of(String answer, List<ToolInvocation> invocations, int iterations,
                                 long inputTokens, long outputTokens,
                                 long cacheReadTokens, long cacheCreationTokens) {
        return new AgentResult(answer, List.copyOf(invocations), iterations,
                inputTokens, outputTokens, cacheReadTokens, cacheCreationTokens);
    }
}
