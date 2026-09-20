package com.bulk.trade.shared.notify;

import com.bulk.trade.trading.event.TaskChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 把「有东西变了」这类领域事件变成推送。
 *
 * <p>它是唯一知道存在一条通知渠道的东西。其余一切只负责发出事件然后继续往前走，
 * **正是这一点让这条渠道可以被替换掉**——换成 WebSocket、邮件摘要、移动推送——
 * 而业务代码一行都不用改。
 *
 * <p>与行情推送一样，在发布线程上同步处理，所以推送发生在事务的成功路径之内。
 * **为一个随后被回滚的改动发出事件，比提交稍微慢一点更糟。**
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
