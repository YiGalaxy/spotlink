package com.bulk.trade.identity.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Which roles an account holds.
 *
 * <p><b>No soft-delete column, and that is not an oversight.</b> Every other
 * table in this schema carries {@code deleted} so history survives a deletion.
 * Here the deletion <em>is</em> the history: revoking a role is meant to remove
 * the grant, and a soft-deleted row would leave the account still holding it,
 * because every query would have to remember to filter. A grant that outlives
 * its revocation is a security bug, not a record.
 *
 * <p>Unassigning is therefore a hard delete, and the audit log is where the
 * fact that it happened is kept.
 */
@Getter
@Setter
@TableName("t_user_role")
public class UserRole {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;
    private Long roleId;
    private OffsetDateTime createdAt;
}
