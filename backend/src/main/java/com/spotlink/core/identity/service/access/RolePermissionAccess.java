package com.spotlink.identity.service.access;

import java.util.List;
import java.util.Collection;
import com.spotlink.identity.entity.RolePermission;

/** 模块对外的数据访问契约；查询实现由本模块 Mapper 负责。 */
public interface RolePermissionAccess {
    boolean existsGrant(Long roleId, Long permissionId);
    List<RolePermission> findByRoleIds(Collection<Long> roleIds);
    int insert(RolePermission entity);
}
