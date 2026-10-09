package com.spotlink.inventory.mapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.math.BigDecimal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spotlink.inventory.entity.InventoryNote;

public interface InventoryNoteMapper extends BaseMapper<InventoryNote> {

    default List<InventoryNote> findOwned(Long enterpriseId, Integer status) {
        return selectList(Wrappers.<InventoryNote>lambdaQuery().eq(InventoryNote::getEnterpriseId, enterpriseId).eq(status != null, InventoryNote::getStatus, status).orderByDesc(InventoryNote::getId));
    }

    default List<InventoryNote> searchOwned(Long enterpriseId, String keyword, boolean onlyFrozen, int limit) {
        var query = Wrappers.<InventoryNote>lambdaQuery().eq(InventoryNote::getEnterpriseId, enterpriseId).orderByDesc(InventoryNote::getId).last("LIMIT " + Math.min(Math.max(limit, 1), 200));
        if (keyword != null && !keyword.isBlank()) query.like(InventoryNote::getCommodityName, keyword.trim());
        if (onlyFrozen) query.gt(InventoryNote::getFrozenQuantity, BigDecimal.ZERO);
        return selectList(query);
    }

    default List<InventoryNote> findInStockOwned(Long enterpriseId) {
        return selectList(Wrappers.<InventoryNote>lambdaQuery().eq(InventoryNote::getEnterpriseId, enterpriseId).in(InventoryNote::getStatus, InventoryNote.Status.IN_STOCK, InventoryNote.Status.PARTIALLY_FROZEN, InventoryNote.Status.FULLY_FROZEN));
    }

    default InventoryNote findSellable(Long enterpriseId, Long categoryId, BigDecimal quantity) {
        return selectList(Wrappers.<InventoryNote>lambdaQuery().eq(InventoryNote::getEnterpriseId, enterpriseId).eq(InventoryNote::getCategoryId, categoryId).in(InventoryNote::getStatus, InventoryNote.Status.IN_STOCK, InventoryNote.Status.PARTIALLY_FROZEN).ge(InventoryNote::getAvailableQuantity, quantity).orderByAsc(InventoryNote::getId).last("LIMIT 1")).stream().findFirst().orElse(null);
    }

    default List<InventoryNote> findMarketInventory(Long categoryId) {
        return selectList(Wrappers.<InventoryNote>lambdaQuery().eq(categoryId != null, InventoryNote::getCategoryId, categoryId).ne(InventoryNote::getStatus, InventoryNote.Status.CANCELLED));
    }
}
