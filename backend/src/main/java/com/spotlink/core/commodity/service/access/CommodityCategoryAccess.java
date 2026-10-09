package com.spotlink.commodity.service.access;

import java.util.List;
import java.util.Collection;
import com.spotlink.commodity.entity.CommodityCategory;

/** 模块对外的数据访问契约；查询实现由本模块 Mapper 负责。 */
public interface CommodityCategoryAccess {
    boolean hasChildren(Long parentId);
    List<CommodityCategory> selectBatchIds(Collection<? extends java.io.Serializable> ids);
    CommodityCategory selectById(java.io.Serializable id);
}
