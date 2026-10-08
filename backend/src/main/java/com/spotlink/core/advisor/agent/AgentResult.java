package com.spotlink.advisor.agent;

import com.spotlink.advisor.tool.ToolCallRecorder;

import java.util.List;

/**
 * 一次顾问回合的结果。
 *
 * <p>令牌计数被带出这次调用，而不是丢掉：**按租户的成本核算，建在它们之上。**
 *
 * <p>也请注意这里**没有**什么。它替换掉的那个手写循环，能自己数循环了多少轮，也能从
 * 响应里读出提示词缓存统计。Spring AI 在内部跑完了工具循环，这两样都不报告，于是循环
 * 轮数没有了，缓存令牌读出来是零。**两者都是真实的损失**——它们被记录在讲解文档里，
 * 而不是用占位数值糊过去。
 */
public record AgentResult(
        String answer,
        List<ToolCallRecorder.Invocation> toolInvocations,
        Integer inputTokens,
        Integer outputTokens,
        List<com.spotlink.advisor.dto.AdvisorProductReference> products
) {

    public static AgentResult of(String answer,
                                 List<ToolCallRecorder.Invocation> toolInvocations,
                                 Integer inputTokens,
                                 Integer outputTokens) {
        return new AgentResult(answer, List.copyOf(toolInvocations), inputTokens, outputTokens, List.of());
    }
}
