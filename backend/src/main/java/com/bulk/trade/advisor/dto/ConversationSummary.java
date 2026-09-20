package com.bulk.trade.advisor.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.OffsetDateTime;

/**
 * 会话列表里的一行。刻意不含消息内容。
 *
 * <p>id 序列化为字符串——为什么雪花 ID 不能以 JSON 数字传输，见 {@code LoginResponse}。
 */
public record ConversationSummary(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String title,
        int messageCount,
        OffsetDateTime lastMessageAt,
        OffsetDateTime createdAt
) {
}
