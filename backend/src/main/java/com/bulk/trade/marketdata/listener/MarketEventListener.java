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
 * 把领域事件变成行情推送。
 *
 * <p>用监听而不是被直接调用：交易代码发出一个事件，并且从不知道谁在消费它，
 * 于是行情推送可以被移除或替换，而订单服务一行都不用改。
 *
 * <p>在发布线程上同步处理。这里可以接受，因为一次广播不过是几次非阻塞的 socket
 * 写入，而且就地处理能让这次更新留在同一个事务的成功路径里——**为一笔随后被回滚的
 * 成交发出推送，比提交稍微慢一点更糟。**
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
