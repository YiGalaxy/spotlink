package com.bulk.trade.commodity.dto;

import com.bulk.trade.commodity.entity.CommodityCategory;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.util.List;

/**
 * 一个品类，连同它的子节点。
 *
 * <p>{@code parentId} 和 {@code id} 是字符串——Snowflake ID 是 19 位，而
 * JavaScript 超过 16 位就会丢失精度，所以一旦以数字发送，客户端回传的每一个 ID
 * 都会变成另一个 ID。
 */
public record CategoryNode(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        @JsonSerialize(using = ToStringSerializer.class) Long parentId,
        String code,
        String name,
        Integer level,
        String unit,
        Integer sortOrder,
        List<CategoryNode> children
) {

    public static CategoryNode of(CommodityCategory entity, List<CategoryNode> children) {
        return new CategoryNode(
                entity.getId(),
                entity.getParentId(),
                entity.getCode(),
                entity.getName(),
                entity.getLevel(),
                entity.getUnit(),
                entity.getSortOrder(),
                children);
    }
}
