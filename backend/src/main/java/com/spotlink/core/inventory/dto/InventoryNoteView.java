package com.spotlink.inventory.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 客户端看到的库存单视图。
 *
 * <p>ID 是字符串：Snowflake ID 是 19 位，而 JavaScript 超过 16 位就会丢失精度，
 * 所以以数字形式传出去会变样。
 *
 * <p>三个数量都暴露出来，而不是只给可用量。只看得到"可用"的卖家分不清货物是真的
 * 没了，还是只是被占用了，而这个区分恰恰是他们第一个会问的问题。
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

        @JsonSerialize(using = ToStringSerializer.class) BigDecimal totalQuantity,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal availableQuantity,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal frozenQuantity,
        String unit,

        Integer status,
        String statusText,

        String remark,
        OffsetDateTime createdAt,
        Integer version
) {
}
