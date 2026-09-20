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
 * 为挂牌和订单把 id 解析成名称。
 *
 * <p>名称是批量取回的。否则一个展示五十条挂牌的行情页会额外发出上百次查询
 * ——这种 N+1 问题在有人加载真实数据之前一直隐形。
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

        // 每份挂牌是谁发布的，决定了查看者可以执行哪些动作，所以它与展示名称
        // 在同一批次里解析，而不是逐行一次查询。
        Map<Long, Long> listerOf = listerOf(orders.stream().map(Order::getListingId));

        // “已签约”没有说明是谁签的，所以逐查看者的进度提示需要合同自身的签署
        // 状态。与上面的名称出于同样的原因，一次性批量加载。
        Map<Long, Contract> contracts = contractsById(
                orders.stream().map(Order::getContractId));

        return orders.stream()
                .map(order -> OrderView.of(
                        order,
                        viewerEnterpriseId,
                        viewerEnterpriseId != null
                                && viewerEnterpriseId.equals(lookup(listerOf, order.getListingId(), null)),
                        viewerEnterpriseId != null
                                && viewerEnterpriseId.equals(order.getSellerId()),
                        OrderProgress.of(order, lookup(contracts, order.getContractId(), null), viewerEnterpriseId),
                        lookup(enterprises, order.getBuyerId(), "—"),
                        lookup(enterprises, order.getSellerId(), "—"),
                        lookup(categories, order.getCategoryId(), "—"),
                        lookup(warehouses, order.getWarehouseId(), "—")))
                .toList();
    }

    /**
     * 为一个允许缺失的键读取展示值。
     *
     * <p><b>它之所以存在，是因为不可变的空 map 在 null 键上会抛异常。</b>当
     * 批次中没有任何一行具备该关联时，批量查询会返回 {@code Map.of()}，而
     * {@code Map.of()} 拒绝 null 键——无论是 {@code get} 还是
     * {@code getOrDefault} 都拒绝，后者正是让这个问题难以被看见的部分。而
     * {@code HashMap} 容忍它。于是同一次调用会因为批次中<em>是否有任何</em>
     * 一行碰巧带有该关联而表现不同，而故障只出现在一行都没有的那种情况里：
     * 一笔没有仓库的订单，与其它同样没有仓库的订单一起渲染时。
     *
     * <p>本类中的每一次查找都走这里，而不直接走 map 的方法。修法不是把空 map
     * 换成 {@code HashMap}——那样今天能跑，然后会被下一个整理代码的人悄悄改回
     * {@code Map.of()}。不传 null 键是调用点自身的性质，而它能经受重构。
     */
    private static <K, V> V lookup(Map<K, V> map, K key, V fallback) {
        return key == null ? fallback : map.getOrDefault(key, fallback);
    }

    /** 合同 id 到合同，针对那些有合同的订单。 */
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

    /** 挂牌 id 到发布它的企业。 */
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

    /** 以 jsonb 存储的规格值，转成供客户端使用的 map。 */
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
