package com.spotlink.advisor.tool;

import com.spotlink.shared.security.SecurityUtils;
import com.spotlink.trading.entity.Listing;
import com.spotlink.trading.service.ListingService;
import com.spotlink.warehouse.entity.Warehouse;
import com.spotlink.warehouse.service.access.WarehouseAccess;
import com.spotlink.trading.service.access.ListingAccess;
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

    private final ListingService listingService;
    private final WarehouseAccess warehouseAccess;
    private final ListingAccess listingAccess;
    private final ProcurementAdvisorTools procurement;

    @Tool(name = "list_my_listings",
            description = """
                    列出本企业已发布的挂牌：方向、商品、数量与剩余量、价格、成交方式、状态
                    和有效期截止时间。用于「我挂牌了什么」「我的挂牌」「还有多少没卖掉」
                    「挂牌价是多少」。只覆盖调用方自己的挂牌——想看整个市场有什么，
                    用 query_market_listings。""")
    public String listMyListings(
            @ToolParam(description = """
                    可选筛选：OPEN（仍在挂牌中）、FILLED、CLOSED（已撤销）、EXPIRED，
                    或 ALL。默认 ALL。""")
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
                    整个平台当前在挂的货——所有企业的在挂挂牌，不只是调用方的。用于
                    「现在最便宜的货是什么」「市场上有没有人卖电解铜」「挂牌价大概多少」，
                    或把某个报价和市场比一比。可按商品名称和买卖方向筛选。每行都标出挂牌方，
                    所以调用方看得出这是谁的报价、是不是自己的。""")
    public String queryMarketListings(
            @ToolParam(description = "只返回商品名称包含这段文字的挂牌。可选。")
            String keyword,
            @ToolParam(description = "SELL 表示卖出的要约，BUY 表示买入的请求。留空表示两者都要。")
            String side) {

        String wantedSide = side == null || side.isBlank() ? null : side.trim().toUpperCase();
        if (wantedSide != null
                && !Listing.Side.SELL.equals(wantedSide) && !Listing.Side.BUY.equals(wantedSide)) {
            return "挂牌方向只能是 SELL 或 BUY。";
        }

        if (keyword != null && keyword.length() > 80) return "商品名称关键字不能超过80个字符。";
        var criteria = new com.spotlink.trading.dto.PublicListingCriteria(keyword, wantedSide,
                null, null, null, null, null, java.time.OffsetDateTime.now());
        long total = listingAccess.countPublic(criteria);
        List<Listing> listings = listingAccess.searchPublic(criteria, "LATEST", ToolCallRecorder.rowLimit());
        if (listings.isEmpty()) {
            return keyword == null || keyword.isBlank()
                    ? "当前市场上没有在挂的挂牌。"
                    : "市场上没有名称包含「%s」的在挂挂牌。".formatted(keyword.trim());
        }

        return procurement.render(listings, total, "公开挂牌，按最新排序；报价不是成交价");
    }

    @Tool(name = "estimate_delivery_cost",
            description = """
                    估算一条公开挂牌到目的地的运输费用。平台当前没有维护真实运费价目表时，
                    只能说明起运仓和配送方式；只有用户明确提供每吨运价与吨数，才可按公式
                    做透明估算。返回货款与运费的服务端计算，以及两项已知费用小计；未知税费等不按0计算。
                    不得把估算说成平台报价，也不得猜测距离或运价。新运价覆盖旧运价，整批费用不能当每吨运价。
                    """)
    public String estimateDeliveryCost(
            @ToolParam(description = "挂牌编号或商品名称关键字，至少提供一个。") String listingNoOrKeyword,
            @ToolParam(required = false, description = "目的地省或市。可选；不填时只返回起运地和数据边界。") String destination,
            @ToolParam(required = false, description = "预计运输吨数。可选；必须为正数。") BigDecimal tonnes,
            @ToolParam(required = false, description = "仅用户明确提供的每吨运价，单位元/吨；不允许模型猜测或自定。可选。") BigDecimal ratePerTonne) {
        if (listingNoOrKeyword == null || listingNoOrKeyword.isBlank()) {
            return "请提供挂牌编号或商品名称，才能查询起运仓。";
        }
        if (listingNoOrKeyword.length() > 80 || (destination != null && destination.length() > 80)) return "挂牌或目的地关键字过长。";
        List<Listing> candidates = listingAccess.findPublicByNumber(listingNoOrKeyword.trim(), Listing.Side.SELL, java.time.OffsetDateTime.now());
        if (candidates.isEmpty()) candidates = listingAccess.findPublicByKeyword(listingNoOrKeyword.trim(), Listing.Side.SELL, java.time.OffsetDateTime.now(), 2);
        if (candidates.size() > 1) return "找到多个挂牌，起运仓或交付方式可能不同。请指定挂牌编号后估算，不能随意选第一条。";
        Listing listing = candidates.isEmpty() ? null : candidates.get(0);
        if (listing == null) {
            return "没有找到匹配的在挂卖方挂牌，无法估算运费。";
        }
        Warehouse warehouse = listing.getWarehouseId() == null ? null : warehouseAccess.selectById(listing.getWarehouseId());
        String origin = warehouse == null ? "—" : warehouseLocation(warehouse);
        String delivery = deliveryText(listing);
        StringBuilder sb = new StringBuilder("挂牌 ").append(listing.getListingNo())
                .append(" 起运地：").append(origin).append("；交付方式：").append(delivery).append("。\n");
        sb.append(procurement.render(List.of(listing), 1, "本次运费估算对应的有效公开挂牌，单价和余量已重新查询")).append('\n');
        if (Listing.DeliveryMethod.SELF_PICKUP.equals(listing.getDeliveryMethod())) sb.append("自提需买方安排运输，费用不等于零。\n");
        if (ratePerTonne == null || tonnes == null || ratePerTonne.signum() <= 0 || tonnes.signum() <= 0) {
            return sb.append("平台当前没有可用于该路线的真实运费价目表，因此不能给出实际运费。"
                    + "如你提供目的地、吨数和每吨运价，可按“吨数 × 每吨运价”做标注为估算的计算。").toString();
        }
        if (destination == null || destination.isBlank()) {
            return sb.append("已收到吨数和运价，但还缺目的地；请补充目的地后再计算。 ").toString();
        }
        if (!"吨".equals(listing.getUnit())) return sb.append("该挂牌不是按吨计量，不能直接将挂牌量代入每吨运价，请先提供明确的重量换算。").toString();
        if (tonnes.compareTo(new BigDecimal("1000000000")) > 0 || ratePerTonne.compareTo(new BigDecimal("1000000000")) > 0
                || tonnes.scale() > 6 || ratePerTonne.scale() > 6) return "估算参数超出范围。";
        if (listing.getRemainingQuantity() != null && tonnes.compareTo(listing.getRemainingQuantity()) > 0) return "需求吨数大于该挂牌剩余量，请减少数量或另选挂牌。";
        BigDecimal total = tonnes.multiply(ratePerTonne).setScale(4, java.math.RoundingMode.HALF_UP);
        if (!ToolCallRecorder.userProvidedFreightRate(ratePerTonne)) return sb.append("你尚未明确确认可沿用的每吨运价，不能把整批费用、旧报价或模型填写的费率用于计算。请补充例如“运价80元/吨”后再估算。").toString();
        BigDecimal goodsCost = listing.getPrice() == null ? null : tonnes.multiply(listing.getPrice()).setScale(4, java.math.RoundingMode.HALF_UP);
        if (goodsCost == null) sb.append("货款：面议，无法计算货款及两项合计。\n");
        else sb.append("货款估算：").append(plain(listing.getPrice())).append(" 元/吨 × ")
                .append(plain(tonnes)).append(" 吨 = ").append(plain(goodsCost)).append(" 元。\n");
        if (goodsCost != null) sb.append("按运费另计的假设，货款与运费两项已知费用小计：")
                .append(plain(goodsCost.add(total))).append(" 元。该小计不是完整到货成本。\n");
        sb.append("装卸、仓储、检验、税费等尚未核实，不按0计算；单价是否含税、是否已含运费需向卖方确认，避免重复计费。\n");
        return sb.append("目的地：").append(destination.trim())
                .append("；按你提供的 ").append(ratePerTonne.stripTrailingZeros().toPlainString())
                .append(" 元/吨 × ").append(tonnes.stripTrailingZeros().toPlainString())
                .append(" 吨，估算运费：").append(total.stripTrailingZeros().toPlainString())
                .append(" 元。该金额是用户参数估算，不是平台运费报价。").toString();
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

    private String warehouseLocation(Warehouse warehouse) {
        StringBuilder value = new StringBuilder(valueOrDash(warehouse.getName()));
        if (warehouse.getProvince() != null || warehouse.getCity() != null) {
            value.append("（").append(valueOrDash(warehouse.getProvince())).append(valueOrDash(warehouse.getCity())).append("）");
        }
        return value.toString();
    }

    private String deliveryText(Listing listing) {
        return Listing.DeliveryMethod.DELIVERED.equals(listing.getDeliveryMethod()) ? "送到" : "自提";
    }

    private String valueOrDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private String plain(BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString();
    }
}
