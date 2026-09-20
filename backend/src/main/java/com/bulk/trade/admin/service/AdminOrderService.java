package com.bulk.trade.admin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bulk.trade.admin.dto.AdminViews;
import com.bulk.trade.identity.entity.Enterprise;
import com.bulk.trade.identity.mapper.EnterpriseMapper;
import com.bulk.trade.trading.entity.Order;
import com.bulk.trade.trading.entity.OrderStatus;
import com.bulk.trade.trading.mapper.OrderMapper;
import com.bulk.trade.warehouse.entity.Warehouse;
import com.bulk.trade.warehouse.mapper.WarehouseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Orders across every tenant.
 *
 * <p><b>This is the one place in the platform where an enterprise id may come
 * from a request parameter.</b> Everywhere else it is read from the caller's
 * session and there is no way to express another company's data; here an
 * operator has to be able to ask about a specific one, because "why was this
 * enterprise's deal cancelled" is a question support cannot answer otherwise.
 *
 * <p>The exception is safe for exactly one reason and it is worth stating: the
 * endpoint is gated by {@code admin:order}, and the absence of the exception
 * everywhere else is asserted by a test rather than promised by a comment. An
 * exception nobody has bounded is not an exception, it is a hole.
 *
 * <p>Reading without a filter returns the whole platform. That is deliberate —
 * the console's order screen opens on everything — and it means the permission
 * gate is the only thing between an ordinary account and every trade on the
 * venue. Which is why it is a permission of its own rather than folded into the
 * overview.
 */
@Service
@RequiredArgsConstructor
public class AdminOrderService {

    private final OrderMapper orderMapper;
    private final EnterpriseMapper enterpriseMapper;
    private final WarehouseMapper warehouseMapper;

    /**
     * @param enterpriseId when present, limits the result to orders that party
     *                     is on either side of. Absent means the whole platform.
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

    /** Every enterprise that appears on an order, for the filter dropdown. */
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

    /** A page, not an export. Bounded so a console request cannot pull the table. */
    private int clamp(int limit) {
        return Math.min(Math.max(limit, 1), 200);
    }
}
