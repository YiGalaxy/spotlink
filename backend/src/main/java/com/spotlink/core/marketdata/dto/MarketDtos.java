package com.spotlink.marketdata.dto;

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
     * 曲线上的一个点。
     *
     * @param time        时间桶的起点
     * @param value       该时间桶的成交均价，或所度量的数量
     * @param tradeCount  这个点背后有多少笔成交——它必须与均价同时出现，因为一笔成交
     *                    算出的均值和五十笔算出的均值，否则在图上长得一模一样
     * @param volume      该时间桶内的成交数量
     */
    public record SeriesPoint(
            OffsetDateTime time,

            /**
             * 始终出现，即使是 null。
             *
             * <p>项目全局的 Jackson 设置会丢弃 null 字段。那通常是对的，在这里却是错的：
             * 一个没有成交的日子，是这条曲线上一个**真实存在的点**，只是它的值缺席，而它
             * 必须和一个「压根没出现在返回体里」的日子区分开。字段被悄悄省略，会让图表
             * 分不清「没有成交」和「没有数据」，也会弄坏任何按下标取值、而不是把它当作
             * 可选字段来处理的客户端。
             */
            @JsonInclude(JsonInclude.Include.ALWAYS)
            BigDecimal value,

            long tradeCount,
            BigDecimal volume
    ) {
    }

    /**
     * 一条完整的曲线。
     *
     * @param seriesKey 稳定的标识，例如 {@code TRADE_PRICE:1002}
     * @param label     展示用名称
     * @param unit      取值的单位
     * @param points    按时间从旧到新排列
     */
    public record SeriesData(
            String seriesKey,
            String label,
            String unit,
            String kind,
            List<SeriesPoint> points
    ) {
    }

    /** 一个可交易的品级，以及它最近一次被观察到的价格。 */
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

    /** 行情看板需要的一切，一次调用拿全。 */
    public record MarketOverview(
            List<QuoteRow> quotes,
            List<SeriesData> series
    ) {
    }
}
