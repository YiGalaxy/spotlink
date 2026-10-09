package com.spotlink.trading.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 摘牌。
 *
 * <p>SELL 指定数量，BUY 还必须明确选择符合采购要求的自有库存，物理属性由源库存提供。
 */
public record OrderAcceptRequest(

        @NotNull(message = "请填写摘牌数量")
        @DecimalMin(value = "0.001", message = "数量必须大于 0")
        BigDecimal quantity,

        @Size(max = 512, message = "备注过长")
        String remark,

        Long inventoryNoteId
) {
    public OrderAcceptRequest(BigDecimal quantity, String remark) { this(quantity, remark, null); }
}
