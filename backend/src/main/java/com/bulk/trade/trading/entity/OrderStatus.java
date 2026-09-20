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
 * <p><b>{@code PENDING_CONFIRM} is entered only by a MANUAL listing</b> — one
 * whose terms say an acceptance must be answered by the lister. A listing that
 * is itself the offer ({@code AUTO}) goes straight to {@code CONFIRMED} at
 * acceptance, because there the contract is already formed and a "waiting for
 * confirmation" state would describe a question nobody is asking.
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

    /**
     * Legal moves for a particular caller.
     *
     * <p><b>Three states are role-sensitive, and for one reason: a step that
     * either party could take is a step nobody owns.</b>
     *
     * <ul>
     *   <li>{@code PENDING_CONFIRM} — only the lister answers. Letting the
     *       counterparty press confirm would let the party who proposed the
     *       deal accept it on the other's behalf, which is the same as having
     *       no confirmation step at all.</li>
     *   <li>{@code CONTRACTED} — only the seller releases the goods. Delivery
     *       starts when the goods move, and only their owner can move them.
     *       The buyer pressing this would be the buyer announcing that someone
     *       else has shipped.</li>
     *   <li>{@code DELIVERING} — only the buyer receives. Completion means the
     *       goods arrived and were accepted, which is a statement about what
     *       the receiver got. The seller confirming their own delivery is a
     *       party marking their own homework.</li>
     * </ul>
     *
     * <p>In every case the counterparty may still walk away while the order is
     * cancellable — declining your own deal is not something anyone needs
     * protecting from.
     *
     * @param callerIsLister published the listing this order accepted
     * @param callerIsSeller is the party releasing the goods
     */
    public static Set<String> allowedFrom(String status, boolean callerIsLister, boolean callerIsSeller) {
        if (PENDING_CONFIRM.equals(status) && !callerIsLister) {
            return Set.of(CANCELLED);
        }
        if (CONTRACTED.equals(status) && !callerIsSeller) {
            return Set.of(CANCELLED);
        }
        if (DELIVERING.equals(status) && callerIsSeller) {
            // Nothing to offer the seller here: the goods are out and the next
            // move is the receiver's. An empty set is the honest answer, and
            // the screen shows the wait rather than a button that would fail.
            return Set.of();
        }
        return allowedFrom(status);
    }

    public static String text(String status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            // Named for who owes the answer, not merely that one is owed: the
            // reader's next move differs depending on which side they are.
            case PENDING_CONFIRM -> "待挂牌方确认";
            case CONFIRMED -> "已确认";
            case CONTRACTED -> "已签约";
            case DELIVERING -> "交收中";
            case COMPLETED -> "已完成";
            case CANCELLED -> "已取消";
            default -> "未知";
        };
    }
}
