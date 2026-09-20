package com.bulk.trade.publicapi.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

/**
 * The handful of aggregates a visitor sees before signing in.
 *
 * <p>Written as SQL rather than composed from the module mappers because these
 * are counts and sums over whole tables, not entity reads. Loading every order
 * to add up its amount would be the same number computed the expensive way.
 *
 * <p>Each is a separate query. Six small aggregates over indexed columns is
 * cheaper than the joins it would take to return them in one row, and each one
 * stays legible on its own.
 */
@Mapper
public interface PublicStatsMapper {

    /** Enterprises that have passed review — a pending application is not a member. */
    @Select("""
            SELECT COUNT(*) FROM t_enterprise
             WHERE deleted = 0 AND status = 1
            """)
    long countApprovedEnterprises();

    /** Open offers, both directions. */
    @Select("""
            SELECT COUNT(*) FROM t_listing
             WHERE deleted = 0 AND status IN ('OPEN', 'PARTIALLY_FILLED')
            """)
    long countOpenListings();

    /**
     * Deals that are actually deals.
     *
     * <p>Cancelled orders are excluded because they were undone, and orders
     * awaiting a lister's answer are excluded because they are still a
     * question. Everything else is a commitment both parties have made, which
     * is what a visitor reading "cumulative traded volume" is being told.
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
     * Everything the platform is holding for its members.
     *
     * <p>An aggregate, never a breakdown: the market feed already publishes
     * total inventory as a series, and a per-enterprise figure would be a
     * member's position rather than the market's size.
     */
    @Select("""
            SELECT COALESCE(SUM(total_quantity), 0) FROM t_inventory_note
             WHERE deleted = 0
            """)
    BigDecimal inventoryQuantity();
}
