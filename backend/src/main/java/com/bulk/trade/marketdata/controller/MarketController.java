package com.bulk.trade.marketdata.controller;

import com.bulk.trade.marketdata.dto.MarketDtos.QuoteRow;
import com.bulk.trade.marketdata.dto.MarketDtos.SeriesData;
import com.bulk.trade.marketdata.service.MarketBroadcaster;
import com.bulk.trade.marketdata.service.MarketService;
import com.bulk.trade.shared.security.SecurityUtils;
import com.bulk.trade.shared.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@Tag(name = "行情", description = "成交行情、曲线与实时推送")
@RestController
@RequestMapping("/api/market")
@RequiredArgsConstructor
public class MarketController {

    private final MarketService marketService;
    private final MarketBroadcaster broadcaster;

    @Operation(summary = "行情列表",
            description = "各品种最新成交价与涨跌。现货成交稀疏，"
                    + "「上一笔」指最近一次成交之前的那笔，而不是昨收——"
                    + "在这么薄的市场上，日收盘价是一种编造。")
    @GetMapping("/quotes")
    public ApiResponse<List<QuoteRow>> quotes(@RequestParam(defaultValue = "90") int days) {
        return ApiResponse.success(marketService.quotes(days));
    }

    @Operation(summary = "价格曲线",
            description = "type 取值 TRADE_PRICE / TRADE_VOLUME / LISTING_VOLUME / INVENTORY。"
                    + "返回的是均价线与成交量，不是 K 线——现货一天可能只有几笔成交，"
                    + "画成蜡烛图会得到一张几乎全空的格子。")
    @GetMapping("/series")
    public ApiResponse<SeriesData> series(
            @RequestParam(defaultValue = "TRADE_PRICE") String type,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "30") int days) {
        return ApiResponse.success(marketService.series(type, categoryId, days));
    }

    @Operation(summary = "实时行情推送（SSE）",
            description = "服务器单向推送。前端用 EventSource 订阅，"
                    + "断线会自动重连；不需要 WebSocket 的双向通道。")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        // Authentication happens in the filter chain before this runs, so a
        // caller reaching here is already authenticated.
        return broadcaster.register();
    }

    @Operation(summary = "推送连接数", description = "用于排查客户端是否真的连上了")
    @GetMapping("/stream/stats")
    public ApiResponse<Map<String, Object>> streamStats() {
        return ApiResponse.success(Map.of(
                "connections", broadcaster.connectionCount(),
                "viewer", SecurityUtils.currentUsername()));
    }
}
