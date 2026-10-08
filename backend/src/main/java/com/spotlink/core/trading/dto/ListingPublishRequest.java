package com.spotlink.trading.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 发布一份挂牌。
 *
 * <p>SELL 挂牌必须指明它所依托的库存单：发布一个出售你尚未指明的货物的
 * 要约，不是要约，而是广告。在挂牌存续期间，该库存单上的货物被冻结。
 *
 * <p>{@code confirmMode} 决定摘牌意味着什么，这使它成为这里最要害的字段。
 * {@code AUTO}（默认值）使该挂牌成为一份摘牌即成交的要约。{@code MANUAL}
 * 使其成为一份等待挂牌方答复的要约邀请，且仅对 SELL 挂牌接受。
 */
public record ListingPublishRequest(

        @NotBlank(message = "请指定挂牌方向")
        String side,

        /** SELL 挂牌必填；BUY 忽略。 */
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

        /** 价格类型为 NEGOTIABLE 时为 null。 */
        BigDecimal price,

        /** FIXED 或 NEGOTIABLE。 */
        String priceType,

        /**
         * AUTO 或 MANUAL。留空表示 AUTO。
         *
         * <p>设为可选而非必填，是为了让一个完全不知道确认环节的既有客户端
         * 保持它原有的行为，而不是悄悄多出一个它没有界面支撑的等待步骤。
         */
        String confirmMode,

        Long warehouseId,

        /** SELF_PICKUP 或 DELIVERED。 */
        String deliveryMethod,

        String paymentTerms,

        @NotNull(message = "请填写挂牌有效期")
        OffsetDateTime validUntil,

        @Size(max = 512) String remark
) {
}
