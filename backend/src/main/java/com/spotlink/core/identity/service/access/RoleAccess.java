package com.spotlink.identity.service.access;

import java.util.List;
import com.spotlink.identity.entity.Role;

/** 模块对外的数据访问契约；查询实现由本模块 Mapper 负责。 */
public interface RoleAccess {
    Role findPlatformRole(String code);
    List<Role> findPlatformRoles();
    Role selectById(java.io.Serializable id);
}
