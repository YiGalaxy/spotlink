package com.spotlink.trading.service;

import com.spotlink.shared.exception.BusinessException;
import com.spotlink.shared.web.ResultCode;
import java.math.BigDecimal;

/** 与 DECIMAL(19,4) 一致；先校验再写入，避免数据库默默舍入报价。 */
public final class TradingNumbers {
    private TradingNumbers() { }

    public static void price(BigDecimal value) {
        if (value == null || value.signum() <= 0 || value.stripTrailingZeros().scale() > 4
                || value.compareTo(new BigDecimal("999999999999999.9999")) > 0) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "单价须大于 0，最多 15 位整数和 4 位小数");
        }
    }
}
