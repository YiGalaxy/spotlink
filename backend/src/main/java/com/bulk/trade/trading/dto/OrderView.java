package com.bulk.trade.trading.dto;

import com.bulk.trade.trading.entity.Order;
import com.bulk.trade.trading.entity.OrderStatus;
import com.bulk.trade.trading.service.OrderProgress;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 客户端看到的订单。
 *
 * <p>{@code myRole} 按调用方逐个解析，这样界面无需猜测查看者站在哪一方就能
 * 正确标注对手方；而 {@code allowedActions} 携带的是该调用方实际可执行的
 * 迁移——与服务端强制执行的是同一张表，因此显示出来的按钮就是按下去管用的
 * 按钮。
 *
 * <p>这后一项保证正是 {@code callerIsLister} 存在的原因。答复一个等待中的
 * 摘牌是某一个具名主体独有的动作，因此在不清楚查看者是谁的情况下算出的动作
 * 列表，会给另一方一个随后被服务端拒绝的按钮——这比不显示更糟，因为按钮读
 * 起来像一句承诺。
 *
 * <p><b>null 值是写出，而不是省略。</b>应用级的 Jackson 配置会丢弃 null
 * 属性，这对一个视图 DTO 而言会让响应形状取决于数据：一个此刻为 null、下一
 * 刻就消失的字段，迫使每个客户端把“缺失”和“null”当成同一回事，而它们不是
 * 一回事——{@code confirmDeadline: null} 的意思是“没有在等任何答复”，而键
 * 的缺失意味着客户端根本不知道服务端说了什么。客户端把这些字段声明为可空，
 * 那么服务端就应该明明白白地讲出来。
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

        /** BUYER 或 SELLER，从调用方的视角看。 */
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

        /**
         * 现在轮到谁，从该调用方的角度——同一笔订单对一方是“待我签署”，对
         * 另一方是“等对方签署”。当没有任何待办时则为 null。
         */
        String statusHint,
        /** 当下一步轮到调用方时为 true。用于排序和配色。 */
        boolean statusHintMine,
        /**
         * 执行下一步动作的按钮标签，当不轮到调用方时为 null。与
         * {@code statusHint} 不同：那个描述一种处境，这个执行一个动作。
         */
        String nextAction,

        List<String> allowedActions,

        /** 挂牌方答复的截止时间；未在等待答复时为 null。 */
        OffsetDateTime confirmDeadline,

        OffsetDateTime confirmedAt,
        OffsetDateTime cancelledAt,
        String cancelReason,
        OffsetDateTime createdAt
) {

    public static OrderView of(Order order,
                               Long viewerEnterpriseId,
                               boolean callerIsLister,
                               boolean callerIsSeller,
                               OrderProgress progress,
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
                progress.text(),
                progress.mine(),
                progress.nextAction(),
                List.copyOf(OrderStatus.allowedFrom(
                        order.getStatus(), callerIsLister, callerIsSeller)),
                order.getConfirmDeadline(),
                order.getConfirmedAt(),
                order.getCancelledAt(),
                order.getCancelReason(),
                order.getCreatedAt());
    }
}
