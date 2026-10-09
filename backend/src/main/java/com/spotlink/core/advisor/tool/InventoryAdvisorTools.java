package com.spotlink.advisor.tool;

import com.spotlink.commodity.entity.CommodityCategory;
import com.spotlink.commodity.service.access.CommodityCategoryAccess;
import com.spotlink.inventory.entity.InventoryNote;
import com.spotlink.inventory.service.access.InventoryNoteAccess;
import com.spotlink.shared.security.SecurityUtils;
import com.spotlink.warehouse.entity.Warehouse;
import com.spotlink.warehouse.service.access.WarehouseAccess;
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
 * 面向库存的顾问工具。
 *
 * <p>按主题从 {@code AdvisorTools} 里拆出来，而不是全堆在一个类里：每个工具的 description
 * 都会占用每一次请求的空间，而一个无上限膨胀的类最终会让工具清单本身变成一项成本。
 *
 * <p>租户规则这里和其他地方一样：没有工具接收企业 id，所有者一律从安全上下文读取。
 */
@Component
@RequiredArgsConstructor
public class InventoryAdvisorTools {

    /** 工具结果会作为输入 token 再次发送；要控制体积。 */
    private static final int MAX_ROWS = 30;

    private final InventoryNoteAccess inventoryNoteAccess;
    private final CommodityCategoryAccess categoryAccess;
    private final WarehouseAccess warehouseAccess;

    @Tool(name = "query_my_inventory",
            description = """
                    列出调用方**自己**的电子库存单：库存单号、商品、品类、仓库、总量/可用量/
                    冻结量以及状态。用于「我有哪些库存」「某个库存单还剩多少」「有多少被冻结了」
                    这类问题。它无法返回别的企业的库存。""")
    public String queryMyInventory(
            @ToolParam(description = "只返回商品名称包含这段文字的库存单。可选。")
            String commodityKeyword,
            @ToolParam(description = "只返回冻结量不为零的库存单。默认 false。")
            Boolean onlyFrozen) {

        Long enterpriseId = SecurityUtils.currentEnterpriseIdOrNull();
        if (enterpriseId == null) {
            return "该账号是平台运营账号，未绑定企业，因此没有库存单。";
        }

        List<InventoryNote> notes = inventoryNoteAccess.searchOwned(enterpriseId, commodityKeyword, Boolean.TRUE.equals(onlyFrozen), MAX_ROWS);
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
                    按商品品类汇总调用方**自己**的库存：每个品类下有多少张库存单，以及总量、
                    可用量和冻结量。用于「我一共多少库存」或「按品类统计一下」。
                    它无法返回别的企业的库存。""")
    public String summariseMyInventory() {

        Long enterpriseId = SecurityUtils.currentEnterpriseIdOrNull();
        if (enterpriseId == null) {
            return "该账号是平台运营账号，未绑定企业，因此没有库存。";
        }

        List<InventoryNote> notes = inventoryNoteAccess.findInStockOwned(enterpriseId);

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
        return warehouseAccess.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Warehouse::getId, Warehouse::getName));
    }

    private Map<Long, String> categoryNames(List<InventoryNote> notes) {
        List<Long> ids = notes.stream().map(InventoryNote::getCategoryId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return categoryAccess.selectBatchIds(ids).stream()
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

    /** 去掉 BigDecimal 保留下来的末尾零，例如 100.000 -> 100。 */
    private String plain(BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString();
    }
}
