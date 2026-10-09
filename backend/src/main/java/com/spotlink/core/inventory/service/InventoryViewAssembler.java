package com.spotlink.inventory.service;

import com.spotlink.commodity.entity.CommodityCategory;
import com.spotlink.commodity.service.access.CommodityCategoryAccess;
import com.spotlink.inventory.dto.InventoryNoteView;
import com.spotlink.inventory.entity.InventoryNote;
import com.spotlink.warehouse.entity.Warehouse;
import com.spotlink.warehouse.service.access.WarehouseAccess;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 把库存单转换为视图，并解析出品类名与仓库名。
 *
 * <p>名称是批量查的，而不是逐行查：否则一页五十张库存单会多打出上百条查询，这正是
 * 那种典型的 N+1——只有等真实数据进来之后才会暴露出来。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryViewAssembler {

    private final CommodityCategoryAccess categoryAccess;
    private final WarehouseAccess warehouseAccess;
    private final ObjectMapper objectMapper;

    public InventoryNoteView toView(InventoryNote note) {
        return toViews(List.of(note)).get(0);
    }

    public List<InventoryNoteView> toViews(Collection<InventoryNote> notes) {
        if (notes.isEmpty()) {
            return List.of();
        }

        Map<Long, String> categoryNames = lookup(
                notes.stream().map(InventoryNote::getCategoryId).collect(Collectors.toSet()),
                ids -> categoryAccess.selectBatchIds(ids).stream()
                        .collect(Collectors.toMap(CommodityCategory::getId, CommodityCategory::getName)));

        Map<Long, String> warehouseNames = lookup(
                notes.stream().map(InventoryNote::getWarehouseId).collect(Collectors.toSet()),
                ids -> warehouseAccess.selectBatchIds(ids).stream()
                        .collect(Collectors.toMap(Warehouse::getId, Warehouse::getName)));

        return notes.stream()
                .map(note -> new InventoryNoteView(
                        note.getId(),
                        note.getNoteNo(),
                        note.getCategoryId(),
                        categoryNames.getOrDefault(note.getCategoryId(), "—"),
                        note.getWarehouseId(),
                        warehouseNames.getOrDefault(note.getWarehouseId(), "—"),
                        note.getCommodityName(),
                        note.getBrand(),
                        note.getOrigin(),
                        readSpec(note.getSpec()),
                        note.getTotalQuantity(),
                        note.getAvailableQuantity(),
                        note.getFrozenQuantity(),
                        note.getUnit(),
                        note.getStatus(),
                        statusText(note),
                        note.getRemark(),
                        note.getCreatedAt()))
                .toList();
    }

    private Map<Long, String> lookup(Set<Long> ids, Function<Set<Long>, Map<Long, String>> loader) {
        Set<Long> present = ids.stream().filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (present.isEmpty()) {
            return Map.of();
        }
        return loader.apply(present);
    }

    private Map<String, Object> readSpec(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<HashMap<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("Could not parse stored spec, returning empty map", e);
            return Map.of();
        }
    }

    private String statusText(InventoryNote note) {
        Integer status = note.getStatus();
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
}
