package com.spotlink.settlement.controller;

import com.spotlink.settlement.entity.FundAccount;
import com.spotlink.settlement.entity.FundFlow;
import com.spotlink.settlement.service.FundService;
import com.spotlink.shared.security.SecurityUtils;
import com.spotlink.shared.web.ApiResponse;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@Tag(name = "资金账户", description = "余额、流水与充值")
@RestController
@RequestMapping("/api/fund")
@RequiredArgsConstructor
public class FundController {

    private final FundService fundService;

    public record AccountView(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String accountNo,
            BigDecimal balance,
            BigDecimal availableBalance,
            BigDecimal frozenBalance,
            String currency
    ) {
        static AccountView of(FundAccount account) {
            return new AccountView(account.getId(), account.getAccountNo(),
                    account.getBalance(), account.getAvailableBalance(),
                    account.getFrozenBalance(), account.getCurrency());
        }
    }

    public record FlowView(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String flowNo,
            String direction,
            String bizType,
            String bizTypeText,
            BigDecimal amount,
            BigDecimal balanceAfter,
            String remark,
            OffsetDateTime createdAt
    ) {
        static FlowView of(FundFlow flow) {
            return new FlowView(flow.getId(), flow.getFlowNo(), flow.getDirection(),
                    flow.getBizType(), FundFlow.BizType.text(flow.getBizType()),
                    flow.getAmount(), flow.getBalanceAfter(), flow.getRemark(),
                    flow.getCreatedAt());
        }
    }

    public record RechargeRequest(
            @NotNull(message = "请填写金额")
            @DecimalMin(value = "0.01", message = "金额必须大于 0")
            BigDecimal amount,
            @Size(max = 512) String remark
    ) {
    }

    @Operation(summary = "我的资金账户",
            description = "balance 是总额，available 是可用，frozen 是被订单占用的保证金")
    @GetMapping("/account")
    public ApiResponse<AccountView> account() {
        return ApiResponse.success(AccountView.of(
                fundService.requireAccount(SecurityUtils.currentEnterpriseId())));
    }

    @Operation(summary = "资金流水", description = "不可变流水，按时间倒序")
    @GetMapping("/flows")
    public ApiResponse<List<FlowView>> flows(@RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(fundService.flows(SecurityUtils.currentEnterpriseId(), limit)
                .stream().map(FlowView::of).toList());
    }

    @Operation(summary = "充值",
            description = "演示用。真实平台的资金由第三方清算机构划入，平台不接触资金池。")
    @PostMapping("/recharge")
    public ApiResponse<AccountView> recharge(@Valid @RequestBody RechargeRequest request) {
        return ApiResponse.success(AccountView.of(fundService.recharge(
                SecurityUtils.currentEnterpriseId(), request.amount(), request.remark())));
    }
}
