package com.bulk.trade.advisor.agent;

/**
 * 作为上下文交给智能体的一段先前回合。
 *
 * <p>只给纯文本。此前的工具调用以它们产出的那段回答重放，而不是以 {@code tool_use} /
 * {@code tool_result} 块：那些块必须按 id 配对，而从存下来的行里重建这层配对既脆弱
 * 又没有收益——模型只需要知道自己已经告诉过用户什么。
 */
public record ConversationTurn(String role, String content) {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    public static ConversationTurn user(String content) {
        return new ConversationTurn(ROLE_USER, content);
    }

    public static ConversationTurn assistant(String content) {
        return new ConversationTurn(ROLE_ASSISTANT, content);
    }
}
