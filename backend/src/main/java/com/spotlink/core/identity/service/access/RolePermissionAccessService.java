package com.spotlink.identity.service.access;

import java.util.List;
import java.util.Collection;
import com.spotlink.identity.entity.RolePermission;
import com.spotlink.identity.mapper.RolePermissionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 跨模块访问入口，沿用调用方事务，不向调用方暴露 ORM 条件。 */
@Service
@RequiredArgsConstructor
public class RolePermissionAccessService implements RolePermissionAccess {
    private final RolePermissionMapper mapper;

    @Override public boolean existsGrant(Long roleId, Long permissionId) {
        return mapper.existsGrant(roleId, permissionId);
    }

    @Override public List<RolePermission> findByRoleIds(Collection<Long> roleIds) {
        return mapper.findByRoleIds(roleIds);
    }

    @Override public int insert(RolePermission entity) {
        return mapper.insert(entity);
    }
}
