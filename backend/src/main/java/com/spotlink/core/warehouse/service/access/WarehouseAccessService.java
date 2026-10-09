package com.spotlink.warehouse.service.access;

import java.util.List;
import java.util.Collection;
import com.spotlink.warehouse.entity.Warehouse;
import com.spotlink.warehouse.mapper.WarehouseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 跨模块访问入口，沿用调用方事务，不向调用方暴露 ORM 条件。 */
@Service
@RequiredArgsConstructor
public class WarehouseAccessService implements WarehouseAccess {
    private final WarehouseMapper mapper;

    @Override public List<Long> findIdsByLocation(String location) {
        return mapper.findIdsByLocation(location);
    }

    @Override public List<Warehouse> selectBatchIds(Collection<? extends java.io.Serializable> ids) {
        return mapper.selectBatchIds(ids);
    }

    @Override public Warehouse selectById(java.io.Serializable id) {
        return mapper.selectById(id);
    }
}
