package com.spotlink.identity.mapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spotlink.identity.entity.Role;

public interface RoleMapper extends BaseMapper<Role> {

    default Role findPlatformRole(String code) {
        return selectOne(Wrappers.<Role>lambdaQuery().eq(Role::getCode, code).isNull(Role::getEnterpriseId));
    }

    default List<Role> findPlatformRoles() {
        return selectList(Wrappers.<Role>lambdaQuery().isNull(Role::getEnterpriseId).orderByAsc(Role::getId));
    }
}
