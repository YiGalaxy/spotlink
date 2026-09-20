package com.bulk.trade.shared.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * One recorded action, written once and never touched again.
 *
 * <p><b>No {@code deleted} and no {@code updated_at}, deliberately.</b> Every
 * other entity in this schema is soft-deleted and has a trigger maintaining its
 * modification time. Neither belongs on a log: a row that can be updated is a
 * row whose testimony can be edited, and a row that can be deleted is one an
 * accused party would most like to remove. The soft-delete conventions used
 * elsewhere are exactly the wrong shape here.
 */
@Getter
@Setter
@TableName("t_audit_log")
public class AuditLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** The tenant the row is about; null for platform-level actions. */
    private Long enterpriseId;

    private Long userId;
    private String username;

    /** Which part of the platform, e.g. {@code enterprise}. */
    private String module;

    /** What was done, e.g. {@code approve}. */
    private String action;

    private String targetType;
    private Long targetId;

    /** Serialised before and after, when the caller knew them. */
    private String beforeData;
    private String afterData;

    private String ip;
    private String userAgent;

    private Boolean success;
    private String errorMessage;
    private Long costMs;

    private OffsetDateTime createdAt;
}
