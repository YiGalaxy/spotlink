package com.spotlink.admin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.spotlink.admin.dto.AdminViews;
import com.spotlink.identity.entity.Enterprise;
import com.spotlink.identity.mapper.EnterpriseMapper;
import com.spotlink.trading.entity.Order;
import com.spotlink.trading.entity.OrderStatus;
import com.spotlink.trading.mapper.OrderMapper;
import com.spotlink.warehouse.entity.Warehouse;
import com.spotlink.warehouse.mapper.WarehouseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 横跨全部租户的订单。
 *
 * <p><b>这是整个平台里唯一允许企业 ID 来自请求参数的地方。</b>其余各处企业 ID 都
 * 从调用方的会话中读取，根本表达不出别家公司的数据；而在这里，运营人员必须能够
 * 针对某一家企业发问，因为"这家企业的单子为什么被取消了"是客服不这么做就答不上来
 * 的问题。
 *
 * <p>这个例外之所以安全，原因只有一个，而且值得讲明：该接口由 {@code admin:order}
 * 把关，而其他所有地方都不存在这个例外，这一点是由测试断言的，不是靠注释承诺的。
 * 一个没人划定边界的例外不叫例外，那叫漏洞。
 *
 * <p>不带筛选条件读取会返回整个平台的数据。这是有意为之——运营后台的订单页默认就
 * 展示全部——这也意味着权限关卡是普通账号与平台上每一笔交易之间的唯一屏障。正因
 * 如此，它是一个独立的权限，而不是并进概览权限里。
 */
@Service
@RequiredArgsConstructor
public class AdminOrderService {

    private final OrderMapper orderMapper;
    private final EnterpriseMapper enterpriseMapper;
    private final WarehouseMapper warehouseMapper;

    /**
     * @param enterpriseId 传入时，把结果限定为该企业作为买方或卖方参与的订单；
     *                     不传则代表整个平台。
     */
    public List<AdminViews.OrderRow> search(Long enterpriseId, String status,
                                            String orderNo, int limit) {
        var query = Wrappers.<Order>lambdaQuery().orderByDesc(Order::getId).last("limit " + clamp(limit));
        if (enterpriseId != null) {
            query.and(w -> w.eq(Order::getBuyerId, enterpriseId)
                    .or().eq(Order::getSellerId, enterpriseId));
        }
        if (status != null && !status.isBlank()) {
            query.eq(Order::getStatus, status.trim().toUpperCase());
        }
        if (orderNo != null && !orderNo.isBlank()) {
            query.like(Order::getOrderNo, orderNo.trim());
        }

        List<Order> orders = orderMapper.selectList(query);
        if (orders.isEmpty()) {
            return List.of();
        }

        Set<Long> partyIds = new HashSet<>();
        Set<Long> warehouseIds = new HashSet<>();
        for (Order order : orders) {
            partyIds.add(order.getBuyerId());
            partyIds.add(order.getSellerId());
            warehouseIds.add(order.getWarehouseId());
        }
        Map<Long, String> enterprises = names(enterpriseMapper.selectBatchIds(partyIds),
                Enterprise::getId, Enterprise::getName);
        Map<Long, String> warehouses = names(
                warehouseIds.stream().filter(java.util.Objects::nonNull).collect(Collectors.toSet()).isEmpty()
                        ? List.of() : warehouseMapper.selectBatchIds(warehouseIds),
                Warehouse::getId, Warehouse::getName);

        return orders.stream()
                .map(order -> new AdminViews.OrderRow(
                        order.getId(),
                        order.getOrderNo(),
                        enterprises.getOrDefault(order.getBuyerId(), "—"),
                        enterprises.getOrDefault(order.getSellerId(), "—"),
                        order.getCommodityName(),
                        order.getQuantity(),
                        order.getUnit(),
                        order.getPrice(),
                        order.getAmount(),
                        order.getAmount() == null ? "—"
                                : order.getAmount().stripTrailingZeros().toPlainString(),
                        order.getStatus(),
                        OrderStatus.text(order.getStatus()),
                        "DELIVERED".equals(order.getDeliveryMethod()) ? "送到" : "自提",
                        warehouses.getOrDefault(order.getWarehouseId(), "—"),
                        order.getCreatedAt()))
                .toList();
    }

    /** 每一家在订单中出现过的企业，供筛选下拉框使用。 */
    public List<AdminViews.EnterpriseRow> orderParties() {
        Set<Long> ids = new HashSet<>();
        orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                        .select(Order::getBuyerId, Order::getSellerId)
                        .last("limit 2000"))
                .forEach(order -> {
                    ids.add(order.getBuyerId());
                    ids.add(order.getSellerId());
                });
        if (ids.isEmpty()) {
            return List.of();
        }
        return enterpriseMapper.selectBatchIds(ids).stream()
                .map(AdminEnterpriseService::toRow)
                .toList();
    }

    private <T> Map<Long, String> names(Collection<T> rows,
                                        java.util.function.Function<T, Long> id,
                                        java.util.function.Function<T, String> name) {
        return rows.stream().collect(Collectors.toMap(id, name, (a, b) -> a));
    }

    /** 是一个页面，不是一次导出。加上上限，避免一个运营后台的请求把整张表拉走。 */
    private int clamp(int limit) {
        return Math.min(Math.max(limit, 1), 200);
    }
}
