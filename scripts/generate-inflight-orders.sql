-- =============================================================================
-- In-flight demo orders: one order in every state the lifecycle can be in.
--
-- scripts/generate-demo-data.sql writes 51 orders and every one of them is
-- COMPLETED. That was deliberate — it exists to give the price chart a history
-- to draw — but the side effect is that the middle of the order lifecycle is
-- empty on a fresh development database. The 「进行中」 tab of the order-status
-- filter therefore shows nothing, and the task list ("who owes the next move")
-- is empty for every account, because every pending task is derived from an
-- order that is still in flight. An empty screen is indistinguishable from a
-- broken feature, and a reviewer cannot tell which one they are looking at.
--
-- This script fills that gap. It creates orders at every status —
-- PENDING_CONFIRM, CONFIRMED, CONTRACTED, DELIVERING and CANCELLED — together
-- with whatever each status requires to be consistent:
--
--   PENDING_CONFIRM  a MANUAL SELL listing with goods actually frozen behind
--                    it, and a live confirm_deadline on the order
--   CONFIRMED        the order alone (nothing has been drafted yet)
--   CONTRACTED       a SIGNED contract, signed by both sides
--   DELIVERING       the same, with delivery started
--   CANCELLED        a cancelled_at and a reason a person can read
--
-- Every order also gets its full transition trail in t_order_status_log, with
-- the operators and reasons the services themselves would have written, because
-- that trail is what the order detail page shows.
--
-- Two properties the script deliberately keeps:
--
--   * It only inserts. No existing row is updated or deleted, so it composes
--     with generate-demo-data.sql and can be re-run against a live scratch
--     database without disturbing anything already there.
--   * The goods and money behind each generated order are visible in the data:
--     a PENDING_CONFIRM order really can be confirmed through the UI (the
--     freeze it points at is FROZEN and owned by the right enterprise), and a
--     CONTRACTED order really does have a signed contract behind it.
--
-- Idempotent: the guard below detects this script's own output and does nothing
-- on a second run.
--
-- Deliberately not a Flyway migration: demo rows are not schema, and a
-- production deployment should be able to skip them.
--
-- Run (the --default-character-set flag matters; see the note further down):
--
--   docker exec -i spotlink-mysql mysql --default-character-set=utf8mb4 -ubulk -pbulk_trade_2026 bulk_trade < scripts/generate-inflight-orders.sql
--
-- Prerequisites: the V1-V8 schema, and the enterprises created by
-- DevelopmentDataInitializer (seller01 / buyer01) plus the six ENT2026DEMO
-- enterprises from scripts/generate-demo-data.sql. The orders are spread over
-- both, so that more than one login has something waiting for it.
-- =============================================================================

-- The rows below are full of Chinese names, and the mysql client takes its
-- connection character set from the OS locale, which is latin1 in this
-- container. Without this line the UTF-8 bytes in this file are stored
-- double-encoded and every Chinese name in the console turns to mojibake.
SET NAMES utf8mb4;

-- MySQL has no anonymous DO $$ ... $$ blocks, so the body is a stored procedure
-- this file creates, calls and drops in one pass. DELIMITER is a client-side
-- directive that the mysql client honours when reading from a redirect, so the
-- command in the header still works as written.
DELIMITER $$

