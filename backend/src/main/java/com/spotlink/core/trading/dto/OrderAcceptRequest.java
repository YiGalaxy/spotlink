package com.spotlink.trading.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 摘牌。
 *
 * <p>数量是唯一真正的输入：其余一切——价格、货物、仓库、交收条款——都来自
 * 被摘的那份挂牌。允许买方重述条款，就意味着他不再是在接受一份要约，而是在
 * 提出另一份要约。
 */
public record OrderAcceptRequest(

        @NotNull(message = "请填写摘牌数量")
        @DecimalMin(value = "0.001", message = "数量必须大于 0")
        BigDecimal quantity,

        @Size(max = 512, message = "备注过长")
        String remark
) {
}
