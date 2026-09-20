-- =============================================================================
-- V8 Listing confirmation mode: who agrees, and when the goods move.
--
-- 挂牌交易 has two shapes on the market, and they differ in exactly one place:
-- whether publishing the listing is already the lister's consent.
--
--   AUTO   (摘牌即成交) — the listing is an offer (要约). Accepting it is an
--          acceptance (承诺). A contract exists the moment the acceptance takes
--          effect, so the goods move in the same transaction and the lister
--          has no second veto.
--
--   MANUAL (摘牌待确认) — the listing is an invitation to treat (要约邀请).
--          Accepting it reserves the goods and puts the question to the lister.
--          Only their answer forms a contract, and only then do goods move.
--
-- Modelled per listing rather than as a platform-wide setting because it is a
-- property of the offer: the same seller may post one listing at a firm price
-- and another that waits for their sign-off.
-- =============================================================================

ALTER TABLE t_listing
    ADD COLUMN confirm_mode VARCHAR(8) NOT NULL DEFAULT 'AUTO';

ALTER TABLE t_listing
    ADD CONSTRAINT ck_listing_confirm_mode
        CHECK (confirm_mode IN ('AUTO', 'MANUAL'));

-- MANUAL is only sound where goods are already reserved behind the offer, which
-- is true of a SELL listing (frozen at publication) and false of a BUY listing
-- (nothing is set aside until someone accepts). Deferring a BUY acceptance
-- would promise goods nobody has reserved, so the database refuses the shape
-- rather than leaving it to a service-level check someone can forget.
ALTER TABLE t_listing
    ADD CONSTRAINT ck_listing_manual_needs_frozen_goods
        CHECK (confirm_mode = 'AUTO' OR side = 'SELL');

-- -----------------------------------------------------------------------------
-- How long the lister has to answer before the acceptance lapses. Null on any
-- order that never waits, which is every order under AUTO.
-- -----------------------------------------------------------------------------
ALTER TABLE t_order
    ADD COLUMN confirm_deadline TIMESTAMPTZ;

-- The expiry sweep is the only reader, and it only ever looks at orders that
-- are still waiting. A partial index keeps it proportional to the backlog
-- rather than to the history of the platform.
CREATE INDEX idx_order_confirm_deadline ON t_order (confirm_deadline)
    WHERE deleted = 0 AND status = 'PENDING_CONFIRM';

-- -----------------------------------------------------------------------------
-- Existing PENDING_CONFIRM rows were created under the old semantics, where
-- goods moved at acceptance and this state was a redundant acknowledgement
-- nobody was obliged to answer. Left alone they would re-enter the new code
-- looking like "goods not moved yet", and a later confirmation would move them
-- a second time. Advancing them to CONFIRMED records the state they were
-- already in in substance; the log rows below say why the row changed without
-- anyone pressing a button.
-- -----------------------------------------------------------------------------
WITH migrated AS (
    UPDATE t_order
       SET status           = 'CONFIRMED',
           confirmed_at     = COALESCE(confirmed_at, updated_at, now()),
           confirm_deadline = NULL,
           updated_at       = now()
     WHERE status = 'PENDING_CONFIRM'
       AND deleted = 0
    RETURNING id
)
INSERT INTO t_order_status_log (id, order_id, from_status, to_status, operator, reason)
SELECT (extract(epoch FROM clock_timestamp()) * 1000000)::bigint + row_number() OVER (),
       id,
       'PENDING_CONFIRM',
       'CONFIRMED',
       'system',
       'V8 迁移：旧语义下摘牌时货权已转移，此状态为冗余确认，补记为已确认'
  FROM migrated;

COMMENT ON COLUMN t_listing.confirm_mode IS
    'AUTO: 摘牌即成交（挂牌是要约，摘牌是承诺）。MANUAL: 摘牌后待挂牌方确认，确认前货权不转移。';
COMMENT ON COLUMN t_order.confirm_deadline IS
    '挂牌方答复摘牌的截止时间；逾期由定时任务作废并解冻。仅 MANUAL 挂牌产生的订单有值。';
