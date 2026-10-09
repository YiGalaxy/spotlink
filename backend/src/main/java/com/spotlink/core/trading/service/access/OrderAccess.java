package com.spotlink.trading.service.access;

import java.util.List;
import java.time.OffsetDateTime;
import com.spotlink.trading.entity.Order;

/** 模块对外的数据访问契约；查询实现由本模块 Mapper 负责。 */
public interface OrderAccess {
    List<Order> findCreatedSince(OffsetDateTime since);
    List<Order> findPartyReferences();
    List<Order> searchPlatformOrders(Long enterpriseId, String status, String orderNo, int limit);
    Order selectById(java.io.Serializable id);
    int updateById(Order entity);
}
