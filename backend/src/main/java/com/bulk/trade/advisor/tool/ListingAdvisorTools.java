package com.bulk.trade.advisor.tool;

import com.bulk.trade.identity.entity.Enterprise;
import com.bulk.trade.identity.mapper.EnterpriseMapper;
import com.bulk.trade.shared.security.SecurityUtils;
import com.bulk.trade.trading.entity.Listing;
import com.bulk.trade.trading.service.ListingService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Listings, from both sides of the shop window.
 *
 * <p><b>Two tools, and the difference between them is the point.</b>
 * {@code list_my_listings} reads the caller's own book — including listings that
 * are filled, withdrawn or expired, which nobody else can see.
 * {@code query_market_listings} reads the public hall: what every enterprise
 * currently has on offer, which an anonymous visitor can already browse on the
 * marketplace page.
 *
 * <p>The second was added because the assistant was asked what the cheapest
 * thing on the platform was and had to say it could not look — a question about
 * <em>public</em> information that the platform publishes to the street. The
 * first tool does not cover it: that answers "what have I listed", not "what is
 * on offer".
 *
 * <p><b>Returning other companies' names and prices is not a leak.</b> The hall
 * is public by design, and a venue that hid who was selling what would not be a
 * venue. What stays private is everything behind those offers — inventory,
 * orders, contracts, funds — and none of it is reachable from here. Worth
 * stating, because "the tool returns other enterprises' data" reads like a
 * violation until you notice which data.
 *
 * <p>Both tools are scoped the way everything else is: neither accepts an
 * enterprise as an argument, so there is no way to ask for one company's
 * listings rather than the market's.
 */
@Component
@RequiredArgsConstructor
public class ListingAdvisorTools {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** Enough to compare offers, few enough that the answer stays readable. */
    private static final int MARKET_ROWS = 15;

    private final ListingService listingService;
    private final EnterpriseMapper enterpriseMapper;

