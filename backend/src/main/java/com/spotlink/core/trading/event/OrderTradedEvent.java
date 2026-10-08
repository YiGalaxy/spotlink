package com.spotlink.trading.event;

import java.math.BigDecimal;

/**
 * 交易达成时抛出，以便行情推送得以更新。
 *
 * <p>用事件而不是由订单服务直接调用行情模块：订单服务不需要知道行情推送的
 * 存在，而行情推送可以被移除或替换，交易代码不必改动。
 *
 * @param categoryId  成交的是哪个品级
 * @param price       单价
 * @param quantity    成交数量
 * @param buyerId     买方企业
 * @param sellerId    卖方企业
 * @param orderNo     供审计轨迹使用
 */
public record OrderTradedEvent(
        Long categoryId,
        String commodityName,
        BigDecimal price,
        BigDecimal quantity,
        String unit,
        Long buyerId,
        Long sellerId,
        String orderNo
) {
}
