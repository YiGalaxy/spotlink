package com.bulk.trade.advisor.dto;

import jakarta.validation.constraints.Size;

/**
 * A title is optional. When omitted the conversation starts as "新对话" and is
 * renamed from its first question, so the client never has to invent a name
 * before anything has been asked.
 */
public record CreateConversationRequest(

        @Size(max = 128, message = "标题过长")
        String title
) {
}
