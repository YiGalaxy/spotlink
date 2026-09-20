package com.bulk.trade.commodity.dto;

import com.bulk.trade.commodity.entity.CommodityCategory;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.util.List;

/**
 * A category with its children.
 *
 * <p>{@code parentId} and {@code id} are strings — a snowflake id is 19 digits
 * and JavaScript loses precision past 16, so sending them as numbers makes every
 * id the client echoes back a different id.
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
