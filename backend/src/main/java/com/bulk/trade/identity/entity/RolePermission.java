package com.bulk.trade.identity.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 一个角色带着哪些权限。没有软删除列的理由与 {@link UserRole} 相同：
 * 收回一项权限，就是把它删掉。
 */
@Getter
@Setter
@TableName("t_role_permission")
public class RolePermission {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long roleId;
    private Long permissionId;
    private OffsetDateTime createdAt;
}
