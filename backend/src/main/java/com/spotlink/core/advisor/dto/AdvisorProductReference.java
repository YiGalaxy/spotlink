package com.spotlink.advisor.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/** 仅由公开挂牌工具生成，不接受模型输出中的商品 ID。 */
public record AdvisorProductReference(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String listingNo, String title, String seller, String quantity, String price, String warehouse, String delivery) {
    public String url() { return "/trading?listing=" + id; }
}
