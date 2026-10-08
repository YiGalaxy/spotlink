package com.spotlink.advisor.dto;

import jakarta.validation.constraints.Size;

public record UpdateContextRequest(@Size(max = 2000, message = "采购需求不能超过2000个字符") String note) {}