DROP PROCEDURE IF EXISTS generate_inflight_orders$$
CREATE PROCEDURE generate_inflight_orders()
inflight_gen: BEGIN
    -- One counter per table rather than one shared counter, because the ids have
    -- to be stable for the guard to be meaningful and are far easier to read
    -- when 6100000000000000021 is obviously order #21.
    --
    -- The 6.1e18..6.6e18 block is reserved for this script. It is disjoint from
    -- generate-demo-data.sql's 5.0e18 block and from real snowflakes, which are
    -- around 2.1e18 and positive. (The demo script stamps its status-log ids
    -- negative for the same reason: an offset added on top of a snowflake-range
    -- id overflows bigint, and there is no room above them.)
    DECLARE v_order_base    BIGINT DEFAULT 6100000000000000000;
    DECLARE v_log_base      BIGINT DEFAULT 6200000000000000000;
    DECLARE v_listing_base  BIGINT DEFAULT 6300000000000000000;
    DECLARE v_freeze_base   BIGINT DEFAULT 6400000000000000000;
    DECLARE v_note_base     BIGINT DEFAULT 6500000000000000000;
    DECLARE v_contract_base BIGINT DEFAULT 6600000000000000000;

    DECLARE v_existing  INT DEFAULT 0;
    DECLARE v_anchors   INT DEFAULT 0;
    DECLARE v_msg       VARCHAR(256) DEFAULT '';

    -- ---------------------------------------------------------------
    -- Idempotency. Any one of this script's rows means a previous run got
    -- through, so there is nothing to add. Checked before anything is written,
    -- which is what makes a second run a no-op rather than a duplicate-key
    -- error halfway through.
    -- ---------------------------------------------------------------
    SELECT (SELECT COUNT(*) FROM t_order WHERE order_no LIKE 'OR2026INFL%')
         + (SELECT COUNT(*) FROM t_listing WHERE listing_no LIKE 'LS2026INFL%')
         + (SELECT COUNT(*) FROM t_contract WHERE contract_no LIKE 'CT2026INFL%')
      INTO v_existing;
    IF v_existing > 0 THEN
        SELECT '待确认/在途演示订单已存在，本次不做任何改动。' AS result;
        LEAVE inflight_gen;
    END IF;

    -- ---------------------------------------------------------------
    -- Anchors. The orders are spread across the two seeded accounts people log
    -- in with and the six demo enterprises. If either half is missing, the
    -- script would produce a lopsided dataset that silently fails to show
    -- pending work in the accounts being demonstrated, so it stops instead.
    -- ---------------------------------------------------------------
    SELECT COUNT(*) INTO v_anchors
      FROM t_enterprise
     WHERE enterprise_code IN ('ENT20260920001', 'ENT20260920002',
                               'ENT2026DEMO0001', 'ENT2026DEMO0002', 'ENT2026DEMO0003',
                               'ENT2026DEMO0004', 'ENT2026DEMO0005', 'ENT2026DEMO0006');
    IF v_anchors < 8 THEN
        SET v_msg = CONCAT('缺少演示企业（需要 8 家，实际 ', v_anchors,
                           ' 家）。请先运行 scripts/generate-demo-data.sql 并启动过后端。');
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = v_msg;
    END IF;

    -- ---------------------------------------------------------------
    -- The plan: one row per order to generate. Everything downstream reads it,
    -- so the shape of the dataset can be read in one place instead of being
    -- reconstructed from six INSERT statements.
    --
    --   status        the status the order ends up in
    --   buyer_code /  who the two parties are, by business code rather than id,
    --   seller_code   because the ids are snowflakes that differ per database
    --   price         kept in line with generate-demo-data.sql's own ratios
    --                 (copper x1.00, aluminium x0.36, zinc x0.39, lithium x2.00)
    --                 so these rows do not bend the price chart into a spike
    --   age_days      how long ago the order was placed
    --   listing_kind  ACTIVE  — a MANUAL listing still waiting for an answer
    --                 LAPSED  — the answer never came; listing is open again
    --                 NULL    — no listing (accepted under AUTO)
    --   listing_extra goods left on the listing after this order took its part
    --   cancel_from / how and by whom a CANCELLED order was cancelled
    --   cancel_operator / cancel_reason
    -- ---------------------------------------------------------------
    DROP TEMPORARY TABLE IF EXISTS tmp_inflight_plan;
    CREATE TEMPORARY TABLE tmp_inflight_plan (
        seq            INT PRIMARY KEY,
        status         VARCHAR(24)    NOT NULL,
        buyer_code     VARCHAR(32)    NOT NULL,
        seller_code    VARCHAR(32)    NOT NULL,
        category_id    BIGINT         NOT NULL,
        commodity_name VARCHAR(128)   NOT NULL,
        brand          VARCHAR(64),
        origin         VARCHAR(64),
        warehouse_id   BIGINT         NOT NULL,
        quantity       DECIMAL(18,3)  NOT NULL,
        price          DECIMAL(19,4)  NOT NULL,
        age_days       INT            NOT NULL,
        listing_kind   VARCHAR(8),
        listing_extra  DECIMAL(18,3)  NOT NULL DEFAULT 0,
        cancel_from    VARCHAR(24),
        cancel_operator VARCHAR(8),
        cancel_reason  VARCHAR(256),

        -- filled in below rather than written out 28 times
        buyer_id           BIGINT,
        seller_id          BIGINT,
        buyer_operator     VARCHAR(64),
        buyer_operator_id  BIGINT,
        seller_operator    VARCHAR(64),
        seller_operator_id BIGINT,
        delivery_method    VARCHAR(16)   NOT NULL DEFAULT 'SELF_PICKUP',
        amount             DECIMAL(19,4),
        listing_quantity   DECIMAL(18,3),
        created_at         DATETIME(6),
        confirmed_at       DATETIME(6),
        contracted_at      DATETIME(6),
        delivering_at      DATETIME(6),
        cancelled_at       DATETIME(6),
        confirm_deadline   DATETIME(6),
        updated_at         DATETIME(6)
    );

    INSERT INTO tmp_inflight_plan
        (seq, status, buyer_code, seller_code, category_id, commodity_name, brand, origin,
         warehouse_id, quantity, price, age_days, listing_kind, listing_extra,
         cancel_from, cancel_operator, cancel_reason)
    VALUES
        -- ---- PENDING_CONFIRM ------------------------------------------------
        -- The lister published a MANUAL SELL listing, somebody accepted it, and
        -- the lister has not answered. The seller is therefore the lister, and
        -- the buyer is whoever pressed 摘牌. seller01's company is the seller on
        -- four of the six so that account has real work waiting on it.
        (1,  'PENDING_CONFIRM', 'ENT20260920002',   'ENT20260920001',   1002, '电解铜', '江铜', '江西', 2001,  30.000, 68500.0000, 1, 'ACTIVE', 20.000, NULL, NULL, NULL),
        (2,  'PENDING_CONFIRM', 'ENT2026DEMO0002',  'ENT20260920001',   1003, '铝锭',   '云铝', '云南', 2001,  25.000, 24500.0000, 1, 'ACTIVE', 20.000, NULL, NULL, NULL),
        (3,  'PENDING_CONFIRM', 'ENT2026DEMO0004',  'ENT20260920001',   1004, '锌锭',   '葫锌', '辽宁', 2002,  20.000, 26600.0000, 2, 'ACTIVE',  0.000, NULL, NULL, NULL),
        (4,  'PENDING_CONFIRM', 'ENT20260920002',   'ENT20260920001',   1006, '碳酸锂', '赣锋', '江西', 2002,  12.000, 136000.0000, 1, 'ACTIVE', 20.000, NULL, NULL, NULL),
        (5,  'PENDING_CONFIRM', 'ENT20260920002',   'ENT2026DEMO0001',  1002, '电解铜', '国产', '浙江', 2001,  18.000, 68100.0000, 2, 'ACTIVE',  0.000, NULL, NULL, NULL),
        (6,  'PENDING_CONFIRM', 'ENT20260920001',   'ENT2026DEMO0003',  1003, '铝锭',   '云铝', '云南', 2001,  22.000, 24420.0000, 1, 'ACTIVE',  0.000, NULL, NULL, NULL),

        -- ---- CONFIRMED ------------------------------------------------------
        -- Accepted under AUTO, so the goods moved at acceptance and the order is
        -- simply waiting for a contract to be drafted. No contract row: that is
        -- the state the CONTRACT_TO_DRAFT task is derived from, and giving them
        -- one would delete the task.
        (7,  'CONFIRMED', 'ENT20260920002',  'ENT2026DEMO0001', 1002, '电解铜', '江铜', '江西', 2001, 40.000, 67800.0000,  4, NULL, 0, NULL, NULL, NULL),
        (8,  'CONFIRMED', 'ENT20260920002',  'ENT2026DEMO0003', 1003, '铝锭',   '云铝', '云南', 2001, 35.000, 24560.0000,  5, NULL, 0, NULL, NULL, NULL),
        (9,  'CONFIRMED', 'ENT20260920002',  'ENT2026DEMO0005', 1006, '碳酸锂', '赣锋', '江西', 2002, 15.000, 135600.0000, 6, NULL, 0, NULL, NULL, NULL),
        (10, 'CONFIRMED', 'ENT20260920001',  'ENT2026DEMO0002', 1004, '锌锭',   '葫锌', '辽宁', 2002, 25.000, 26480.0000,  7, NULL, 0, NULL, NULL, NULL),
        (11, 'CONFIRMED', 'ENT2026DEMO0002', 'ENT20260920001',  1002, '电解铜', '国产', '浙江', 2001, 30.000, 68200.0000,  8, NULL, 0, NULL, NULL, NULL),
        (12, 'CONFIRMED', 'ENT2026DEMO0006', 'ENT20260920001',  1003, '铝锭',   '云铝', '云南', 2001, 28.000, 24450.0000,  9, NULL, 0, NULL, NULL, NULL),

        -- ---- CONTRACTED -----------------------------------------------------
        -- Both signatures on the contract, delivery not started. Either party
        -- can start it, which is what the DELIVERY_TO_START task says.
        (13, 'CONTRACTED', 'ENT20260920002',   'ENT2026DEMO0002', 1002, '电解铜', '江铜', '江西', 2001, 50.000, 67400.0000,  11, NULL, 0, NULL, NULL, NULL),
        (14, 'CONTRACTED', 'ENT20260920002',   'ENT2026DEMO0004', 1006, '碳酸锂', '赣锋', '江西', 2002, 20.000, 136800.0000, 13, NULL, 0, NULL, NULL, NULL),
        (15, 'CONTRACTED', 'ENT20260920002',   'ENT20260920001',  1004, '锌锭',   '葫锌', '辽宁', 2002, 30.000, 26700.0000,  15, NULL, 0, NULL, NULL, NULL),
        (16, 'CONTRACTED', 'ENT20260920001',   'ENT2026DEMO0005', 1003, '铝锭',   '云铝', '云南', 2001, 32.000, 24380.0000,  16, NULL, 0, NULL, NULL, NULL),
        (17, 'CONTRACTED', 'ENT2026DEMO0001',  'ENT20260920002',  1002, '电解铜', '国产', '浙江', 2001, 45.000, 67600.0000,  17, NULL, 0, NULL, NULL, NULL),
        (18, 'CONTRACTED', 'ENT2026DEMO0003',  'ENT20260920002',  1006, '碳酸锂', '赣锋', '江西', 2002, 18.000, 135200.0000, 18, NULL, 0, NULL, NULL, NULL),

        -- ---- DELIVERING -----------------------------------------------------
        (19, 'DELIVERING', 'ENT20260920002',   'ENT2026DEMO0001', 1003, '铝锭',   '云铝', '云南', 2001, 42.000, 24500.0000,  19, NULL, 0, NULL, NULL, NULL),
        (20, 'DELIVERING', 'ENT20260920002',   'ENT2026DEMO0006', 1002, '电解铜', '江铜', '江西', 2001, 38.000, 67200.0000,  21, NULL, 0, NULL, NULL, NULL),
        (21, 'DELIVERING', 'ENT20260920001',   'ENT2026DEMO0003', 1004, '锌锭',   '葫锌', '辽宁', 2002, 26.000, 26520.0000,  22, NULL, 0, NULL, NULL, NULL),
        (22, 'DELIVERING', 'ENT2026DEMO0004',  'ENT20260920001',  1006, '碳酸锂', '赣锋', '江西', 2002, 14.000, 137200.0000, 23, NULL, 0, NULL, NULL, NULL),
        (23, 'DELIVERING', 'ENT2026DEMO0002',  'ENT20260920002',  1003, '铝锭',   '云铝', '云南', 2001, 33.000, 24600.0000,  24, NULL, 0, NULL, NULL, NULL),
        (24, 'DELIVERING', 'ENT2026DEMO0006',  'ENT20260920002',  1002, '电解铜', '江铜', '江西', 2001, 24.000, 67900.0000,  26, NULL, 0, NULL, NULL, NULL),

        -- ---- CANCELLED ------------------------------------------------------
        -- Three called off by a person, one that lapsed because the lister never
        -- answered. The last one keeps its listing: the acceptance expired, the
        -- goods went back on offer, and the listing is open again with its
        -- original quantity — which is exactly what the sweep does.
        (25, 'CANCELLED', 'ENT20260920002',   'ENT2026DEMO0001', 1002, '电解铜', '国产', '浙江', 2001, 20.000, 68000.0000,   6, NULL,      0.000, 'CONFIRMED',       'BUYER',  '买方资金安排调整，双方协商一致取消'),
        (26, 'CANCELLED', 'ENT2026DEMO0005',  'ENT20260920001',  1006, '碳酸锂', '赣锋', '江西', 2002, 10.000, 136400.0000,  3, NULL,      0.000, 'CONFIRMED',       'BUYER',  '下游订单取消，双方协商解除'),
        (27, 'CANCELLED', 'ENT2026DEMO0002',  'ENT2026DEMO0004', 1003, '铝锭',   '云铝', '云南', 2001, 16.000, 24480.0000,   12, NULL,     0.000, 'CONFIRMED',       'SELLER', '过磅规格与约定不符，卖方提出解除'),
        (28, 'CANCELLED', 'ENT20260920001',   'ENT2026DEMO0006', 1004, '锌锭',   '葫锌', '辽宁', 2002, 12.000, 26600.0000,    9, 'LAPSED', 25.000, 'PENDING_CONFIRM', 'SYSTEM', '挂牌方未在确认期限内答复，摘牌自动失效');

    -- ---------------------------------------------------------------
    -- Resolve the parties, and the name to sign their log rows with.
    --
    -- The services log the caller's login name when there is one and nothing
    -- else, so a demo enterprise gets its contact name and the seeded accounts
    -- get the username the log would really carry ('seller01', 'buyer01').
    -- ---------------------------------------------------------------
    UPDATE tmp_inflight_plan p
      JOIN t_enterprise b  ON b.enterprise_code = p.buyer_code
      JOIN t_enterprise s  ON s.enterprise_code = p.seller_code
      LEFT JOIN t_user bu  ON bu.enterprise_id = b.id
      LEFT JOIN t_user su  ON su.enterprise_id = s.id
       SET p.buyer_id           = b.id,
           p.seller_id          = s.id,
           p.buyer_operator     = COALESCE(bu.username, b.contact_name, b.short_name),
           p.buyer_operator_id  = bu.id,
           p.seller_operator    = COALESCE(su.username, s.contact_name, s.short_name),
           p.seller_operator_id = su.id;

    -- ---------------------------------------------------------------
    -- Derived figures and the timeline.
    --
    -- created_at spread over the past weeks so the order list has a shape and
    -- the price chart's recent window is not a single spike; the later
    -- timestamps follow from it, so every row's history reads in order.
    --
    -- The confirm_deadline is days away, not hours: the trading sweep cancels
    -- any acceptance past its deadline, and a demo dataset whose pending work
    -- deletes itself overnight is worse than none. It stays inside the
    -- listing's validity, as OrderService.answerDeadlineFor requires.
    -- ---------------------------------------------------------------
    UPDATE tmp_inflight_plan
       SET amount           = ROUND(price * quantity, 4),
           listing_quantity = quantity + listing_extra,
           delivery_method  = IF(seq % 3 = 0, 'DELIVERED', 'SELF_PICKUP'),
           created_at       = NOW(6) - INTERVAL age_days DAY
                                       - INTERVAL ((seq * 37) % 420) MINUTE;

    UPDATE tmp_inflight_plan
       SET confirmed_at = CASE
               WHEN status = 'PENDING_CONFIRM' THEN NULL
               WHEN status = 'CANCELLED' AND cancel_from = 'PENDING_CONFIRM' THEN NULL
               ELSE created_at + INTERVAL 2 HOUR END,
           contracted_at = IF(status IN ('CONTRACTED', 'DELIVERING'),
                              created_at + INTERVAL 26 HOUR, NULL),
           delivering_at = IF(status = 'DELIVERING',
                              created_at + INTERVAL 74 HOUR, NULL),
           cancelled_at  = IF(status = 'CANCELLED',
                              created_at + INTERVAL 48 HOUR, NULL),
           confirm_deadline = IF(listing_kind = 'ACTIVE',
                                 NOW(6) + INTERVAL (5 + seq % 3) DAY, NULL);

    UPDATE tmp_inflight_plan
       SET updated_at = COALESCE(cancelled_at, delivering_at, contracted_at,
                                 confirmed_at, created_at);

    -- Everything from here on is permanent, so it goes in one transaction:
    -- either the whole dataset lands or none of it does, and a failure halfway
    -- through does not leave a half-filled lifecycle behind the guard.
    START TRANSACTION;

    -- ---------------------------------------------------------------
    -- Behind every PENDING_CONFIRM order: goods that are really reserved.
    --
    -- This mirrors what publishing a SELL listing does — a note is frozen and
    -- the freeze is what the confirmation later spends — so the order can be
    -- confirmed in the UI and the goods will move to the buyer as they would
    -- for an order placed through the app.
    --
    -- Goods are added to the seller's stock rather than taken from its existing
    -- notes: this script must not modify rows it did not create.
    -- ---------------------------------------------------------------
    INSERT INTO t_inventory_note
        (id, note_no, enterprise_id, category_id, warehouse_id, commodity_name,
         brand, origin, spec, total_quantity, available_quantity, frozen_quantity,
         unit, production_date, status, version, remark, created_at, updated_at)
    SELECT v_note_base + p.seq,
           CONCAT('IN2026INFL', LPAD(p.seq, 5, '0')),
           p.seller_id, p.category_id, p.warehouse_id, p.commodity_name,
           p.brand, p.origin, '{}',
           p.listing_quantity + 20,
           p.listing_quantity + 20 - IF(p.listing_kind = 'ACTIVE', p.listing_quantity, 0),
           IF(p.listing_kind = 'ACTIVE', p.listing_quantity, 0),
           '吨', DATE(p.created_at) - INTERVAL 15 DAY,
           -- 4 = partially frozen while the listing is waiting, 2 = back in
           -- stock once a lapsed acceptance released it (see FreezeService).
           IF(p.listing_kind = 'ACTIVE', 4, 2),
           0,
           IF(p.listing_kind = 'ACTIVE', '演示库存：挂牌冻结', '演示库存：摘牌逾期失效，冻结已释放'),
           p.created_at - INTERVAL (2 + p.seq % 2) DAY,
           p.updated_at
      FROM tmp_inflight_plan p
     WHERE p.listing_kind IS NOT NULL;

    INSERT INTO t_freeze_record
        (id, freeze_no, enterprise_id, entity_type, entity_id, quantity, amount,
         biz_type, biz_id, status, reason, released_at, created_at, updated_at)
    SELECT v_freeze_base + p.seq,
           CONCAT('FZ2026INFL', LPAD(p.seq, 5, '0')),
           p.seller_id, 'INVENTORY', v_note_base + p.seq, p.listing_quantity, NULL,
           -- biz_id is NULL exactly as ListingService writes it: the freeze is
           -- created before the listing it backs exists.
           'LISTING', NULL,
           IF(p.listing_kind = 'ACTIVE', 'FROZEN', 'RELEASED'),
           IF(p.listing_kind = 'ACTIVE', '挂牌冻结', '摘牌逾期，冻结释放'),
           IF(p.listing_kind = 'ACTIVE', NULL, p.cancelled_at),
           p.created_at - INTERVAL (2 + p.seq % 2) DAY,
           p.updated_at
      FROM tmp_inflight_plan p
     WHERE p.listing_kind IS NOT NULL;

    -- ---------------------------------------------------------------
    -- The listings the pending acceptances came from.
    --
    -- All SELL and all MANUAL: the database refuses MANUAL on a BUY listing
    -- (ck_listing_manual_needs_frozen_goods) because a deferred acceptance
    -- would promise goods nobody reserved, and only a SELL listing is frozen
    -- at publication. remaining_quantity is what the acceptance left behind,
    -- which is why some read FILLED and some PARTIALLY_FILLED.
    -- ---------------------------------------------------------------
    INSERT INTO t_listing
        (id, listing_no, enterprise_id, side, category_id, commodity_name, brand,
         origin, spec, quantity, remaining_quantity, unit, price, price_type,
         warehouse_id, delivery_method, payment_terms, freeze_id, valid_until,
         status, confirm_mode, version, remark, created_at, updated_at)
    SELECT v_listing_base + p.seq,
           CONCAT('LS2026INFL', LPAD(p.seq, 5, '0')),
           p.seller_id, 'SELL', p.category_id, p.commodity_name, p.brand,
           p.origin, '{}', p.listing_quantity,
           IF(p.listing_kind = 'ACTIVE', p.listing_extra, p.listing_quantity),
           '吨', p.price, 'FIXED',
           p.warehouse_id, 'SELF_PICKUP', 'MARGIN_THEN_BALANCE',
           v_freeze_base + p.seq,
           NOW(6) + INTERVAL 12 DAY,
           CASE WHEN p.listing_kind = 'LAPSED' THEN 'OPEN'
                WHEN p.listing_extra = 0 THEN 'FILLED'
                ELSE 'PARTIALLY_FILLED' END,
           'MANUAL', 0,
           IF(p.listing_kind = 'ACTIVE', '演示挂牌：摘牌待确认', '演示挂牌：摘牌逾期后重新开放'),
           p.created_at - INTERVAL (2 + p.seq % 2) DAY,
           p.updated_at
      FROM tmp_inflight_plan p
     WHERE p.listing_kind IS NOT NULL;

    -- ---------------------------------------------------------------
    -- The orders themselves.
    --
    -- goods_freeze_id is NULL on a waiting acceptance for the same reason the
    -- service leaves it NULL: nothing has moved yet, and setting it would make
    -- the confirmation look like a second transfer of the same goods.
    -- ---------------------------------------------------------------
    INSERT INTO t_order
        (id, order_no, listing_id, buyer_id, seller_id, category_id, commodity_name,
         spec, quantity, unit, price, amount, warehouse_id, delivery_method,
         payment_terms, goods_freeze_id, margin_freeze_id, status, contract_id,
         confirmed_at, cancelled_at, cancel_reason, confirm_deadline, version,
         remark, created_at, updated_at)
    SELECT v_order_base + p.seq,
           CONCAT('OR2026INFL', LPAD(p.seq, 5, '0')),
           IF(p.listing_kind IS NULL, NULL, v_listing_base + p.seq),
           p.buyer_id, p.seller_id, p.category_id, p.commodity_name,
           '{}', p.quantity, '吨', p.price, p.amount, p.warehouse_id, p.delivery_method,
           'MARGIN_THEN_BALANCE', NULL, NULL, p.status,
           IF(p.status IN ('CONTRACTED', 'DELIVERING'), v_contract_base + p.seq, NULL),
           p.confirmed_at, p.cancelled_at, p.cancel_reason, p.confirm_deadline, 0,
           '演示数据：在途订单',
           p.created_at, p.updated_at
      FROM tmp_inflight_plan p;

    -- ---------------------------------------------------------------
    -- Contracts behind the CONTRACTED and DELIVERING orders.
    --
    -- SIGNED requires both signatures (ck_contract_signed_complete); one
    -- signature is not a contract, and an order cannot reach CONTRACTED without
    -- a fully signed one behind it. Terms mirror what ContractService writes —
    -- snapshot values as strings, the same keys, the same clauses — so the
    -- contract page renders a document rather than an empty JSON object.
    -- ---------------------------------------------------------------
    INSERT INTO t_contract
        (id, contract_no, order_id, buyer_id, seller_id, title, terms, quantity,
         unit, price, amount, weight_tolerance, status, buyer_signed_at,
         buyer_signed_by, seller_signed_at, seller_signed_by, created_at, updated_at)
    SELECT v_contract_base + p.seq,
           CONCAT('CT2026INFL', LPAD(p.seq, 5, '0')),
           v_order_base + p.seq, p.buyer_id, p.seller_id,
           CONCAT(p.commodity_name, ' ',
                  TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST(p.quantity AS CHAR))),
                  ' 吨（金额 ',
                  TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST(p.amount AS CHAR))),
                  ' 元）购销合同'),
           JSON_OBJECT(
               'commodityName', p.commodity_name,
               'quantity', TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST(p.quantity AS CHAR))),
               'unit', '吨',
               'price', TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST(p.price AS CHAR))),
               'amount', TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST(p.amount AS CHAR))),
               'deliveryMethod', IF(p.delivery_method = 'DELIVERED', '送到', '自提'),
               'paymentTerms', 'MARGIN_THEN_BALANCE',
               'weightTolerance', '3.00%',
               'settlementBasis', '结算重量以实际过磅重量为准，磅差在约定范围内按实际重量结算，超出范围时由双方协商处理，系统不自动结算。',
               'qualityDispute', '买方应在收货后 7 日内提出质量异议，逾期视为验收合格。',
               'disputeResolution', '争议由双方协商解决，协商不成提交平台所在地法院管辖。'),
           p.quantity, '吨', p.price, p.amount, 3.00, 'SIGNED',
           p.contracted_at, p.buyer_operator_id,
           p.contracted_at, p.seller_operator_id,
           p.confirmed_at, p.contracted_at
      FROM tmp_inflight_plan p
     WHERE p.status IN ('CONTRACTED', 'DELIVERING');

    -- ---------------------------------------------------------------
    -- The transition trail. One row per move the order actually made, in the
    -- order it made them, with the party who made it and a reason — which is
    -- the whole point of the table: a status says where an order is, the log
    -- says how it got there and who moved it.
    -- ---------------------------------------------------------------

    -- 1. Acceptance. A MANUAL listing leaves the order waiting for the lister;
    --    an AUTO one is already a deal, so the same act reads differently.
    INSERT INTO t_order_status_log
        (id, order_id, from_status, to_status, operator_id, operator, reason, created_at)
    SELECT v_log_base + p.seq * 10 + 1, v_order_base + p.seq, NULL,
           IF(p.listing_kind IS NOT NULL, 'PENDING_CONFIRM', 'CONFIRMED'),
           p.buyer_operator_id, p.buyer_operator,
           IF(p.listing_kind IS NOT NULL, '摘牌，待挂牌方确认', '摘牌成交'),
           p.created_at
      FROM tmp_inflight_plan p;

    -- 2. Both signatures landed, so the contract took effect and the order
    --    advanced in the same breath.
    INSERT INTO t_order_status_log
        (id, order_id, from_status, to_status, operator_id, operator, reason, created_at)
    SELECT v_log_base + p.seq * 10 + 2, v_order_base + p.seq,
           'CONFIRMED', 'CONTRACTED', p.buyer_operator_id, p.buyer_operator,
           '合同签署生效', p.contracted_at
      FROM tmp_inflight_plan p
     WHERE p.status IN ('CONTRACTED', 'DELIVERING');

    -- 3. Delivery started. Either party may start it, so this one is signed by
    --    the other side from the one that signed the contract.
    INSERT INTO t_order_status_log
        (id, order_id, from_status, to_status, operator_id, operator, reason, created_at)
    SELECT v_log_base + p.seq * 10 + 3, v_order_base + p.seq,
           'CONTRACTED', 'DELIVERING', p.seller_operator_id, p.seller_operator,
           '开始交收', p.delivering_at
      FROM tmp_inflight_plan p
     WHERE p.status = 'DELIVERING';

    -- 4. Cancellations. The lapsed one is attributed to 'system', the way the
    --    sweep signs its own work: crediting a person for a timeout nobody
    --    performed would be a lie in the record kept for disputes.
    INSERT INTO t_order_status_log
        (id, order_id, from_status, to_status, operator_id, operator, reason, created_at)
    SELECT v_log_base + p.seq * 10 + 4, v_order_base + p.seq,
           p.cancel_from, 'CANCELLED',
           CASE p.cancel_operator WHEN 'BUYER'  THEN p.buyer_operator_id
                                  WHEN 'SELLER' THEN p.seller_operator_id
                                  ELSE NULL END,
           CASE p.cancel_operator WHEN 'BUYER'  THEN p.buyer_operator
                                  WHEN 'SELLER' THEN p.seller_operator
                                  ELSE 'system' END,
           p.cancel_reason,
           p.cancelled_at
      FROM tmp_inflight_plan p
     WHERE p.status = 'CANCELLED';

    COMMIT;

    DROP TEMPORARY TABLE IF EXISTS tmp_inflight_plan;

    SELECT '在途演示订单已生成。' AS message;
