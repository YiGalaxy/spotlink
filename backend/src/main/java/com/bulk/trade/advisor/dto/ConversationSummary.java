package com.bulk.trade.advisor.dto;

import java.time.OffsetDateTime;

/** A row in the conversation list. Deliberately excludes the messages. */
public record ConversationSummary(
        Long id,
        String title,
        int messageCount,
        OffsetDateTime lastMessageAt,
        OffsetDateTime createdAt
) {
}
