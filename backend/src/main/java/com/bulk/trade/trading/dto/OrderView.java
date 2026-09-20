package com.bulk.trade.trading.dto;

import com.bulk.trade.trading.entity.Order;
import com.bulk.trade.trading.entity.OrderStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
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
 *
 * <p>That second guarantee is why {@code callerIsLister} exists. Answering a
 * waiting acceptance is one named party's move alone, so an action list
 * computed without knowing who is looking would offer the other side a button
 * the server then rejects — worse than hiding it, because a button reads as a
 * promise.
 *
 * <p><b>Nulls are written, not omitted.</b> The application-wide Jackson
 * setting drops null properties, which for a view DTO makes the response shape
 * depend on the data: a field that is null one moment and absent the next
 * forces every client to treat "missing" and "null" as the same thing, and they
 * are not — {@code confirmDeadline: null} means "no answer is awaited", while a
 * missing key means the client has no idea what the server said. The client
 * declares these fields as nullable, so the server should say so out loud.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
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

        /** When the lister's answer is due; null unless one is awaited. */
        OffsetDateTime confirmDeadline,

        OffsetDateTime confirmedAt,
        OffsetDateTime cancelledAt,
        String cancelReason,
        OffsetDateTime createdAt
) {

    public static OrderView of(Order order,
                               Long viewerEnterpriseId,
                               boolean callerIsLister,
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
                List.copyOf(OrderStatus.allowedFrom(order.getStatus(), callerIsLister)),
                order.getConfirmDeadline(),
                order.getConfirmedAt(),
                order.getCancelledAt(),
                order.getCancelReason(),
                order.getCreatedAt());
    }
}
