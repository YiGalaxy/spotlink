package com.spotlink.inventory.service.access;

import java.util.List;
import java.math.BigDecimal;
import com.spotlink.inventory.entity.InventoryNote;
import com.spotlink.inventory.mapper.InventoryNoteMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 跨模块访问入口，沿用调用方事务，不向调用方暴露 ORM 条件。 */
@Service
@RequiredArgsConstructor
public class InventoryNoteAccessService implements InventoryNoteAccess {
    private final InventoryNoteMapper mapper;

    @Override public List<InventoryNote> findInStockOwned(Long enterpriseId) {
        return mapper.findInStockOwned(enterpriseId);
    }

    @Override public List<InventoryNote> findMarketInventory(Long categoryId) {
        return mapper.findMarketInventory(categoryId);
    }

    @Override public InventoryNote findSellable(Long enterpriseId, Long categoryId, BigDecimal quantity) {
        return mapper.findSellable(enterpriseId, categoryId, quantity);
    }

    @Override public List<InventoryNote> matchingCandidates(Long enterpriseId, Long categoryId, String unit, Long warehouseId, BigDecimal quantity) {
        return mapper.matchingCandidates(enterpriseId, categoryId, unit, warehouseId, quantity);
    }

    @Override public int insert(InventoryNote entity) {
        return mapper.insert(entity);
    }

    @Override public List<InventoryNote> searchOwned(Long enterpriseId, String keyword, boolean onlyFrozen, int limit) {
        return mapper.searchOwned(enterpriseId, keyword, onlyFrozen, limit);
    }

    @Override public InventoryNote selectById(java.io.Serializable id) {
        return mapper.selectById(id);
    }

    @Override public int updateById(InventoryNote entity) {
        return mapper.updateById(entity);
    }
}
