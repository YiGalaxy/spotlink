-- =============================================================================
-- Rich commodity catalogue: a large, varied demo dataset built by combining a
-- pool of ~100 base commodity descriptors into inventory, listings, orders,
-- contracts and freezes.
--
-- The two scripts that came before it each fill one narrow slice:
--
--   scripts/generate-demo-data.sql       51 COMPLETED trades for the price chart
--   scripts/generate-inflight-orders.sql 28 orders, one per lifecycle state
--
-- Neither produces a *catalogue*. Every listing there is the same handful of
-- commodities under one of four names, and the market page therefore looks the
-- same on every database. This script does the opposite: it declares a pool of
-- realistic descriptors (brand + grade + origin + spec + unit + price level for
-- the six tradable categories that exist) and then combines them at random into
-- several hundred inventory notes, listings and orders spread across every
-- enterprise, every category, every listing status and every order status.
--
-- The properties it deliberately keeps, because they are what make demo data
-- usable rather than merely present:
--
--   * The freeze trail is exact. A SELL listing freezes the seller's goods, so
--     every OPEN / PARTIALLY_FILLED SELL listing has a FROZEN t_freeze_record
--     behind it and its inventory note's frozen_quantity equals the sum of the
--     FROZEN freezes on that note. The released and consumed halves of the
--     trail are written too, which is what an expired or fully sold listing
--     leaves behind. Getting this wrong is the classic failure of demo data:
--     the numbers look plausible and the release path is untestable.
--   * Enterprise ids are resolved by enterprise_code, never hard-coded. They
--     are time-based snowflakes and differ on every rebuild.
--   * Every row carries whatever its state requires: contracts behind
--     CONTRACTED / DELIVERING / COMPLETED orders, a confirm_deadline on
--     PENDING_CONFIRM, cancelled_at plus a human-readable reason on CANCELLED,
--     and at least one t_order_status_log row on every order.
--   * It only inserts. No existing row is updated or deleted.
--   * Idempotent: the guard below detects this script's own rows and does
--     nothing on a second run.
--
-- Deliberately not a Flyway migration: demo rows are not schema, and a
-- production deployment should be able to skip them.
--
-- Run (the --default-character-set flag matters; see the note further down):
--
--   docker exec -i spotlink-mysql mysql --default-character-set=utf8mb4 -ubulk -pbulk_trade_2026 bulk_trade < scripts/generate-rich-catalogue.sql
--
-- Prerequisites: the V1-V8 schema, the enterprises and users created by
-- DevelopmentDataInitializer (ENT20260920001..3, seller01 / buyer01), and the
-- six ENT2026DEMO enterprises from scripts/generate-demo-data.sql. All nine are
-- used, so more than one login has something to look at.
-- =============================================================================

-- The rows below are full of Chinese names, and the mysql client takes its
-- connection character set from the OS locale, which is latin1 in this
-- container. Without this line the UTF-8 bytes in this file are stored
-- double-encoded and every Chinese name in the console turns to mojibake.
-- Check with HEX() on any one row if in doubt.
SET NAMES utf8mb4;

-- MySQL has no anonymous DO $$ ... $$ blocks, so the body is a stored procedure
-- this file creates, calls and drops in one pass. DELIMITER is a client-side
-- directive that the mysql client honours when reading from a redirect, so the
-- command in the header still works as written.
DELIMITER $$

