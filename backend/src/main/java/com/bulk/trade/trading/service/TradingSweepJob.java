package com.bulk.trade.trading.service;

import com.bulk.trade.trading.config.TradingProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The background tidy-up for things that expire by themselves.
 *
 * <p>Two jobs, both answering the same question — what happens when nobody
 * does anything. An acceptance whose lister never replied, and an offer whose
 * validity ran out. Both have to resolve on their own, because the party who
 * would otherwise resolve them is precisely the one not acting.
 *
 * <p><b>Why a sweep and not a read-time check.</b> Deriving "expired" when
 * someone loads a page is tempting and wrong: reading a marketplace would
 * mutate rows, two readers would race to do the same work, and the state would
 * depend on traffic. A sweep makes expiry a fact about time rather than about
 * who happened to look.
 *
 * <p><b>Known limitation:</b> {@code @Scheduled} runs on every instance, so a
 * multi-instance deployment would sweep concurrently. That is survivable here —
 * both jobs re-read status and skip work already done, and the transitions are
 * guarded — but it is wasteful and would need a lock (ShedLock, or a
 * {@code pg_advisory_lock}) before it could be called correct at scale.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradingSweepJob {

    private final OrderService orderService;
    private final ListingService listingService;
    private final TradingProperties properties;

    @Scheduled(fixedDelayString = "${bulk.trading.sweep-interval-ms:300000}",
            initialDelayString = "${bulk.trading.sweep-initial-delay-ms:60000}")
    public void sweep() {
        try {
            int lapsed = orderService.expireOverdueConfirmations();
            int expired = listingService.expireOverdue();
            if (lapsed > 0 || expired > 0) {
                log.info("Sweep: {} acceptance(s) lapsed, {} listing(s) expired", lapsed, expired);
            }
        } catch (Exception e) {
            // A failed sweep must not kill the scheduler: the next tick
            // retries, and a thread that dies here would silently stop expiring
            // anything at all.
            log.error("Trading sweep failed; will retry on the next tick", e);
        }
    }
}
