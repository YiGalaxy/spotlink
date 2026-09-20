package com.bulk.trade.trading.service;

import com.bulk.trade.commodity.entity.CommodityCategory;
import com.bulk.trade.contract.entity.Contract;
import com.bulk.trade.contract.mapper.ContractMapper;
import com.bulk.trade.commodity.mapper.CommodityCategoryMapper;
import com.bulk.trade.identity.entity.Enterprise;
import com.bulk.trade.identity.mapper.EnterpriseMapper;
import com.bulk.trade.trading.dto.ListingView;
import com.bulk.trade.trading.dto.OrderView;
import com.bulk.trade.trading.entity.Listing;
import com.bulk.trade.trading.entity.Order;
import com.bulk.trade.trading.mapper.ListingMapper;
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
    private final ListingMapper listingMapper;
    private final ContractMapper contractMapper;
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

        // Who published each listing decides which actions the viewer may take,
        // so it is resolved in the same batch as the display names rather than
        // one query per row.
        Map<Long, Long> listerOf = listerOf(orders.stream().map(Order::getListingId));

        // "已签约" does not say who signed, so the per-viewer progress hint needs
        // the contract's own signature state. Loaded in one batch for the same
        // reason as the names above.
        Map<Long, Contract> contracts = contractsById(
                orders.stream().map(Order::getContractId));

        return orders.stream()
                .map(order -> OrderView.of(
                        order,
                        viewerEnterpriseId,
                        viewerEnterpriseId != null
                                && viewerEnterpriseId.equals(lookup(listerOf, order.getListingId(), null)),
                        OrderProgress.of(order, lookup(contracts, order.getContractId(), null), viewerEnterpriseId),
                        lookup(enterprises, order.getBuyerId(), "—"),
                        lookup(enterprises, order.getSellerId(), "—"),
                        lookup(categories, order.getCategoryId(), "—"),
                        lookup(warehouses, order.getWarehouseId(), "—")))
                .toList();
    }

    /**
     * Reads a display value for a key that is allowed to be absent.
     *
     * <p><b>This exists because the immutable empty map throws on a null
     * key.</b> The batch lookups return {@code Map.of()} when no row in the
     * batch has the relation, and {@code Map.of()} rejects a null key — both
     * from {@code get} and from {@code getOrDefault}, which is the part that
     * made this hard to see. A {@code HashMap} tolerates it. So the same call
     * behaved differently depending on whether <em>any</em> row in the batch
     * happened to carry the relation, and the failure appeared only in the case
     * where none did: an order with no warehouse, rendered alongside other
     * orders that also had none.
     *
     * <p>Every lookup in this class goes through here rather than through a map
     * method directly. The fix is not to swap the empty map for a {@code
     * HashMap} — that would work today and be quietly reverted to {@code
     * Map.of()} by the next person tidying up. Not passing a null key is a
     * property of the call site, and it survives refactoring.
     */
    private static <K, V> V lookup(Map<K, V> map, K key, V fallback) {
        return key == null ? fallback : map.getOrDefault(key, fallback);
    }

    /** Contract id to the contract, for the orders that have one. */
    private Map<Long, Contract> contractsById(Stream<Long> contractIds) {
        Set<Long> distinct = contractIds
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return contractMapper.selectBatchIds(distinct).stream()
                .collect(Collectors.toMap(Contract::getId, contract -> contract));
    }

    /** Listing id to the enterprise that published it. */
    private Map<Long, Long> listerOf(Stream<Long> listingIds) {
        Set<Long> distinct = listingIds
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return listingMapper.selectBatchIds(distinct).stream()
                .collect(Collectors.toMap(Listing::getId, Listing::getEnterpriseId));
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
