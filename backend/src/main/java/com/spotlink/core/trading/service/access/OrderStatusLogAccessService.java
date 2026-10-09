package com.spotlink.trading.service.access;


import com.spotlink.trading.entity.OrderStatusLog;
import com.spotlink.trading.mapper.OrderStatusLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 跨模块访问入口，沿用调用方事务，不向调用方暴露 ORM 条件。 */
@Service
@RequiredArgsConstructor
public class OrderStatusLogAccessService implements OrderStatusLogAccess {
    private final OrderStatusLogMapper mapper;

    @Override public int insert(OrderStatusLog entity) {
        return mapper.insert(entity);
    }
}
