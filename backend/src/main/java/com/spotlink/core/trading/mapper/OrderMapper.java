package com.spotlink.trading.mapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Locale;
import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spotlink.trading.entity.Order;

public interface OrderMapper extends BaseMapper<Order> {
    @org.apache.ibatis.annotations.Select("SELECT * FROM t_order WHERE id=#{id} AND deleted=0 FOR UPDATE")
    Order lockById(@org.apache.ibatis.annotations.Param("id") Long id);

    default long countPendingConfirmations(Long listingId) {
        return selectCount(Wrappers.<Order>lambdaQuery().eq(Order::getListingId, listingId).eq(Order::getStatus, com.spotlink.trading.entity.OrderStatus.PENDING_CONFIRM));
    }

    default List<Order> findOverdueConfirmations(OffsetDateTime now) {
        return selectList(Wrappers.<Order>lambdaQuery().eq(Order::getStatus, com.spotlink.trading.entity.OrderStatus.PENDING_CONFIRM).isNotNull(Order::getConfirmDeadline).lt(Order::getConfirmDeadline, now));
    }

    default List<Order> findParticipantOrders(Long enterpriseId, String status) {
        var query = Wrappers.<Order>lambdaQuery().and(w -> w.eq(Order::getBuyerId, enterpriseId).or().eq(Order::getSellerId, enterpriseId)).orderByDesc(Order::getId);
        if (status != null && !status.isBlank()) query.eq(Order::getStatus, status);
        return selectList(query);
    }

    default List<Order> searchPlatformOrders(Long enterpriseId, String status, String orderNo, int limit) {
        var query = Wrappers.<Order>lambdaQuery().orderByDesc(Order::getId).last("LIMIT " + Math.min(Math.max(limit, 1), 200));
        if (enterpriseId != null) query.and(w -> w.eq(Order::getBuyerId, enterpriseId).or().eq(Order::getSellerId, enterpriseId));
        if (status != null && !status.isBlank()) query.eq(Order::getStatus, status.trim().toUpperCase(Locale.ROOT));
        if (orderNo != null && !orderNo.isBlank()) query.like(Order::getOrderNo, orderNo.trim());
        return selectList(query);
    }

    default List<Order> findPartyReferences() {
        return selectList(Wrappers.<Order>lambdaQuery().select(Order::getBuyerId, Order::getSellerId).last("LIMIT 2000"));
    }

    default List<Order> findCreatedSince(OffsetDateTime since) {
        return selectList(Wrappers.<Order>lambdaQuery().ge(Order::getCreatedAt, since).orderByAsc(Order::getCreatedAt).last("LIMIT 5000"));
    }
}
