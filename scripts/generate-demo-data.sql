-- =============================================================================
-- Demo dataset generator.
--
-- Run against a database that already has the V1-V7 schema and seed rows:
--     docker exec -i bulk-trade-postgres psql -U bulk -d bulk_trade \
--       < scripts/generate-demo-data.sql
--
-- Generates trades spread across the past 60 days rather than all at one
-- instant. That is the whole point: a price series with every trade on the same
-- day is a single point, which is what the market page looked like before this.
--
-- Deliberately not a Flyway migration. Migrations describe schema; demo rows
-- are not schema, and a production deployment should be able to skip them.
-- =============================================================================

DO $$
DECLARE
    -- Trading calendar: weekdays only, matching the real 09:00-16:45 session.
    v_days        INT := 60;
    v_price       NUMERIC(19,4) := 67200.0000;   -- opening level for 电解铜
    v_day         DATE;
    v_trades_today INT;
    v_i           INT;
    v_buyer       BIGINT;
    v_seller      BIGINT;
    v_price_today NUMERIC(19,4);
    v_qty         NUMERIC(18,3);
    v_amount      NUMERIC(19,4);
    v_ts          TIMESTAMPTZ;
    v_seq         BIGINT := 5000000000000000000;  -- synthetic ids, disjoint from snowflakes
    -- Separate counter for the status log. Adding an offset to v_seq overflowed
    -- bigint: snowflake-range ids leave no headroom above them. Negative ids
    -- cannot collide with snowflakes, which are always positive.
    v_log_seq     BIGINT := 0;
    v_sellers     BIGINT[];
    v_buyers      BIGINT[];
    v_categories  BIGINT[] := ARRAY[1002, 1003, 1004, 1006];  -- 电解铜 铝锭 锌锭 碳酸锂
    v_cat         BIGINT;
    v_cat_name    TEXT;
    v_unit        TEXT;
    v_note_id     BIGINT;
    v_acct        BIGINT;
