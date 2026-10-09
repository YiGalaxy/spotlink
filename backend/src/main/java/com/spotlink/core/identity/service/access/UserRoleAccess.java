package com.spotlink.identity.service.access;


import com.spotlink.identity.entity.UserRole;

/** 模块对外的数据访问契约；查询实现由本模块 Mapper 负责。 */
public interface UserRoleAccess {
    int deleteByUserId(Long userId);
    boolean existsGrant(Long userId, Long roleId);
    int insert(UserRole entity);
}
