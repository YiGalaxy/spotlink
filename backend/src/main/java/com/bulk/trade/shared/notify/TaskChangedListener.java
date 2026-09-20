package com.bulk.trade.shared.notify;

import com.bulk.trade.trading.event.TaskChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Turns "something changed" domain events into pushes.
 *
 * <p>The only thing that knows a notification channel exists. Everything else
 * publishes an event and moves on, which is what lets the channel be replaced —
 * a websocket, an email digest, a mobile push — without any business code
 * changing.
 *
 * <p>Handled synchronously on the publishing thread, like the market feed, so
 * the push happens inside the transaction's success path. An event fired for a
 * change that then rolled back would be worse than a slightly slower commit.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskChangedListener {

    private final TaskBroadcaster broadcaster;

    @EventListener
    public void onTaskChanged(TaskChangedEvent event) {
        broadcaster.notifyAll(event.reason(), event.enterpriseIds());
        log.debug("Pushed task change '{}' to {} enterprise(s)",
                event.reason(), event.enterpriseIds().length);
    }
}
