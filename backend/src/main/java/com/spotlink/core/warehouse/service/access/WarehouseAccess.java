package com.spotlink.warehouse.service.access;

import java.util.List;
import java.util.Collection;
import com.spotlink.warehouse.entity.Warehouse;

/** 模块对外的数据访问契约；查询实现由本模块 Mapper 负责。 */
public interface WarehouseAccess {
    List<Long> findIdsByLocation(String location);
    List<Warehouse> selectBatchIds(Collection<? extends java.io.Serializable> ids);
    Warehouse selectById(java.io.Serializable id);
}
