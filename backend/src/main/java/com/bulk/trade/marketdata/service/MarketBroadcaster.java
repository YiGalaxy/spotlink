package com.bulk.trade.marketdata.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通过 SSE 把行情更新推送给已连接的浏览器。
 *
 * <p><b>为什么用 SSE 而不是 WebSocket。</b>行情是单向的：服务端有消息，客户端听。SSE 就是
 * 普通 HTTP，自己会重连，不需要协议升级，不需要特殊的代理配置，而且在浏览器的网络面板里
 * 直接可读。要用 WebSocket，就得额外付出握手、心跳、重连策略和网关配置，换来的却是一条
 * 本功能根本用不上的客户端到服务端通道。
 *
 * <p>连接放在 {@link CopyOnWriteArrayList} 里，因为写入模式是大量读取、极少新增。发送失败
 * 说明客户端已经没了，所以直接丢弃这个 emitter 而不是重试——**一个死掉的浏览器不会在这条
 * 连接上回来，它会另开一条。**
 */
@Slf4j
@Component
public class MarketBroadcaster {

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final AtomicLong connectionCounter = new AtomicLong();

    public SseEmitter register() {
        // 不设超时：行情推送本就该一直开着。连接断了浏览器会自己重连。
        SseEmitter emitter = new SseEmitter(0L);

        emitter.onCompletion(() -> {
            emitters.remove(emitter);
            log.debug("SSE connection closed; {} remaining", emitters.size());
        });
        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            emitter.complete();
        });
        emitter.onError(e -> emitters.remove(emitter));

        emitters.add(emitter);
        long id = connectionCounter.incrementAndGet();
        log.debug("SSE connection opened (#{}); {} active", id, emitters.size());

        try {
            // 立刻发一个事件，是告诉客户端这条流已经通了，而不是让它一直猜到
            // 第一笔成交发生为止。
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(Map.of("message", "行情推送已连接")));
        } catch (IOException e) {
            emitters.remove(emitter);
        }
        return emitter;
    }

    /**
     * 向所有监听者发送一个事件。
     *
     * <p>发送失败意味着客户端已断开，而不是一个值得上报的错误：把它移除，循环继续，
     * **于是一个死掉的浏览器无法阻止更新送达其他浏览器。**
     */
    public void broadcast(String eventName, Object payload) {
        if (emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload));
            } catch (Exception e) {
                emitters.remove(emitter);
                log.debug("Dropped an SSE connection: {}", e.getMessage());
            }
        }
    }

    public int connectionCount() {
        return emitters.size();
    }
}
