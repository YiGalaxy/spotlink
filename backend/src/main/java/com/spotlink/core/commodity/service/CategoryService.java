package com.spotlink.commodity.service;

import com.spotlink.commodity.dto.CategoryNode;
import com.spotlink.commodity.entity.CommodityCategory;
import com.spotlink.commodity.mapper.CommodityCategoryMapper;
import com.spotlink.shared.exception.BusinessException;
import com.spotlink.shared.web.ResultCode;
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
     * 完整的品类目录，以树的形式呈现。
     *
     * <p>一次查询全部加载，再在内存里组装。品类数据量小、改动也少，所以按层级做递归
     * 查询只会多出若干次往返而毫无收益——而且这样能让排序保持确定，这一点很重要，
     * 因为前端是直接照着渲染的。
     */
    public List<CategoryNode> tree() {
        List<CommodityCategory> all = categoryMapper.findActiveOrdered();

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
