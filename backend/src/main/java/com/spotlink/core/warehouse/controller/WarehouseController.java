package com.spotlink.warehouse.controller;

import com.spotlink.shared.web.ApiResponse;
import com.spotlink.warehouse.dto.WarehouseView;
import com.spotlink.warehouse.service.WarehouseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "交收仓库", description = "指定交收仓库")
@RestController
@RequestMapping("/api/warehouses")
@RequiredArgsConstructor
public class WarehouseController {

    private final WarehouseService warehouseService;

    @Operation(summary = "可用仓库列表")
    @GetMapping
    public ApiResponse<List<WarehouseView>> list() {
        return ApiResponse.success(warehouseService.listActive());
    }
}