DROP PROCEDURE IF EXISTS generate_rich_catalogue$$
CREATE PROCEDURE generate_rich_catalogue()
rich_gen: BEGIN
    -- -----------------------------------------------------------------
    -- Id ranges. Every id this script writes is a fixed base plus a small
    -- sequence number, so the dataset is byte-identical on every database and
    -- the guard below has something stable to look for.
    --
    --   7.1e18 notes          (generate-demo-data.sql owns 5.0e18,
    --   7.2e18 listings        generate-inflight-orders.sql owns 6.1e18-6.6e18,
    --   7.3e18 orders          real snowflakes are ~2.1e18 and positive, and
    --   7.4e18 status logs     nothing may sit above them: an offset added on
    --   7.5e18 contracts       top of a snowflake-range id overflows bigint)
    --   7.6e18 freezes
    -- -----------------------------------------------------------------
    DECLARE v_note_base     BIGINT DEFAULT 7100000000000000000;
    DECLARE v_listing_base  BIGINT DEFAULT 7200000000000000000;
    DECLARE v_order_base    BIGINT DEFAULT 7300000000000000000;
    DECLARE v_log_base      BIGINT DEFAULT 7400000000000000000;
    DECLARE v_contract_base BIGINT DEFAULT 7500000000000000000;
    DECLARE v_freeze_base   BIGINT DEFAULT 7600000000000000000;
    -- The repair at the bottom of this header block writes its own freezes;
    -- they live in a block of their own so they can never collide with the
    -- generated ones above.
    DECLARE v_repair_base   BIGINT DEFAULT 7700000000000000000;

    -- Shape of the dataset. Raising these is the only edit needed to make the
    -- catalogue bigger; the arithmetic below is all keyed off the sequence
    -- number, so nothing else depends on the counts.
    DECLARE v_notes      INT DEFAULT 400;   -- inventory notes, half of them backing a listing
    DECLARE v_listings   INT DEFAULT 300;   -- one per sequence number, SELL : BUY = 2 : 1
    DECLARE v_standalone INT DEFAULT 150;   -- orders with no listing behind them

    DECLARE v_existing    INT DEFAULT 0;
    DECLARE v_enterprises INT DEFAULT 0;
    DECLARE v_categories  INT DEFAULT 0;
    DECLARE v_warehouses  INT DEFAULT 0;
    DECLARE v_msg         VARCHAR(256) DEFAULT '';

    -- -----------------------------------------------------------------
    -- Prerequisites. The dataset is spread over every enterprise in the
    -- database, so a database that has only the six demo companies would
    -- produce a catalogue that silently misses the accounts people log in
    -- with. Stop with a message that says what to run instead.
    -- -----------------------------------------------------------------
    SELECT COUNT(*) INTO v_enterprises FROM t_enterprise WHERE deleted = 0;
    IF v_enterprises < 8 THEN
        SET v_msg = CONCAT('企业数量不足（需要 8 家以上，实际 ', v_enterprises,
                           ' 家）。请先运行 scripts/generate-demo-data.sql 并启动过后端。');
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = v_msg;
    END IF;

    -- The pool below names its categories and warehouses by id. Reading them
    -- first and refusing to run without them is the difference between a
    -- catalogue that references real grades and one that quietly produces
    -- goods no category page can show — the same failure mode as a hard-coded
    -- enterprise id, one level down.
    SELECT COUNT(*) INTO v_categories
      FROM t_commodity_category
     WHERE deleted = 0 AND id IN (1002, 1003, 1004, 1006, 1007, 1009);
    IF v_categories < 6 THEN
        SET v_msg = CONCAT('品类数据不完整（需要 6 个可交易品类，实际 ', v_categories,
                           ' 个）。t_commodity_category 的种子数据在 V4 迁移中。');
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = v_msg;
    END IF;

    SELECT COUNT(*) INTO v_warehouses
      FROM t_warehouse WHERE deleted = 0 AND id IN (2001, 2002) AND status = 1;
    IF v_warehouses < 2 THEN
        SET v_msg = CONCAT('交割仓库数据不完整（需要 2001/2002 两个可用仓库，实际 ',
                           v_warehouses, ' 个）。');
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = v_msg;
    END IF;

    -- =================================================================
    -- Repair: a frozen note with no freeze record behind it.
    --
    -- scripts/generate-demo-data.sql freezes goods by writing frozen_quantity
    -- on the note and never writes the t_freeze_record that says why. Those
    -- rows predate this script and this script may not update them, so the
    -- missing half of the record is inserted here instead: the note is the
    -- evidence that the goods were reserved, and the freeze row is the
    -- missing explanation.
    --
    -- Idempotent by construction rather than by the guard below — the NOT
    -- EXISTS means a second run finds nothing left to write. Deliberately
    -- outside the transaction and outside the guard, so it also runs when the
    -- catalogue itself is already present.
    --
    -- The id comes from the note id rather than from a row number. A row
    -- number renumbers whenever the set of notes needing repair changes, and
    -- the second run would then collide with the rows the first run wrote.
    --
    -- Notes this script writes are excluded on purpose. They are written in
    -- the same transaction as their freezes, so they can never legitimately be
    -- in this state — and if a bug ever put them there, repairing them quietly
    -- would hide the bug behind a green check.
    -- =================================================================
    INSERT INTO t_freeze_record
        (id, freeze_no, enterprise_id, entity_type, entity_id, quantity, amount,
         biz_type, biz_id, status, reason, released_at, created_at, updated_at)
    SELECT v_repair_base
               + MOD(n.id, 100000000000000) * 10 + n.id DIV 1000000000000000000,
           CONCAT('FZ2026RCHREP',
                  LPAD(MOD(n.id, 100000000000000) * 10
                       + n.id DIV 1000000000000000000, 10, '0')),
           n.enterprise_id, 'INVENTORY', n.id, n.frozen_quantity, NULL,
           -- biz_id stays NULL, which is how ListingService writes it too: the
           -- freeze exists before the listing it backs does.
           'LISTING', NULL, 'FROZEN', '演示数据修复：历史冻结补记',
           NULL, n.updated_at, n.updated_at
      FROM t_inventory_note n
     WHERE n.deleted = 0
       AND n.frozen_quantity > 0
       AND n.note_no NOT LIKE 'IN2026RCH%'
       AND NOT EXISTS (SELECT 1 FROM t_freeze_record f
                        WHERE f.entity_id = n.id
                          AND f.entity_type = 'INVENTORY'
                          AND f.status = 'FROZEN');

    -- =================================================================
    -- Idempotency. Any one of this script's rows means a previous run got
    -- through, so there is nothing to add. Checked before anything else is
    -- written, which is what makes a second run a no-op rather than a
    -- duplicate-key error halfway through.
    -- =================================================================
    SELECT (SELECT COUNT(*) FROM t_inventory_note WHERE note_no LIKE 'IN2026RCH%')
         + (SELECT COUNT(*) FROM t_listing        WHERE listing_no LIKE 'LS2026RCH%')
         + (SELECT COUNT(*) FROM t_order          WHERE order_no LIKE 'OR2026RCH%')
      INTO v_existing;
    IF v_existing > 0 THEN
        SELECT '富目录演示数据已存在，本次不做任何改动。' AS result;
        LEAVE rich_gen;
    END IF;

    -- =================================================================
    -- 1. Sequence, pool, enterprises.
    --
    -- MySQL has no generate_series, so the number series every INSERT below
    -- joins against is a recursive CTE. It has to cover the largest of the
    -- three counts, not their sum, because the notes, the listings and the
    -- standalone orders are three parallel runs over the same numbers.
    -- =================================================================
    DROP TEMPORARY TABLE IF EXISTS tmp_rch_seq;
    CREATE TEMPORARY TABLE tmp_rch_seq (n INT PRIMARY KEY);
    INSERT INTO tmp_rch_seq (n)
    WITH RECURSIVE s(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM s WHERE n < 400)
    SELECT n FROM s;

    -- The pool: ~100 realistic descriptors, written out rather than derived
    -- from a cross join of brand x grade, because a hand-written list is the
    -- only way to keep every combination plausible (there is no 云铝 电解铜).
    -- A derived table, not a permanent table: it is scaffolding for this run,
    -- not part of the model, and a table for it would outlive its usefulness
    -- and need migrating.
    --
    -- category_id values must be ones that exist (1002 电解铜, 1003 铝锭,
    -- 1004 锌锭, 1006 碳酸锂, 1007 氢氧化锂, 1009 精铟); inventing a category
    -- would produce goods that no category page can show.
    DROP TEMPORARY TABLE IF EXISTS tmp_rch_pool;
    CREATE TEMPORARY TABLE tmp_rch_pool (
        seq            INT PRIMARY KEY,
        category_id    BIGINT        NOT NULL,
        commodity_name VARCHAR(128)  NOT NULL,
        brand          VARCHAR(64),
        origin         VARCHAR(64),
        spec           VARCHAR(256)  NOT NULL DEFAULT '{}',
        unit           VARCHAR(16)   NOT NULL DEFAULT '吨',
        base_price     DECIMAL(19,4) NOT NULL
    );
    INSERT INTO tmp_rch_pool
        (seq, category_id, commodity_name, brand, origin, spec, unit, base_price)
    SELECT * FROM (VALUES
        -- ---- 电解铜 (1002) --------------------------------------------
        ROW(  1, 1002, '江铜电解铜',          '江铜',   '江西',   '{"铜含量":"99.95%","执行标准":"GB/T 467-2010"}', '吨', 68000),
        ROW(  2, 1002, '江铜 1# 电解铜',      '江铜',   '江西',   '{"铜含量":"99.95%","执行标准":"GB/T 467-2010"}', '吨', 68100),
        ROW(  3, 1002, '江铜阴极铜',          '江铜',   '江西',   '{"铜含量":"99.993%"}',                            '吨', 68300),
        ROW(  4, 1002, '铜陵电解铜',          '铜陵',   '安徽',   '{"铜含量":"99.95%","执行标准":"GB/T 467-2010"}', '吨', 67800),
        ROW(  5, 1002, '铜陵 1# 电解铜',      '铜陵',   '安徽',   '{"铜含量":"99.95%","执行标准":"GB/T 467-2010"}', '吨', 67700),
        ROW(  6, 1002, '铜陵阴极铜',          '铜陵',   '安徽',   '{"铜含量":"99.95%"}',                            '吨', 67600),
        ROW(  7, 1002, '云铜电解铜',          '云铜',   '云南',   '{"铜含量":"99.95%","执行标准":"GB/T 467-2010"}', '吨', 67500),
        ROW(  8, 1002, '云铜 1# 电解铜',      '云铜',   '云南',   '{"铜含量":"99.95%"}',                            '吨', 67400),
        ROW(  9, 1002, '云铜 A 级电解铜',     '云铜',   '云南',   '{"铜含量":"99.95%"}',                            '吨', 67600),
        ROW( 10, 1002, '金川电解铜',          '金川',   '甘肃',   '{"铜含量":"99.99%"}',                            '吨', 68600),
        ROW( 11, 1002, '金川 1# 电解铜',      '金川',   '甘肃',   '{"铜含量":"99.99%"}',                            '吨', 68500),
        ROW( 12, 1002, '大冶电解铜',          '大冶',   '湖北',   '{"铜含量":"99.95%"}',                            '吨', 67300),
        ROW( 13, 1002, '大冶 1# 电解铜',      '大冶',   '湖北',   '{"铜含量":"99.95%"}',                            '吨', 67200),
        ROW( 14, 1002, '中条山电解铜',        '中条山', '山西',   '{"铜含量":"99.95%"}',                            '吨', 67100),
        ROW( 15, 1002, '白银电解铜',          '白银',   '甘肃',   '{"铜含量":"99.95%"}',                            '吨', 67250),
        ROW( 16, 1002, '紫金电解铜',          '紫金',   '福建',   '{"铜含量":"99.95%"}',                            '吨', 67900),
        ROW( 17, 1002, '铜冠电解铜',          '铜冠',   '安徽',   '{"铜含量":"99.95%"}',                            '吨', 67650),
        ROW( 18, 1002, '楚雄电解铜',          '楚雄',   '云南',   '{"铜含量":"99.95%"}',                            '吨', 67450),
        ROW( 19, 1002, '国润电解铜',          '国润',   '山东',   '{"铜含量":"99.95%"}',                            '吨', 67550),
        ROW( 20, 1002, '方圆电解铜',          '方圆',   '山东',   '{"铜含量":"99.95%"}',                            '吨', 67520),
        ROW( 21, 1002, '恒邦电解铜',          '恒邦',   '山东',   '{"铜含量":"99.95%"}',                            '吨', 67480),
        ROW( 22, 1002, '1# 电解铜',           '国产',   '上海',   '{"铜含量":"99.95%"}',                            '吨', 67600),
        ROW( 23, 1002, 'A 级电解铜',          '国产',   '浙江',   '{}',                                             '吨', 67750),
        ROW( 24, 1002, '高纯阴极铜',          '国产',   '江苏',   '{"铜含量":"99.993%"}',                           '吨', 68400),
        -- ---- 铝锭 (1003) ----------------------------------------------
        ROW( 25, 1003, '云铝铝锭',            '云铝',     '云南',   '{"铝含量":"99.70%","执行标准":"GB/T 1196-2017"}', '吨', 24500),
        ROW( 26, 1003, '云铝 A00 铝锭',       '云铝',     '云南',   '{"铝含量":"99.70%"}',                             '吨', 24520),
        ROW( 27, 1003, '云铝 A0 铝锭',        '云铝',     '云南',   '{"铝含量":"99.60%"}',                             '吨', 24400),
        ROW( 28, 1003, '中铝铝锭',            '中铝',     '广西',   '{"铝含量":"99.70%"}',                             '吨', 24450),
        ROW( 29, 1003, '中铝 A00 铝锭',       '中铝',     '广西',   '{"铝含量":"99.70%"}',                             '吨', 24480),
        ROW( 30, 1003, '信发铝锭',            '信发',     '山东',   '{"铝含量":"99.70%"}',                             '吨', 24380),
        ROW( 31, 1003, '信发 A00 铝锭',       '信发',     '山东',   '{"铝含量":"99.70%"}',                             '吨', 24420),
        ROW( 32, 1003, '魏桥铝锭',            '魏桥',     '山东',   '{"铝含量":"99.70%"}',                             '吨', 24430),
        ROW( 33, 1003, '魏桥 A00 铝锭',       '魏桥',     '山东',   '{"铝含量":"99.70%"}',                             '吨', 24460),
        ROW( 34, 1003, '天山铝锭',            '天山',     '新疆',   '{"铝含量":"99.70%"}',                             '吨', 24300),
        ROW( 35, 1003, '天山 A00 铝锭',       '天山',     '新疆',   '{"铝含量":"99.70%"}',                             '吨', 24340),
        ROW( 36, 1003, '神火铝锭',            '神火',     '河南',   '{"铝含量":"99.70%"}',                             '吨', 24410),
        ROW( 37, 1003, '伊电铝锭',            '伊电',     '河南',   '{"铝含量":"99.70%"}',                             '吨', 24390),
        ROW( 38, 1003, '东方希望铝锭',        '东方希望', '内蒙古', '{"铝含量":"99.70%"}',                             '吨', 24280),
        ROW( 39, 1003, '榆林铝锭',            '榆林',     '陕西',   '{"铝含量":"99.70%"}',                             '吨', 24360),
        ROW( 40, 1003, '包铝铝锭',            '包铝',     '内蒙古', '{"铝含量":"99.70%"}',                             '吨', 24320),
        ROW( 41, 1003, '青铜峡铝锭',          '青铜峡',   '宁夏',   '{"铝含量":"99.70%"}',                             '吨', 24370),
        ROW( 42, 1003, '连城铝锭',            '连城',     '甘肃',   '{"铝含量":"99.70%"}',                             '吨', 24350),
        ROW( 43, 1003, '云铝重熔用铝锭',      '云铝',     '云南',   '{"铝含量":"99.70%"}',                             '吨', 24540),
        ROW( 44, 1003, '中铝重熔用铝锭',      '中铝',     '广西',   '{"铝含量":"99.70%"}',                             '吨', 24500),
        -- ---- 锌锭 (1004) ----------------------------------------------
        ROW( 45, 1004, '葫芦岛锌锭',          '葫锌',     '辽宁',   '{"锌含量":"99.995%","执行标准":"GB/T 470-2008"}', '吨', 26600),
        ROW( 46, 1004, '葫锌 0# 锌锭',        '葫锌',     '辽宁',   '{"锌含量":"99.995%"}',                            '吨', 26620),
        ROW( 47, 1004, '葫锌 1# 锌锭',        '葫锌',     '辽宁',   '{"锌含量":"99.99%"}',                             '吨', 26400),
        ROW( 48, 1004, '驰宏锌锭',            '驰宏',     '云南',   '{"锌含量":"99.995%"}',                            '吨', 26500),
        ROW( 49, 1004, '驰宏 0# 锌锭',        '驰宏',     '云南',   '{"锌含量":"99.995%"}',                            '吨', 26540),
        ROW( 50, 1004, '白银 0# 锌锭',        '白银',     '甘肃',   '{"锌含量":"99.995%"}',                            '吨', 26520),
        ROW( 51, 1004, '豫光 0# 锌锭',        '豫光',     '河南',   '{"锌含量":"99.995%"}',                            '吨', 26480),
        ROW( 52, 1004, '株冶 0# 锌锭',        '株冶',     '湖南',   '{"锌含量":"99.995%"}',                            '吨', 26560),
        ROW( 53, 1004, '株冶 1# 锌锭',        '株冶',     '湖南',   '{"锌含量":"99.99%"}',                             '吨', 26380),
        ROW( 54, 1004, '陕西锌业 0# 锌锭',    '陕西锌业', '陕西',   '{"锌含量":"99.995%"}',                            '吨', 26420),
        ROW( 55, 1004, '韶冶 0# 锌锭',        '韶冶',     '广东',   '{"锌含量":"99.995%"}',                            '吨', 26510),
        ROW( 56, 1004, '河池 0# 锌锭',        '河池',     '广西',   '{"锌含量":"99.995%"}',                            '吨', 26460),
        ROW( 57, 1004, '会泽 0# 锌锭',        '会泽',     '云南',   '{"锌含量":"99.995%"}',                            '吨', 26490),
        ROW( 58, 1004, '来宾 0# 锌锭',        '来宾',     '广西',   '{"锌含量":"99.995%"}',                            '吨', 26470),
        ROW( 59, 1004, '铜冠 0# 锌锭',        '铜冠',     '安徽',   '{"锌含量":"99.995%"}',                            '吨', 26530),
        ROW( 60, 1004, '0# 锌锭',             '国产',     '浙江',   '{}',                                              '吨', 26550),
        -- ---- 碳酸锂 (1006) --------------------------------------------
        ROW( 61, 1006, '电池级碳酸锂',            '国产',     '青海',   '{"锂含量":"99.5%","级别":"电池级"}', '吨', 136000),
        ROW( 62, 1006, '工业级碳酸锂',            '国产',     '青海',   '{"锂含量":"99.2%","级别":"工业级"}', '吨', 128000),
        ROW( 63, 1006, '赣锋电池级碳酸锂',        '赣锋',     '江西',   '{"锂含量":"99.5%","级别":"电池级"}', '吨', 136500),
        ROW( 64, 1006, '天齐电池级碳酸锂',        '天齐',     '四川',   '{"锂含量":"99.5%","级别":"电池级"}', '吨', 136200),
        ROW( 65, 1006, '盐湖电池级碳酸锂',        '盐湖',     '青海',   '{"锂含量":"99.5%","级别":"电池级"}', '吨', 135000),
        ROW( 66, 1006, '蓝科电池级碳酸锂',        '蓝科',     '青海',   '{"锂含量":"99.5%","级别":"电池级"}', '吨', 134800),
        ROW( 67, 1006, '藏格电池级碳酸锂',        '藏格',     '西藏',   '{"锂含量":"99.5%","级别":"电池级"}', '吨', 134500),
        ROW( 68, 1006, '中信国安电池级碳酸锂',    '中信国安', '青海',   '{"锂含量":"99.5%","级别":"电池级"}', '吨', 134200),
        ROW( 69, 1006, '志存电池级碳酸锂',        '志存',     '江西',   '{"锂含量":"99.5%","级别":"电池级"}', '吨', 135200),
        ROW( 70, 1006, '永兴电池级碳酸锂',        '永兴',     '江西',   '{"锂含量":"99.5%","级别":"电池级"}', '吨', 135600),
        ROW( 71, 1006, '雅化电池级碳酸锂',        '雅化',     '四川',   '{"锂含量":"99.5%","级别":"电池级"}', '吨', 135300),
        ROW( 72, 1006, '融捷电池级碳酸锂',        '融捷',     '四川',   '{"锂含量":"99.5%","级别":"电池级"}', '吨', 135100),
        ROW( 73, 1006, '中矿电池级碳酸锂',        '中矿',     '江西',   '{"锂含量":"99.5%","级别":"电池级"}', '吨', 134900),
        ROW( 74, 1006, '盛新电池级碳酸锂',        '盛新',     '四川',   '{"锂含量":"99.5%","级别":"电池级"}', '吨', 134600),
        ROW( 75, 1006, '赣锋工业级碳酸锂',        '赣锋',     '江西',   '{"锂含量":"99.2%","级别":"工业级"}', '吨', 128600),
        ROW( 76, 1006, '天齐工业级碳酸锂',        '天齐',     '四川',   '{"锂含量":"99.2%","级别":"工业级"}', '吨', 128400),
        ROW( 77, 1006, '盐湖工业级碳酸锂',        '盐湖',     '青海',   '{"锂含量":"99.2%","级别":"工业级"}', '吨', 127600),
        ROW( 78, 1006, '蓝科工业级碳酸锂',        '蓝科',     '青海',   '{"锂含量":"99.2%","级别":"工业级"}', '吨', 127400),
        -- ---- 氢氧化锂 (1007) ------------------------------------------
        ROW( 79, 1007, '电池级氢氧化锂',          '国产', '江西', '{"锂含量":"56.5%","级别":"电池级"}', '吨', 121000),
        ROW( 80, 1007, '工业级氢氧化锂',          '国产', '江西', '{"锂含量":"55.0%","级别":"工业级"}', '吨', 112000),
        ROW( 81, 1007, '赣锋电池级氢氧化锂',      '赣锋', '江西', '{"锂含量":"56.5%","级别":"电池级"}', '吨', 121500),
        ROW( 82, 1007, '天齐电池级氢氧化锂',      '天齐', '四川', '{"锂含量":"56.5%","级别":"电池级"}', '吨', 121200),
        ROW( 83, 1007, '雅化电池级氢氧化锂',      '雅化', '四川', '{"锂含量":"56.5%","级别":"电池级"}', '吨', 120600),
        ROW( 84, 1007, '容汇电池级氢氧化锂',      '容汇', '江西', '{"锂含量":"56.5%","级别":"电池级"}', '吨', 120400),
        ROW( 85, 1007, '致远电池级氢氧化锂',      '致远', '江西', '{"锂含量":"56.5%","级别":"电池级"}', '吨', 120200),
        ROW( 86, 1007, '天华电池级氢氧化锂',      '天华', '四川', '{"锂含量":"56.5%","级别":"电池级"}', '吨', 120000),
        ROW( 87, 1007, '赣锋单水氢氧化锂',        '赣锋', '江西', '{"锂含量":"56.5%"}',                  '吨', 121800),
        ROW( 88, 1007, '天齐单水氢氧化锂',        '天齐', '四川', '{"锂含量":"56.5%"}',                  '吨', 121400),
        ROW( 89, 1007, '雅化单水氢氧化锂',        '雅化', '四川', '{"锂含量":"56.5%"}',                  '吨', 120800),
        ROW( 90, 1007, '容汇工业级氢氧化锂',      '容汇', '江西', '{"锂含量":"55.0%","级别":"工业级"}', '吨', 112500),
        -- ---- 精铟 (1009) — priced and traded by the kilogram -----------
        ROW( 91, 1009, '4N 精铟',                 '国产',     '湖南', '{"铟含量":"99.99%"}',  '千克', 1680),
        ROW( 92, 1009, '5N 精铟',                 '国产',     '湖南', '{"铟含量":"99.999%"}', '千克', 1850),
        ROW( 93, 1009, '株冶 4N 精铟',            '株冶',     '湖南', '{"铟含量":"99.99%"}',  '千克', 1690),
        ROW( 94, 1009, '韶冶 4N 精铟',            '韶冶',     '广东', '{"铟含量":"99.99%"}',  '千克', 1685),
        ROW( 95, 1009, '云锡 4N 精铟',            '云锡',     '云南', '{"铟含量":"99.99%"}',  '千克', 1700),
        ROW( 96, 1009, '华锡 4N 精铟',            '华锡',     '广西', '{"铟含量":"99.99%"}',  '千克', 1675),
        ROW( 97, 1009, '豫光 4N 精铟',            '豫光',     '河南', '{"铟含量":"99.99%"}',  '千克', 1670),
        ROW( 98, 1009, '中金岭南 4N 精铟',        '中金岭南', '广东', '{"铟含量":"99.99%"}',  '千克', 1695),
        ROW( 99, 1009, '株冶 5N 精铟',            '株冶',     '湖南', '{"铟含量":"99.999%"}', '千克', 1860),
        ROW(100, 1009, '云锡高纯铟锭',            '云锡',     '云南', '{"铟含量":"99.999%"}', '千克', 1880)
    ) AS v(seq, category_id, commodity_name, brand, origin, spec, unit, base_price);

    -- The parties. Every id is read from the database at run time; none is
    -- written down here, because enterprise ids are time-based snowflakes and
    -- differ on every rebuild. The operator name is what the services write
    -- into t_order_status_log: the login name when there is a user account,
    -- the contact name otherwise.
    DROP TEMPORARY TABLE IF EXISTS tmp_rch_ent;
    CREATE TEMPORARY TABLE tmp_rch_ent (
        idx         INT PRIMARY KEY,
        id          BIGINT      NOT NULL,
        code        VARCHAR(32) NOT NULL,
        operator    VARCHAR(64),
        operator_id BIGINT
    );
    INSERT INTO tmp_rch_ent (idx, id, code, operator, operator_id)
    SELECT ROW_NUMBER() OVER (ORDER BY e.id),
           e.id,
           e.enterprise_code,
           COALESCE((SELECT u.username FROM t_user u
                      WHERE u.enterprise_id = e.id ORDER BY u.id LIMIT 1),
                    e.contact_name, e.short_name),
           (SELECT u.id FROM t_user u
             WHERE u.enterprise_id = e.id ORDER BY u.id LIMIT 1)
      FROM t_enterprise e
     WHERE e.deleted = 0;

    -- =================================================================
    -- 2. The listing plan: one row per listing, plus everything that has to be
    --    consistent with it (the note it freezes, the order that takes part of
    --    it, the freeze records either side of that).
    --
    -- Everything is derived from the sequence number with small arithmetic
    -- hashes (value * prime % range) rather than RAND(), for two reasons: the
    -- dataset is then identical on every database, and a shape can be reasoned
    -- about and debugged instead of only observed. RAND() would also make the
    -- second run of this file — and any bug report about it — unreproducible.
    --
    -- SELL : BUY is 2 : 1. A SELL listing freezes goods at publication
    -- (ListingService.freezeForListing) and therefore needs a note behind it; a
    -- BUY listing freezes nothing at publication and so has no freeze_id, which
    -- is why only the SELL side creates note rows below.
    -- =================================================================
    DROP TEMPORARY TABLE IF EXISTS tmp_rch_listing;
    CREATE TEMPORARY TABLE tmp_rch_listing (
        seq            INT PRIMARY KEY,
        id             BIGINT,
        listing_no     VARCHAR(32),
        side           VARCHAR(8),
        sell_ordinal   INT,             -- 1..n among SELL listings, 0 for BUY
        ent_idx        INT,
        enterprise_id  BIGINT,
        pool_seq       INT,
        category_id    BIGINT,
        commodity_name VARCHAR(128),
        brand          VARCHAR(64),
        origin         VARCHAR(64),
        spec           VARCHAR(256),
        unit           VARCHAR(16),
        warehouse_id   BIGINT,
        quantity       DECIMAL(18,3),   -- Q, as published
        sold_qty       DECIMAL(18,3) DEFAULT 0,  -- C, taken and the goods gone
        pending_qty    DECIMAL(18,3) DEFAULT 0,  -- P, accepted but unanswered
        lapsed_qty     DECIMAL(18,3) DEFAULT 0,  -- an acceptance that timed out
        remaining_qty  DECIMAL(18,3),   -- R = Q - C - P, what a buyer can still take
        reserved_qty   DECIMAL(18,3),   -- Q - C, what the active freeze still covers
        note_frozen    DECIMAL(18,3),   -- what the backing note must report frozen
        note_leftover  DECIMAL(18,3),   -- spare stock the backing note keeps
        note_seq       INT,
        note_id        BIGINT,
        counter_idx    INT,             -- accepting party: buyer on SELL, seller on BUY
        counter_id     BIGINT,
        counter_note_id BIGINT,         -- accepting seller's note, BUY listings only
        counter_note_seq INT,
        counter_note_leftover DECIMAL(18,3),
        price_type     VARCHAR(16),
        price          DECIMAL(19,4),
        confirm_mode   VARCHAR(8),
        status         VARCHAR(24),
        freeze_id          BIGINT,
        freeze_sold_id     BIGINT,
        freeze_active_id   BIGINT,
        freeze_active_status VARCHAR(16),
        valid_until    DATETIME(6),
        created_at     DATETIME(6),
        updated_at     DATETIME(6)
    );

    INSERT INTO tmp_rch_listing
        (seq, id, listing_no, side, sell_ordinal, ent_idx, pool_seq, warehouse_id)
    SELECT s.n,
           v_listing_base + s.n,
           CONCAT('LS2026RCH', LPAD(s.n, 5, '0')),
           -- Two SELLs then one BUY. seq % 3 = 0 is the BUY.
           IF(s.n % 3 = 0, 'BUY', 'SELL'),
           IF(s.n % 3 = 0, 0, s.n - FLOOR((s.n - 1) / 3)),
           1 + (s.n * 7) % 9,
           -- gcd(37,100) = 1, so 1 + (n*37)%100 walks the whole pool.
           1 + (s.n * 37) % 100,
           IF(s.n % 7 < 4, 2001, 2002)
      FROM tmp_rch_seq s
     WHERE s.n <= v_listings;

    UPDATE tmp_rch_listing l
      JOIN tmp_rch_pool p ON p.seq = l.pool_seq
       SET l.category_id    = p.category_id,
           l.commodity_name = p.commodity_name,
           l.brand          = p.brand,
           l.origin         = p.origin,
           l.spec           = p.spec,
           l.unit           = p.unit,
           -- Quantity scales with the unit: tonnes for the bulk grades,
           -- kilograms for 精铟, so a 180-tonne indium listing cannot happen.
           l.quantity       = IF(p.unit = '千克', 20 + (l.seq * 53) % 480,
                                                 5 + (l.seq * 29) % 180);

    UPDATE tmp_rch_listing l
      JOIN tmp_rch_ent e ON e.idx = l.ent_idx
       SET l.enterprise_id = e.id;

    -- The accepting party, and the note the goods come from.
    --
    -- SELL: the goods are the lister's own, so the backing note (odd sequence
    -- numbers, 1..399) is created by this script at 7.1e18 + the same number.
    -- BUY:  the goods belong to whoever accepts the bid, so the sold part is
    -- drawn from a note in the 1000+ block belonging to that seller.
    UPDATE tmp_rch_listing l
       SET l.counter_idx    = 1 + ((l.ent_idx - 1) + 1 + l.seq % 7) % 9,
           l.note_seq       = IF(l.side = 'SELL', 2 * l.sell_ordinal - 1, NULL),
           l.note_id        = IF(l.side = 'SELL', v_note_base + 2 * l.sell_ordinal - 1, NULL),
           l.counter_note_seq = IF(l.side = 'BUY', 1000 + l.seq, NULL),
           l.counter_note_id  = IF(l.side = 'BUY', v_note_base + 1000 + l.seq, NULL);

    UPDATE tmp_rch_listing l
      JOIN tmp_rch_ent e ON e.idx = l.counter_idx
       SET l.counter_id = e.id;

    -- confirm_mode first: it decides what can be pending. MANUAL is refused on
    -- a BUY listing by ck_listing_manual_needs_frozen_goods, because a deferred
    -- acceptance would promise goods nobody reserved — so BUY is always AUTO.
    --
    -- The modulus here is 7 and the statuses below use 20: deliberately
    -- coprime, so MANUAL listings land in every status bucket. An earlier draft
    -- keyed this off seq % 5, which is not coprime with 20 — every listing in
    -- the 13 mod 20 bucket is also 3 mod 5, so the two shapes never met and
    -- the dataset came out with no PENDING_CONFIRM orders at all.
    UPDATE tmp_rch_listing
       SET confirm_mode = IF(side = 'SELL' AND seq % 7 IN (1, 2, 3, 4), 'MANUAL', 'AUTO');

    -- Status mix, before the quantities that depend on it: six OPEN in ten,
    -- one in four PARTIALLY_FILLED, and one each of FILLED / CLOSED / EXPIRED
    -- per twenty. The market page reads OPEN and PARTIALLY_FILLED, the owner's
    -- page reads all five, so all five have to exist.
    UPDATE tmp_rch_listing
       SET status = CASE
               WHEN seq % 20 <= 11 THEN 'OPEN'
               WHEN seq % 20 <= 16 THEN 'PARTIALLY_FILLED'
               WHEN seq % 20 = 17 THEN 'FILLED'
               WHEN seq % 20 = 18 THEN 'CLOSED'
               ELSE 'EXPIRED' END;

    -- Pending acceptance: only a MANUAL listing can be waiting for its lister.
    -- Two shapes produce one — a partial lot accepted and unanswered, and the
    -- whole remainder accepted at once, where remaining hits zero so the
    -- listing reads FILLED while the goods are still reserved (the same shape
    -- generate-inflight-orders.sql produces).
    UPDATE tmp_rch_listing
       SET pending_qty = CASE
               WHEN confirm_mode <> 'MANUAL' THEN 0
               WHEN status IN ('PARTIALLY_FILLED', 'FILLED')
                    AND seq % 20 IN (13, 14, 15)
                    THEN FLOOR(quantity * 0.30)
               WHEN status = 'FILLED' AND FLOOR(seq / 20) % 2 = 0 THEN quantity
               ELSE 0 END;

    UPDATE tmp_rch_listing
       SET sold_qty = CASE
               WHEN status = 'FILLED' THEN quantity - pending_qty
               WHEN status = 'PARTIALLY_FILLED' THEN
                   CASE WHEN pending_qty > 0 THEN FLOOR(quantity * 0.25)
                        ELSE FLOOR(quantity * 0.40) END
               ELSE 0 END;

    -- A lapsed acceptance: the answer never came, the sweep cancelled the
    -- order, the part went back on offer and the listing is OPEN again with a
    -- cancelled order behind it. Cheap to write and the only way the expiry
    -- sweep's own output appears anywhere in the demo data.
    UPDATE tmp_rch_listing
       SET lapsed_qty = IF(confirm_mode = 'MANUAL' AND status = 'OPEN' AND seq % 20 = 6,
                           FLOOR(quantity * 0.25), 0);

    UPDATE tmp_rch_listing
       SET remaining_qty = quantity - sold_qty - pending_qty,
           reserved_qty  = quantity - sold_qty;

    -- The freeze records this listing leaves behind.
    --
    --   freeze_sold_id   the part that was taken: the goods left the note, so
    --                    the record is CONSUMED with released_at set.
    --   freeze_active_id everything still covered by a freeze: the remainder
    --                    of an open listing, plus any part accepted and not yet
    --                    answered (the acceptance does not split the freeze —
    --                    only confirming it does, see
    --                    FreezeService.consumeInventoryPartial — so the two are
    --                    one record).
    --
    -- The rule that makes the whole trail verifiable:
    --
    --   note.frozen_quantity = SUM(quantity) of this note's FROZEN records
    --
    -- and it is enforced here rather than hoped for: note_frozen is computed
    -- from reserved_qty under exactly the conditions that make the FROZEN
    -- record exist below.
    UPDATE tmp_rch_listing
       SET freeze_sold_id   = IF(sold_qty > 0, v_freeze_base + seq * 2, NULL),
           freeze_active_id = IF(side = 'SELL' AND reserved_qty > 0,
                                 v_freeze_base + seq * 2 + 1, NULL),
           note_frozen      = IF(side = 'SELL'
                                 AND (status IN ('OPEN', 'PARTIALLY_FILLED') OR pending_qty > 0),
                                 reserved_qty, 0);

    UPDATE tmp_rch_listing
       SET freeze_active_status = IF(note_frozen > 0, 'FROZEN', 'RELEASED'),
           -- The listing points at the freeze that is live now: the consumed
           -- record when part was taken, otherwise the reserved one. A listing
           -- that was closed or expired points at the record it released,
           -- which is where its history is readable.
           freeze_id = IF(side = 'SELL', COALESCE(freeze_sold_id, freeze_active_id), NULL);

    -- Spare stock each backing note keeps beyond what its listing reserved.
    -- Computed here rather than in a derived table at the INSERT below because
    -- MySQL refuses to reopen a temporary table inside one statement, and the
    -- INSERT already reads this one. A fifth of the notes keep nothing, which
    -- is what produces the fully-frozen shape — one in five and not one in
    -- three, because 3 is the modulus that decides BUY from SELL and a shared
    -- modulus would put every spare-stock-free note on the unsupported side.
    UPDATE tmp_rch_listing
       SET note_leftover = IF(side = 'SELL' AND seq % 5 <> 0,
                              IF(unit = '千克', 20 + (seq * 13) % 300,
                                               2 + (seq * 7) % 30), 0),
           counter_note_leftover = IF(side = 'BUY' AND seq % 5 <> 0,
                              IF(unit = '千克', 20 + (seq * 17) % 200,
                                               2 + (seq * 5) % 20), 0);

    -- Prices. FIXED listings carry one; NEGOTIABLE ones must carry none
    -- (ck_listing_price_present). The level varies by grade — copper x1.00,
    -- aluminium x0.36, zinc x0.39, lithium carbonate x2.00 of the copper
    -- opening in generate-demo-data.sql — and the roundings match how each
    -- grade is actually quoted: tens of yuan per tonne, whole yuan per kilo.
    UPDATE tmp_rch_listing l
      JOIN tmp_rch_pool p ON p.seq = l.pool_seq
       -- 11 rather than 10: a tenth of the sequence number is correlated with
       -- the status bucket above (20 is a multiple of 10), which would leave
       -- whole statuses quoted only one way.
       SET l.price_type = IF(l.seq % 11 < 8, 'FIXED', 'NEGOTIABLE'),
           l.price      = IF(l.seq % 11 < 8,
                             ROUND(p.base_price * (0.98 + (l.seq % 9) * 0.005),
                                   IF(p.unit = '千克', 0, -1)),
                             NULL);

    -- Timestamps. Open listings are recent and their deadline is still ahead:
    -- the sweep expires anything OPEN or PARTIALLY_FILLED whose valid_until has
    -- passed, and a demo dataset whose open listings delete themselves
    -- overnight is worse than none. Finished listings are history, which is
    -- where the expired and closed shapes belong.
    UPDATE tmp_rch_listing
       SET created_at = IF(status IN ('OPEN', 'PARTIALLY_FILLED'),
                           NOW(6) - INTERVAL (8 + (seq * 7) % 20) DAY,
                           NOW(6) - INTERVAL (30 + (seq * 7) % 90) DAY)
                        - INTERVAL (seq * 13) % 720 MINUTE;

    UPDATE tmp_rch_listing
       SET valid_until = IF(status IN ('OPEN', 'PARTIALLY_FILLED'),
                            created_at + INTERVAL (30 + seq % 15) DAY,
                            created_at + INTERVAL (10 + seq % 20) DAY),
           updated_at  = IF(status IN ('OPEN', 'PARTIALLY_FILLED'),
                            created_at + INTERVAL (2 + seq % 9) HOUR,
                            created_at + INTERVAL (5 + seq % 20) DAY);

    -- Everything from here on is permanent, so it goes in one transaction:
    -- either the whole catalogue lands or none of it does, and a failure
    -- halfway through does not leave a half-written dataset sitting behind the
    -- guard (which would then refuse to finish the job on the next run).
    -- Building temporary tables inside a transaction is safe: CREATE TEMPORARY
    -- TABLE does not cause an implicit commit.
    START TRANSACTION;

    -- =================================================================
    -- 3. Inventory notes.
    --    (a) plain stock, one per even sequence number, 200 of them, spread
    --        over every enterprise and every descriptor in the pool;
    --    (b) the notes behind the SELL listings, whose frozen quantity is
    --        exactly what those listings hold;
    --    (c) the notes the goods of a filled BUY listing came out of, owned by
    --        whoever accepted the bid.
    --
    -- The balance CHECK (available + frozen = total) is satisfied by
    -- construction here — every row computes available as total - frozen —
    -- rather than by hoping the arithmetic works out.
    -- =================================================================
    INSERT INTO t_inventory_note
        (id, note_no, enterprise_id, category_id, warehouse_id, commodity_name,
         brand, origin, spec, total_quantity, available_quantity, frozen_quantity,
         unit, production_date, status, version, remark, created_at, updated_at)
    SELECT v_note_base + s.n,
           CONCAT('IN2026RCH', LPAD(s.n, 5, '0')),
           e.id, p.category_id, IF(s.n % 2 = 0, 2002, 2001),
           p.commodity_name, p.brand, p.origin, p.spec,
           -- Written twice because temporary tables cannot be reopened inside
           -- one statement: total and available are the same number for an
           -- unfrozen note. A few tonnes to a few hundred — the whole point of
           -- a catalogue is that the sizes differ.
           IF(p.unit = '千克', 50 + (s.n * 97) % 3000, 3 + (s.n * 43) % 400),
           IF(p.unit = '千克', 50 + (s.n * 97) % 3000, 3 + (s.n * 43) % 400),
           0,
           p.unit,
           CURRENT_DATE - INTERVAL (20 + (s.n * 11) % 200) DAY,
           2, 0, '演示数据：现货库存',
           NOW(6) - INTERVAL (2 + (s.n * 3) % 90) DAY,
           NOW(6) - INTERVAL (2 + (s.n * 3) % 90) DAY
      FROM tmp_rch_seq s
      JOIN tmp_rch_ent  e ON e.idx = 1 + (s.n * 5) % 9
      JOIN tmp_rch_pool p ON p.seq = 1 + (s.n * 37) % 100
     WHERE s.n <= v_notes
       AND s.n % 2 = 0;

    -- (b) The notes behind the SELL listings.
    --
    -- total = (what the freeze still covers) + (stock the listing never
    -- offered). available follows as total - frozen, which is what makes the
    -- released case readable: a closed or expired listing shows its goods back
    -- in available and its frozen_quantity at zero, which is exactly what
    -- ListingService.releaseListingFreeze does to the row.
    --
    -- status follows FreezeService.deriveStatus: nothing frozen is in stock,
    -- nothing available is fully frozen, otherwise partially frozen. A note
    -- whose goods all left the warehouse is delivered, not empty.
    INSERT INTO t_inventory_note
        (id, note_no, enterprise_id, category_id, warehouse_id, commodity_name,
         brand, origin, spec, total_quantity, available_quantity, frozen_quantity,
         unit, production_date, status, version, remark, created_at, updated_at)
    SELECT l.note_id,
           CONCAT('IN2026RCH', LPAD(l.note_seq, 5, '0')),
           l.enterprise_id, l.category_id, l.warehouse_id, l.commodity_name,
           l.brand, l.origin, l.spec,
           l.reserved_qty + l.note_leftover,
           l.reserved_qty + l.note_leftover - l.note_frozen,
           l.note_frozen,
           l.unit,
           DATE(l.created_at) - INTERVAL 20 DAY,
           CASE WHEN l.note_frozen = 0 AND l.note_leftover = 0 THEN 5  -- delivered
                WHEN l.note_frozen = 0 THEN 2                         -- in stock
                WHEN l.note_leftover = 0 THEN 3                       -- fully frozen
                ELSE 4 END,                                           -- partially frozen
           0,
           CASE WHEN l.note_frozen > 0 THEN '演示数据：挂牌冻结中'
                ELSE '演示数据：挂牌已了结，冻结已释放' END,
           l.created_at - INTERVAL 3 DAY,
           l.updated_at
      FROM tmp_rch_listing l
     WHERE l.side = 'SELL';

    -- (c) The accepting seller's note for a filled BUY listing. The goods left
    --     it when the bid was accepted, so only the leftovers remain.
    INSERT INTO t_inventory_note
        (id, note_no, enterprise_id, category_id, warehouse_id, commodity_name,
         brand, origin, spec, total_quantity, available_quantity, frozen_quantity,
         unit, production_date, status, version, remark, created_at, updated_at)
    SELECT l.counter_note_id,
           CONCAT('IN2026RCH', LPAD(l.counter_note_seq, 5, '0')),
           l.counter_id, l.category_id, l.warehouse_id, l.commodity_name,
           l.brand, l.origin, l.spec,
           l.counter_note_leftover, l.counter_note_leftover, 0,
           l.unit,
           DATE(l.created_at) - INTERVAL 20 DAY,
           IF(l.counter_note_leftover = 0, 5, 2),
           0, '演示数据：摘牌方交货',
           l.created_at - INTERVAL 2 DAY,
           l.updated_at
      FROM tmp_rch_listing l
     WHERE l.side = 'BUY'
       AND l.sold_qty > 0;

    -- =================================================================
    -- 4. Freezes. Two records per SELL listing at most, one per BUY listing
    --    that was taken.
    --
    -- Ownership matters and is not decorative: FreezeService.loadFrozen
    -- refuses a freeze whose enterprise_id is not the caller's, so a freeze
    -- written against the wrong company is invisible to the company that
    -- should own it — which is how a previous script silently wrote inventory
    -- for an enterprise that did not exist.
    -- =================================================================

    -- 4a. The part that was sold: goods left the note, record CONSUMED.
    INSERT INTO t_freeze_record
        (id, freeze_no, enterprise_id, entity_type, entity_id, quantity, amount,
         biz_type, biz_id, status, reason, released_at, created_at, updated_at)
    SELECT l.freeze_sold_id,
           CONCAT('FZ2026RCH', LPAD(l.seq * 2, 5, '0')),
           IF(l.side = 'SELL', l.enterprise_id, l.counter_id),
           'INVENTORY',
           IF(l.side = 'SELL', l.note_id, l.counter_note_id),
           l.sold_qty, NULL,
           -- biz_id NULL: ListingService freezes before the listing exists.
           'LISTING', NULL, 'CONSUMED', '摘牌成交，冻结已消耗',
           -- When the goods moved: the fill order's confirmed_at, derived the
           -- same way the order plan below derives it (created_at + 2 hours)
           -- rather than read from it, because the plan rows are written after
           -- this statement.
           l.created_at + INTERVAL (1 + l.seq % 3) DAY + INTERVAL 2 HOUR,
           l.created_at, l.updated_at
      FROM tmp_rch_listing l
     WHERE l.sold_qty > 0;

    -- 4b. What is still covered: FROZEN while the listing is open or an
    --     acceptance is unanswered, RELEASED once the listing was closed or
    --     expired and the goods went back on the market. The released record
    --     is not decoration — without it the return of the goods has no
    --     explanation in the trail, and the release path is untestable.
    INSERT INTO t_freeze_record
        (id, freeze_no, enterprise_id, entity_type, entity_id, quantity, amount,
         biz_type, biz_id, status, reason, released_at, created_at, updated_at)
    SELECT l.freeze_active_id,
           CONCAT('FZ2026RCH', LPAD(l.seq * 2 + 1, 5, '0')),
           l.enterprise_id, 'INVENTORY', l.note_id, l.reserved_qty, NULL,
           'LISTING', NULL, l.freeze_active_status,
           CASE WHEN l.freeze_active_status = 'FROZEN'
                     THEN IF(l.sold_qty > 0, '部分消耗后剩余', '挂牌冻结')
                     ELSE '撤牌或到期，冻结释放' END,
           IF(l.freeze_active_status = 'FROZEN', NULL, l.updated_at),
           l.created_at, l.updated_at
      FROM tmp_rch_listing l
     WHERE l.freeze_active_id IS NOT NULL;

    -- =================================================================
    -- 5. Listings.
    -- =================================================================
    INSERT INTO t_listing
        (id, listing_no, enterprise_id, side, category_id, commodity_name, brand,
         origin, spec, quantity, remaining_quantity, unit, price, price_type,
         warehouse_id, delivery_method, payment_terms, freeze_id, valid_until,
         status, confirm_mode, version, remark, created_at, updated_at)
    SELECT l.id, l.listing_no, l.enterprise_id, l.side, l.category_id,
           l.commodity_name, l.brand, l.origin, l.spec,
           l.quantity, l.remaining_qty, l.unit,
           -- NEGOTIABLE carries no price at all; the agreed price lives on the
           -- order that came out of it.
           l.price, l.price_type,
           l.warehouse_id, IF(l.seq % 6 = 0, 'DELIVERED', 'SELF_PICKUP'),
           'MARGIN_THEN_BALANCE',
           l.freeze_id, l.valid_until, l.status, l.confirm_mode, 0,
           CASE WHEN l.status = 'EXPIRED' THEN '演示挂牌：有效期届满未成交'
                WHEN l.status = 'CLOSED' THEN '演示挂牌：挂牌方撤牌'
                WHEN l.side = 'BUY' THEN '演示挂牌：买方求购'
                WHEN l.confirm_mode = 'MANUAL' THEN '演示挂牌：摘牌待确认'
                ELSE '演示挂牌：摘牌即成交' END,
           l.created_at, l.updated_at
      FROM tmp_rch_listing l;

    -- =================================================================
    -- 6. Orders.
    --
    --   FILL       one per listing that had part of it taken. The goods moved,
    --              so the order is CONFIRMED or beyond — never PENDING_CONFIRM,
    --              which is a state in which nothing has moved at all.
    --   PENDING    one per listing with an unanswered acceptance.
    --   LAPSED     the acceptance that timed out and was cancelled by the
    --              sweep, signed 'system' because nobody performed it.
    --   STANDALONE orders with no listing row, the same shape
    --              generate-demo-data.sql writes for its completed history.
    --              These are the volume: a few hundred rows spread over every
    --              status, every enterprise and the whole pool.
    -- =================================================================
    DROP TEMPORARY TABLE IF EXISTS tmp_rch_order;
    CREATE TEMPORARY TABLE tmp_rch_order (
        seq            INT PRIMARY KEY,
        id             BIGINT,
        order_no       VARCHAR(32),
        origin         VARCHAR(12),
        listing_seq    INT,
        listing_id     BIGINT,
        buyer_idx      INT,
        seller_idx     INT,
        buyer_id       BIGINT,
        seller_id      BIGINT,
        pool_seq       INT,
        category_id    BIGINT,
        commodity_name VARCHAR(128),
        spec           VARCHAR(256),
        unit           VARCHAR(16),
        quantity       DECIMAL(18,3),
        price          DECIMAL(19,4),
        amount         DECIMAL(19,4),
        warehouse_id   BIGINT,
        delivery_method VARCHAR(16),
        status         VARCHAR(24),
        contract_id    BIGINT,
        goods_freeze_id BIGINT,
        via_pending    TINYINT DEFAULT 0,
        cancel_from    VARCHAR(24),
        cancel_operator VARCHAR(8),
        cancel_reason  VARCHAR(256),
        created_at     DATETIME(6),
        confirmed_at   DATETIME(6),
        contracted_at  DATETIME(6),
        delivering_at  DATETIME(6),
        completed_at   DATETIME(6),
        cancelled_at   DATETIME(6),
        confirm_deadline DATETIME(6),
        updated_at     DATETIME(6),
        buyer_operator     VARCHAR(64),
        buyer_operator_id  BIGINT,
        seller_operator    VARCHAR(64),
        seller_operator_id BIGINT
    );

    INSERT INTO tmp_rch_order
        (seq, id, order_no, origin, listing_seq, listing_id, buyer_idx, seller_idx,
         pool_seq, quantity, status, via_pending, goods_freeze_id)
    SELECT l.seq, v_order_base + l.seq, CONCAT('OR2026RCH', LPAD(l.seq, 5, '0')),
           'FILL', l.seq, l.id,
           -- On a SELL listing the lister sells and the counterparty buys; on a
           -- BUY listing it is the other way round.
           IF(l.side = 'SELL', l.counter_idx, l.ent_idx),
           IF(l.side = 'SELL', l.ent_idx, l.counter_idx),
           l.pool_seq, l.sold_qty,
           -- (seq + seq/20) % 4 rather than seq % 4: 20 is divisible by 4, so
           -- seq % 4 is constant within a status bucket and every partial sale
           -- in the same bucket would land on the same order status.
           CASE (l.seq + FLOOR(l.seq / 20)) % 4
                WHEN 0 THEN 'COMPLETED' WHEN 1 THEN 'DELIVERING'
                WHEN 2 THEN 'CONTRACTED' ELSE 'CONFIRMED' END,
           0, l.freeze_sold_id
      FROM tmp_rch_listing l
     WHERE l.sold_qty > 0;

    -- The unanswered acceptance. goods_freeze_id stays NULL for the same reason
    -- OrderService leaves it NULL: nothing has moved yet, and filling it in
    -- would make the later confirmation look like a second transfer.
    INSERT INTO tmp_rch_order
        (seq, id, order_no, origin, listing_seq, listing_id, buyer_idx, seller_idx,
         pool_seq, quantity, status, via_pending, goods_freeze_id)
    SELECT 1000 + l.seq, v_order_base + 1000 + l.seq,
           CONCAT('OR2026RCH', LPAD(1000 + l.seq, 5, '0')),
           'PENDING', l.seq, l.id,
           IF(l.side = 'SELL', l.counter_idx, l.ent_idx),
           IF(l.side = 'SELL', l.ent_idx, l.counter_idx),
           l.pool_seq, l.pending_qty,
           'PENDING_CONFIRM', 1, NULL
      FROM tmp_rch_listing l
     WHERE l.pending_qty > 0;

    INSERT INTO tmp_rch_order
        (seq, id, order_no, origin, listing_seq, listing_id, buyer_idx, seller_idx,
         pool_seq, quantity, status, via_pending,
         cancel_from, cancel_operator, cancel_reason)
    SELECT 2000 + l.seq, v_order_base + 2000 + l.seq,
           CONCAT('OR2026RCH', LPAD(2000 + l.seq, 5, '0')),
           'LAPSED', l.seq, l.id,
           IF(l.side = 'SELL', l.counter_idx, l.ent_idx),
           IF(l.side = 'SELL', l.ent_idx, l.counter_idx),
           l.pool_seq, l.lapsed_qty,
           'CANCELLED', 1,
           'PENDING_CONFIRM', 'SYSTEM', '挂牌方未在确认期限内答复，摘牌自动失效'
      FROM tmp_rch_listing l
     WHERE l.lapsed_qty > 0;

    INSERT INTO tmp_rch_order
        (seq, id, order_no, origin, pool_seq, buyer_idx, seller_idx, status,
         cancel_from, cancel_operator, cancel_reason)
    SELECT 3000 + s.n, v_order_base + 3000 + s.n,
           CONCAT('OR2026RCH', LPAD(3000 + s.n, 5, '0')),
           'STANDALONE',
           1 + (s.n * 37) % 100,
           1 + (s.n * 5) % 9,
           -- +1..7 modulo 9, never 0, so the two parties always differ
           -- (ck_order_parties_differ).
           1 + ((s.n * 5) % 9 + 1 + s.n % 7) % 9,
           CASE s.n % 5 WHEN 0 THEN 'COMPLETED' WHEN 1 THEN 'DELIVERING'
                        WHEN 2 THEN 'CONTRACTED' WHEN 3 THEN 'CONFIRMED'
                        ELSE 'CANCELLED' END,
           -- A person called these off, after confirmation and before the
           -- contract; the ones the sweep cancels are the LAPSED rows above.
           'CONFIRMED',
           IF(s.n % 2 = 0, 'BUYER', 'SELLER'),
           CASE s.n % 3 WHEN 0 THEN '买方资金安排调整，双方协商一致取消'
                        WHEN 1 THEN '下游订单取消，双方协商解除'
                        ELSE '过磅规格与约定不符，卖方提出解除' END
      FROM tmp_rch_seq s
     WHERE s.n <= v_standalone;

    UPDATE tmp_rch_order o
      JOIN tmp_rch_pool p ON p.seq = o.pool_seq
       SET o.category_id    = p.category_id,
           o.commodity_name = p.commodity_name,
           o.spec           = p.spec,
           o.unit           = p.unit,
           o.quantity       = IF(o.origin = 'STANDALONE',
                                 IF(p.unit = '千克', 30 + (o.seq * 71) % 900,
                                                      8 + (o.seq * 19) % 160),
                                 o.quantity);

    -- Price: the listing's own price where the listing had one, the pool's
    -- market level otherwise (a negotiated listing still settles at a number).
    -- The band is +-3% of the descriptor's level, so no lot is ever an order of
    -- magnitude away from the chart — a copper lot at 1100 元/吨 is the kind of
    -- outlier that makes the whole demo look broken.
    UPDATE tmp_rch_order o
      JOIN tmp_rch_listing l ON l.seq = o.listing_seq
       SET o.price = l.price
     WHERE o.listing_seq IS NOT NULL
       AND l.price IS NOT NULL;

    UPDATE tmp_rch_order o
      JOIN tmp_rch_pool p ON p.seq = o.pool_seq
       SET o.price = ROUND(p.base_price * (0.97 + (o.seq % 11) * 0.006),
                           IF(p.unit = '千克', 0, -1))
     WHERE o.price IS NULL;

    UPDATE tmp_rch_order o
      SET o.amount = ROUND(o.price * o.quantity, 4),
          o.warehouse_id = IF(o.seq % 7 < 4, 2001, 2002),
          o.delivery_method = IF(o.seq % 6 = 0, 'DELIVERED', 'SELF_PICKUP');

    -- Two statements rather than one join: a temporary table cannot be
    -- referenced twice in the same statement, so the buyer and the seller are
    -- resolved one side at a time.
    UPDATE tmp_rch_order o
      JOIN tmp_rch_ent b ON b.idx = o.buyer_idx
       SET o.buyer_id = b.id,
           o.buyer_operator = b.operator, o.buyer_operator_id = b.operator_id;

    UPDATE tmp_rch_order o
      JOIN tmp_rch_ent s ON s.idx = o.seller_idx
       SET o.seller_id = s.id,
           o.seller_operator = s.operator, o.seller_operator_id = s.operator_id;

    UPDATE tmp_rch_order o
      JOIN tmp_rch_listing l ON l.seq = o.listing_seq
       SET o.listing_id = l.id
     WHERE o.listing_seq IS NOT NULL;

    UPDATE tmp_rch_order o
      JOIN tmp_rch_listing l ON l.seq = o.listing_seq
       SET o.created_at = CASE o.origin
               WHEN 'FILL'    THEN l.created_at + INTERVAL (1 + o.seq % 3) DAY
               WHEN 'PENDING' THEN l.created_at + INTERVAL 6 HOUR
               ELSE                l.created_at + INTERVAL 8 HOUR END
     WHERE o.listing_seq IS NOT NULL;

    UPDATE tmp_rch_order
       SET created_at = NOW(6) - INTERVAL (5 + (seq * 11) % 120) DAY
                                  - INTERVAL (seq * 17) % 600 MINUTE
     WHERE origin = 'STANDALONE';

    -- The timeline. Each step follows from created_at, so every row's history
    -- reads in order and no two timestamps contradict each other.
    UPDATE tmp_rch_order
       SET confirmed_at  = IF(status = 'PENDING_CONFIRM'
                              OR cancel_from = 'PENDING_CONFIRM',
                              NULL, created_at + INTERVAL 2 HOUR),
           contracted_at = IF(status IN ('CONTRACTED', 'DELIVERING', 'COMPLETED'),
                              created_at + INTERVAL 26 HOUR, NULL),
           delivering_at = IF(status IN ('DELIVERING', 'COMPLETED'),
                              created_at + INTERVAL 74 HOUR, NULL),
           completed_at  = IF(status = 'COMPLETED',
                              created_at + INTERVAL 120 HOUR, NULL),
           cancelled_at  = IF(status = 'CANCELLED',
                              IF(cancel_from = 'PENDING_CONFIRM',
                                 created_at + INTERVAL 3 DAY,
                                 created_at + INTERVAL 48 HOUR),
                              NULL),
           -- Days away, not hours: the sweep cancels any acceptance past its
           -- deadline, and a demo dataset whose pending work deletes itself
           -- overnight is worse than none.
           confirm_deadline = IF(status = 'PENDING_CONFIRM',
                                 NOW(6) + INTERVAL (4 + seq % 14) DAY, NULL);

    UPDATE tmp_rch_order
       SET updated_at = COALESCE(cancelled_at, completed_at, delivering_at,
                                 contracted_at, confirmed_at, created_at),
           contract_id = IF(status IN ('CONTRACTED', 'DELIVERING', 'COMPLETED'),
                            v_contract_base + seq, NULL);

    INSERT INTO t_order
        (id, order_no, listing_id, buyer_id, seller_id, category_id, commodity_name,
         spec, quantity, unit, price, amount, warehouse_id, delivery_method,
         payment_terms, goods_freeze_id, margin_freeze_id, status, contract_id,
         confirmed_at, cancelled_at, cancel_reason, confirm_deadline, version,
         remark, created_at, updated_at)
    SELECT o.id, o.order_no, o.listing_id, o.buyer_id, o.seller_id, o.category_id,
           o.commodity_name, o.spec, o.quantity, o.unit, o.price, o.amount,
           o.warehouse_id, o.delivery_method, 'MARGIN_THEN_BALANCE',
           o.goods_freeze_id, NULL, o.status, o.contract_id,
           o.confirmed_at, o.cancelled_at, o.cancel_reason, o.confirm_deadline, 0,
           '演示数据：富目录订单',
           o.created_at, o.updated_at
      FROM tmp_rch_order o;

    -- =================================================================
    -- 7. Contracts. Behind every order that got past CONFIRMED, and only
    --    those: CONFIRMED with no contract is the state the CONTRACT_TO_DRAFT
    --    task is derived from, so giving those one would delete the task.
    --
    -- SIGNED requires both signatures (ck_contract_signed_complete); terms
    -- mirror what ContractService writes — the same keys, snapshot values as
    -- strings — so the contract page renders a document rather than an empty
    -- JSON object.
    -- =================================================================
    INSERT INTO t_contract
        (id, contract_no, order_id, buyer_id, seller_id, title, terms, quantity,
         unit, price, amount, weight_tolerance, status, buyer_signed_at,
         buyer_signed_by, seller_signed_at, seller_signed_by, created_at, updated_at)
    SELECT o.contract_id,
           CONCAT('CT2026RCH', LPAD(o.seq, 5, '0')),
           o.id, o.buyer_id, o.seller_id,
           CONCAT(o.commodity_name, ' ',
                  TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST(o.quantity AS CHAR))),
                  ' ', o.unit, '（金额 ',
                  TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST(o.amount AS CHAR))),
                  ' 元）购销合同'),
           JSON_OBJECT(
               'commodityName', o.commodity_name,
               'quantity', TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST(o.quantity AS CHAR))),
               'unit', o.unit,
               'price', TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST(o.price AS CHAR))),
               'amount', TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST(o.amount AS CHAR))),
               'deliveryMethod', IF(o.delivery_method = 'DELIVERED', '送到', '自提'),
               'paymentTerms', 'MARGIN_THEN_BALANCE',
               'weightTolerance', '3.00%',
               'settlementBasis', '结算重量以实际过磅重量为准，磅差在约定范围内按实际重量结算，超出范围时由双方协商处理，系统不自动结算。',
               'qualityDispute', '买方应在收货后 7 日内提出质量异议，逾期视为验收合格。',
               'disputeResolution', '争议由双方协商解决，协商不成提交平台所在地法院管辖。'),
           o.quantity, o.unit, o.price, o.amount, 3.00, 'SIGNED',
           o.contracted_at, o.buyer_operator_id,
           o.contracted_at, o.seller_operator_id,
           o.confirmed_at, o.contracted_at
      FROM tmp_rch_order o
     WHERE o.status IN ('CONTRACTED', 'DELIVERING', 'COMPLETED');

    -- =================================================================
    -- 8. The transition trail. A status column says where an order is; this
    --    says how it got there and who moved it, and it is what the order
    --    detail page reads. Every order gets at least the acceptance row —
    --    the steps after it are conditional on how far the order went.
    -- =================================================================

    -- Step 1: the acceptance. A MANUAL listing leaves the order waiting for
    -- the lister; an AUTO one is a deal the moment it is taken, so the same
    -- act reads differently.
    INSERT INTO t_order_status_log
        (id, order_id, from_status, to_status, operator_id, operator, reason, created_at)
    SELECT v_log_base + o.seq * 10 + 1, o.id, NULL,
           IF(o.via_pending = 1, 'PENDING_CONFIRM', 'CONFIRMED'),
           o.buyer_operator_id, o.buyer_operator,
           IF(o.via_pending = 1, '摘牌，待挂牌方确认', '摘牌成交'),
           o.created_at
      FROM tmp_rch_order o;

    -- Step 2: the lister answered, and the goods moved with the answer.
    INSERT INTO t_order_status_log
        (id, order_id, from_status, to_status, operator_id, operator, reason, created_at)
    SELECT v_log_base + o.seq * 10 + 2, o.id,
           'PENDING_CONFIRM', 'CONFIRMED',
           o.seller_operator_id, o.seller_operator,
           '挂牌方确认摘牌', o.confirmed_at
      FROM tmp_rch_order o
     WHERE o.via_pending = 1
       AND o.status IN ('CONFIRMED', 'CONTRACTED', 'DELIVERING', 'COMPLETED');

    -- Step 3: both signatures landed, so the contract took effect.
    INSERT INTO t_order_status_log
        (id, order_id, from_status, to_status, operator_id, operator, reason, created_at)
    SELECT v_log_base + o.seq * 10 + 3, o.id,
           'CONFIRMED', 'CONTRACTED', o.buyer_operator_id, o.buyer_operator,
           '合同签署生效', o.contracted_at
      FROM tmp_rch_order o
     WHERE o.status IN ('CONTRACTED', 'DELIVERING', 'COMPLETED');

    -- Step 4: delivery started. Either party may start it, so this one is
    -- signed by the side that did not sign the contract step above.
    INSERT INTO t_order_status_log
        (id, order_id, from_status, to_status, operator_id, operator, reason, created_at)
    SELECT v_log_base + o.seq * 10 + 4, o.id,
           'CONTRACTED', 'DELIVERING', o.seller_operator_id, o.seller_operator,
           '开始交收', o.delivering_at
      FROM tmp_rch_order o
     WHERE o.status IN ('DELIVERING', 'COMPLETED');

    INSERT INTO t_order_status_log
        (id, order_id, from_status, to_status, operator_id, operator, reason, created_at)
    SELECT v_log_base + o.seq * 10 + 5, o.id,
           'DELIVERING', 'COMPLETED', o.buyer_operator_id, o.buyer_operator,
           '收货确认，订单完成', o.completed_at
      FROM tmp_rch_order o
     WHERE o.status = 'COMPLETED';

    -- Step 6: cancellation. The lapsed ones are attributed to 'system', the way
    -- the sweep signs its own work: crediting a person for a timeout nobody
    -- performed would be a lie in the record kept for disputes.
    INSERT INTO t_order_status_log
        (id, order_id, from_status, to_status, operator_id, operator, reason, created_at)
    SELECT v_log_base + o.seq * 10 + 6, o.id,
           o.cancel_from, 'CANCELLED',
           CASE o.cancel_operator WHEN 'BUYER'  THEN o.buyer_operator_id
                                  WHEN 'SELLER' THEN o.seller_operator_id
                                  ELSE NULL END,
           CASE o.cancel_operator WHEN 'BUYER'  THEN o.buyer_operator
                                  WHEN 'SELLER' THEN o.seller_operator
                                  ELSE 'system' END,
           o.cancel_reason, o.cancelled_at
      FROM tmp_rch_order o
     WHERE o.status = 'CANCELLED';

    COMMIT;

    DROP TEMPORARY TABLE IF EXISTS tmp_rch_seq;
    DROP TEMPORARY TABLE IF EXISTS tmp_rch_pool;
    DROP TEMPORARY TABLE IF EXISTS tmp_rch_ent;
    DROP TEMPORARY TABLE IF EXISTS tmp_rch_listing;
    DROP TEMPORARY TABLE IF EXISTS tmp_rch_order;

    SELECT '富目录演示数据已生成。' AS message;
