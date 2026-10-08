package com.spotlink.shared.notify;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 把「你的待办变了」推送到某一家企业的浏览器。
 *
 * <p><b>按租户分键，这就是整个设计。</b>{@code marketdata} 里的行情推送是发给所有人的，
 * 因为价格本来就是公开的。这一条不能这么做：一笔等待某家具名公司答复的摘牌，是那家公司
 * 自己的事，而一条把某企业的泄露给另一家企业的流，会一举抵消系统里其他所有地方的租户
 * 边界。分键取自调用方的令牌，绝不取自参数，所以在调用点上没有任何可以写错的地方。
 *
 * <p><b>事件不携带任何数据——只携带「有东西变了」这条消息。</b>把待办本身发过去，就意味着
 * 要在这里把它算出来，于是「什么算作待办」就有了两个判断的地方。客户端改为重新拉取，
 * 规则因此留在可被测试的 {@code TaskService} 里。
 *
 * <p>用 SSE 而不是 WebSocket，理由与行情推送相同：这是单向的，它自己会重连，也不需要
 * 协议升级。
 */
@Slf4j
@Component
public class TaskBroadcaster {

    /** 企业 id 映射到该企业已打开的连接。 */
    private final Map<Long, List<SseEmitter>> byEnterprise = new ConcurrentHashMap<>();

    /**
     * 为一家企业打开一条流。
     *
     * <p>不设超时：通知通道本就该一直开着，断了浏览器会自己重连。
     */
    public SseEmitter register(Long enterpriseId) {
        SseEmitter emitter = new SseEmitter(0L);
        List<SseEmitter> connections =
                byEnterprise.computeIfAbsent(enterpriseId, key -> new CopyOnWriteArrayList<>());

        Runnable drop = () -> {
            connections.remove(emitter);
            // 空列表也要一并移除，否则长跑的进程会为每一家曾经连过的企业
            // 留下一条记录，并且永远不释放。
            byEnterprise.computeIfPresent(enterpriseId, (key, list) -> list.isEmpty() ? null : list);
        };

        emitter.onCompletion(drop);
        emitter.onTimeout(() -> {
            drop.run();
            emitter.complete();
        });
        emitter.onError(e -> drop.run());

        connections.add(emitter);

        try {
            // 立刻发一个事件，是告诉客户端这条流已经通了，而不是让它一直
            // 猜到第一次变更发生为止。
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(Map.of("message", "待办推送已连接")));
        } catch (IOException e) {
            drop.run();
        }
        log.debug("Task stream opened for enterprise {}; {} connection(s)",
                enterpriseId, connections.size());
        return emitter;
    }

    /**
     * 告诉某一家企业：它的待办变了。
     *
     * <p>发送失败意味着浏览器已经关了，而不是一个值得上报的错误：丢掉这条连接，其余
     * 连接不受影响。**一个死掉的标签页，不能阻止这家公司其他标签页收到通知。**
     */
    public void notify(Long enterpriseId, String reason) {
        if (enterpriseId == null) {
            // 平台账号没有租户，因此也没有待办。
            return;
        }
        List<SseEmitter> connections = byEnterprise.get(enterpriseId);
        if (connections == null || connections.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : connections) {
            try {
                emitter.send(SseEmitter.event()
                        .name("tasks")
                        .data(Map.of("reason", reason)));
            } catch (Exception e) {
                connections.remove(emitter);
                log.debug("Dropped a task stream: {}", e.getMessage());
            }
        }
    }

    /** 一次通知多家企业，跳过 null。 */
    public void notifyAll(String reason, Long... enterpriseIds) {
        for (Long id : enterpriseIds) {
            notify(id, reason);
        }
    }

    /**
     * 某一家企业当前的连接数；供健康检查端点使用。
     *
     * <p>要对空租户做防护，因为平台运营方没有租户，而 {@link ConcurrentHashMap#get} 遇到
     * null 键会抛异常，而不是返回 null。同样的防护也出现在 {@link #notify} 里，它从一开始
     * 就有——这个方法没有，于是健康检查端点对那个最可能调用它的账号返回了 500。
     */
    public int connectionCount(Long enterpriseId) {
        if (enterpriseId == null) {
            return 0;
        }
        List<SseEmitter> connections = byEnterprise.get(enterpriseId);
        return connections == null ? 0 : connections.size();
    }

    /** 全部企业的连接总数；供健康检查端点使用。 */
    public int connectionCount() {
        return byEnterprise.values().stream().mapToInt(List::size).sum();
    }
}
