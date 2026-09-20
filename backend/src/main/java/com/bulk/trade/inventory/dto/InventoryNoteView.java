package com.bulk.trade.inventory.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * An inventory note as the client sees it.
 *
 * <p>Ids are strings: a snowflake id is 19 digits and JavaScript loses
 * precision past 16, so a numeric id would come back changed.
 *
 * <p>The three quantity figures are all exposed, not just the available one.
 * A seller who sees only "available" cannot tell whether goods are gone or
 * merely reserved, and that distinction is the first thing they will ask about.
 */
public record InventoryNoteView(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String noteNo,

        @JsonSerialize(using = ToStringSerializer.class) Long categoryId,
        String categoryName,

        @JsonSerialize(using = ToStringSerializer.class) Long warehouseId,
        String warehouseName,

        String commodityName,
        String brand,
        String origin,
        Map<String, Object> spec,

        BigDecimal totalQuantity,
        BigDecimal availableQuantity,
        BigDecimal frozenQuantity,
        String unit,

        Integer status,
        String statusText,

        OffsetDateTime createdAt
) {
}
