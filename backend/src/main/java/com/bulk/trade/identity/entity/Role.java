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
 * A role.
 *
 * <p>A null {@code enterpriseId} marks a platform-wide role shared by all
 * tenants; a non-null value scopes the role to one company.
 */
@Getter
@Setter
@TableName("t_role")
public class Role {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long enterpriseId;
    private String code;
    private String name;
    private String description;

    /** System roles cannot be renamed or deleted by tenants. */
    private Boolean isSystem;

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
