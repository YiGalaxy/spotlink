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
 * 挂牌，覆盖橱窗的两侧。
 *
 * <p><b>两个工具，而它们之间的区别正是重点。</b>{@code list_my_listings} 读的是调用方
 * 自己的账本 —— 包括已成交、已撤牌、已过期的挂牌，这些别人看不到。
 * {@code query_market_listings} 读的是公共大厅：所有企业当前挂出来的货，匿名访客在
 * 交易市场页面上本来就能浏览。
 *
 * <p>第二个工具是后加的，因为助手被问到平台上最便宜的东西是什么时，只能回答它看不了 ——
 * 而那是一个关于<em>公开</em>信息的问题，平台本来就把这些信息对外发布。第一个工具覆盖不了它：
 * 那个工具回答的是「我挂了什么」，而不是「市场上有什么在挂」。
 *
 * <p><b>返回其他公司的名称和价格不是泄露。</b>大厅本来就是公开设计的，一个连谁在卖什么都
 * 藏起来的市场就不成其为市场。需要保密的是这些报价背后的东西 —— 库存、订单、合同、资金 ——
 * 而这里一个都够不到。这一点值得写明，因为「这个工具会返回其他企业的数据」读起来像是违规，
 * 直到你注意到它返回的是哪一类数据。
 *
 * <p>两个工具的权限范围和其他一切逻辑一致：都不接受企业作为参数，所以没有任何办法去索要
 * 某一家公司的挂牌，而不是市场的挂牌。
 */
@Component
@RequiredArgsConstructor
public class ListingAdvisorTools {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 多到足以比较报价，又少到能让回答保持可读。 */
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
                // 加这个标记，是因为调用方接下来通常就要比价，而没有它，助手会兴高采烈地
                // 建议去接受一个根本接受不了的报价 —— 那是他们自己挂的。
                sb.append("（本方）");
            }
            sb.append('\n');
        }

        // 加上这句，是因为调用方接下来通常就要判断价格高低，而一串报价不等于一串成交。
        sb.append("以上是挂牌报价，不是成交价。判断价位是否合理要用 query_market_price 看实际成交。");
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /**
     * 判断某条挂牌是否满足所请求的过滤条件。
     *
     * <p>{@code OPEN} 也把 {@code PARTIALLY_FILLED} 包含在内，因为调用方真正想表达的是
     * 「还挂着可以买」，而部分成交的挂牌符合这一点。按状态字面量比较，恰恰会把最有话可说的
     * 那些挂牌藏起来。
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
     * 面议的挂牌没有价格，这时说「0 元」比说没有价格更糟。
     */
    private String priceText(Listing listing) {
        if (listing.getPrice() == null) {
            return "面议";
        }
        return plain(listing.getPrice()) + " 元/" + listing.getUnit();
    }

    /** 成交需要哪一方同意 —— 买方最先需要知道的事实。 */
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
