package com.spotlink.identity.service.access;

import java.util.List;
import java.util.Collection;
import com.spotlink.identity.entity.Permission;

/** 模块对外的数据访问契约；查询实现由本模块 Mapper 负责。 */
public interface PermissionAccess {
    List<Permission> selectBatchIds(Collection<? extends java.io.Serializable> ids);
    List<Permission> findAdminPermissions();
    List<Permission> findAllOrdered();
}
