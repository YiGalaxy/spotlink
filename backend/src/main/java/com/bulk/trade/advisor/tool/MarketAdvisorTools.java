package com.bulk.trade.advisor.tool;

import com.bulk.trade.marketdata.dto.MarketDtos.QuoteRow;
import com.bulk.trade.marketdata.dto.MarketDtos.SeriesData;
import com.bulk.trade.marketdata.dto.MarketDtos.SeriesPoint;
import com.bulk.trade.marketdata.service.MarketService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Market data for the advisor.
 *
 * <p>Unlike every other tool here, this one is <b>not tenant-scoped</b> — and
 * that is correct rather than an oversight. Prices are public: the listing hall
 * shows them to every authenticated enterprise, and a market where one company
 * could see prices and another could not would not be a market. Nothing here
 * touches inventory, orders or contracts, so there is no tenant data to leak.
 *
 * <p>The tool deliberately reports how many trades stand behind an average. On
 * a spot market most grades trade once or twice a day, and "the average price
 * is 66,800" means something very different when it comes from one trade than
 * from forty. An advisor that omits that context invites the user to treat a
 * single data point as a trend.
 */
@Component
@RequiredArgsConstructor
public class MarketAdvisorTools {

    private final MarketService marketService;

    @Tool(name = "query_market_price",
            description = """
                    Returns traded prices from the platform: latest price, change against the
                    previous trade, number of trades and total volume, per commodity grade.
                    Use it for questions like "电解铜什么价", "最近行情怎么样", "哪些品种在涨".
                    Prices are public platform data, not company data — anyone can see them.

                    Note the trade count. This is a spot market where a grade may trade once
                    or twice a day, so an average from one trade is a data point and not a
                    trend. Say so when the count is low.""")
    public String queryMarketPrice(
            @ToolParam(description = "Grade name to filter on, e.g. 电解铜. Leave empty for all grades.")
            String categoryName) {

        List<QuoteRow> quotes = marketService.quotes(180);
        if (quotes.isEmpty()) {
            return "平台上还没有任何成交记录。";
        }

        List<QuoteRow> filtered = quotes;
        if (categoryName != null && !categoryName.isBlank()) {
            String keyword = categoryName.trim();
            filtered = quotes.stream()
                    .filter(q -> q.categoryName().contains(keyword)
                            || keyword.contains(q.categoryName()))
                    .toList();
            if (filtered.isEmpty()) {
                return "没有找到品种「" + keyword + "」的成交记录。平台当前有成交的品种："
                        + quotes.stream().map(QuoteRow::categoryName).toList();
            }
        }

        StringBuilder sb = new StringBuilder("平台成交行情（近 180 天）：\n");
        for (QuoteRow quote : filtered) {
            sb.append("- ").append(quote.categoryName())
              .append(" | 最新成交价 ").append(plain(quote.latestPrice()))
              .append(" 元/").append(quote.unit());
            if (quote.change() != null && quote.change().signum() != 0) {
                sb.append(" | 较上一笔 ").append(quote.change().signum() > 0 ? "+" : "")
                  .append(plain(quote.change()))
                  .append("（").append(plain(quote.changePercent())).append("%）");
            } else {
                sb.append(" | 较上一笔 持平");
            }
            sb.append(" | 成交 ").append(quote.tradeCount()).append(" 笔")
              .append("，累计 ").append(plain(quote.volume())).append(quote.unit())
              .append('\n');
        }

        long totalTrades = filtered.stream().mapToLong(QuoteRow::tradeCount).sum();
        if (totalTrades <= filtered.size()) {
            sb.append("\n注意：这些品种多数只有一两笔成交，"
                    + "「最新成交价」是单次成交的价格，不足以代表趋势。");
        }
        return sb.toString();
    }

    @Tool(name = "query_price_trend",
            description = """
                    Returns the daily average traded price for one grade over a period, with
                    the number of trades behind each day. Use it when asked about a trend,
                    "最近涨了吗", or when a single latest price is not enough to answer.

                    Days with no trading are reported as such. This market is thin: gaps are
                    normal and must not be described as stability.""")
    public String queryPriceTrend(
            @ToolParam(description = "Grade name, e.g. 电解铜")
            String categoryName,
            @ToolParam(description = "Look-back window in days, between 7 and 90. Defaults to 30.")
            Integer days) {

        if (categoryName == null || categoryName.isBlank()) {
            return "请指定要查询的品种名称，例如「电解铜」。";
        }
        int window = days == null ? 30 : Math.min(Math.max(days, 7), 90);

        // Resolve the name to an id via the quote list, so the caller can pass a
        // name rather than an internal identifier.
        Long categoryId = marketService.quotes(180).stream()
                .filter(q -> q.categoryName().contains(categoryName.trim())
                        || categoryName.trim().contains(q.categoryName()))
                .map(QuoteRow::categoryId)
                .findFirst()
                .orElse(null);
        if (categoryId == null) {
            return "没有找到品种「" + categoryName.trim() + "」的成交记录。";
        }

        SeriesData series = marketService.series(MarketService.SERIES_TRADE_PRICE,
                categoryId, window);
        List<SeriesPoint> points = series.points();

        long traded = points.stream().filter(p -> p.value() != null).count();
        long empty = points.size() - traded;
        if (traded == 0) {
            return categoryName.trim() + " 在近 " + window + " 天内没有成交记录。";
        }

        StringBuilder sb = new StringBuilder(categoryName.trim())
                .append(" 近 ").append(window).append(" 天成交均价（元/")
                .append(series.unit().contains("/") ? series.unit().split("/")[1] : series.unit())
                .append("）：\n");

        BigDecimal first = null;
        BigDecimal last = null;
        for (SeriesPoint point : points) {
            if (point.value() == null) {
                continue;
            }
            if (first == null) {
                first = point.value();
            }
            last = point.value();
            sb.append("- ").append(point.time().toLocalDate())
              .append("  均价 ").append(plain(point.value()))
              .append("  ").append(point.tradeCount()).append(" 笔\n");
        }

        sb.append("\n区间内有成交 ").append(traded).append(" 天，无成交 ")
          .append(empty).append(" 天。");
        if (first != null && last != null && first.signum() != 0) {
            BigDecimal change = last.subtract(first);
            BigDecimal percent = change.multiply(BigDecimal.valueOf(100))
                    .divide(first, 2, java.math.RoundingMode.HALF_UP);
            sb.append(" 首日 ").append(plain(first)).append(" → 最新 ")
              .append(plain(last)).append("，变动 ")
              .append(change.signum() > 0 ? "+" : "").append(plain(percent)).append("%。");
        }
        return sb.toString();
    }

    private String plain(BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString();
    }
}
