package com.bulk.trade.shared.notify;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Pushes "your pending work changed" to the browsers of one enterprise.
 *
 * <p><b>Keyed by tenant, and that is the whole design.</b> The market feed in
 * {@code marketdata} broadcasts to everyone because prices are public. This one
 * cannot: an acceptance waiting for a named company's answer is that company's
 * business, and a stream that leaked one enterprise's events to another would
 * undo the tenant boundary everywhere else in the system at a stroke. The key
 * comes from the caller's token, never from a parameter, so there is nothing to
 * get wrong at the call site.
 *
 * <p><b>The event carries no data — only the news that something changed.</b>
 * Sending the task itself would mean computing it here, and then there would be
 * two places that decide what counts as pending. The client refetches instead,
 * so the rules stay in {@code TaskService} where they can be tested.
 *
 * <p>SSE rather than WebSocket, for the same reason as the market feed: this is
 * one-directional, it reconnects on its own, and it needs no protocol upgrade.
 */
@Slf4j
@Component
public class TaskBroadcaster {

    /** Enterprise id to that enterprise's open connections. */
    private final Map<Long, List<SseEmitter>> byEnterprise = new ConcurrentHashMap<>();

    /**
     * Opens a stream for one enterprise.
     *
     * <p>No timeout: a notification channel is meant to stay open, and the
     * browser reconnects on its own if it drops.
     */
    public SseEmitter register(Long enterpriseId) {
        SseEmitter emitter = new SseEmitter(0L);
        List<SseEmitter> connections =
                byEnterprise.computeIfAbsent(enterpriseId, key -> new CopyOnWriteArrayList<>());

        Runnable drop = () -> {
            connections.remove(emitter);
            // Remove the empty list too, or a long-running process accumulates
            // one entry per enterprise that ever connected and never frees it.
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
            // An immediate event tells the client the stream is live, rather
            // than leaving it guessing until the first change.
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
     * Tells one enterprise that its pending work changed.
     *
     * <p>A failing send is a closed browser, not an error worth surfacing: the
     * connection is dropped and the others are unaffected. One dead tab must
     * not stop the company's other tabs from being told.
     */
    public void notify(Long enterpriseId, String reason) {
        if (enterpriseId == null) {
            // Platform accounts have no tenant and therefore no tasks.
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

    /** Notifies several enterprises at once, skipping nulls. */
    public void notifyAll(String reason, Long... enterpriseIds) {
        for (Long id : enterpriseIds) {
            notify(id, reason);
        }
    }

    /**
     * Open connections for one enterprise; for the health endpoint.
     *
     * <p>Guards against a null tenant because a platform operator has none, and
     * {@link ConcurrentHashMap#get} throws on a null key rather than returning
     * null. The same guard appears in {@link #notify}, which had it from the
     * start — this method did not, and the health endpoint answered 500 to the
     * one account most likely to call it.
     */
    public int connectionCount(Long enterpriseId) {
        if (enterpriseId == null) {
            return 0;
        }
        List<SseEmitter> connections = byEnterprise.get(enterpriseId);
        return connections == null ? 0 : connections.size();
    }

    /** Total open connections across every enterprise; for the health endpoint. */
    public int connectionCount() {
        return byEnterprise.values().stream().mapToInt(List::size).sum();
    }
}
