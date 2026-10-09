package com.spotlink.identity.mapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spotlink.identity.entity.UserRole;

/** 日常 CRUD；需要更多能力的连接查询，就写在用到它的地方。 */
public interface UserRoleMapper extends BaseMapper<UserRole> {

    default List<UserRole> findByUserId(Long userId) {
        return selectList(Wrappers.<UserRole>lambdaQuery().eq(UserRole::getUserId, userId));
    }

    default int deleteByUserId(Long userId) {
        return delete(Wrappers.<UserRole>lambdaQuery().eq(UserRole::getUserId, userId));
    }

    default boolean existsGrant(Long userId, Long roleId) {
        return selectCount(Wrappers.<UserRole>lambdaQuery().eq(UserRole::getUserId, userId).eq(UserRole::getRoleId, roleId)) > 0;
    }
}
