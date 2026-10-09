package com.spotlink.trading.service.access;

import java.util.List;
import java.time.OffsetDateTime;
import com.spotlink.trading.entity.Order;
import com.spotlink.trading.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 跨模块访问入口，沿用调用方事务，不向调用方暴露 ORM 条件。 */
@Service
@RequiredArgsConstructor
public class OrderAccessService implements OrderAccess {
    private final OrderMapper mapper;

    @Override public List<Order> findCreatedSince(OffsetDateTime since) {
        return mapper.findCreatedSince(since);
    }

    @Override public List<Order> findPartyReferences() {
        return mapper.findPartyReferences();
    }

    @Override public List<Order> searchPlatformOrders(Long enterpriseId, String status, String orderNo, int limit) {
        return mapper.searchPlatformOrders(enterpriseId, status, orderNo, limit);
    }

    @Override public Order selectById(java.io.Serializable id) {
        return mapper.selectById(id);
    }

    @Override public int updateById(Order entity) {
        return mapper.updateById(entity);
    }
}