END$$
DELIMITER ;

CALL generate_inflight_orders();

DROP PROCEDURE generate_inflight_orders;

-- Summary. Same shape as generate-demo-data.sql's, so the two read alike.
SELECT CONCAT('订单 ', status) AS 项目, CAST(COUNT(*) AS CHAR) AS 数量
    FROM t_order WHERE order_no LIKE 'OR2026INFL%' GROUP BY status
UNION ALL SELECT '挂牌（MANUAL）',   CAST(COUNT(*) AS CHAR) FROM t_listing   WHERE listing_no  LIKE 'LS2026INFL%'
UNION ALL SELECT '合同（已签署）',   CAST(COUNT(*) AS CHAR) FROM t_contract  WHERE contract_no LIKE 'CT2026INFL%'
UNION ALL SELECT '状态流水',         CAST(COUNT(*) AS CHAR) FROM t_order_status_log
    WHERE order_id BETWEEN 6100000000000000001 AND 6100000000000000028
UNION ALL SELECT '冻结记录',         CAST(COUNT(*) AS CHAR) FROM t_freeze_record WHERE freeze_no LIKE 'FZ2026INFL%'
UNION ALL SELECT '新增库存单',       CAST(COUNT(*) AS CHAR) FROM t_inventory_note WHERE note_no LIKE 'IN2026INFL%'
ORDER BY 1;
