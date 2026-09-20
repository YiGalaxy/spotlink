package com.bulk.trade.trading.dto;

import com.bulk.trade.trading.entity.Listing;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 客户端看到的挂牌。
 *
 * <p>{@code mine} 按调用方逐个计算，这样行情页无需再发一次请求就能在自己的
 * 挂牌上隐藏“摘牌”按钮。摘自己的牌不算交易。
 *
 * <p>{@code confirmModeText} 会一并送到行情页，而不只是送到挂牌方自己的
 * 列表里。一次摘牌是直接成交还是等待挂牌方，是买方在按下按钮之前最需要知道
 * 的唯一一件事；事后再发现，正是一方觉得自己被误导的由来。
 *
 * <p>null 值是写出而不是省略，原因见 {@link OrderView}：一个形状取决于其
 * 取值的响应，是每个客户端都得靠猜的响应。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ListingView(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String listingNo,
        String side,
        String sideText,

        @JsonSerialize(using = ToStringSerializer.class) Long enterpriseId,
        String enterpriseName,

        @JsonSerialize(using = ToStringSerializer.class) Long categoryId,
        String categoryName,

        String commodityName,
        String brand,
        String origin,
        Map<String, Object> spec,

        BigDecimal quantity,
        BigDecimal remainingQuantity,
        String unit,

        BigDecimal price,
        String priceType,
        String priceText,

        /** AUTO 或 MANUAL。 */
        String confirmMode,
        /** "摘牌即成交" 或 "需挂牌方确认"。 */
        String confirmModeText,

        @JsonSerialize(using = ToStringSerializer.class) Long warehouseId,
        String warehouseName,

        String deliveryMethod,
        String deliveryMethodText,

        OffsetDateTime validUntil,
        String status,
        String statusText,

        /** 当调用方拥有该挂牌时为 true。 */
        boolean mine,

        OffsetDateTime createdAt
) {

    public static ListingView of(Listing listing,
                                 String enterpriseName,
                                 String categoryName,
                                 String warehouseName,
                                 boolean mine) {
        return new ListingView(
                listing.getId(),
                listing.getListingNo(),
                listing.getSide(),
                Listing.Side.SELL.equals(listing.getSide()) ? "卖方挂牌" : "买方挂牌",
                listing.getEnterpriseId(),
                enterpriseName,
                listing.getCategoryId(),
                categoryName,
                listing.getCommodityName(),
                listing.getBrand(),
                listing.getOrigin(),
                Map.of(),
                listing.getQuantity(),
                listing.getRemainingQuantity(),
                listing.getUnit(),
                listing.getPrice(),
                listing.getPriceType(),
                Listing.PriceType.NEGOTIABLE.equals(listing.getPriceType())
                        ? "面议"
                        : listing.getPrice().stripTrailingZeros().toPlainString(),
                listing.getConfirmMode(),
                confirmModeText(listing.getConfirmMode()),
                listing.getWarehouseId(),
                warehouseName,
                listing.getDeliveryMethod(),
                Listing.DeliveryMethod.DELIVERED.equals(listing.getDeliveryMethod()) ? "送到" : "自提",
                listing.getValidUntil(),
                listing.getStatus(),
                statusText(listing.getStatus()),
                mine,
                listing.getCreatedAt());
    }

    private static String confirmModeText(String mode) {
        return Listing.ConfirmMode.MANUAL.equals(mode) ? "需挂牌方确认" : "摘牌即成交";
    }

    private static String statusText(String status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case Listing.Status.OPEN -> "挂牌中";
            case Listing.Status.PARTIALLY_FILLED -> "部分成交";
            case Listing.Status.FILLED -> "已成交";
            case Listing.Status.CLOSED -> "已撤牌";
            case Listing.Status.EXPIRED -> "已过期";
            default -> "未知";
        };
    }
}
