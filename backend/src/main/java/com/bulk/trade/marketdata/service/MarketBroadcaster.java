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
 * Pushes market updates to connected browsers over SSE.
 *
 * <p><b>Why SSE and not WebSocket.</b> Market data is one-directional: the
 * server has news, the client listens. SSE is plain HTTP, reconnects by itself,
 * needs no protocol upgrade and no special proxy configuration, and is readable
 * in the browser's network panel. A WebSocket would add a handshake, a
 * heartbeat, a reconnect policy and gateway configuration to buy a
 * client-to-server channel that this feature has no use for.
 *
 * <p>Connections are held in a {@link CopyOnWriteArrayList} because the write
 * pattern is many reads and rare adds. Sends that fail mean the client is gone,
 * so the emitter is dropped rather than retried — a dead browser will not come
 * back on this connection, it will open a new one.
 */
@Slf4j
@Component
public class MarketBroadcaster {

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final AtomicLong connectionCounter = new AtomicLong();

    public SseEmitter register() {
        // No timeout: a market feed is meant to stay open. Browsers reconnect on
        // their own if the connection drops.
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
            // An immediate event tells the client the stream is live rather than
            // leaving it guessing until the first trade happens.
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(Map.of("message", "行情推送已连接")));
        } catch (IOException e) {
            emitters.remove(emitter);
        }
        return emitter;
    }

    /**
     * Sends an event to every listener.
     *
     * <p>A failing send is a disconnected client, not an error worth surfacing:
     * it is removed and the loop continues, so one dead browser cannot stop
     * updates reaching the others.
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
