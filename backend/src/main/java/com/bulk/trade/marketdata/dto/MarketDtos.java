package com.bulk.trade.marketdata.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Market data shapes.
 *
 * <p><b>Note what is absent: there is no OHLC bar here.</b> On a futures feed
 * every minute produces a bar, so candles are dense and meaningful. On a spot
 * platform trades are sparse — a quiet grade may see two a day — and drawing
 * candles produces a chart that is almost entirely empty grid with a few
 * isolated sticks. What the data actually supports is an average-price line
 * with individual trades plotted as points, so the sample size behind the line
 * is visible instead of implied.
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