    @Tool(name = "list_my_listings",
            description = """
                    Lists the listings this enterprise has published: direction, commodity,
                    quantity and what remains, price, how the deal closes, status, and the
                    validity deadline. Use it for "我挂牌了什么", "我的挂牌", "还有多少没卖掉",
                    "挂牌价是多少". Covers the caller's own listings only — for what the whole
                    market has on offer use query_market_listings.""")
    public String listMyListings(
            @ToolParam(description = """
                    Optional filter: OPEN (anything still on offer), FILLED, CLOSED
                    (withdrawn), EXPIRED, or ALL. Defaults to ALL.""")
            String status) {

        Long enterpriseId = SecurityUtils.currentEnterpriseIdOrNull();
        if (enterpriseId == null) {
            return "该账号是平台运营账号，未绑定企业，没有自己的挂牌。";
        }

        List<Listing> listings = listingService.listMine(enterpriseId);
        if (listings.isEmpty()) {
            return "当前企业还没有发布过挂牌。";
        }

        String wanted = status == null ? "ALL" : status.trim().toUpperCase();
        List<Listing> shown = "ALL".equals(wanted) || wanted.isBlank()
                ? listings
                : listings.stream().filter(listing -> matches(listing, wanted)).toList();

        if (shown.isEmpty()) {
            return "当前企业没有「%s」状态的挂牌。".formatted(statusText(wanted));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("我的挂牌共 ").append(shown.size()).append(" 条");
        if (shown.size() < listings.size()) {
            sb.append("（全部 ").append(listings.size()).append(" 条）");
        }
        sb.append("：\n");

        for (Listing listing : shown) {
            sb.append("- ").append(listing.getListingNo())
              .append(" | ").append(sideText(listing))
              .append(" ").append(listing.getCommodityName())
              .append(' ').append(plain(listing.getRemainingQuantity())).append('/')
              .append(plain(listing.getQuantity())).append(' ').append(listing.getUnit())
              .append(" | ").append(priceText(listing))
              .append(" | ").append(confirmModeText(listing))
              .append(" | ").append(statusText(listing.getStatus()));
            if (listing.getValidUntil() != null) {
                sb.append(" | 有效期至 ").append(listing.getValidUntil().format(DATE));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    @Tool(name = "query_market_listings",
            description = """
                    What the whole platform currently has on offer — every enterprise's open
                    listings, not just the caller's. Use it for "现在最便宜的货是什么", "市场上
                    有没有人卖电解铜", "挂牌价大概多少", or to compare an offer against the
                    market. Filterable by commodity name and by side. Each row names its
                    seller, so the caller can see whose offer it is and whether it is theirs.""")
    public String queryMarketListings(
            @ToolParam(description = "Only listings whose commodity name contains this text. Optional.")
            String keyword,
            @ToolParam(description = "SELL for offers to sell, BUY for requests to buy. Leave empty for both.")
            String side) {

        Long enterpriseId = SecurityUtils.currentEnterpriseIdOrNull();
        String wantedSide = side == null || side.isBlank() ? null : side.trim().toUpperCase();
        if (wantedSide != null
                && !Listing.Side.SELL.equals(wantedSide) && !Listing.Side.BUY.equals(wantedSide)) {
            return "挂牌方向只能是 SELL 或 BUY。";
        }

        List<Listing> listings = listingService.browse(null, wantedSide, keyword);
        if (listings.isEmpty()) {
            return keyword == null || keyword.isBlank()
                    ? "当前市场上没有在挂的挂牌。"
                    : "市场上没有名称包含「%s」的在挂挂牌。".formatted(keyword.trim());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("市场在挂挂牌共 ").append(listings.size()).append(" 条");
        if (listings.size() > MARKET_ROWS) {
            sb.append("，按发布时间由新到旧列出前 ").append(MARKET_ROWS).append(" 条");
        }
        sb.append("：\n");

        for (Listing listing : listings.stream().limit(MARKET_ROWS).toList()) {
            sb.append("- ").append(listing.getCommodityName())
              .append(" | ").append(sideText(listing))
              .append(' ').append(plain(listing.getRemainingQuantity())).append('/')
              .append(plain(listing.getQuantity())).append(' ').append(listing.getUnit())
              .append(" | ").append(priceText(listing))
              .append(" | ").append(confirmModeText(listing))
              .append(" | 挂牌方 ").append(enterpriseName(listing.getEnterpriseId()));
            if (enterpriseId != null && enterpriseId.equals(listing.getEnterpriseId())) {
                // Marked because the caller is usually about to compare prices,
                // and without it the assistant will cheerfully recommend
                // accepting an offer that cannot be accepted — it is their own.
                sb.append("（本方）");
            }
            sb.append('\n');
        }

        // Said because the caller is usually about to judge a price, and a list
        // of asking prices is not a list of trades.
        sb.append("以上是挂牌报价，不是成交价。判断价位是否合理要用 query_market_price 看实际成交。");
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Whether a listing answers to the requested filter.
     *
     * <p>{@code OPEN} covers {@code PARTIALLY_FILLED} as well, because "still on
     * offer" is what the caller means and a partly-sold listing qualifies. A
     * literal status comparison would hide exactly the listings with the most
     * to say.
     */
    private boolean matches(Listing listing, String status) {
        if (Listing.Status.OPEN.equals(status)) {
            return listing.isOpenForTrade();
        }
        return status.equals(listing.getStatus());
    }

    private String sideText(Listing listing) {
        return Listing.Side.SELL.equals(listing.getSide()) ? "卖方挂牌" : "买方挂牌";
    }

    /**
     * A negotiable listing has no price, and saying "0 元" would be a worse
     * answer than saying there is none.
     */
    private String priceText(Listing listing) {
        if (listing.getPrice() == null) {
            return "面议";
        }
        return plain(listing.getPrice()) + " 元/" + listing.getUnit();
    }

    /** Whose agreement closes the deal — the fact a buyer most needs first. */
    private String confirmModeText(Listing listing) {
        return listing.awaitsListerConfirm() ? "需挂牌方确认" : "摘牌即成交";
    }

    private String statusText(String status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case Listing.Status.OPEN -> "挂牌中";
            case Listing.Status.PARTIALLY_FILLED -> "部分成交";
            case Listing.Status.FILLED -> "已成交";
            case Listing.Status.CLOSED -> "已撤牌";
            case Listing.Status.EXPIRED -> "已过期";
            default -> status;
        };
    }

    private String enterpriseName(Long id) {
        Enterprise enterprise = id == null ? null : enterpriseMapper.selectById(id);
        return enterprise == null ? "—" : enterprise.getName();
    }

    private String plain(BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString();
    }
}
