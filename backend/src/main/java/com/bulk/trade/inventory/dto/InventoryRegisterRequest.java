package com.bulk.trade.inventory.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Registers goods into a designated warehouse.
 *
 * <p>There is no {@code enterpriseId} field on purpose: the owner is taken from
 * the authenticated principal, so a caller cannot register goods under another
 * company's name.
 */
public record InventoryRegisterRequest(

        @NotNull(message = "请选择品类")
        Long categoryId,

        @NotNull(message = "请选择交收仓库")
        Long warehouseId,

        @NotBlank(message = "请填写商品名称")
        @Size(max = 128, message = "商品名称过长")
        String commodityName,

        @Size(max = 64, message = "品牌过长")
        String brand,

        @Size(max = 64, message = "产地过长")
        String origin,

        /** Values keyed by the category's spec schema. */
        Map<String, Object> spec,

        @NotNull(message = "请填写数量")
        @DecimalMin(value = "0.001", message = "数量必须大于 0")
        BigDecimal quantity,

        @Size(max = 16, message = "单位过长")
        String unit,

        @Size(max = 256, message = "备注过长")
        String remark
) {
}
