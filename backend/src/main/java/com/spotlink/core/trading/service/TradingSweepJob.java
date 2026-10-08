package com.spotlink.trading.service;

import com.spotlink.trading.config.TradingProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 针对那些会自行过期的事项的后台整理工作。
 *
 * <p>两个任务，回答的是同一个问题——当没有任何人做任何事时会发生什么。
 * 一个挂牌方从未答复的摘牌，和一份有效期已过的要约。两者都必须自行了结，
 * 因为本该去了结它们的那一方，恰恰就是没有在行动的那一方。
 *
 * <p><b>为什么用扫描而不是读取时检查。</b>在有人加载页面时才推导出“已过期”
 * 很诱人，但也是错的：读一次行情就会改动数据行，两个读者会竞相做同一件事，
 * 而状态会取决于流量。扫描让过期成为一个关于时间的事实，而不是关于谁碰巧
 * 看了一眼的事实。
 *
 * <p><b>已知局限：</b>{@code @Scheduled} 会在每个实例上运行，因此多实例部署
 * 会并发扫描。在这里这是可以承受的——两个任务都会重新读取状态并跳过已经做完
 * 的工作，而且状态迁移本身有防护——但它是浪费的，要在规模上称得上正确，还需
 * 要一把锁（ShedLock，或一个 {@code pg_advisory_lock}）。
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
            // 一次失败的扫描绝不能弄死调度器：下一个节拍会重试，而在这里死掉
            // 的线程会无声地让所有过期处理彻底停摆。
            log.error("Trading sweep failed; will retry on the next tick", e);
        }
    }
}
