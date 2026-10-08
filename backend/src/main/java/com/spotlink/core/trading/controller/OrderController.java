package com.spotlink.trading.controller;

import com.spotlink.shared.security.SecurityUtils;
import com.spotlink.shared.web.ApiResponse;
import com.spotlink.trading.dto.OrderView;
import com.spotlink.trading.entity.OrderStatus;
import com.spotlink.trading.entity.OrderStatusLog;
import com.spotlink.trading.service.OrderService;
import com.spotlink.trading.service.TradingViewAssembler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "订单", description = "订单状态流转与历史")
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final TradingViewAssembler viewAssembler;

    @Operation(summary = "我的订单",
            description = "买方的订单和卖方的订单都会返回，myRole 标明当前账号是哪一方")
    @GetMapping
    public ApiResponse<List<OrderView>> mine(@RequestParam(required = false) String status) {
        Long enterpriseId = SecurityUtils.currentEnterpriseId();
        return ApiResponse.success(viewAssembler.toOrderViews(
                orderService.listMine(enterpriseId, status), enterpriseId));
    }

    @Operation(summary = "订单详情")
    @GetMapping("/{id}")
    public ApiResponse<OrderView> get(@PathVariable Long id) {
        Long enterpriseId = SecurityUtils.currentEnterpriseId();
        return ApiResponse.success(viewAssembler.toOrderViews(
                List.of(orderService.get(id, enterpriseId)), enterpriseId).get(0));
    }

    @Operation(summary = "订单状态历史", description = "完整的流转轨迹，含操作人和时间")
    @GetMapping("/{id}/history")
    public ApiResponse<List<Map<String, Object>>> history(@PathVariable Long id) {
        Long enterpriseId = SecurityUtils.currentEnterpriseId();
        List<OrderStatusLog> logs = orderService.history(id, enterpriseId);
        return ApiResponse.success(logs.stream()
                .map(log -> Map.<String, Object>of(
                        "fromStatus", log.getFromStatus() == null ? "" : log.getFromStatus(),
                        "fromText", log.getFromStatus() == null ? "创建" : OrderStatus.text(log.getFromStatus()),
                        "toStatus", log.getToStatus(),
                        "toText", OrderStatus.text(log.getToStatus()),
                        "operator", log.getOperator() == null ? "—" : log.getOperator(),
                        "reason", log.getReason() == null ? "" : log.getReason(),
                        "createdAt", log.getCreatedAt()))
                .toList());
    }

    @Operation(summary = "确认订单")
    @PostMapping("/{id}/confirm")
    public ApiResponse<OrderView> confirm(@PathVariable Long id) {
        Long enterpriseId = SecurityUtils.currentEnterpriseId();
        return ApiResponse.success(viewAssembler.toOrderViews(
                List.of(orderService.confirm(id, SecurityUtils.currentUser())), enterpriseId).get(0));
    }

    @Operation(summary = "拒绝摘牌",
            description = "仅挂牌方本人可操作，且仅对「待挂牌方确认」的订单有效。"
                    + "此时货权尚未转移，拒绝后挂牌数量原样恢复。")
    @PostMapping("/{id}/reject")
    public ApiResponse<OrderView> reject(@PathVariable Long id,
                                         @RequestBody(required = false) Map<String, String> body) {
        Long enterpriseId = SecurityUtils.currentEnterpriseId();
        String reason = body == null ? null : body.get("reason");
        return ApiResponse.success(viewAssembler.toOrderViews(
                List.of(orderService.reject(id, reason, SecurityUtils.currentUser())), enterpriseId).get(0));
    }

    @Operation(summary = "取消订单",
            description = "交收开始前可取消，货物会退回卖方（挂牌仍有效则重新占用该挂牌）")
    @PostMapping("/{id}/cancel")
    public ApiResponse<OrderView> cancel(@PathVariable Long id,
                                         @RequestBody(required = false) Map<String, String> body) {
        Long enterpriseId = SecurityUtils.currentEnterpriseId();
        String reason = body == null ? null : body.get("reason");
        return ApiResponse.success(viewAssembler.toOrderViews(
                List.of(orderService.cancel(id, reason, SecurityUtils.currentUser())), enterpriseId).get(0));
    }

    @Operation(summary = "开始交收")
    @PostMapping("/{id}/deliver")
    public ApiResponse<OrderView> deliver(@PathVariable Long id) {
        Long enterpriseId = SecurityUtils.currentEnterpriseId();
        return ApiResponse.success(viewAssembler.toOrderViews(
                List.of(orderService.startDelivery(id, SecurityUtils.currentUser())), enterpriseId).get(0));
    }

    @Operation(summary = "完成交收")
    @PostMapping("/{id}/complete")
    public ApiResponse<OrderView> complete(@PathVariable Long id) {
        Long enterpriseId = SecurityUtils.currentEnterpriseId();
        return ApiResponse.success(viewAssembler.toOrderViews(
                List.of(orderService.complete(id, SecurityUtils.currentUser())), enterpriseId).get(0));
    }
}
