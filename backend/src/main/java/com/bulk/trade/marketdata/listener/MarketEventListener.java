package com.bulk.trade.marketdata.listener;

import com.bulk.trade.marketdata.service.MarketBroadcaster;
import com.bulk.trade.trading.event.OrderTradedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns domain events into market feed updates.
 *
 * <p>Listening rather than being called: the trading code raises an event and
 * never learns who consumes it, so the market feed can be removed or replaced
 * without touching the order service.
 *
 * <p>Handled synchronously on the publishing thread. That is acceptable here
 * because a broadcast is a handful of non-blocking socket writes, and doing it
 * inline keeps the update in the same transaction's success path — an event
 * fired for a trade that then rolled back would be worse than a slightly slower
 * commit.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketEventListener {

    private final MarketBroadcaster broadcaster;

    @EventListener
    public void onOrderTraded(OrderTradedEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "TRADE");
        payload.put("categoryId", String.valueOf(event.categoryId()));
        payload.put("commodityName", event.commodityName());
        payload.put("price", event.price());
        payload.put("quantity", event.quantity());
        payload.put("unit", event.unit());
        payload.put("orderNo", event.orderNo());
        payload.put("time", OffsetDateTime.now());

        broadcaster.broadcast("trade", payload);
        log.debug("Broadcast trade for {} at {}", event.commodityName(), event.price());
    }
}
