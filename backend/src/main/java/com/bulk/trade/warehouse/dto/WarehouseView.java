package com.bulk.trade.warehouse.dto;

import com.bulk.trade.warehouse.entity.Warehouse;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/** 客户端看到的仓库视图。ID 以字符串传输——参见 CategoryNode。 */
public record WarehouseView(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String code,
        String name,
        String shortName,
        String province,
        String city,
        String address,
        String contactName,
        String contactPhone
) {

    public static WarehouseView of(Warehouse entity) {
        return new WarehouseView(
                entity.getId(),
                entity.getCode(),
                entity.getName(),
                entity.getShortName(),
                entity.getProvince(),
                entity.getCity(),
                entity.getAddress(),
                entity.getContactName(),
                entity.getContactPhone());
    }
}
