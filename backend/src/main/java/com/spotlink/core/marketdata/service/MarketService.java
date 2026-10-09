package com.spotlink.marketdata.service;

import com.spotlink.commodity.entity.CommodityCategory;
import com.spotlink.commodity.service.access.CommodityCategoryAccess;
import com.spotlink.inventory.entity.InventoryNote;
import com.spotlink.inventory.service.access.InventoryNoteAccess;
import com.spotlink.marketdata.dto.MarketDtos.QuoteRow;
import com.spotlink.marketdata.dto.MarketDtos.SeriesData;
import com.spotlink.marketdata.dto.MarketDtos.SeriesPoint;
import com.spotlink.trading.entity.Listing;
import com.spotlink.trading.entity.Order;
import com.spotlink.trading.service.access.ListingAccess;
import com.spotlink.trading.service.access.OrderAccess;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 把成交、挂牌与库存聚合成曲线。
 *
 * <p><b>没有用时序数据库。</b>专用存储的价值出现在数据是 tick 级的时候——每秒几千行，
 * 每个查询都做一次索引扫描已经撑不住。一个每天几笔成交的现货平台不是那种负载，为了解决
 * 一个数据本身并不存在的问题而引入第二个数据库，会是另一种错误。下面的查询直接跑在本来
 * 就存着事实的那几张表上，这也意味着行情看板永远不会和订单簿对不上。
 *
 * <p>聚合发生在有界读取之后的内存里，而不是在 SQL 里。这里的行数很少，而逻辑在 Java 中
 * 更容易读懂、也更容易测试；等到这句话不再成立的那天，聚合再挪进查询里。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketService {

    /** 一条曲线最多能回溯多久。设上界，是为了避免客户端把全部历史一次要走。 */
    private static final int MAX_DAYS = 180;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final OrderAccess orderAccess;
    private final ListingAccess listingAccess;
    private final InventoryNoteAccess inventoryNoteAccess;
    private final CommodityCategoryAccess categoryAccess;

    // ------------------------------------------------------------------
    // 最新行情
    // ------------------------------------------------------------------

    /**
     * 每个品种的最新价，以及与上一笔成交相比的变动。
     *
     * <p>「上一笔」指的是最新一笔之前的那一笔，而不是昨收：在这么薄的市场上，
     * 日收盘价是一种虚构，诚实的比较对象是上一笔实际成交。
     */
    public List<QuoteRow> quotes(int days) {
        List<Order> orders = recentOrders(days);
        Map<Long, List<Order>> byCategory = orders.stream()
                .filter(o -> o.getPrice() != null)
                .collect(Collectors.groupingBy(Order::getCategoryId));

        Map<Long, String> names = categoryNames(byCategory.keySet());

        List<QuoteRow> rows = new ArrayList<>();
        byCategory.forEach((categoryId, trades) -> {
            List<Order> sorted = trades.stream()
                    .sorted(Comparator.comparing(Order::getCreatedAt))
                    .toList();
            Order latest = sorted.get(sorted.size() - 1);
            BigDecimal previous = sorted.size() > 1
                    ? sorted.get(sorted.size() - 2).getPrice() : latest.getPrice();

            BigDecimal change = latest.getPrice().subtract(previous);
            BigDecimal percent = previous.signum() == 0
                    ? BigDecimal.ZERO
                    : change.multiply(BigDecimal.valueOf(100))
                            .divide(previous, 2, RoundingMode.HALF_UP);

            BigDecimal volume = sorted.stream()
                    .map(Order::getQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            rows.add(new QuoteRow(
                    categoryId,
                    names.getOrDefault(categoryId, "—"),
                    latest.getPrice(),
                    previous,
                    change,
                    percent,
                    sorted.size(),
                    volume,
                    latest.getUnit(),
                    latest.getCreatedAt()));
        });

        rows.sort(Comparator.comparing(QuoteRow::tradeCount).reversed());
        return rows;
    }

    // ------------------------------------------------------------------
    // 曲线
    // ------------------------------------------------------------------

    /** 客户端可以请求的曲线类型。 */
    public static final String SERIES_TRADE_PRICE = "TRADE_PRICE";
    public static final String SERIES_TRADE_VOLUME = "TRADE_VOLUME";
    public static final String SERIES_LISTING_VOLUME = "LISTING_VOLUME";
    public static final String SERIES_INVENTORY = "INVENTORY";

    /**
     * 构建一条曲线。
     *
     * @param seriesType {@code SERIES_*} 常量之一
     * @param categoryId 可选的品种筛选
     * @param days       回溯窗口，会被夹到 {@link #MAX_DAYS} 以内
     */
    public SeriesData series(String seriesType, Long categoryId, int days) {
        int window = Math.min(Math.max(days, 1), MAX_DAYS);
        LocalDate from = LocalDate.now(ZONE).minusDays(window - 1L);

        return switch (seriesType == null ? SERIES_TRADE_PRICE : seriesType) {
            case SERIES_TRADE_VOLUME -> tradeSeries(categoryId, from, true);
            case SERIES_LISTING_VOLUME -> listingSeries(categoryId, from);
            case SERIES_INVENTORY -> inventorySeries(categoryId, from);
            default -> tradeSeries(categoryId, from, false);
        };
    }

    /**
     * 每日成交均价。
     *
     * <p>均价旁边始终跟着它背后的成交笔数。在薄市场上，一笔成交算出的均值和四十笔算出的
     * 均值在图上长得一模一样，而这个差别恰恰是「这个数字能信几分」的全部答案。
     */
    private SeriesData tradeSeries(Long categoryId, LocalDate from, boolean volumeMode) {
        List<Order> orders = recentOrders(MAX_DAYS).stream()
                .filter(o -> o.getPrice() != null && o.getCreatedAt() != null)
                .filter(o -> categoryId == null || categoryId.equals(o.getCategoryId()))
                .filter(o -> !o.getCreatedAt().atZoneSameInstant(ZONE).toLocalDate().isBefore(from))
                .toList();

        Map<LocalDate, List<Order>> byDay = orders.stream()
                .collect(Collectors.groupingBy(
                        o -> o.getCreatedAt().atZoneSameInstant(ZONE).toLocalDate(),
                        LinkedHashMap::new,
                        Collectors.toList()));

        List<SeriesPoint> points = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(LocalDate.now(ZONE)); day = day.plusDays(1)) {
            List<Order> dayOrders = byDay.get(day);
            if (dayOrders == null || dayOrders.isEmpty()) {
                // 没有成交的日期发一个值为 null 的点，这样图上是断开的一截，
                // 而不是一条直直穿过去的线。
                points.add(new SeriesPoint(
                        day.atStartOfDay(ZONE).toOffsetDateTime(), null, 0, BigDecimal.ZERO));
                continue;
            }
            BigDecimal volume = dayOrders.stream()
                    .map(Order::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal value = volumeMode
                    ? volume
                    : dayOrders.stream()
                            .map(Order::getPrice)
                            .reduce(BigDecimal.ZERO, BigDecimal::add)
                            .divide(BigDecimal.valueOf(dayOrders.size()), 2, RoundingMode.HALF_UP);

            points.add(new SeriesPoint(
                    day.atStartOfDay(ZONE).toOffsetDateTime(),
                    value, dayOrders.size(), volume));
        }

        String label = categoryId == null
                ? (volumeMode ? "全平台成交量" : "全平台成交均价")
                : categoryNames(java.util.Set.of(categoryId)).getOrDefault(categoryId, "—")
                        + (volumeMode ? " 成交量" : " 成交均价");

        return new SeriesData(
                (volumeMode ? SERIES_TRADE_VOLUME : SERIES_TRADE_PRICE)
                        + (categoryId == null ? ":ALL" : ":" + categoryId),
                label,
                volumeMode ? "吨" : "元/吨",
                volumeMode ? "bar" : "line",
                points);
    }

    /** 每日在挂挂牌的报盘总量。 */
    private SeriesData listingSeries(Long categoryId, LocalDate from) {
        List<Listing> listings = listingAccess.findByCategory(categoryId);
        Map<LocalDate, BigDecimal> byDay = new LinkedHashMap<>();
        for (Listing listing : listings) {
            if (listing.getCreatedAt() == null) {
                continue;
            }
            LocalDate day = listing.getCreatedAt().atZoneSameInstant(ZONE).toLocalDate();
            byDay.merge(day, listing.getQuantity(), BigDecimal::add);
        }
        return buildDatedSeries(SERIES_LISTING_VOLUME, "挂牌量", "吨", "bar", from, byDay);
    }

    /** 每日在库总量，按入库日期归集。 */
    private SeriesData inventorySeries(Long categoryId, LocalDate from) {
        List<InventoryNote> notes = inventoryNoteAccess.findMarketInventory(categoryId);
        Map<LocalDate, BigDecimal> byDay = new LinkedHashMap<>();
        for (InventoryNote note : notes) {
            if (note.getCreatedAt() == null) {
                continue;
            }
            LocalDate day = note.getCreatedAt().atZoneSameInstant(ZONE).toLocalDate();
            byDay.merge(day, note.getTotalQuantity(), BigDecimal::add);
        }
        return buildDatedSeries(SERIES_INVENTORY, "在库量", "吨", "line", from, byDay);
    }

    private SeriesData buildDatedSeries(String type, String label, String unit, String kind,
                                        LocalDate from, Map<LocalDate, BigDecimal> byDay) {
        List<SeriesPoint> points = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(LocalDate.now(ZONE)); day = day.plusDays(1)) {
            BigDecimal value = byDay.get(day);
            points.add(new SeriesPoint(
                    day.atStartOfDay(ZONE).toOffsetDateTime(),
                    value == null ? null : value,
                    value == null ? 0 : 1,
                    value == null ? BigDecimal.ZERO : value));
        }
        return new SeriesData(type + ":ALL", label, unit, kind, points);
    }

    // ------------------------------------------------------------------

    private List<Order> recentOrders(int days) {
        OffsetDateTime since = LocalDate.now(ZONE).minusDays(days)
                .atStartOfDay(ZONE).toOffsetDateTime();
        return orderAccess.findCreatedSince(since);
    }

    private Map<Long, String> categoryNames(java.util.Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return categoryAccess.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(CommodityCategory::getId, CommodityCategory::getName));
    }
}
