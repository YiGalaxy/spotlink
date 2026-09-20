package com.bulk.trade.advisor.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * One turn of a conversation. Append-only: rows are written once and read back
 * in order, never edited.
 */
@Getter
@Setter
@TableName("t_ai_message")
public class AdvisorMessage {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long conversationId;
    private Long enterpriseId;
    private Long userId;

    /** {@link Role#USER} or {@link Role#ASSISTANT}. */
    private String role;

    private String content;

    /** JSON array of tool invocations, serialised as text and stored as jsonb. */
    private String toolCalls;

    private Integer iterations;
    private Long inputTokens;
    private Long outputTokens;
    private Long cacheReadTokens;
    private Long cacheCreationTokens;
    private String model;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    public static final class Role {
        public static final String USER = "user";
        public static final String ASSISTANT = "assistant";

        private Role() {
        }
    }
}
