package com.bulk.trade.advisor.agent;

import com.bulk.trade.advisor.tool.ToolCallRecorder;

import java.util.List;

/**
 * Outcome of one advisor turn.
 *
 * <p>Token counters are carried out of the call rather than discarded: they are
 * what per-tenant cost accounting is built on.
 *
 * <p>Note what is <em>not</em> here. The hand-written loop this replaced could
 * count its own iterations and read prompt-cache statistics from the response.
 * Spring AI runs the tool loop internally and reports neither, so iteration
 * count is gone and cache tokens read as zero. Both are real losses — they are
 * recorded in the walkthrough rather than papered over with placeholder values.
 */
public record AgentResult(
        String answer,
        List<ToolCallRecorder.Invocation> toolInvocations,
        Integer inputTokens,
        Integer outputTokens
) {

    public static AgentResult of(String answer,
                                 List<ToolCallRecorder.Invocation> toolInvocations,
                                 Integer inputTokens,
                                 Integer outputTokens) {
        return new AgentResult(answer, List.copyOf(toolInvocations), inputTokens, outputTokens);
    }
}
