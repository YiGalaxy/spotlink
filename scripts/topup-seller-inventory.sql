-- =============================================================================
-- Tops up 华东金属材料有限公司's stock so the trade flow can be exercised.
--
-- The demo generator hands most inventory to the six DEMO enterprises, which
-- leaves the seeded seller account (seller01) with too little to keep running
-- the end-to-end checks against. This adds a few notes it can actually sell.
--
-- Idempotent: note numbers are fixed, so re-running adds nothing.
--
--   docker exec -i spotlink-mysql mysql -ubulk -pbulk_trade_2026 bulk_trade \
--     < scripts/topup-seller-inventory.sql
-- =============================================================================

-- The note names below are Chinese and the mysql client picks its connection
-- character set from the OS locale, which is latin1 in this container; without
-- this line the UTF-8 bytes in this file would be stored double-encoded.
SET NAMES utf8mb4;

INSERT INTO t_inventory_note (
    id, note_no, enterprise_id, category_id, warehouse_id,
    commodity_name, brand, origin, spec,
    total_quantity, available_quantity, frozen_quantity, unit,
    production_date, status, version, remark
)
SELECT v.id, v.note_no,
       -- Resolved by code, never hard-coded. An earlier version carried a
       -- literal id, which worked until the database was rebuilt: snowflake ids
       -- are time-based, so a fresh schema issues a different one, and the rows
       -- landed on an enterprise that no longer existed — stock that nobody
       -- could see, with nothing failing to say so.
       e.id,
       v.category_id, v.warehouse_id,
       v.commodity_name, v.brand, v.origin, '{}',
       v.qty, v.qty, 0, '吨',
       DATE '2026-08-01', 2, 0, '演示库存'
  FROM (VALUES
        ROW(9000000000000000101, 'IN20260901000001', 1002, 2001,
            '江铜电解铜', '江铜', '江西', 200.000),
        ROW(9000000000000000102, 'IN20260901000002', 1003, 2001,
            '云铝铝锭',   '云铝', '云南', 300.000),
        ROW(9000000000000000103, 'IN20260901000003', 1004, 2002,
            '葫芦岛锌锭', '葫锌', '辽宁', 180.000),
        ROW(9000000000000000104, 'IN20260901000004', 1006, 2002,
            '电池级碳酸锂', '赣锋', '江西', 120.000)
       ) AS v(id, note_no, category_id, warehouse_id, commodity_name, brand, origin, qty)
  JOIN t_enterprise e
    ON e.enterprise_code = 'ENT20260920001'   -- 华东金属材料有限公司 (seller01)
   AND e.deleted = 0
 WHERE NOT EXISTS (
     SELECT 1 FROM t_inventory_note n WHERE n.note_no = v.note_no
 );

-- The trade flow reads its own writes back through this index, so make sure the
-- planner can see the new rows straight away.
ANALYZE TABLE t_inventory_note;
