package com.bulk.trade.advisor.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(

        @NotBlank(message = "提问内容不能为空")
        @Size(max = 4000, message = "提问内容过长")
        String message
) {
}
