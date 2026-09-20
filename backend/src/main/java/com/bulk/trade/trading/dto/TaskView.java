package com.bulk.trade.trading.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One thing the caller has to act on.
 *
 * <p><b>Why this is a record and not a sentence.</b> The same pending work is
 * asked for by two very different clients: the AI advisor, which wants prose,
 * and the web console, which wants rows to render buttons from. The first
 * version of this logic returned a formatted string from inside the advisor's
 * tool, which meant the console could not use it at all — and a second copy
 * written for the console would have been a second set of rules about who owes
 * the next move. Those rules are the part that must not disagree, so they live
 * in one service and each client formats the result its own way.
 *
 * <p>{@code kind} is what the client switches on to decide which buttons to
 * show; {@code action} is the already-worded instruction for display. Keeping
 * both means a button's label and a button's behaviour cannot drift apart.
 *
 * @param kind           see {@link Kind}
 * @param action         what the caller should do, in Chinese, ready to display
 * @param targetType     {@link TargetType#ORDER} or {@link TargetType#CONTRACT}
 * @param targetId       the order or contract id, for the action call
 * @param targetNo       its human-readable number
 * @param commodityName  what the work is about
 * @param counterparty   the other enterprise, so a list of tasks is scannable
 * @param detail         one sentence of context — a deadline, a missing step
 * @param deadline       when this lapses, or null if it does not
 */
public record TaskView(
        String kind,
        String action,
        String targetType,
        @JsonSerialize(using = ToStringSerializer.class) Long targetId,
        String targetNo,
        String commodityName,
        String counterparty,
        BigDecimal quantity,
        String unit,
        BigDecimal amount,
        String detail,
        OffsetDateTime deadline
) {

    /**
     * The kinds of pending work, in the order they should be listed.
     *
     * <p>Ordered by how much the caller's silence costs. A waiting acceptance
     * expires and takes the deal with it; an unsigned contract blocks the
     * order; drafting is the caller's own housekeeping and can wait. A list
     * sorted by creation time would put them in whatever order the platform
     * happened to generate them, which is the one order that means nothing.
     */
    public static final class Kind {
        /** Someone accepted the caller's listing; only they can answer. */
        public static final String ACCEPTANCE_PENDING = "ACCEPTANCE_PENDING";
        /** A contract awaits the caller's signature. */
        public static final String CONTRACT_TO_SIGN = "CONTRACT_TO_SIGN";
        /** The order is confirmed but no contract has been drafted. */
        public static final String CONTRACT_TO_DRAFT = "CONTRACT_TO_DRAFT";
        /** Signed and ready; delivery has not started. */
        public static final String DELIVERY_TO_START = "DELIVERY_TO_START";
        /** Goods are moving; the caller should confirm when they arrive. */
        public static final String DELIVERY_TO_COMPLETE = "DELIVERY_TO_COMPLETE";

        private Kind() {
        }
    }

    /** What the caller has to call to act on this. */
    public static final class TargetType {
        public static final String ORDER = "ORDER";
        public static final String CONTRACT = "CONTRACT";

        private TargetType() {
        }
    }
}
