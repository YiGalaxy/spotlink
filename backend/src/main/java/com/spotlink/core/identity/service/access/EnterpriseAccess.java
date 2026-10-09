package com.spotlink.identity.service.access;

import java.util.List;
import java.util.Collection;
import com.spotlink.identity.entity.Enterprise;

/** 模块对外的数据访问契约；查询实现由本模块 Mapper 负责。 */
public interface EnterpriseAccess {
    Enterprise findByCode(String code);
    int insert(Enterprise entity);
    List<Enterprise> searchEnterprises(Integer status, String keyword);
    List<Enterprise> selectBatchIds(Collection<? extends java.io.Serializable> ids);
    Enterprise selectById(java.io.Serializable id);
    int updateById(Enterprise entity);
}
