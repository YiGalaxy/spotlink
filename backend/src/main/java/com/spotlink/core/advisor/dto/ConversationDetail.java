package com.spotlink.advisor.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.util.List;

public record ConversationDetail(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String title,
        String contextNote,
        List<MessageView> messages,
        String engine
) {
    public ConversationDetail(Long id, String title, String contextNote, List<MessageView> messages) {
        this(id, title, contextNote, messages, "spring-ai");
    }
}
