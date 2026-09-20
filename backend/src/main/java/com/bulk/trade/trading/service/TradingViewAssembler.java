package com.bulk.trade.trading.service;

import com.bulk.trade.commodity.entity.CommodityCategory;
import com.bulk.trade.commodity.mapper.CommodityCategoryMapper;
import com.bulk.trade.identity.entity.Enterprise;
import com.bulk.trade.identity.mapper.EnterpriseMapper;
import com.bulk.trade.trading.dto.ListingView;
import com.bulk.trade.trading.dto.OrderView;
import com.bulk.trade.trading.entity.Listing;
import com.bulk.trade.trading.entity.Order;
import com.bulk.trade.warehouse.entity.Warehouse;
import com.bulk.trade.warehouse.mapper.WarehouseMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Resolves ids to names for listings and orders.
 *
 * <p>Names are fetched in batches. A marketplace page showing fifty listings
 * would otherwise fire a hundred extra queries — the N+1 that stays invisible
 * until someone loads real data.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradingViewAssembler {

    private final EnterpriseMapper enterpriseMapper;
    private final CommodityCategoryMapper categoryMapper;
    private final WarehouseMapper warehouseMapper;
    private final ObjectMapper objectMapper;

    public List<ListingView> toListingViews(Collection<Listing> listings, Long viewerEnterpriseId) {
        if (listings.isEmpty()) {
            return List.of();
        }
        Map<Long, String> enterprises = lookup(
                listings.stream().map(Listing::getEnterpriseId),
                ids -> enterpriseMapper.selectBatchIds(ids).stream()
                        .collect(Collectors.toMap(Enterprise::getId, Enterprise::getName)));
        Map<Long, String> categories = categories(listings.stream().map(Listing::getCategoryId));
        Map<Long, String> warehouses = warehouses(listings.stream().map(Listing::getWarehouseId));

        return listings.stream()
                .map(listing -> ListingView.of(
                        listing,
                        enterprises.getOrDefault(listing.getEnterpriseId(), "—"),
                        categories.getOrDefault(listing.getCategoryId(), "—"),
                        warehouses.getOrDefault(listing.getWarehouseId(), "—"),
                        listing.getEnterpriseId().equals(viewerEnterpriseId)))
                .toList();
    }

    public List<OrderView> toOrderViews(Collection<Order> orders, Long viewerEnterpriseId) {
        if (orders.isEmpty()) {
            return List.of();
        }
        Set<Long> partyIds = new HashSet<>();
        orders.forEach(order -> {
            partyIds.add(order.getBuyerId());
            partyIds.add(order.getSellerId());
        });
        Map<Long, String> enterprises = lookup(partyIds.stream(),
                ids -> enterpriseMapper.selectBatchIds(ids).stream()
                        .collect(Collectors.toMap(Enterprise::getId, Enterprise::getName)));
        Map<Long, String> categories = categories(orders.stream().map(Order::getCategoryId));
        Map<Long, String> warehouses = warehouses(orders.stream().map(Order::getWarehouseId));

        return orders.stream()
                .map(order -> OrderView.of(
                        order,
                        viewerEnterpriseId,
                        enterprises.getOrDefault(order.getBuyerId(), "—"),
                        enterprises.getOrDefault(order.getSellerId(), "—"),
                        categories.getOrDefault(order.getCategoryId(), "—"),
                        warehouses.getOrDefault(order.getWarehouseId(), "—")))
                .toList();
    }

    /** Spec values stored as jsonb, as a map for the client. */
    public Map<String, Object> readSpec(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<HashMap<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("Could not parse stored spec", e);
            return Map.of();
        }
    }

    private Map<Long, String> categories(Stream<Long> ids) {
        return lookup(ids, batch -> categoryMapper.selectBatchIds(batch).stream()
                .collect(Collectors.toMap(CommodityCategory::getId, CommodityCategory::getName)));
    }

    private Map<Long, String> warehouses(Stream<Long> ids) {
        return lookup(ids, batch -> warehouseMapper.selectBatchIds(batch).stream()
                .collect(Collectors.toMap(Warehouse::getId, Warehouse::getName)));
    }

    private Map<Long, String> lookup(Stream<Long> ids, Function<Set<Long>, Map<Long, String>> loader) {
        Set<Long> distinct = ids.filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        return distinct.isEmpty() ? Map.of() : loader.apply(distinct);
    }
}
