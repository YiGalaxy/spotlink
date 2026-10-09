package com.spotlink.identity.service.access;

import java.util.List;
import com.spotlink.identity.entity.User;

/** 模块对外的数据访问契约；查询实现由本模块 Mapper 负责。 */
public interface UserAccess {
    List<User> findActiveAccounts();
    User findByUsername(String username);
    List<User> findEnterpriseMembers(Long enterpriseId, boolean includeDisabled, int limit);
    int insert(User entity);
    List<User> searchAccounts(String keyword, Integer status);
    User selectById(java.io.Serializable id);
    int updateById(User entity);
}
