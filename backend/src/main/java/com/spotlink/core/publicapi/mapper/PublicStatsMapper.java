package com.spotlink.publicapi.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

/**
 * 访客登录前能看到的那几个汇总数字。
 *
 * <p>写成 SQL，而不是用各模块的 mapper 拼出来，因为这些是对整张表的计数与求和，而不是
 * 实体读取。把每一笔订单加载进来再加总金额，等于用最贵的方式算同一个数。
 *
 * <p>每一个都是独立查询。六个走索引的小聚合，比为了拼成一行而写出的连接更便宜，
 * 而且每一个单独看都还算清楚。
 */
@Mapper
public interface PublicStatsMapper {

    /** 已通过审核的企业——还在申请中的不算会员。 */
    @Select("""
            SELECT COUNT(*) FROM t_enterprise
             WHERE deleted = 0 AND status = 1
            """)
    long countApprovedEnterprises();

    /** 两个方向的在挂要约。 */
    @Select("""
            SELECT COUNT(*) FROM t_listing
             WHERE deleted = 0 AND status IN ('OPEN', 'PARTIALLY_FILLED')
            """)
    long countOpenListings();

    /**
     * 真正算数的成交。
     *
     * <p>排除已取消的订单，因为它们被撤销了；排除等待挂牌方答复的订单，因为它们还只是
     * 一个问题。其余的才是双方都已经作出的承诺——而一个访客在看「累计成交量」时，
     * 被告知的正是这件事。
     */
    @Select("""
            SELECT COUNT(*) FROM t_order
             WHERE deleted = 0 AND status NOT IN ('PENDING_CONFIRM', 'CANCELLED')
            """)
    long countTrades();

    @Select("""
            SELECT COALESCE(SUM(quantity), 0) FROM t_order
             WHERE deleted = 0 AND status NOT IN ('PENDING_CONFIRM', 'CANCELLED')
            """)
    BigDecimal tradedQuantity();

    @Select("""
            SELECT COALESCE(SUM(amount), 0) FROM t_order
             WHERE deleted = 0 AND status NOT IN ('PENDING_CONFIRM', 'CANCELLED')
            """)
    BigDecimal tradedAmount();

    /**
     * 平台为会员保管的全部货物。
     *
     * <p>只给总量，绝不给明细：行情推送本来就把在库总量作为一条序列公开，而按企业拆开的
     * 数字会是某个会员的持仓，而不是市场的规模。
     */
    @Select("""
            SELECT COALESCE(SUM(total_quantity), 0) FROM t_inventory_note
             WHERE deleted = 0
            """)
    BigDecimal inventoryQuantity();
}
