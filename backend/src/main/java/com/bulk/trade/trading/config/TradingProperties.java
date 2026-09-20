package com.bulk.trade.trading.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Trading-module settings.
 *
 * @param sweepIntervalMs how often the background sweep runs. It answers
 *                        acceptances whose lister never replied and expires
 *                        listings past their validity. Both are the kind of
 *                        work nobody triggers by hand, so the interval is what
 *                        bounds how long a stalled offer can sit there.
 * @param confirmWindow   how long a lister has to answer an acceptance on a
 *                        MANUAL listing. Configurable because the useful value
 *                        depends entirely on the commodity: a truckload of coal
 *                        wants hours, a cargo of ore can want days. It is also
 *                        capped by the listing's own validity — see
 *                        {@code OrderService.answerDeadlineFor}.
 */
@ConfigurationProperties(prefix = "bulk.trading")
public record TradingProperties(
        long sweepIntervalMs,
        Duration confirmWindow
) {

    public long effectiveSweepIntervalMs() {
        // A zero or negative interval fires the sweep continuously, which turns
        // a background tidy-up into a busy loop against the database.
        return sweepIntervalMs <= 0 ? 300_000L : sweepIntervalMs;
    }

    public Duration effectiveConfirmWindow() {
        return confirmWindow == null || confirmWindow.isZero() || confirmWindow.isNegative()
                ? Duration.ofHours(24)
                : confirmWindow;
    }
}
