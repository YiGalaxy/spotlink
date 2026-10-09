package com.spotlink.identity.service.access;

import java.util.List;
import com.spotlink.identity.entity.Role;
import com.spotlink.identity.mapper.RoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 跨模块访问入口，沿用调用方事务，不向调用方暴露 ORM 条件。 */
@Service
@RequiredArgsConstructor
public class RoleAccessService implements RoleAccess {
    private final RoleMapper mapper;

    @Override public Role findPlatformRole(String code) {
        return mapper.findPlatformRole(code);
    }

    @Override public List<Role> findPlatformRoles() {
        return mapper.findPlatformRoles();
    }

    @Override public Role selectById(java.io.Serializable id) {
        return mapper.selectById(id);
    }
}
