package com.bulk.trade.advisor.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 一个聊天会话，归单个用户所有。
 *
 * <p>归属按用户而不是按企业：对话记录是个人的工作上下文，同一家公司的同事没有理由
 * 读到另一个账号问过什么。
 */
@Getter
@Setter
@TableName("t_ai_conversation")
public class Conversation {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long enterpriseId;
    private Long userId;
    private String title;
    private Integer messageCount;
    private OffsetDateTime lastMessageAt;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;

    @TableLogic
    private Integer deleted;
}
