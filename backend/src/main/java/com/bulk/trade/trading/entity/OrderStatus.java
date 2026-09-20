package com.bulk.trade.trading.entity;

import java.util.Map;
import java.util.Set;

/**
 * The order lifecycle, as an explicit transition table.
 *
 * <p><b>Why a table and not if-statements.</b> When transitions live in the
 * service as scattered {@code if (status == ...)} checks, the set of legal moves
 * exists only in the author's head, and every new status is a chance to introduce
 * a path nobody intended. Here the legal moves are data: one place to read, one
 * place to test, and a transition that is not listed simply cannot happen.
 *
 * <pre>
 *   PENDING_CONFIRM ──→ CONFIRMED ──→ CONTRACTED ──→ DELIVERING ──→ COMPLETED
 *         │                 │             │
 *         └─────────────────┴─────────────┴──→ CANCELLED
 * </pre>
 *
 * <p>Cancelling is allowed until delivery starts, because that is when goods
 * physically move. After that the remedy is not an order state change.
 */
public final class OrderStatus {

    public static final String PENDING_CONFIRM = "PENDING_CONFIRM";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String CONTRACTED = "CONTRACTED";
    public static final String DELIVERING = "DELIVERING";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";

    /**
     * Legal moves. Immutable and total: every status appears as a key, terminal
     * states map to an empty set, so a missing entry is a compile-time-visible
     * omission rather than a silent "anything goes".
     */
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            PENDING_CONFIRM, Set.of(CONFIRMED, CANCELLED),
            CONFIRMED, Set.of(CONTRACTED, CANCELLED),
            CONTRACTED, Set.of(DELIVERING, CANCELLED),
            DELIVERING, Set.of(COMPLETED),
            COMPLETED, Set.of(),
            CANCELLED, Set.of()
    );

    private OrderStatus() {
    }

    public static boolean canTransition(String from, String to) {
        Set<String> allowed = TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    public static boolean isTerminal(String status) {
        return COMPLETED.equals(status) || CANCELLED.equals(status);
    }

    public static Set<String> allowedFrom(String status) {
        return TRANSITIONS.getOrDefault(status, Set.of());
    }

    public static String text(String status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case PENDING_CONFIRM -> "待确认";
            case CONFIRMED -> "已确认";
            case CONTRACTED -> "已签约";
            case DELIVERING -> "交收中";
            case COMPLETED -> "已完成";
            case CANCELLED -> "已取消";
            default -> "未知";
        };
    }
}
