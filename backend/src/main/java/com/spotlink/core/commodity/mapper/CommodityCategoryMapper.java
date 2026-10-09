package com.spotlink.commodity.mapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spotlink.commodity.entity.CommodityCategory;

public interface CommodityCategoryMapper extends BaseMapper<CommodityCategory> {

    default List<CommodityCategory> findActiveOrdered() {
        return selectList(Wrappers.<CommodityCategory>lambdaQuery().eq(CommodityCategory::getStatus, 1).orderByAsc(CommodityCategory::getSortOrder).orderByAsc(CommodityCategory::getId));
    }

    default boolean hasChildren(Long parentId) {
        return selectCount(Wrappers.<CommodityCategory>lambdaQuery().eq(CommodityCategory::getParentId, parentId)) > 0;
    }
}
