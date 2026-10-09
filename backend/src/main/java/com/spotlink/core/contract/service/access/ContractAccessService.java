package com.spotlink.contract.service.access;

import java.util.List;
import java.util.Collection;
import com.spotlink.contract.entity.Contract;
import com.spotlink.contract.mapper.ContractMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 跨模块访问入口，沿用调用方事务，不向调用方暴露 ORM 条件。 */
@Service
@RequiredArgsConstructor
public class ContractAccessService implements ContractAccess {
    private final ContractMapper mapper;

    @Override public Contract findByContractNo(String contractNo) {
        return mapper.findByContractNo(contractNo);
    }

    @Override public List<Contract> findRecentParticipantContracts(Long enterpriseId, int limit) {
        return mapper.findRecentParticipantContracts(enterpriseId, limit);
    }

    @Override public List<Contract> selectBatchIds(Collection<? extends java.io.Serializable> ids) {
        return mapper.selectBatchIds(ids);
    }
}
