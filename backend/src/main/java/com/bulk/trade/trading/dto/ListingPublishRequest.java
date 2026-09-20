package com.bulk.trade.trading.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Publishes a listing.
 *
 * <p>A SELL listing must name the inventory note it is backed by: publishing an
 * offer to sell goods you have not identified is not an offer, it is an
 * advertisement. The note's goods are frozen for as long as the listing is open.
 */
public record ListingPublishRequest(

        @NotBlank(message = "请指定挂牌方向")
        String side,

        /** Required for SELL listings; ignored for BUY. */
        Long inventoryNoteId,

        @NotNull(message = "请选择品类")
        Long categoryId,

        @NotBlank(message = "请填写商品名称")
        @Size(max = 128, message = "商品名称过长")
        String commodityName,

        @Size(max = 64) String brand,
        @Size(max = 64) String origin,
        Map<String, Object> spec,

        @NotNull(message = "请填写数量")
        @DecimalMin(value = "0.001", message = "数量必须大于 0")
        BigDecimal quantity,

        @Size(max = 16) String unit,

        /** Null when the price type is NEGOTIABLE. */
        BigDecimal price,

        /** FIXED or NEGOTIABLE. */
        String priceType,

        Long warehouseId,

        /** SELF_PICKUP or DELIVERED. */
        String deliveryMethod,

        String paymentTerms,

        @NotNull(message = "请填写挂牌有效期")
        OffsetDateTime validUntil,

        @Size(max = 512) String remark
) {
}
