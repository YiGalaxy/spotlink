package com.spotlink.identity.service.access;


import com.spotlink.identity.entity.UserRole;
import com.spotlink.identity.mapper.UserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 跨模块访问入口，沿用调用方事务，不向调用方暴露 ORM 条件。 */
@Service
@RequiredArgsConstructor
public class UserRoleAccessService implements UserRoleAccess {
    private final UserRoleMapper mapper;

    @Override public int deleteByUserId(Long userId) {
        return mapper.deleteByUserId(userId);
    }

    @Override public boolean existsGrant(Long userId, Long roleId) {
        return mapper.existsGrant(userId, roleId);
    }

    @Override public int insert(UserRole entity) {
        return mapper.insert(entity);
    }
}
