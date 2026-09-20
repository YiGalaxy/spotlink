package com.bulk.trade.advisor.tool;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bulk.trade.commodity.entity.CommodityCategory;
import com.bulk.trade.commodity.mapper.CommodityCategoryMapper;
import com.bulk.trade.inventory.entity.InventoryNote;
import com.bulk.trade.inventory.mapper.InventoryNoteMapper;
import com.bulk.trade.shared.security.SecurityUtils;
import com.bulk.trade.warehouse.entity.Warehouse;
import com.bulk.trade.warehouse.mapper.WarehouseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Advisor tools over inventory.
 *
 * <p>Split from {@code AdvisorTools} by subject rather than lumped into one
 * class: each tool's description occupies space in every request, and a class
 * that grows without bound eventually makes the tool list itself a cost.
 *
 * <p>The tenant rule is the same here as everywhere: no tool takes an
 * enterprise id, and the owner is read from the security context.
 */
@Component
@RequiredArgsConstructor
public class InventoryAdvisorTools {

    /** Tool results are re-sent as input tokens; keep them small. */
    private static final int MAX_ROWS = 30;

    private final InventoryNoteMapper inventoryNoteMapper;
    private final CommodityCategoryMapper categoryMapper;
    private final WarehouseMapper warehouseMapper;

    @Tool(name = "query_my_inventory",
            description = """
                    Lists the caller's OWN electronic inventory notes (电子库存单): note number,
                    commodity, category, warehouse, total/available/frozen quantity and status.
                    Use it for questions like "我有哪些库存", "某个库存单还剩多少", or "有多少被冻结了".
                    It cannot return another company's inventory.""")
    public String queryMyInventory(
            @ToolParam(description = "Only return notes whose commodity name contains this text. Optional.")
            String commodityKeyword,
            @ToolParam(description = "Only return notes with a non-zero frozen quantity. Defaults to false.")
            Boolean onlyFrozen) {

        Long enterpriseId = SecurityUtils.currentEnterpriseIdOrNull();
        if (enterpriseId == null) {
            return "该账号是平台运营账号，未绑定企业，因此没有库存单。";
        }

        var query = Wrappers.<InventoryNote>lambdaQuery()
                .eq(InventoryNote::getEnterpriseId, enterpriseId)
                .orderByDesc(InventoryNote::getId)
                .last("limit " + MAX_ROWS);

        if (commodityKeyword != null && !commodityKeyword.isBlank()) {
            query.like(InventoryNote::getCommodityName, commodityKeyword.trim());
        }
        if (Boolean.TRUE.equals(onlyFrozen)) {
            query.gt(InventoryNote::getFrozenQuantity, BigDecimal.ZERO);
        }

        List<InventoryNote> notes = inventoryNoteMapper.selectList(query);
        if (notes.isEmpty()) {
            return "没有符合条件的库存单。";
        }

        Map<Long, String> warehouseNames = warehouseNames(notes);

        StringBuilder sb = new StringBuilder("库存单（共 ")
                .append(notes.size()).append(" 条，最多显示 ").append(MAX_ROWS).append(" 条）：\n");
        for (InventoryNote note : notes) {
            sb.append("- ").append(note.getNoteNo())
              .append(" | ").append(note.getCommodityName())
              .append(" | ").append(warehouseNames.getOrDefault(note.getWarehouseId(), "—"))
              .append(" | 总量 ").append(plain(note.getTotalQuantity())).append(note.getUnit())
              .append("，可用 ").append(plain(note.getAvailableQuantity()))
              .append("，冻结 ").append(plain(note.getFrozenQuantity()))
              .append(" | ").append(statusText(note.getStatus()))
              .append('\n');
        }
        return sb.toString();
    }

    @Tool(name = "summarise_my_inventory",
            description = """
                    Summarises the caller's OWN inventory grouped by commodity category: how many
                    notes and the total, available and frozen quantity per category. Use it for
                    "我一共多少库存" or "按品类统计一下". It cannot return another company's inventory.""")
    public String summariseMyInventory() {

        Long enterpriseId = SecurityUtils.currentEnterpriseIdOrNull();
        if (enterpriseId == null) {
            return "该账号是平台运营账号，未绑定企业，因此没有库存。";
        }

        List<InventoryNote> notes = inventoryNoteMapper.selectList(
                Wrappers.<InventoryNote>lambdaQuery()
                        .eq(InventoryNote::getEnterpriseId, enterpriseId)
                        .in(InventoryNote::getStatus,
                                InventoryNote.Status.IN_STOCK,
                                InventoryNote.Status.PARTIALLY_FROZEN,
                                InventoryNote.Status.FULLY_FROZEN));

        if (notes.isEmpty()) {
            return "当前没有在库的库存单。";
        }

        Map<Long, String> categoryNames = categoryNames(notes);

        record Totals(int count, BigDecimal total, BigDecimal available, BigDecimal frozen) {
        }
        Map<String, Totals> byCategory = new LinkedHashMap<>();
        for (InventoryNote note : notes) {
            String key = categoryNames.getOrDefault(note.getCategoryId(), "未分类")
                    + "/" + note.getUnit();
            Totals current = byCategory.getOrDefault(key,
                    new Totals(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
            byCategory.put(key, new Totals(
                    current.count() + 1,
                    current.total().add(note.getTotalQuantity()),
                    current.available().add(note.getAvailableQuantity()),
                    current.frozen().add(note.getFrozenQuantity())));
        }

        StringBuilder sb = new StringBuilder("在库汇总（按品类）：\n");
        for (var entry : byCategory.entrySet()) {
            Totals totals = entry.getValue();
            sb.append("- ").append(entry.getKey())
              .append(" | ").append(totals.count()).append(" 单")
              .append(" | 总量 ").append(plain(totals.total()))
              .append("，可用 ").append(plain(totals.available()))
              .append("，冻结 ").append(plain(totals.frozen()))
              .append('\n');
        }
        return sb.toString();
    }

    private Map<Long, String> warehouseNames(List<InventoryNote> notes) {
        List<Long> ids = notes.stream().map(InventoryNote::getWarehouseId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return warehouseMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Warehouse::getId, Warehouse::getName));
    }

    private Map<Long, String> categoryNames(List<InventoryNote> notes) {
        List<Long> ids = notes.stream().map(InventoryNote::getCategoryId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return categoryMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(CommodityCategory::getId, CommodityCategory::getName));
    }

    private String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case InventoryNote.Status.DRAFT -> "草稿";
            case InventoryNote.Status.PENDING_REVIEW -> "待审核";
            case InventoryNote.Status.IN_STOCK -> "在库";
            case InventoryNote.Status.FULLY_FROZEN -> "全部冻结";
            case InventoryNote.Status.PARTIALLY_FROZEN -> "部分冻结";
            case InventoryNote.Status.DELIVERED -> "已交收";
            case InventoryNote.Status.CANCELLED -> "已注销";
            default -> "未知";
        };
    }

    /** Drops the trailing zeros BigDecimal keeps, e.g. 100.000 -> 100. */
    private String plain(BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString();
    }
}