END$$
DELIMITER ;

CALL generate_rich_catalogue();

DROP PROCEDURE generate_rich_catalogue;

-- Summary. Same shape as the other two scripts' summaries, so the three read
-- alike, plus the two invariants this script exists to keep: a note whose
-- quantity does not add up, and a frozen note with no freeze behind it. Both
-- have to be zero.
SELECT '库存单（本次）' AS 项目, CAST(COUNT(*) AS CHAR) AS 数量
    FROM t_inventory_note WHERE note_no LIKE 'IN2026RCH%'
UNION ALL SELECT '商品名称（去重）', CAST(COUNT(DISTINCT commodity_name) AS CHAR)
    FROM t_inventory_note WHERE deleted = 0
UNION ALL SELECT CONCAT('挂牌 ', status), CAST(COUNT(*) AS CHAR)
    FROM t_listing WHERE listing_no LIKE 'LS2026RCH%' GROUP BY status
UNION ALL SELECT CONCAT('订单 ', status), CAST(COUNT(*) AS CHAR)
    FROM t_order WHERE order_no LIKE 'OR2026RCH%' GROUP BY status
UNION ALL SELECT '合同（已签署）', CAST(COUNT(*) AS CHAR)
    FROM t_contract WHERE contract_no LIKE 'CT2026RCH%'
