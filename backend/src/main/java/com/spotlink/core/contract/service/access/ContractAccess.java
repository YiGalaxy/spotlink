package com.spotlink.contract.service.access;

import java.util.List;
import java.util.Collection;
import com.spotlink.contract.entity.Contract;

/** 模块对外的数据访问契约；查询实现由本模块 Mapper 负责。 */
public interface ContractAccess {
    Contract findByContractNo(String contractNo);
    List<Contract> findRecentParticipantContracts(Long enterpriseId, int limit);
    List<Contract> selectBatchIds(Collection<? extends java.io.Serializable> ids);
}
