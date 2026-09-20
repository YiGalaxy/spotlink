package com.bulk.trade.admin.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

/**
 * The console's headline counts.
 *
 * <p>SQL rather than entity reads, for the same reason as the public stats: a
 * count over a table is not a reason to load the table. One statement per figure
 * keeps each legible; six small aggregates over indexed columns are cheaper than
 * the joins it would take to return them as one row.
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
     * Orders still in flight.
     *
     * <p>Cancelled is finished, and so is completed; everything else is a deal
     * somebody still has to move. This is the number an operator watches,
     * because a rising one means the market is working and a stuck one means
     * something is not.
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
