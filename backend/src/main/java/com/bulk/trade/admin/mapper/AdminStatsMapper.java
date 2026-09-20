package com.bulk.trade.admin.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

/**
 * 运营后台的头部统计数字。
 *
 * <p>用 SQL 而不是读实体，理由与公开统计接口相同：要对一张表做计数，并不构成把
 * 整张表加载出来的理由。每个数字一条语句是为了让每条都清晰可读；六个走索引列的
 * 小聚合，比为了凑成一行返回而不得不写的那些 join 更便宜。
 */
@Mapper
public interface AdminStatsMapper {

    @Select("SELECT COUNT(*) FROM t_enterprise WHERE deleted = 0")
    long enterpriseCount();

    @Select("SELECT COUNT(*) FROM t_enterprise WHERE deleted = 0 AND status = 0")
    long pendingEnterpriseCount();

    @Select("SELECT COUNT(*) FROM t_enterprise WHERE deleted = 0 AND status = 3")
    long frozenEnterpriseCount();

    @Select("SELECT COUNT(*) FROM t_user WHERE deleted = 0")
    long userCount();

    @Select("SELECT COUNT(*) FROM t_order WHERE deleted = 0")
    long orderCount();

    /**
     * 仍在流转中的订单。
     *
     * <p>已取消算结束，已完成也算结束；其余的都还是一笔有人要去推动的交易。这个
     * 数字是运营人员会盯着的那个，因为它往上走说明市场在运转，而卡住不动则说明
     * 有地方出了问题。
     */
    @Select("""
            SELECT COUNT(*) FROM t_order
             WHERE deleted = 0 AND status NOT IN ('COMPLETED', 'CANCELLED')
            """)
    long activeOrderCount();

    @Select("""
            SELECT COUNT(*) FROM t_listing
             WHERE deleted = 0 AND status IN ('OPEN', 'PARTIALLY_FILLED')
            """)
    long openListingCount();

    @Select("""
            SELECT COALESCE(SUM(amount), 0) FROM t_order
             WHERE deleted = 0 AND status NOT IN ('PENDING_CONFIRM', 'CANCELLED')
            """)
    BigDecimal tradedAmount();

    @Select("SELECT COUNT(*) FROM t_audit_log")
    long auditCount();
}
