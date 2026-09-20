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
 * 一个平台账号。
 *
 * <p>平台运营方的 {@code enterpriseId} 为 null，代码正是靠这一点区分「看得见所有租户」
 * 和「只看得见一个租户」。
 */
@Getter
@Setter
@TableName("t_user")
public class User {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long enterpriseId;
    private String username;

    /** BCrypt 哈希。永不记入日志，永不通过接口返回。 */
    private String password;

    private String realName;
    private String phone;
    private String email;

    /** 1=企业用户，2=平台运营，3=超级管理员。 */
    private Integer userType;

    /** 0=已停用，1=正常，2=已锁定。 */
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
