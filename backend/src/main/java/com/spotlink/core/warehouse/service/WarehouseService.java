package com.spotlink.warehouse.service;

import com.spotlink.warehouse.dto.WarehouseView;
import com.spotlink.warehouse.entity.Warehouse;
import com.spotlink.warehouse.mapper.WarehouseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WarehouseService {
    private final WarehouseMapper warehouses;

    public List<WarehouseView> listActive() {
        return warehouses.findActiveOrdered().stream().map(WarehouseView::of).toList();
    }
}
