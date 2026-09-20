package com.bulk.trade.advisor.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.OffsetDateTime;

/**
 * A row in the conversation list. Deliberately excludes the messages.
 *
 * <p>The id is serialised as a string — see {@code LoginResponse} for why a
 * snowflake id must not travel as a JSON number.
 */
public record ConversationSummary(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String title,
        int messageCount,
        OffsetDateTime lastMessageAt,
        OffsetDateTime createdAt
) {
}
