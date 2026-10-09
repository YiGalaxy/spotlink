package com.spotlink.settlement.mapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spotlink.settlement.entity.FundAccount;

public interface FundAccountMapper extends BaseMapper<FundAccount> {

    default FundAccount findByEnterpriseId(Long enterpriseId) {
        return selectOne(Wrappers.<FundAccount>lambdaQuery().eq(FundAccount::getEnterpriseId, enterpriseId));
    }
}