BEGIN
    -- ---------------------------------------------------------------
    -- Enterprises: sellers and buyers, so a trade can have two parties.
    -- ---------------------------------------------------------------
    FOR v_i IN 1..6 LOOP
        v_seq := v_seq + 1;
        INSERT INTO t_enterprise (id, enterprise_code, name, short_name,
            unified_social_credit_code, legal_person, contact_name, contact_phone,
            province, city, trader_code, status, qualifications, registered_at, approved_at)
        VALUES (v_seq, 'ENT2026DEMO' || LPAD(v_i::TEXT, 4, '0'),
                '有色金属贸易有限公司' || v_i, '有色贸易' || v_i,
                '91330100MA2DEMO' || LPAD(v_i::TEXT, 4, '0'),
                '负责人' || v_i, '联系人' || v_i, '1380000' || LPAD(v_i::TEXT, 4, '0'),
                '浙江省', '杭州市', 'T' || LPAD((100 + v_i)::TEXT, 4, '0'),
                1, '[]'::jsonb, now() - INTERVAL '90 days', now() - INTERVAL '88 days');

        INSERT INTO t_fund_account (id, account_no, enterprise_id, balance,
            available_balance, frozen_balance, status)
        VALUES (v_seq + 10000, 'ACCDEMO' || LPAD(v_i::TEXT, 4, '0'), v_seq,
                8000000, 8000000, 0, 1);
    END LOOP;

    -- ---------------------------------------------------------------
    -- Inventory held by both sides, so either can be the seller.
    -- ---------------------------------------------------------------
    FOR v_i IN 1..12 LOOP
        v_seq := v_seq + 1;
        v_cat := v_categories[1 + (v_i % 4)];
        v_cat_name := CASE v_cat
            WHEN 1002 THEN '电解铜' WHEN 1003 THEN '铝锭'
            WHEN 1004 THEN '锌锭' ELSE '碳酸锂' END;
        v_unit := CASE v_cat WHEN 1006 THEN '吨' ELSE '吨' END;

        INSERT INTO t_inventory_note (id, note_no, enterprise_id, category_id,
            warehouse_id, commodity_name, brand, origin, spec,
            total_quantity, available_quantity, frozen_quantity, unit, status, version, created_at)
        SELECT v_seq, 'IN2026DEMO' || LPAD(v_i::TEXT, 4, '0'),
               e.id, v_cat, 2001, v_cat_name || '现货', '国产', '浙江', '{}'::jsonb,
               qty, qty, 0, v_unit, 2, 0,
               now() - (INTERVAL '1 day' * (70 - v_i))
        FROM (SELECT id FROM t_enterprise
              WHERE enterprise_code LIKE 'ENT2026DEMO%'
              ORDER BY id OFFSET (v_i - 1) % 6 LIMIT 1) e,
             (SELECT (50 + (v_i * 13) % 120)::NUMERIC(18,3) AS qty) q;
    END LOOP;

    -- ---------------------------------------------------------------
    -- Trade history: weekdays get trades, weekends are quiet, and the
    -- price random-walks so the series has shape rather than a flat line.
    -- ---------------------------------------------------------------
    SELECT array_agg(id ORDER BY id) INTO v_sellers
    FROM t_enterprise WHERE enterprise_code LIKE 'ENT2026DEMO%';
    v_buyers := v_sellers;

    FOR v_day IN
        SELECT d::DATE FROM generate_series(
            (CURRENT_DATE - v_days)::TIMESTAMP, CURRENT_DATE::TIMESTAMP, '1 day') d
    LOOP
        -- Weekends: the trading session does not run, so no trades.
        CONTINUE WHEN EXTRACT(ISODOW FROM v_day) IN (6, 7);

        -- 0 to 4 trades a day. Some days are genuinely empty, which the chart
        -- must show as a break rather than interpolate through.
        v_trades_today := (random() * 4)::INT;
        CONTINUE WHEN v_trades_today = 0;

        -- Random walk with a slight upward drift, clamped so it stays sane.
        v_price := v_price * (1 + (random() - 0.47) * 0.012);
        v_price := GREATEST(58000, LEAST(78000, v_price));
        v_price := round(v_price, 2);

        FOR v_i IN 1..v_trades_today LOOP
            v_seq := v_seq + 1;
            v_seller := v_sellers[1 + (random() * (array_length(v_sellers, 1) - 1))::INT];
            v_buyer := v_buyers[1 + (random() * (array_length(v_buyers, 1) - 1))::INT];
            CONTINUE WHEN v_seller = v_buyer;

            v_cat := v_categories[1 + (random() * 3)::INT];
            v_cat_name := CASE v_cat
                WHEN 1002 THEN '电解铜' WHEN 1003 THEN '铝锭'
                WHEN 1004 THEN '锌锭' ELSE '碳酸锂' END;

            -- Price level varies by grade; copper is the base.
            v_price_today := round(v_price * CASE v_cat
                WHEN 1002 THEN 1.00 WHEN 1003 THEN 0.36
                WHEN 1004 THEN 0.39 ELSE 2.00 END, 2);

            v_qty := (5 + (random() * 45))::NUMERIC(18,3);
            v_amount := round(v_price_today * v_qty, 4);

            -- Between 09:00 and 16:45 on the trading day.
            v_ts := (v_day + TIME '09:00') + (random() * INTERVAL '7 hours 45 minutes');

            INSERT INTO t_order (id, order_no, buyer_id, seller_id, category_id,
                commodity_name, spec, quantity, unit, price, amount,
                warehouse_id, delivery_method, payment_terms, status, version,
                confirmed_at, created_at, updated_at)
            VALUES (v_seq, 'OR2026DEMO' || LPAD((v_seq % 100000)::TEXT, 5, '0'),
                    v_buyer, v_seller, v_cat, v_cat_name, '{}'::jsonb,
                    v_qty, '吨', v_price_today, v_amount,
                    2001, 'SELF_PICKUP', 'MARGIN_THEN_BALANCE',
                    'COMPLETED', 0, v_ts + INTERVAL '1 hour', v_ts, v_ts);

            v_log_seq := v_log_seq - 1;
            INSERT INTO t_order_status_log (id, order_id, from_status, to_status,
                operator, reason, created_at)
            VALUES (v_log_seq, v_seq, NULL, 'COMPLETED',
                    '系统', '演示数据：历史成交', v_ts);

            -- Give the buyer a note for what they bought, mirroring what the
            -- real acceptance flow does.
            v_seq := v_seq + 1;
            INSERT INTO t_inventory_note (id, note_no, enterprise_id, category_id,
                warehouse_id, commodity_name, brand, origin, spec,
                total_quantity, available_quantity, frozen_quantity, unit, status, version,
                remark, created_at)
            VALUES (v_seq, 'IN2026DEMO' || LPAD((v_seq % 100000)::TEXT, 5, '0'),
                    v_buyer, v_cat, 2001, v_cat_name || '现货', '国产', '浙江', '{}'::jsonb,
                    v_qty, v_qty, 0, '吨', 2, 0, '演示数据：成交所得', v_ts);
        END LOOP;
    END LOOP;

    -- ---------------------------------------------------------------
    -- A few listings still open, so the market hall is not empty.
    -- Each picks a note with enough free goods and freezes it, mirroring
    -- what publishing a listing actually does.
    -- ---------------------------------------------------------------
    FOR v_note_id, v_seller, v_cat, v_cat_name, v_qty IN
        SELECT n.id, n.enterprise_id, n.category_id, n.commodity_name,
               LEAST(n.available_quantity, 20)::NUMERIC(18,3)
        FROM t_inventory_note n
        WHERE n.note_no LIKE 'IN2026DEMO%'
          AND n.available_quantity >= 20
          AND n.status = 2
        ORDER BY n.id
        LIMIT 5
    LOOP
        v_seq := v_seq + 1;
        INSERT INTO t_listing (id, listing_no, enterprise_id, side, category_id,
            commodity_name, brand, origin, spec, quantity, remaining_quantity, unit,
            price, price_type, warehouse_id, delivery_method, payment_terms,
            freeze_id, valid_until, status, version, created_at)
        VALUES (v_seq, 'LS2026DEMO' || LPAD((v_seq % 100000)::TEXT, 5, '0'),
                v_seller, 'SELL', v_cat, v_cat_name, '国产', '浙江', '{}'::jsonb,
                v_qty, v_qty, '吨',
                68000 + (v_seq % 400), 'FIXED', 2001, 'SELF_PICKUP', 'MARGIN_THEN_BALANCE',
                NULL, now() + INTERVAL '7 days', 'OPEN', 0, now() - INTERVAL '1 day');

        -- Freeze the goods the listing offers, exactly as the service does.
        UPDATE t_inventory_note
        SET available_quantity = available_quantity - v_qty,
            frozen_quantity = frozen_quantity + v_qty,
            status = 4
        WHERE id = v_note_id;
    END LOOP;

    RAISE NOTICE 'Demo data generated.';
END $$;

-- Summary
SELECT '专营企业' AS 项目, count(*)::TEXT AS 数量 FROM t_enterprise WHERE enterprise_code LIKE 'ENT2026DEMO%'
UNION ALL SELECT '成交订单', count(*)::TEXT FROM t_order WHERE order_no LIKE 'OR2026DEMO%'
UNION ALL SELECT '成交跨天', (max(created_at)::date - min(created_at)::date)::TEXT || ' 天'
    FROM t_order WHERE order_no LIKE 'OR2026DEMO%'
UNION ALL SELECT '挂牌', count(*)::TEXT FROM t_listing WHERE listing_no LIKE 'LS2026DEMO%'
UNION ALL SELECT '库存单', count(*)::TEXT FROM t_inventory_note WHERE note_no LIKE 'IN2026DEMO%';
