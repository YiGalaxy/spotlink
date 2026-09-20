package com.bulk.trade.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Edits the <em>description</em> of an inventory note.
 *
 * <p><b>Quantity, warehouse and unit are deliberately absent.</b> Those are
 * physical facts about goods sitting in a named place, not attributes of a
 * record. Changing the quantity does not change how much copper is in the shed
 * — it only makes the platform disagree with reality. Goods moving in or out is
 * an inbound or outbound movement, and goods moving between warehouses is a
 * transfer; both are events with their own records, not an edit to a form.
 *
 * <p>What can be corrected here is everything a clerk might have mistyped:
 * the commodity name, brand, origin, specification and remark.
 */
public record InventoryUpdateRequest(

        @NotNull(message = "请选择品类")
        Long categoryId,

        @NotBlank(message = "请填写商品名称")
        @Size(max = 128, message = "商品名称过长")
        String commodityName,

        @Size(max = 64, message = "品牌过长")
        String brand,

        @Size(max = 64, message = "产地过长")
        String origin,

        Map<String, Object> spec,

        @Size(max = 256, message = "备注过长")
        String remark
) {
}
