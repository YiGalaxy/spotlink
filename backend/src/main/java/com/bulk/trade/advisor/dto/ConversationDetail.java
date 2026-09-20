package com.bulk.trade.advisor.dto;

import java.util.List;

public record ConversationDetail(
        Long id,
        String title,
        List<MessageView> messages
) {
}
