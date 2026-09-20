package com.bulk.trade.publicapi.controller;

import com.bulk.trade.publicapi.dto.PublicStats;
import com.bulk.trade.publicapi.mapper.PublicStatsMapper;
import com.bulk.trade.shared.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * The public face of the platform.
 *
 * <p>Read-only, unauthenticated, and deliberately tiny. Everything here is a
 * statement about the venue as a whole; the moment a figure would describe one
 * member, it belongs behind the token.
 */
@Tag(name = "公开信息", description = "无需登录即可访问的平台数据")
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicController {

    private final PublicStatsMapper statsMapper;

    @Operation(summary = "平台概览",
            description = "入驻企业数、在挂挂牌数、累计成交笔数/数量/金额、在库总量。全部为平台整体口径。")
    @GetMapping("/stats")
    public ApiResponse<PublicStats> stats() {
        BigDecimal tradedQuantity = statsMapper.tradedQuantity();
        BigDecimal tradedAmount = statsMapper.tradedAmount();

        return ApiResponse.success(new PublicStats(
                statsMapper.countApprovedEnterprises(),
                statsMapper.countOpenListings(),
                statsMapper.countTrades(),
                tradedQuantity,
                tradedAmount,
                PublicStats.formatAmount(tradedAmount),
                statsMapper.inventoryQuantity()));
    }
}
