package com.spotlink.publicapi.controller;

import com.spotlink.publicapi.dto.PublicStats;
import com.spotlink.publicapi.service.PublicStatsService;
import com.spotlink.shared.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


/**
 * 平台的公开门面。
 *
 * <p>只读、免鉴权，并且刻意很小。这里的一切都是关于整个市场的陈述；一旦某个数字会描述
 * 到某一家的具体情况，它就属于令牌之后。
 */
@Tag(name = "公开信息", description = "无需登录即可访问的平台数据")
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicController {

    private final PublicStatsService statsService;

    @Operation(summary = "平台概览",
            description = "入驻企业数、在挂挂牌数、累计成交笔数/数量/金额、在库总量。全部为平台整体口径。")
    @GetMapping("/stats")
    public ApiResponse<PublicStats> stats() {
        return ApiResponse.success(statsService.overview());
    }
}
