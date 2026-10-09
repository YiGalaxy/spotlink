package com.spotlink.trading.mapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spotlink.trading.entity.Listing;

public interface ListingMapper extends BaseMapper<Listing> {
    @org.apache.ibatis.annotations.Select("SELECT * FROM t_listing WHERE id=#{id} AND deleted=0 FOR UPDATE")
    Listing lockById(@org.apache.ibatis.annotations.Param("id") Long id);
    default List<Listing> findPublicByNumber(String number, String side, OffsetDateTime now) {
        return selectList(publicQuery(now).eq(Listing::getListingNo, number)
                .eq(side != null, Listing::getSide, side).last("LIMIT 1"));
    }

    default List<Listing> findPublicByKeyword(String keyword, String side, OffsetDateTime now, int limit) {
        return selectList(publicQuery(now).eq(side != null, Listing::getSide, side)
                .like(Listing::getCommodityName, keyword).orderByDesc(Listing::getId)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 200)));
    }

    default long countPublic(com.spotlink.trading.dto.PublicListingCriteria criteria) {
        return selectCount(publicSearch(criteria));
    }

    default List<Listing> searchPublic(com.spotlink.trading.dto.PublicListingCriteria criteria,
                                      String sort, int limit) {
        String order = switch (sort) {
            case "PRICE_ASC" -> "(price IS NULL) ASC, price ASC, id DESC";
            case "QUANTITY_DESC" -> "remaining_quantity DESC, id DESC";
            case "LATEST" -> "id DESC";
            default -> throw new IllegalArgumentException("未知挂牌排序");
        };
        return selectList(publicSearch(criteria).last("ORDER BY " + order + " LIMIT " + Math.min(Math.max(limit, 1), 200)));
    }

    private com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Listing> publicSearch(
            com.spotlink.trading.dto.PublicListingCriteria c) {
        var query = publicQuery(c.now());
        if (c.side() != null) query.eq(Listing::getSide, c.side());
        if (c.keyword() != null && !c.keyword().isBlank()) {
            String pattern = "%" + c.keyword().trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            query.apply("commodity_name LIKE {0} ESCAPE '!'", pattern);
        }
        if (c.warehouseIds() != null) {
            // 空集合表达没有匹配仓库，不能退化为全市场读取。
            if (c.warehouseIds().isEmpty()) query.apply("1 = 0");
            else query.in(Listing::getWarehouseId, c.warehouseIds());
        }
        if (c.unit() != null && !c.unit().isBlank()) query.eq(Listing::getUnit, c.unit().trim());
        if (c.minQuantity() != null) query.ge(Listing::getRemainingQuantity, c.minQuantity());
        if (c.maxPrice() != null) query.le(Listing::getPrice, c.maxPrice());
        if (c.deliveryMethod() != null) query.eq(Listing::getDeliveryMethod, c.deliveryMethod());
        return query;
    }

    private com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Listing> publicQuery(OffsetDateTime now) {
        return Wrappers.<Listing>lambdaQuery().in(Listing::getStatus, Listing.Status.OPEN, Listing.Status.PARTIALLY_FILLED)
                .gt(Listing::getRemainingQuantity, BigDecimal.ZERO).gt(Listing::getValidUntil, now);
    }

    default List<Listing> findExpiredOpen(OffsetDateTime now) {
        return selectList(Wrappers.<Listing>lambdaQuery().in(Listing::getStatus, Listing.Status.OPEN, Listing.Status.PARTIALLY_FILLED).lt(Listing::getValidUntil, now));
    }

    default List<Listing> browseOpen(Long categoryId, String side, String keyword) {
        var query = Wrappers.<Listing>lambdaQuery().in(Listing::getStatus, Listing.Status.OPEN, Listing.Status.PARTIALLY_FILLED).gt(Listing::getRemainingQuantity, BigDecimal.ZERO).orderByDesc(Listing::getId);
        if (categoryId != null) query.eq(Listing::getCategoryId, categoryId);
        if (side != null && !side.isBlank()) query.eq(Listing::getSide, side);
        if (keyword != null && !keyword.isBlank()) query.like(Listing::getCommodityName, keyword.trim());
        return selectList(query);
    }

    default List<Listing> findOwned(Long enterpriseId) {
        return selectList(Wrappers.<Listing>lambdaQuery().eq(Listing::getEnterpriseId, enterpriseId).orderByDesc(Listing::getId));
    }

    default List<Listing> findByCategory(Long categoryId) {
        return selectList(Wrappers.<Listing>lambdaQuery().eq(categoryId != null, Listing::getCategoryId, categoryId));
    }
}
