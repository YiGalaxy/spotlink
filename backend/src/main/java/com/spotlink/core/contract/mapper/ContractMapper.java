package com.spotlink.contract.mapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spotlink.contract.entity.Contract;

public interface ContractMapper extends BaseMapper<Contract> {

    default Contract findByOrderId(Long orderId) {
        return selectOne(Wrappers.<Contract>lambdaQuery().eq(Contract::getOrderId, orderId));
    }

    default Contract findByContractNo(String contractNo) {
        return selectOne(Wrappers.<Contract>lambdaQuery().eq(Contract::getContractNo, contractNo));
    }

    default List<Contract> findParticipantContracts(Long enterpriseId) {
        return selectList(Wrappers.<Contract>lambdaQuery().and(w -> w.eq(Contract::getBuyerId, enterpriseId).or().eq(Contract::getSellerId, enterpriseId)).orderByDesc(Contract::getId));
    }

    default List<Contract> findRecentParticipantContracts(Long enterpriseId, int limit) {
        return selectList(Wrappers.<Contract>lambdaQuery().and(w -> w.eq(Contract::getBuyerId, enterpriseId).or().eq(Contract::getSellerId, enterpriseId)).orderByDesc(Contract::getId).last("LIMIT " + Math.min(Math.max(limit, 1), 200)));
    }
}
