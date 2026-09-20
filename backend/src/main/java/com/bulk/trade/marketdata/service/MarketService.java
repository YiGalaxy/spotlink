package com.bulk.trade.marketdata.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bulk.trade.commodity.entity.CommodityCategory;
import com.bulk.trade.commodity.mapper.CommodityCategoryMapper;
import com.bulk.trade.inventory.entity.InventoryNote;
import com.bulk.trade.inventory.mapper.InventoryNoteMapper;
import com.bulk.trade.marketdata.dto.MarketDtos.QuoteRow;
import com.bulk.trade.marketdata.dto.MarketDtos.SeriesData;
import com.bulk.trade.marketdata.dto.MarketDtos.SeriesPoint;
import com.bulk.trade.trading.entity.Listing;
import com.bulk.trade.trading.entity.Order;
import com.bulk.trade.trading.mapper.ListingMapper;
import com.bulk.trade.trading.mapper.OrderMapper;
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
 * Aggregates trades, listings and inventory into series.
 *
 * <p><b>No time-series database.</b> A dedicated store earns its keep when the
 * data is tick-level — thousands of rows a second where an index scan per query
 * stops being viable. A spot platform with a handful of trades a day is not
 * that workload, and adding a second database to solve a problem the data does
 * not have would be its own kind of mistake. The queries below run against the
 * tables that already hold the facts, which also means the market board can
 * never disagree with the order book.
 *
 * <p>Aggregation happens in memory after a bounded read rather than in SQL.
 * The row counts here are small and the logic is easier to read and to test in
 * Java; the day that stops being true, the aggregation moves into the query.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketService {

    /** How far back a series may reach. Bounded so a client cannot ask for everything. */
    private static final int MAX_DAYS = 180;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final OrderMapper orderMapper;
    private final ListingMapper listingMapper;
    private final InventoryNoteMapper inventoryNoteMapper;
    private final CommodityCategoryMapper categoryMapper;

    // ------------------------------------------------------------------
    // Quotes
    // ------------------------------------------------------------------

    /**
     * Latest price per grade, with the change against the previous trade.
     *
     * <p>"Previous" means the trade before the latest one, not yesterday's
     * close: on a market this thin a daily close is a fiction, and the honest
     * comparison is against whatever traded last.
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
    // Series
    // ------------------------------------------------------------------

    /** Series keys the client may ask for. */
    public static final String SERIES_TRADE_PRICE = "TRADE_PRICE";
    public static final String SERIES_TRADE_VOLUME = "TRADE_VOLUME";
    public static final String SERIES_LISTING_VOLUME = "LISTING_VOLUME";
    public static final String SERIES_INVENTORY = "INVENTORY";

    /**
     * Builds one series.
     *
     * @param seriesType one of the {@code SERIES_*} constants
     * @param categoryId optional grade filter
     * @param days       look-back window, clamped to {@link #MAX_DAYS}
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
     * Average traded price per day.
     *
     * <p>An average is reported alongside the number of trades behind it. On a
     * thin market an average of one trade and an average of forty look the same
     * on a chart, and the difference is the entire question of how much the
     * number can be trusted.
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
                // Gaps are emitted as null-valued points so the chart shows a
                // break in trading rather than a straight line through it.
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

    /** Total quantity offered per day, across open listings. */
    private SeriesData listingSeries(Long categoryId, LocalDate from) {
        List<Listing> listings = listingMapper.selectList(Wrappers.<Listing>lambdaQuery()
                .eq(categoryId != null, Listing::getCategoryId, categoryId));
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

    /** Total goods held in stock per day, by registration date. */
    private SeriesData inventorySeries(Long categoryId, LocalDate from) {
        List<InventoryNote> notes = inventoryNoteMapper.selectList(
                Wrappers.<InventoryNote>lambdaQuery()
                        .eq(categoryId != null, InventoryNote::getCategoryId, categoryId)
                        .ne(InventoryNote::getStatus, InventoryNote.Status.CANCELLED));
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
        return orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                .ge(Order::getCreatedAt, since)
                .orderByAsc(Order::getCreatedAt)
                .last("limit 5000"));
    }

    private Map<Long, String> categoryNames(java.util.Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return categoryMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(CommodityCategory::getId, CommodityCategory::getName));
    }
}
