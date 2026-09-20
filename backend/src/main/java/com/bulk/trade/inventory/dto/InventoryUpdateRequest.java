package com.bulk.trade.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 修改库存单的<em>描述信息</em>。
 *
 * <p><b>数量、仓库和单位刻意不在这里。</b>它们是关于货物实际停放在某个指定地点的
 * 物理事实，而不是某条记录的属性。改数量并不会改变棚里到底有多少铜——它只会让平台
 * 和现实不一致。货物进出是入库或出库作业，货物在仓库之间移动是移库；两者都是带自己
 * 记录的事件，而不是对表单的一次编辑。
 *
 * <p>这里能纠正的，是文员可能打错的一切：商品名称、品牌、产地、规格和备注。
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
