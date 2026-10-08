package com.spotlink.commodity.dto;

import com.spotlink.commodity.entity.CommodityCategory;
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
        List<java.util.Map<String, Object>> specSchema,
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
                parseSchema(entity.getSpecSchema()),
                children);
    }

    private static List<java.util.Map<String, Object>> parseSchema(String value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(value,
                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("品类规格定义无效", e);
        }
    }
}
