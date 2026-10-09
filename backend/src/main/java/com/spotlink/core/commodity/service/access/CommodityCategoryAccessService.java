package com.spotlink.commodity.service.access;

import java.util.List;
import java.util.Collection;
import com.spotlink.commodity.entity.CommodityCategory;
import com.spotlink.commodity.mapper.CommodityCategoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 跨模块访问入口，沿用调用方事务，不向调用方暴露 ORM 条件。 */
@Service
@RequiredArgsConstructor
public class CommodityCategoryAccessService implements CommodityCategoryAccess {
    private final CommodityCategoryMapper mapper;

    @Override public boolean hasChildren(Long parentId) {
        return mapper.hasChildren(parentId);
    }

    @Override public List<CommodityCategory> selectBatchIds(Collection<? extends java.io.Serializable> ids) {
        return mapper.selectBatchIds(ids);
    }

    @Override public CommodityCategory selectById(java.io.Serializable id) {
        return mapper.selectById(id);
    }
}
