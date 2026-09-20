package com.bulk.trade.trading.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Accepting a listing (摘牌).
 *
 * <p>Quantity is the only real input: everything else — price, goods,
 * warehouse, delivery terms — comes from the listing being accepted. Letting a
 * buyer restate the terms would mean they are no longer accepting an offer but
 * proposing a different one.
 */
public record OrderAcceptRequest(

        @NotNull(message = "请填写摘牌数量")
        @DecimalMin(value = "0.001", message = "数量必须大于 0")
        BigDecimal quantity,

        @Size(max = 512, message = "备注过长")
        String remark
) {
}
