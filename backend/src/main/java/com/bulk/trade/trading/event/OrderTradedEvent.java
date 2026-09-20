package com.bulk.trade.trading.event;

import java.math.BigDecimal;

/**
 * Raised when a trade completes, so the market feed can update.
 *
 * <p>An event rather than a direct call from the order service into the market
 * module: the order service does not need to know a market feed exists, and the
 * feed can be removed or replaced without the trading code changing.
 *
 * @param categoryId  which grade traded
 * @param price       price per unit
 * @param quantity    quantity traded
 * @param buyerId     buyer enterprise
 * @param sellerId    seller enterprise
 * @param orderNo     for the audit trail
 */
public record OrderTradedEvent(
        Long categoryId,
        String commodityName,
        BigDecimal price,
        BigDecimal quantity,
        String unit,
        Long buyerId,
        Long sellerId,
        String orderNo
) {
}
