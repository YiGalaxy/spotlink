package com.bulk.trade.inventory.controller;

import com.bulk.trade.inventory.dto.InventoryNoteView;
import com.bulk.trade.inventory.dto.InventoryRegisterRequest;
import com.bulk.trade.inventory.service.InventoryService;
import com.bulk.trade.inventory.service.InventoryViewAssembler;
import com.bulk.trade.shared.security.SecurityUtils;
import com.bulk.trade.shared.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Inventory notes (电子库存单).
 *
 * <p>No endpoint takes an enterprise id. The owner always comes from the
 * authenticated principal, so a caller cannot read or act on another company's
 * goods by supplying a different id.
 */
@Tag(name = "电子库存单", description = "入库、查询与注销")
@RestController
@RequestMapping("/api/inventory-notes")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;
    private final InventoryViewAssembler viewAssembler;

    @Operation(summary = "我的库存单", description = "按状态可选筛选")
    @GetMapping
    public ApiResponse<List<InventoryNoteView>> listMine(
            @RequestParam(required = false) Integer status) {
        return ApiResponse.success(viewAssembler.toViews(
                inventoryService.listMine(SecurityUtils.currentEnterpriseId(), status)));
    }

    @Operation(summary = "库存单详情")
    @GetMapping("/{id}")
    public ApiResponse<InventoryNoteView> get(@PathVariable Long id) {
        return ApiResponse.success(viewAssembler.toView(
                inventoryService.get(id, SecurityUtils.currentEnterpriseId())));
    }

    @Operation(summary = "登记入库",
            description = "在指定交收仓库登记一批货物，生成电子库存单。物品初始为「在库」状态。")
    @PostMapping
    public ApiResponse<InventoryNoteView> register(
            @Valid @RequestBody InventoryRegisterRequest request) {
        return ApiResponse.success(viewAssembler.toView(
                inventoryService.register(request, SecurityUtils.currentEnterpriseId())));
    }

    @Operation(summary = "注销库存单",
            description = "仅当没有任何冻结时可注销。有挂牌或订单占用时会被拒绝。")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> cancel(@PathVariable Long id) {
        inventoryService.cancel(id, SecurityUtils.currentEnterpriseId());
        return ApiResponse.success();
    }
}
