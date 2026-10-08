package com.spotlink.contract.controller;

import com.spotlink.contract.dto.ContractView;
import com.spotlink.contract.entity.Contract;
import com.spotlink.contract.service.ContractService;
import com.spotlink.identity.entity.Enterprise;
import com.spotlink.identity.mapper.EnterpriseMapper;
import com.spotlink.shared.security.SecurityUtils;
import com.spotlink.shared.web.ApiResponse;
import com.spotlink.trading.service.TradingViewAssembler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "合同", description = "合同起草与签署")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ContractController {

    private final ContractService contractService;
    private final EnterpriseMapper enterpriseMapper;
    private final TradingViewAssembler viewAssembler;

    @Operation(summary = "我的合同")
    @GetMapping("/contracts")
    public ApiResponse<List<ContractView>> mine() {
        Long enterpriseId = SecurityUtils.currentEnterpriseId();
        return ApiResponse.success(contractService.listMine(enterpriseId).stream()
                .map(contract -> toView(contract, enterpriseId))
                .toList());
    }

    @Operation(summary = "订单的合同")
    @GetMapping("/orders/{orderId}/contract")
    public ApiResponse<ContractView> byOrder(@PathVariable Long orderId) {
        Long enterpriseId = SecurityUtils.currentEnterpriseId();
        return ApiResponse.success(toView(
                contractService.getByOrder(orderId, enterpriseId), enterpriseId));
    }

    @Operation(summary = "起草合同",
            description = "订单确认后由任一方发起。条款按订单快照生成，之后订单变动不会改写合同。")
    @PostMapping("/orders/{orderId}/contract")
    public ApiResponse<ContractView> draft(@PathVariable Long orderId) {
        Long enterpriseId = SecurityUtils.currentEnterpriseId();
        Contract contract = contractService.draftForOrder(orderId, SecurityUtils.currentUser());
        return ApiResponse.success(toView(contract, enterpriseId));
    }

    @Operation(summary = "签署合同",
            description = "双方都签署后合同生效，订单自动进入「已签约」。只有一方签署不构成合同。")
    @PostMapping("/contracts/{id}/sign")
    public ApiResponse<ContractView> sign(@PathVariable Long id) {
        Long enterpriseId = SecurityUtils.currentEnterpriseId();
        Contract contract = contractService.sign(id, SecurityUtils.currentUser());
        return ApiResponse.success(toView(contract, enterpriseId));
    }

    private ContractView toView(Contract contract, Long enterpriseId) {
        String buyerName = enterpriseName(contract.getBuyerId());
        String sellerName = enterpriseName(contract.getSellerId());
        return ContractView.of(contract, enterpriseId, buyerName, sellerName,
                contract.getTitle(), viewAssembler.readSpec(contract.getTerms()));
    }

    private String enterpriseName(Long id) {
        Enterprise enterprise = enterpriseMapper.selectById(id);
        return enterprise == null ? "—" : enterprise.getName();
    }
}
