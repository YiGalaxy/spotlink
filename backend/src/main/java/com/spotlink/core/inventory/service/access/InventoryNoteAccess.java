package com.spotlink.inventory.service.access;

import java.util.List;
import java.math.BigDecimal;
import com.spotlink.inventory.entity.InventoryNote;

/** 模块对外的数据访问契约；查询实现由本模块 Mapper 负责。 */
public interface InventoryNoteAccess {
    List<InventoryNote> findInStockOwned(Long enterpriseId);
    List<InventoryNote> findMarketInventory(Long categoryId);
    InventoryNote findSellable(Long enterpriseId, Long categoryId, BigDecimal quantity);
    List<InventoryNote> matchingCandidates(Long enterpriseId, Long categoryId, String unit, Long warehouseId, BigDecimal quantity);
    int insert(InventoryNote entity);
    List<InventoryNote> searchOwned(Long enterpriseId, String keyword, boolean onlyFrozen, int limit);
    InventoryNote selectById(java.io.Serializable id);
    int updateById(InventoryNote entity);
}
