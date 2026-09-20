-- =============================================================================
-- Demo dataset generator.
--
-- Run against a database that already has the V1-V7 schema and seed rows:
--     docker exec -i spotlink-mysql mysql -ubulk -pbulk_trade_2026 bulk_trade \
--       < scripts/generate-demo-data.sql
--
-- Generates trades spread across the past 60 days rather than all at one
-- instant. That is the whole point: a price series with every trade on the same
-- day is a single point, which is what the market page looked like before this.
--
-- Deliberately not a Flyway migration. Migrations describe schema; demo rows
-- are not schema, and a production deployment should be able to skip them.
-- =============================================================================

-- The rows below are full of Chinese names, and the mysql client takes its
-- connection character set from the OS locale, which is latin1 in this
-- container. Without this line the UTF-8 bytes in this file would be stored
-- double-encoded. psql negotiated a UTF-8 encoding with the server on its own;
-- the MySQL client has to be told.
SET NAMES utf8mb4;

-- PostgreSQL ran this file as a DO $$ ... $$ anonymous block. MySQL has no
-- anonymous blocks, so the same body is a stored procedure that this file
-- creates, calls and drops in one pass. DELIMITER is a client-side directive
-- that the mysql client honours when reading from a redirect, so the command
-- in the header still works as written.
DELIMITER $$
DROP PROCEDURE IF EXISTS generate_demo_data$$
CREATE PROCEDURE generate_demo_data()
demo_gen: BEGIN
    -- Trading calendar: weekdays only, matching the real 09:00-16:45 session.
    DECLARE v_days        INT DEFAULT 60;
    DECLARE v_price       DECIMAL(19,4) DEFAULT 67200.0000;   -- opening level for 电解铜
    DECLARE v_day         DATE;
    DECLARE v_day_i       INT DEFAULT 0;
    DECLARE v_trades_today INT;
    DECLARE v_i           INT;
    DECLARE v_buyer       BIGINT;
    DECLARE v_seller      BIGINT;
    DECLARE v_price_today DECIMAL(19,4);
    DECLARE v_qty         DECIMAL(18,3);
    DECLARE v_amount      DECIMAL(19,4);
    DECLARE v_ts          DATETIME(6);
    DECLARE v_seq         BIGINT DEFAULT 5000000000000000000;  -- synthetic ids, disjoint from snowflakes
    -- Separate counter for the status log. Adding an offset to v_seq overflowed
    -- bigint: snowflake-range ids leave no headroom above them. Negative ids
    -- cannot collide with snowflakes, which are always positive.
    DECLARE v_log_seq     BIGINT DEFAULT 0;
    -- v_sellers, v_buyers and v_categories were BIGINT[] arrays here. MySQL has
    -- no array type: the seller/buyer id list is now the tmp_demo_enterprises
    -- table built further down, and the category ids are written out in ELT()
    -- at each place they were indexed.
    DECLARE v_cat         BIGINT;
    DECLARE v_cat_name    VARCHAR(64);
    DECLARE v_unit        VARCHAR(16);
    DECLARE v_note_id     BIGINT;
    DECLARE v_acct        BIGINT;
    DECLARE v_off         INT;      -- index offset, for LIMIT ... OFFSET
    DECLARE v_secs        INT;      -- seconds into the trading session
    DECLARE v_demo_count  INT DEFAULT 0;
    DECLARE v_done        TINYINT DEFAULT 0;

    -- PostgreSQL had no cursor here because `FOR v_note_id, ... IN SELECT`
    -- iterates a query directly. MySQL has no FOR-IN-SELECT, so the listing
    -- loop at the bottom of this procedure drives a cursor over the same
    -- query, with the same column order.
    DECLARE cur_listing CURSOR FOR
        SELECT n.id, n.enterprise_id, n.category_id, n.commodity_name,
               LEAST(n.available_quantity, 20)
        FROM t_inventory_note n
        WHERE n.note_no LIKE 'IN2026DEMO%'
          AND n.available_quantity >= 20
          AND n.status = 2
        ORDER BY n.id
        LIMIT 5;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_done = 1;

    -- ---------------------------------------------------------------
    -- Idempotency. The PostgreSQL original carried no guard: its enterprise
    -- ids were fixed, so a second run died on the first duplicate key, and
    -- the trade ids were not fixed at all (the number of trades per day is
    -- random) so it could not simply be re-run either. MySQL behaves the
    -- same way, so detect this script's own output and do nothing.
    -- ---------------------------------------------------------------
    SELECT COUNT(*) INTO v_demo_count
      FROM t_enterprise WHERE enterprise_code LIKE 'ENT2026DEMO%';
    IF v_demo_count > 0 THEN
        SELECT 'Demo data already generated; nothing to do.' AS result;
        LEAVE demo_gen;
    END IF;

    -- ---------------------------------------------------------------
    -- Enterprises: sellers and buyers, so a trade can have two parties.
    -- ---------------------------------------------------------------
    SET v_i := 1;
    WHILE v_i <= 6 DO
        SET v_seq := v_seq + 1;
        INSERT INTO t_enterprise (id, enterprise_code, name, short_name,
            unified_social_credit_code, legal_person, contact_name, contact_phone,
            province, city, trader_code, status, qualifications, registered_at, approved_at)
        VALUES (v_seq, CONCAT('ENT2026DEMO', LPAD(v_i, 4, '0')),
                CONCAT('有色金属贸易有限公司', v_i), CONCAT('有色贸易', v_i),
                CONCAT('91330100MA2DEMO', LPAD(v_i, 4, '0')),
                CONCAT('负责人', v_i), CONCAT('联系人', v_i), CONCAT('1380000', LPAD(v_i, 4, '0')),
                '浙江省', '杭州市', CONCAT('T', LPAD(100 + v_i, 4, '0')),
                1, '[]', NOW(6) - INTERVAL 90 DAY, NOW(6) - INTERVAL 88 DAY);

        INSERT INTO t_fund_account (id, account_no, enterprise_id, balance,
            available_balance, frozen_balance, status)
        VALUES (v_seq + 10000, CONCAT('ACCDEMO', LPAD(v_i, 4, '0')), v_seq,
                8000000, 8000000, 0, 1);

        SET v_i := v_i + 1;
    END WHILE;

    -- ---------------------------------------------------------------
    -- Inventory held by both sides, so either can be the seller.
    -- ---------------------------------------------------------------
    SET v_i := 1;
    WHILE v_i <= 12 DO
        SET v_seq := v_seq + 1;
        -- 电解铜 铝锭 锌锭 碳酸锂
        SET v_cat := ELT(1 + (v_i % 4), 1002, 1003, 1004, 1006);
        SET v_cat_name := CASE v_cat
            WHEN 1002 THEN '电解铜' WHEN 1003 THEN '铝锭'
            WHEN 1004 THEN '锌锭' ELSE '碳酸锂' END;
        SET v_unit := CASE v_cat WHEN 1006 THEN '吨' ELSE '吨' END;

        -- The PostgreSQL statement returned a day count to subtract from now();
        -- LIMIT/OFFSET only accepts a local variable in a stored program, so
        -- the offset is computed first.
        SET v_off := (v_i - 1) % 6;

        INSERT INTO t_inventory_note (id, note_no, enterprise_id, category_id,
            warehouse_id, commodity_name, brand, origin, spec,
            total_quantity, available_quantity, frozen_quantity, unit, status, version, created_at)
        SELECT v_seq, CONCAT('IN2026DEMO', LPAD(v_i, 4, '0')),
               e.id, v_cat, 2001, CONCAT(v_cat_name, '现货'), '国产', '浙江', '{}',
               qty, qty, 0, v_unit, 2, 0,
               NOW(6) - INTERVAL (70 - v_i) DAY
        FROM (SELECT id FROM t_enterprise
              WHERE enterprise_code LIKE 'ENT2026DEMO%'
              ORDER BY id LIMIT 1 OFFSET v_off) e,
             (SELECT (50 + (v_i * 13) % 120) AS qty) q;

        SET v_i := v_i + 1;
    END WHILE;

    -- ---------------------------------------------------------------
    -- Trade history: weekdays get trades, weekends are quiet, and the
    -- price random-walks so the series has shape rather than a flat line.
    -- ---------------------------------------------------------------
    -- SELECT array_agg(id ORDER BY id) INTO v_sellers ... v_buyers := v_sellers
    -- built both lists here. MySQL has no array type, so the same query fills a
    -- temporary table whose idx is the position the arrays were indexed by.
    CREATE TEMPORARY TABLE tmp_demo_enterprises (idx INT PRIMARY KEY, id BIGINT NOT NULL);
    INSERT INTO tmp_demo_enterprises (idx, id)
    SELECT ROW_NUMBER() OVER (ORDER BY id), id
    FROM t_enterprise WHERE enterprise_code LIKE 'ENT2026DEMO%';

    -- generate_series had no MySQL equivalent before 8.0's recursive CTEs, and
    -- a recursive CTE cannot carry the random walk below, so the day range is
    -- walked with a counter instead: CURRENT_DATE - v_days .. CURRENT_DATE.
    SET v_day_i := 0;
    days_loop: WHILE v_day_i <= v_days DO
        SET v_day := CURRENT_DATE - INTERVAL (v_days - v_day_i) DAY;
        SET v_day_i := v_day_i + 1;

        -- Weekends: the trading session does not run, so no trades.
        -- EXTRACT(ISODOW ...) has no MySQL form; DAYOFWEEK() numbers Sunday 1
        -- through Saturday 7, so the two weekend days are 1 and 7.
        IF DAYOFWEEK(v_day) IN (1, 7) THEN
            ITERATE days_loop;
        END IF;

        -- 0 to 4 trades a day. Some days are genuinely empty, which the chart
        -- must show as a break rather than interpolate through.
        SET v_trades_today := FLOOR(RAND() * 4);
        IF v_trades_today = 0 THEN
            ITERATE days_loop;
        END IF;

        -- Random walk with a slight upward drift, clamped so it stays sane.
        SET v_price := v_price * (1 + (RAND() - 0.47) * 0.012);
        SET v_price := GREATEST(58000, LEAST(78000, v_price));
        SET v_price := ROUND(v_price, 2);

        SET v_i := 0;
        trade_loop: WHILE v_i < v_trades_today DO
            SET v_i := v_i + 1;
            SET v_seq := v_seq + 1;
            SET v_off := FLOOR(RAND() * 6);
            SELECT id INTO v_seller FROM tmp_demo_enterprises WHERE idx = 1 + v_off;
            SET v_off := FLOOR(RAND() * 6);
            SELECT id INTO v_buyer FROM tmp_demo_enterprises WHERE idx = 1 + v_off;
            IF v_seller = v_buyer THEN
                ITERATE trade_loop;
            END IF;

            -- 电解铜 铝锭 锌锭 碳酸锂
            SET v_cat := ELT(1 + FLOOR(RAND() * 4), 1002, 1003, 1004, 1006);
            SET v_cat_name := CASE v_cat
                WHEN 1002 THEN '电解铜' WHEN 1003 THEN '铝锭'
                WHEN 1004 THEN '锌锭' ELSE '碳酸锂' END;

            -- Price level varies by grade; copper is the base.
            SET v_price_today := ROUND(v_price * CASE v_cat
                WHEN 1002 THEN 1.00 WHEN 1003 THEN 0.36
                WHEN 1004 THEN 0.39 ELSE 2.00 END, 2);

            SET v_qty := ROUND(5 + (RAND() * 45), 3);
            SET v_amount := ROUND(v_price_today * v_qty, 4);

            -- Between 09:00 and 16:45 on the trading day. The PostgreSQL
            -- version added a fractional interval; TIMESTAMP(date, time) plus
            -- whole seconds is the MySQL form, so the spread is per second.
            SET v_secs := FLOOR(RAND() * 27900);   -- 7 hours 45 minutes
            SET v_ts := TIMESTAMP(v_day, '09:00:00') + INTERVAL v_secs SECOND;

            INSERT INTO t_order (id, order_no, buyer_id, seller_id, category_id,
                commodity_name, spec, quantity, unit, price, amount,
                warehouse_id, delivery_method, payment_terms, status, version,
                confirmed_at, created_at, updated_at)
            VALUES (v_seq, CONCAT('OR2026DEMO', LPAD(v_seq % 100000, 5, '0')),
                    v_buyer, v_seller, v_cat, v_cat_name, '{}',
                    v_qty, '吨', v_price_today, v_amount,
                    2001, 'SELF_PICKUP', 'MARGIN_THEN_BALANCE',
                    'COMPLETED', 0, v_ts + INTERVAL 1 HOUR, v_ts, v_ts);

            SET v_log_seq := v_log_seq - 1;
            INSERT INTO t_order_status_log (id, order_id, from_status, to_status,
                operator, reason, created_at)
            VALUES (v_log_seq, v_seq, NULL, 'COMPLETED',
                    '系统', '演示数据：历史成交', v_ts);

            -- Give the buyer a note for what they bought, mirroring what the
            -- real acceptance flow does.
            SET v_seq := v_seq + 1;
            INSERT INTO t_inventory_note (id, note_no, enterprise_id, category_id,
                warehouse_id, commodity_name, brand, origin, spec,
                total_quantity, available_quantity, frozen_quantity, unit, status, version,
                remark, created_at)
            VALUES (v_seq, CONCAT('IN2026DEMO', LPAD(v_seq % 100000, 5, '0')),
                    v_buyer, v_cat, 2001, CONCAT(v_cat_name, '现货'), '国产', '浙江', '{}',
                    v_qty, v_qty, 0, '吨', 2, 0, '演示数据：成交所得', v_ts);
        END WHILE trade_loop;
    END WHILE days_loop;

    -- ---------------------------------------------------------------
    -- A few listings still open, so the market hall is not empty.
    -- Each picks a note with enough free goods and freezes it, mirroring
    -- what publishing a listing actually does.
    -- ---------------------------------------------------------------
    SET v_done := 0;
    OPEN cur_listing;
    listing_loop: LOOP
        FETCH cur_listing INTO v_note_id, v_seller, v_cat, v_cat_name, v_qty;
        IF v_done = 1 THEN
            LEAVE listing_loop;
        END IF;

        SET v_seq := v_seq + 1;
        INSERT INTO t_listing (id, listing_no, enterprise_id, side, category_id,
            commodity_name, brand, origin, spec, quantity, remaining_quantity, unit,
            price, price_type, warehouse_id, delivery_method, payment_terms,
            freeze_id, valid_until, status, version, created_at)
        VALUES (v_seq, CONCAT('LS2026DEMO', LPAD(v_seq % 100000, 5, '0')),
                v_seller, 'SELL', v_cat, v_cat_name, '国产', '浙江', '{}',
                v_qty, v_qty, '吨',
                -- Priced by grade off the same ratio the orders use. This used
                -- to be a flat 68000 plus the row number, which put aluminium
                -- and zinc on copper's price level — the market page showed a
                -- metal at nearly three times what it is worth, and an asking
                -- price that absurd makes the whole dataset look fabricated.
                ROUND(v_price * CASE v_cat
                    WHEN 1002 THEN 1.00 WHEN 1003 THEN 0.36
                    WHEN 1004 THEN 0.39 ELSE 2.00 END, 2),
                'FIXED', 2001, 'SELF_PICKUP', 'MARGIN_THEN_BALANCE',
                NULL, NOW(6) + INTERVAL 7 DAY, 'OPEN', 0, NOW(6) - INTERVAL 1 DAY);

        -- Freeze the goods the listing offers, exactly as the service does.
        UPDATE t_inventory_note
        SET available_quantity = available_quantity - v_qty,
            frozen_quantity = frozen_quantity + v_qty,
            status = 4
        WHERE id = v_note_id;
    END LOOP;
    CLOSE cur_listing;

    -- RAISE NOTICE has no MySQL form; a result set carries the same message.
    SELECT 'Demo data generated.' AS message;
END$$
DELIMITER ;

CALL generate_demo_data();

DROP PROCEDURE generate_demo_data;

-- Summary
SELECT '专营企业' AS 项目, CAST(COUNT(*) AS CHAR) AS 数量 FROM t_enterprise WHERE enterprise_code LIKE 'ENT2026DEMO%'
UNION ALL SELECT '成交订单', CAST(COUNT(*) AS CHAR) FROM t_order WHERE order_no LIKE 'OR2026DEMO%'
UNION ALL SELECT '成交跨天', CONCAT(CAST(DATEDIFF(MAX(created_at), MIN(created_at)) AS CHAR), ' 天')
    FROM t_order WHERE order_no LIKE 'OR2026DEMO%'
UNION ALL SELECT '挂牌', CAST(COUNT(*) AS CHAR) FROM t_listing WHERE listing_no LIKE 'LS2026DEMO%'
UNION ALL SELECT '库存单', CAST(COUNT(*) AS CHAR) FROM t_inventory_note WHERE note_no LIKE 'IN2026DEMO%';
