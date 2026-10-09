package com.spotlink.identity.mapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Collection;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spotlink.identity.entity.RolePermission;

/** 日常 CRUD；需要更多能力的连接查询，就写在用到它的地方。 */
public interface RolePermissionMapper extends BaseMapper<RolePermission> {

    default List<RolePermission> findByRoleIds(Collection<Long> roleIds) {
        return roleIds.isEmpty() ? List.of() : selectList(Wrappers.<RolePermission>lambdaQuery().in(RolePermission::getRoleId, roleIds));
    }

    default boolean existsGrant(Long roleId, Long permissionId) {
        return selectCount(Wrappers.<RolePermission>lambdaQuery().eq(RolePermission::getRoleId, roleId).eq(RolePermission::getPermissionId, permissionId)) > 0;
    }
}
