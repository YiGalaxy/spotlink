package com.spotlink.identity.mapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spotlink.identity.entity.Permission;

/** 日常 CRUD；需要更多能力的连接查询，就写在用到它的地方。 */
public interface PermissionMapper extends BaseMapper<Permission> {

    default List<Permission> findAllOrdered() {
        return selectList(Wrappers.<Permission>lambdaQuery().orderByAsc(Permission::getSortOrder).orderByAsc(Permission::getId));
    }

    default List<Permission> findAdminPermissions() {
        return selectList(Wrappers.<Permission>lambdaQuery().likeRight(Permission::getCode, "admin:"));
    }
}
