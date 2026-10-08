package com.spotlink.inventory.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 把货物登记进指定交收仓库。
 *
 * <p>刻意不设 {@code enterpriseId} 字段：归属方取自已认证的主体身份，所以调用方
 * 无法把货物登记到别家公司名下。
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

        /** 取值按品类的规格 schema 组织。 */
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
