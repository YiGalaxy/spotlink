package com.bulk.trade.advisor.agent;

/**
 * A prior turn handed to the agent as context.
 *
 * <p>Plain text only. Earlier tool calls are replayed as the answer they
 * produced, not as {@code tool_use} / {@code tool_result} blocks: those must be
 * paired by id, and rebuilding that pairing from stored rows is fragile for no
 * benefit — the model only needs to know what it already told the user.
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
