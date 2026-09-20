package com.bulk.trade.identity.entity;

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
 * A platform account.
 *
 * <p>{@code enterpriseId} is null for platform operators, which is exactly how
 * the code distinguishes "sees all tenants" from "sees one tenant".
 */
@Getter
@Setter
@TableName("t_user")
public class User {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long enterpriseId;
    private String username;

    /** BCrypt hash. Never logged, never returned by an API. */
    private String password;

    private String realName;
    private String phone;
    private String email;

    /** 1=enterprise user, 2=platform operator, 3=super admin. */
    private Integer userType;

    /** 0=disabled, 1=active, 2=locked. */
    private Integer status;

    private OffsetDateTime lastLoginAt;
    private String lastLoginIp;

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

    public static final class Type {
        public static final int ENTERPRISE = 1;
        public static final int PLATFORM_OPERATOR = 2;
        public static final int SUPER_ADMIN = 3;

        private Type() {
        }
    }

    public static final class Status {
        public static final int DISABLED = 0;
        public static final int ACTIVE = 1;
        public static final int LOCKED = 2;

        private Status() {
        }
    }
}
