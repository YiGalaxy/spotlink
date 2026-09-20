package com.bulk.trade.shared.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 一条被记录下来的操作，写一次，之后永不被碰。
 *
 * <p><b>刻意没有 {@code deleted}，也没有 {@code updated_at}。</b>这份 schema 里
 * 其他每一个实体都是软删除的，都有触发器维护它的修改时间。**这两样都不属于一份日志**：
 * 一行能被更新的日志，是证词能被编辑的日志；一行能被删除的日志，是被告最想删掉的那种。
 * 别处那套软删除约定，在这里恰好是错的形状。
 */
@Getter
@Setter
@TableName("t_audit_log")
public class AuditLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 这一行说的是哪个租户；平台级动作为 null。 */
    private Long enterpriseId;

    private Long userId;
    private String username;

    /** 属于平台的哪一部分，例如 {@code enterprise}。 */
    private String module;

    /** 做了什么，例如 {@code approve}。 */
    private String action;

    private String targetType;
    private Long targetId;

    /** 变更前后的序列化内容，调用方知道时才写。 */
    private String beforeData;
    private String afterData;

    private String ip;
    private String userAgent;

    private Boolean success;
    private String errorMessage;
    private Long costMs;

    private OffsetDateTime createdAt;
}
