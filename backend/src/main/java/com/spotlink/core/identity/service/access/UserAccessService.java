package com.spotlink.identity.service.access;

import java.util.List;
import com.spotlink.identity.entity.User;
import com.spotlink.identity.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 跨模块访问入口，沿用调用方事务，不向调用方暴露 ORM 条件。 */
@Service
@RequiredArgsConstructor
public class UserAccessService implements UserAccess {
    private final UserMapper mapper;

    @Override public List<User> findActiveAccounts() {
        return mapper.findActiveAccounts();
    }

    @Override public User findByUsername(String username) {
        return mapper.findByUsername(username);
    }

    @Override public List<User> findEnterpriseMembers(Long enterpriseId, boolean includeDisabled, int limit) {
        return mapper.findEnterpriseMembers(enterpriseId, includeDisabled, limit);
    }

    @Override public int insert(User entity) {
        return mapper.insert(entity);
    }

    @Override public List<User> searchAccounts(String keyword, Integer status) {
        return mapper.searchAccounts(keyword, status);
    }

    @Override public User selectById(java.io.Serializable id) {
        return mapper.selectById(id);
    }

    @Override public int updateById(User entity) {
        return mapper.updateById(entity);
    }
}
