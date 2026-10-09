package com.spotlink.identity.service.access;

import java.util.List;
import java.util.Collection;
import com.spotlink.identity.entity.Permission;
import com.spotlink.identity.mapper.PermissionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 跨模块访问入口，沿用调用方事务，不向调用方暴露 ORM 条件。 */
@Service
@RequiredArgsConstructor
public class PermissionAccessService implements PermissionAccess {
    private final PermissionMapper mapper;
    @Override public List<Permission> selectBatchIds(Collection<? extends java.io.Serializable> ids) {
        return mapper.selectBatchIds(ids);
    }

    @Override public List<Permission> findAdminPermissions() {
        return mapper.findAdminPermissions();
    }

    @Override public List<Permission> findAllOrdered() {
        return mapper.findAllOrdered();
    }
}
