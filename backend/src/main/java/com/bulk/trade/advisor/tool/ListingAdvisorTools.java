package com.bulk.trade.advisor.tool;

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
 * What this enterprise has put on the market.
 *
 * <p><b>Added because the assistant was asked "我挂牌了些啥货物" and had to say
 * it could not look that up.</b> It was telling the truth — every other tool
 * reads inventory, orders or contracts, and none of those is a listing. A
 * frozen quantity on an inventory note says goods are reserved; only the
 * listing says what they are reserved <em>for</em>, at what price, and until
 * when. Answering by inference from the freeze was the alternative, and it
 * would have been a guess dressed as an answer.
 *
 * <p>Scoped like everything else: the service call takes the caller's own
 * enterprise, and there is no parameter through which another one could be
 * named.
 */
@Component
@RequiredArgsConstructor
public class ListingAdvisorTools {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ListingService listingService;

    @Tool(name = "list_my_listings",
            description = """
                    Lists the listings this enterprise has published: direction, commodity,
                    quantity and what remains, price, how the deal closes, status, and the
                    validity deadline. Use it for "我挂牌了什么", "我的挂牌", "还有多少没卖掉",
                    "挂牌价是多少". Covers listings only — for the goods behind them use
                    query_my_inventory.""")
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

    private String plain(BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString();
    }
}
