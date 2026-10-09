package com.spotlink.trading.mapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spotlink.trading.entity.OrderStatusLog;

public interface OrderStatusLogMapper extends BaseMapper<OrderStatusLog> {

    default List<OrderStatusLog> findByOrderId(Long orderId) {
        return selectList(Wrappers.<OrderStatusLog>lambdaQuery().eq(OrderStatusLog::getOrderId, orderId).orderByAsc(OrderStatusLog::getId));
    }
}
