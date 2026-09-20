package com.bulk.trade.trading.event;

/**
 * Raised when something a party has to act on has changed.
 *
 * <p>Carries only <em>whose</em> list changed and <em>why</em> — never the task
 * itself. Recomputing the task here would put a second copy of "what counts as
 * pending" into the event, and the two copies would eventually disagree; the
 * listener refetches through {@code TaskService} instead, so there is one
 * answer to that question and it lives where it can be tested.
 *
 * <p>An event rather than a direct call, like {@link OrderTradedEvent}: the
 * order service raises it and never learns that a notification channel exists,
 * so the channel can be removed without the trading code changing.
 *
 * @param reason        a short label for logs, e.g. "摘牌待确认"
 * @param enterpriseIds every enterprise whose pending work changed — often
 *                      both parties, because a move by one changes what the
 *                      other sees even when it does not create work for them
 */
public record TaskChangedEvent(String reason, Long... enterpriseIds) {
}
