package com.spotlink.settlement.mapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spotlink.settlement.entity.FundFlow;

public interface FundFlowMapper extends BaseMapper<FundFlow> {

    default List<FundFlow> findRecentByAccountId(Long accountId, int limit) {
        return selectList(Wrappers.<FundFlow>lambdaQuery().eq(FundFlow::getAccountId, accountId).orderByDesc(FundFlow::getId).last("LIMIT " + Math.min(Math.max(limit, 1), 200)));
    }
}
