package com.spotlink.inventory.controller;

import com.spotlink.inventory.dto.InventoryNoteView;
import com.spotlink.inventory.dto.InventoryRegisterRequest;
import com.spotlink.inventory.dto.InventoryUpdateRequest;
import com.spotlink.inventory.service.InventoryService;
import com.spotlink.inventory.service.InventoryViewAssembler;
import com.spotlink.shared.security.SecurityUtils;
import com.spotlink.shared.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 电子库存单。
 *
 * <p>没有任何接口接收企业 ID。归属方永远取自已认证的主体身份，所以调用方无法通过
 * 传入另一个 ID 来读取或操作别家公司的货物。
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

    @Operation(summary = "修改库存单",
            description = "只能改商品名称、品牌、产地、规格、备注等描述信息。"
                    + "数量、仓库、单位不可修改——那些是货物的物理事实，"
                    + "变动要走入库/出库/移库流程，而不是改表单。")
    @PutMapping("/{id}")
    public ApiResponse<InventoryNoteView> update(@PathVariable Long id,
                                                 @Valid @RequestBody InventoryUpdateRequest request) {
        return ApiResponse.success(viewAssembler.toView(
                inventoryService.update(id, request, SecurityUtils.currentEnterpriseId())));
    }

    @Operation(summary = "注销库存单",
            description = "仅当没有任何冻结时可注销。有挂牌或订单占用时会被拒绝。")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> cancel(@PathVariable Long id) {
        inventoryService.cancel(id, SecurityUtils.currentEnterpriseId());
        return ApiResponse.success();
    }
}
