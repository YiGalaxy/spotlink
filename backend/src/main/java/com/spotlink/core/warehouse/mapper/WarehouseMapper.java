package com.spotlink.warehouse.mapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spotlink.warehouse.entity.Warehouse;

public interface WarehouseMapper extends BaseMapper<Warehouse> {

    default List<Warehouse> findActiveOrdered() {
        return selectList(Wrappers.<Warehouse>lambdaQuery().eq(Warehouse::getStatus, 1).orderByAsc(Warehouse::getCode));
    }

    default List<Long> findIdsByLocation(String location) {
        String pattern = "%" + location.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return selectList(Wrappers.<Warehouse>lambdaQuery().and(q -> q.apply("province LIKE {0} ESCAPE '!'", pattern).or().apply("city LIKE {0} ESCAPE '!'", pattern).or().apply("name LIKE {0} ESCAPE '!'", pattern)).last("LIMIT 500")).stream().map(Warehouse::getId).toList();
    }
}
