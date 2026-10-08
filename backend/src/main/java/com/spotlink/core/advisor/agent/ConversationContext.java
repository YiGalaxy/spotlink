package com.spotlink.advisor.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 按最近完整问答保留上下文；可编辑采购需求单独保存，不生成不可核实的自动记忆。 */
public final class ConversationContext {
    private ConversationContext() {}
    public static List<ConversationTurn> bounded(List<ConversationTurn> turns) {
        return bounded(turns, 10000);
    }
    public static List<ConversationTurn> bounded(List<ConversationTurn> turns, int maxChars) {
        List<ConversationTurn> selected = new ArrayList<>();
        int budget = maxChars;
        for (int i = turns.size() - 1; i >= 1; i -= 2) {
            ConversationTurn answer = turns.get(i), question = turns.get(i - 1);
            if (!ConversationTurn.ROLE_USER.equals(question.role()) || !"assistant".equals(answer.role())) continue;
            int length = question.content().length() + answer.content().length();
            if (length > budget) break;
            selected.add(answer); selected.add(question); budget -= length;
        }
        Collections.reverse(selected);
        return List.copyOf(selected);
    }
}
