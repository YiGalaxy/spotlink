package com.bulk.trade.trading.dto;

import com.bulk.trade.trading.entity.Order;
import com.bulk.trade.trading.entity.OrderStatus;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * An order as the client sees it.
 *
 * <p>{@code myRole} is resolved per caller so the UI can label the counterparty
 * correctly without guessing which side the viewer is on, and {@code
 * allowedActions} carries the transitions this caller may actually perform —
 * the same table the server enforces, so a button shown is a button that works.
 */
public record OrderView(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String orderNo,
        @JsonSerialize(using = ToStringSerializer.class) Long listingId,

        @JsonSerialize(using = ToStringSerializer.class) Long buyerId,
        String buyerName,
        @JsonSerialize(using = ToStringSerializer.class) Long sellerId,
        String sellerName,

        /** BUYER or SELLER, from the caller's point of view. */
        String myRole,
        String counterpartyName,

        @JsonSerialize(using = ToStringSerializer.class) Long categoryId,
        String categoryName,
        String commodityName,

        BigDecimal quantity,
        String unit,
        BigDecimal price,
        BigDecimal amount,
        String amountText,

        String warehouseName,
        String deliveryMethodText,

        String status,
        String statusText,
        List<String> allowedActions,

        OffsetDateTime confirmedAt,
        OffsetDateTime cancelledAt,
        String cancelReason,
        OffsetDateTime createdAt
) {

    public static OrderView of(Order order,
                               Long viewerEnterpriseId,
                               String buyerName,
                               String sellerName,
                               String categoryName,
                               String warehouseName) {
        String role = order.roleOf(viewerEnterpriseId);
        return new OrderView(
                order.getId(),
                order.getOrderNo(),
                order.getListingId(),
                order.getBuyerId(),
                buyerName,
                order.getSellerId(),
                sellerName,
                role,
                "BUYER".equals(role) ? sellerName : buyerName,
                order.getCategoryId(),
                categoryName,
                order.getCommodityName(),
                order.getQuantity(),
                order.getUnit(),
                order.getPrice(),
                order.getAmount(),
                order.getAmount() == null ? "—" : order.getAmount().stripTrailingZeros().toPlainString(),
                warehouseName,
                "DELIVERED".equals(order.getDeliveryMethod()) ? "送到" : "自提",
                order.getStatus(),
                OrderStatus.text(order.getStatus()),
                List.copyOf(OrderStatus.allowedFrom(order.getStatus())),
                order.getConfirmedAt(),
                order.getCancelledAt(),
                order.getCancelReason(),
                order.getCreatedAt());
    }
}
