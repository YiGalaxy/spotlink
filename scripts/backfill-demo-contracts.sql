-- =============================================================================
-- Gives the historical demo orders the contracts they should have had.
--
-- `generate-demo-data.sql` produces a few dozen COMPLETED orders whose purpose
-- is to give the price chart a history. It writes them with a final status and
-- goes no further — so those orders reach 已完成 without ever having a
-- contract, which the real flow cannot produce. Nothing fails; opening one in
-- the console shows a completed order beside a card saying no contract has been
-- drafted, which reads as a bug in the console rather than as a shortcut in the
-- data.
--
-- Idempotent: only orders with no contract_id are touched.
--
--   docker exec -i spotlink-mysql mysql --default-character-set=utf8mb4 \
--     -ubulk -pbulk_trade_2026 bulk_trade < scripts/backfill-demo-contracts.sql
-- =============================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------- contracts
-- Signed by both parties, because the orders they belong to are past signature:
-- COMPLETED, CONTRACTED and DELIVERING all sit downstream of a signed contract
-- in the real lifecycle, so a draft or a half-signed one would be the same
-- inconsistency in a different place.
--
-- Terms carry the three clauses the contract review looks for, so a reviewer
-- opening one of these sees a complete document rather than an empty shell.
INSERT INTO t_contract (
    id, contract_no, order_id, buyer_id, seller_id, title, terms,
    quantity, unit, price, amount, weight_tolerance,
    status, buyer_signed_at, buyer_signed_by, seller_signed_at, seller_signed_by,
    created_at, updated_at, deleted
)
SELECT
    -- Contract ids in their own reserved range, well clear of the snowflake
    -- values the application issues.
    7000000000000000000 + row_number() OVER (ORDER BY o.id),
    CONCAT('CT2026BF', LPAD(ROW_NUMBER() OVER (ORDER BY o.id), 6, '0')),
    o.id, o.buyer_id, o.seller_id,
    CONCAT(o.commodity_name, ' ', o.quantity, o.unit, '购销合同'),
    JSON_OBJECT(
        'settlementBasis', '结算重量以实际过磅重量为准；磅差在约定容差内按实际重量结算，超出部分由双方协商，系统不自动结算。',
        'qualityDispute', '买方应在收货后 7 日内提出质量异议，逾期视为验收合格。',
        'disputeResolution', '双方协商解决；协商不成的，提交平台所在地有管辖权的人民法院。'
    ),
    o.quantity, o.unit, o.price, o.amount, 3.0000,
    'SIGNED',
    -- Signed shortly after the order was confirmed, not at the same instant:
    -- two signatures with identical timestamps look machine-made, which is
    -- exactly what they are, and the point of demo data is not to look like it.
    DATE_ADD(COALESCE(o.confirmed_at, o.created_at), INTERVAL 20 MINUTE),
    o.buyer_id,
    DATE_ADD(COALESCE(o.confirmed_at, o.created_at), INTERVAL 95 MINUTE),
    o.seller_id,
    COALESCE(o.confirmed_at, o.created_at),
    COALESCE(o.confirmed_at, o.created_at),
    0
  FROM t_order o
 WHERE o.deleted = 0
   AND o.contract_id IS NULL
   AND o.status IN ('COMPLETED', 'CONTRACTED', 'DELIVERING');

-- ---------------------------------------------------------------- link them
-- Matched on order_id, which is unique per contract, so this cannot attach a
-- contract to the wrong order.
UPDATE t_order o
   JOIN t_contract c ON c.order_id = o.id AND c.deleted = 0
   SET o.contract_id = c.id
 WHERE o.deleted = 0
   AND o.contract_id IS NULL;

ANALYZE TABLE t_contract;
ANALYZE TABLE t_order;
