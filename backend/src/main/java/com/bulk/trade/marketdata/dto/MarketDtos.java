package com.bulk.trade.marketdata.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 行情数据的结构。
 *
 * <p><b>注意这里没有什么：没有 OHLC K 线。</b>在期货行情里，每一分钟都会产生一根
 * K 线，所以蜡烛图密集且有意义。而在现货平台上成交是稀疏的——一个冷清的品种一天可能
 * 只成交两笔——画出来的蜡烛图几乎全是空白网格，只有几根孤零零的柱子。数据真正支持的
 * 是一条均价折线，加上以散点形式画出的逐笔成交，这样折线背后的样本量就看得见，而不是
 * 只能靠猜。
 */
public final class MarketDtos {

    private MarketDtos() {
    }

    /**
     * One point on a series.
     *
     * @param time        bucket start
     * @param value       average price for the bucket, or the measured quantity
     * @param tradeCount  how many trades produced this point — the honest
     *                    companion to an average, since an average of one trade
     *                    and an average of fifty look identical otherwise
     * @param volume      quantity traded in the bucket
     */
    public record SeriesPoint(
            OffsetDateTime time,

            /**
             * Always present, even when null.
             *
             * <p>The project-wide Jackson setting drops null fields. That is
             * usually right, but wrong here: a day with no trades is a real
             * point in the series whose value is absent, and it has to be
             * distinguishable from a day that is missing from the payload
             * altogether. A silently omitted field makes the chart unable to
             * tell "no trading" from "no data", and it breaks any client that
             * indexes the field rather than treating it as optional.
             */
            @JsonInclude(JsonInclude.Include.ALWAYS)
            BigDecimal value,

            long tradeCount,
            BigDecimal volume
    ) {
    }

    /**
     * A complete series.
     *
     * @param seriesKey stable identifier, e.g. {@code TRADE_PRICE:1002}
     * @param label     display name
     * @param unit      unit of the value
     * @param points    ordered oldest first
     */
    public record SeriesData(
            String seriesKey,
            String label,
            String unit,
            String kind,
            List<SeriesPoint> points
    ) {
    }

    /** A tradable grade with its latest observed price. */
    public record QuoteRow(
            Long categoryId,
            String categoryName,
            BigDecimal latestPrice,
            BigDecimal previousPrice,
            BigDecimal change,
            BigDecimal changePercent,
            long tradeCount,
            BigDecimal volume,
            String unit,
            OffsetDateTime lastTradedAt
    ) {
    }

    /** Everything the market board needs in one call. */
    public record MarketOverview(
            List<QuoteRow> quotes,
            List<SeriesData> series
    ) {
    }
}
