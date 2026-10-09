package com.spotlink.trading.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.spotlink.trading.entity.GoodsTransfer;

public interface GoodsTransferMapper extends BaseMapper<GoodsTransfer> {
    default GoodsTransfer findByOrder(Long orderId) {
        return selectOne(Wrappers.<GoodsTransfer>lambdaQuery().eq(GoodsTransfer::getOrderId, orderId));
    }
}
