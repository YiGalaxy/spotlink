package com.bulk.trade.warehouse.dto;

import com.bulk.trade.warehouse.entity.Warehouse;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/** A warehouse as the client sees it. Ids travel as strings — see CategoryNode. */
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
