package com.bulk.trade.publicapi.dto;

import java.math.BigDecimal;

/**
 * 平台对尚未登录的人所做的自我介绍。
 *
 * <p>每一个数字都是整个交易场所的汇总。这个结构里刻意不含任何按会员拆分的明细——
 * 这些数字描述的是市场的规模，而市场的规模恰恰是这件事里唯一不属于任何私人事务的部分。
 *
 * <p>数量字段不带单位，因为不同商品的计量方式不同（吨、千克）；把它们加总成一个数字本身
 * 就已经是一个宽松的陈述了，再给它标上「吨」，就会让它变成一句假话。
 *
 * @param tradedAmountText 预先格式化好用于展示。十九位数的金额没法读，而在浏览器里做
 *                         四舍五入等于四舍五入两次
 */
public record PublicStats(
        long enterpriseCount,
        long openListingCount,
        long tradeCount,
        BigDecimal tradedQuantity,
        BigDecimal tradedAmount,
        String tradedAmountText,
        BigDecimal inventoryQuantity
) {

    /**
     * 把一个以元为单位的数字，变成人一眼能读的东西。
     *
     * <p>放在这里而不是放在客户端，是为了让首页和将来的报表对「3.2 亿元」是什么意思
     * 达成一致，而不是各自按自己的方式四舍五入。
     */
    public static String formatAmount(BigDecimal amount) {
        if (amount == null || amount.signum() == 0) {
            return "0 元";
        }
        BigDecimal yi = new BigDecimal("100000000");
        BigDecimal wan = new BigDecimal("10000");
        if (amount.compareTo(yi) >= 0) {
            return amount.divide(yi, 2, java.math.RoundingMode.HALF_UP)
                    .stripTrailingZeros().toPlainString() + " 亿元";
        }
        if (amount.compareTo(wan) >= 0) {
            return amount.divide(wan, 2, java.math.RoundingMode.HALF_UP)
                    .stripTrailingZeros().toPlainString() + " 万元";
        }
        return amount.stripTrailingZeros().toPlainString() + " 元";
    }
}
