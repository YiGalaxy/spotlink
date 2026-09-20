package com.bulk.trade.publicapi.dto;

import java.math.BigDecimal;

/**
 * What the platform says about itself to someone who has not signed in.
 *
 * <p>Every figure is an aggregate over the whole venue. There is deliberately
 * no per-member breakdown anywhere in this shape — these numbers describe the
 * market's size, and the market's size is the one thing about it that is
 * nobody's private business.
 *
 * <p>Quantities arrive with no unit because different commodities are measured
 * differently (tonnes, kilograms); summing them into one figure is already a
 * loose statement, and labelling it "吨" would make it a false one.
 *
 * @param tradedAmountText pre-formatted for display, since a nineteen-digit
 *                         amount is unreadable and rounding it in the browser
 *                         would be rounding it twice
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
     * Turns a yuan figure into something a person reads at a glance.
     *
     * <p>Done here rather than in the client so that the homepage and a future
     * report agree on what "3.2 亿元" means, instead of each rounding its own
     * way.
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
