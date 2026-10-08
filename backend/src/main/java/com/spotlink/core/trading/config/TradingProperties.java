package com.spotlink.trading.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 交易模块的配置项。
 *
 * @param sweepIntervalMs 后台扫描任务的运行间隔。它负责处理挂牌方始终未答复
 *                        的摘牌请求，以及让超过有效期的挂牌过期。这两类工作
 *                        都属于没人会手动触发的活，因此这个间隔决定了停滞的
 *                        要约最多能滞留多久。
 * @param confirmWindow   MANUAL 挂牌下挂牌方答复一次摘牌的时限。之所以可配
 *                        置，是因为合适的取值完全取决于商品：一车皮煤要的是
 *                        几小时，一船矿石可能要几天。它同时还受挂牌自身有效
 *                        期的上限约束——参见
 *                        {@code OrderService.answerDeadlineFor}。
 */
@ConfigurationProperties(prefix = "bulk.trading")
public record TradingProperties(
        long sweepIntervalMs,
        Duration confirmWindow
) {

    public long effectiveSweepIntervalMs() {
        // 间隔为零或负数会让扫描连续触发，把一次后台整理变成对数据库的空转
        // 忙循环。
        return sweepIntervalMs <= 0 ? 300_000L : sweepIntervalMs;
    }

    public Duration effectiveConfirmWindow() {
        return confirmWindow == null || confirmWindow.isZero() || confirmWindow.isNegative()
                ? Duration.ofHours(24)
                : confirmWindow;
    }
}
