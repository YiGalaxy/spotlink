package com.spotlink.advisor.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 会话中的一个回合。只追加：行写一次，按顺序读回，永不修改。
 */
@Getter
@Setter
@TableName("t_ai_message")
public class AdvisorMessage {
    private String productsJson;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long conversationId;
    private Long enterpriseId;
    private Long userId;

    /** {@link Role#USER} 或 {@link Role#ASSISTANT}。 */
    private String role;

    private String content;

    /** 工具调用的 JSON 数组，序列化为文本，以 jsonb 存储。 */
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
