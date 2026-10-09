package com.spotlink.identity.mapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spotlink.identity.entity.User;

public interface UserMapper extends BaseMapper<User> {

    default User findByUsername(String username) {
        return selectOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, username));
    }

    default List<User> searchAccounts(String keyword, Integer status) {
        var query = Wrappers.<User>lambdaQuery().orderByDesc(User::getId).last("LIMIT 200");
        if (status != null) query.eq(User::getStatus, status);
        if (keyword != null && !keyword.isBlank()) query.and(w -> w.like(User::getUsername, keyword.trim()).or().like(User::getRealName, keyword.trim()));
        return selectList(query);
    }

    default List<User> findActiveAccounts() {
        return selectList(Wrappers.<User>lambdaQuery().eq(User::getStatus, User.Status.ACTIVE));
    }

    default List<User> findEnterpriseMembers(Long enterpriseId, boolean includeDisabled, int limit) {
        return selectList(Wrappers.<User>lambdaQuery().eq(User::getEnterpriseId, enterpriseId)
                .eq(!includeDisabled, User::getStatus, User.Status.ACTIVE).orderByAsc(User::getId).last("LIMIT " + Math.min(Math.max(limit, 1), 200)));
    }
}
