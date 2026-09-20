package com.bulk.trade.trading.dto;

import com.bulk.trade.trading.entity.Listing;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * A listing as the client sees it.
 *
 * <p>{@code mine} is computed per caller so the market screen can hide the
 * "accept" button on one's own listings without a second request. Accepting
 * your own offer is not a trade.
 *
 * <p>{@code confirmModeText} is carried to the market screen, not just to the
 * owner's own list. Whether an acceptance closes the deal or waits for the
 * lister is the single fact a buyer most needs before pressing the button, and
 * discovering it afterwards is how a party ends up feeling misled.
 *
 * <p>Nulls are written rather than omitted, for the reason given on
 * {@link OrderView}: a response whose shape depends on its values is one every
 * client has to guess at.
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

        /** AUTO or MANUAL. */
        String confirmMode,
        /** "摘牌即成交" or "需挂牌方确认". */
        String confirmModeText,

        @JsonSerialize(using = ToStringSerializer.class) Long warehouseId,
        String warehouseName,

        String deliveryMethod,
        String deliveryMethodText,

        OffsetDateTime validUntil,
        String status,
        String statusText,

        /** True when the caller owns this listing. */
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
