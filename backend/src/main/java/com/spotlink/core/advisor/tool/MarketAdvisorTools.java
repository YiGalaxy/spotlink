package com.spotlink.advisor.tool;

import com.spotlink.marketdata.dto.MarketDtos.QuoteRow;
import com.spotlink.marketdata.dto.MarketDtos.SeriesData;
import com.spotlink.marketdata.dto.MarketDtos.SeriesPoint;
import com.spotlink.marketdata.service.MarketService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 供顾问使用的行情数据。
 *
 * <p>与这里的其他每个工具都不同，这一个<b>不做租户隔离</b> —— 而这是正确的，不是疏忽。
 * 价格是公开的：挂牌大厅把它们展示给每一个已认证的企业，而一个某家公司看得见价格、另一家
 * 看不见的市场就不成其为市场。这里不碰库存、订单或合同，所以没有租户数据可泄露。
 *
 * <p>这个工具刻意报告一个均价背后站着多少笔成交。在现货市场上，多数品种一天只成交一两次，
 * 而「均价 66800」来自一笔成交和来自四十笔成交，含义完全不同。省略掉这个上下文的顾问，
 * 等于在请用户把一个单独的数据点当成趋势。
 */
@Component
@RequiredArgsConstructor
public class MarketAdvisorTools {

    private final MarketService marketService;

    @Tool(name = "query_market_price",
            description = """
                    返回平台上的成交价格：按商品品级给出最新价、相对上一笔成交的涨跌、
                    成交笔数和总成交量。用于「电解铜什么价」「最近行情怎么样」「哪些品种在涨」
                    这类问题。价格是平台公开数据，不是企业数据——任何人都能看到。

                    注意成交笔数。这是现货市场，一个品级一天可能只成交一两笔，所以一笔
                    成交算出的均值是一个数据点，不是趋势。笔数低的时候要说明这一点。""")
    public String queryMarketPrice(
            @ToolParam(description = "要筛选的品级名称，例如 电解铜。留空表示所有品级。")
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
                    返回某个品级在一段时间内的每日成交均价，以及每一天背后的成交笔数。
                    被问及趋势、「最近涨了吗」，或仅凭一个最新价不足以回答时用它。

                    没有成交的日子会照实说明。这个市场很薄：有空档是正常的，
                    不能把它描述成稳定。""")
    public String queryPriceTrend(
            @ToolParam(description = "品级名称，例如 电解铜")
            String categoryName,
            @ToolParam(description = "回溯天数，7 到 90 之间。默认 30。")
            Integer days) {

        if (categoryName == null || categoryName.isBlank()) {
            return "请指定要查询的品种名称，例如「电解铜」。";
        }
        int window = days == null ? 30 : Math.min(Math.max(days, 7), 90);

        // 借报价列表把名称解析成 id，这样调用方可以传名称，
        // 而不必传一个内部标识符。
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