UNION ALL SELECT '冻结记录', CAST(COUNT(*) AS CHAR)
    FROM t_freeze_record WHERE freeze_no LIKE 'FZ2026RCH%'
UNION ALL SELECT '状态流水', CAST(COUNT(*) AS CHAR)
    FROM t_order_status_log WHERE id BETWEEN 7400000000000000000 AND 7400000000000099999
UNION ALL SELECT '企业不存在的挂牌（应为 0）', CAST(COUNT(*) AS CHAR)
    FROM t_listing l
   WHERE l.deleted = 0
     AND NOT EXISTS (SELECT 1 FROM t_enterprise e WHERE e.id = l.enterprise_id)
UNION ALL SELECT '库存数量不平（应为 0）', CAST(COUNT(*) AS CHAR)
    FROM t_inventory_note n
   WHERE n.deleted = 0 AND n.available_quantity + n.frozen_quantity <> n.total_quantity
UNION ALL SELECT '冻结无记录（应为 0）', CAST(COUNT(*) AS CHAR)
    FROM t_inventory_note n
   WHERE n.deleted = 0 AND n.frozen_quantity > 0
     AND NOT EXISTS (SELECT 1 FROM t_freeze_record f
                      WHERE f.entity_id = n.id
                        AND f.entity_type = 'INVENTORY'
                        AND f.status = 'FROZEN')
ORDER BY 1;
