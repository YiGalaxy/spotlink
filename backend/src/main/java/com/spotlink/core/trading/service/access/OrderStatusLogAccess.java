package com.spotlink.trading.service.access;


import com.spotlink.trading.entity.OrderStatusLog;

/** 模块对外的数据访问契约；查询实现由本模块 Mapper 负责。 */
public interface OrderStatusLogAccess {
    int insert(OrderStatusLog entity);
}
