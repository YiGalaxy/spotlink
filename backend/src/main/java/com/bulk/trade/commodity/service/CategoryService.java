package com.bulk.trade.commodity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bulk.trade.commodity.dto.CategoryNode;
import com.bulk.trade.commodity.entity.CommodityCategory;
import com.bulk.trade.commodity.mapper.CommodityCategoryMapper;
import com.bulk.trade.shared.exception.BusinessException;
import com.bulk.trade.shared.web.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CommodityCategoryMapper categoryMapper;

    /**
     * The whole catalogue as a tree.
     *
     * <p>Loaded in one query and assembled in memory. The catalogue is small and
     * changes rarely, so a recursive query per level would be more round trips
     * for no benefit — and this keeps the ordering deterministic, which matters
     * because the frontend renders it directly.
     */
    public List<CategoryNode> tree() {
        List<CommodityCategory> all = categoryMapper.selectList(
                Wrappers.<CommodityCategory>lambdaQuery()
                        .eq(CommodityCategory::getStatus, 1)
                        .orderByAsc(CommodityCategory::getSortOrder)
                        .orderByAsc(CommodityCategory::getId));

        Map<Long, List<CommodityCategory>> byParent = new LinkedHashMap<>();
        for (CommodityCategory category : all) {
            byParent.computeIfAbsent(category.getParentId(), key -> new ArrayList<>())
                    .add(category);
        }
        return build(CommodityCategory.ROOT_PARENT_ID, byParent);
    }

    public CommodityCategory get(Long id) {
        CommodityCategory category = categoryMapper.selectById(id);
        if (category == null) {
            throw BusinessException.of(ResultCode.CATEGORY_NOT_FOUND);
        }
        return category;
    }

    private List<CategoryNode> build(Long parentId, Map<Long, List<CommodityCategory>> byParent) {
        List<CommodityCategory> children = byParent.get(parentId);
        if (children == null || children.isEmpty()) {
            return List.of();
        }
        List<CategoryNode> nodes = new ArrayList<>(children.size());
        for (CommodityCategory child : children) {
            nodes.add(CategoryNode.of(child, build(child.getId(), byParent)));
        }
        return nodes;
    }
}
