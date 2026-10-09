package com.spotlink.contract.service;

import com.spotlink.contract.dto.ContractView;
import com.spotlink.contract.entity.Contract;
import com.spotlink.identity.entity.Enterprise;
import com.spotlink.identity.service.access.EnterpriseAccess;
import com.spotlink.trading.service.TradingViewAssembler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ContractViewAssembler {
    private final EnterpriseAccess enterprises;
    private final TradingViewAssembler tradingViews;

    public ContractView toView(Contract contract, Long enterpriseId) {
        return ContractView.of(contract, enterpriseId, enterpriseName(contract.getBuyerId()),
                enterpriseName(contract.getSellerId()), contract.getTitle(), tradingViews.readSpec(contract.getTerms()));
    }

    private String enterpriseName(Long id) {
        Enterprise enterprise = enterprises.selectById(id);
        return enterprise == null ? "—" : enterprise.getName();
    }
}
