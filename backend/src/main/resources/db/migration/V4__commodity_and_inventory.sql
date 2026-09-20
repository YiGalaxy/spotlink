-- =============================================================================
-- V4 Commodity catalogue, warehouses, electronic inventory notes, freezes.
--
-- Two ideas carry most of the weight here.
--
-- 1. An "electronic inventory note" (电子库存单) is NOT a warehouse receipt
--    (仓单). Under the Civil Code a warehouse receipt is a document of title:
--    it can be pledged and endorsed. Trading a document of title as a
--    standardised instrument is what gets a spot platform reclassified as a
--    de facto futures exchange. Calling it an inventory note and defining it as
--    nothing more than a digital record of goods in a named warehouse keeps the
--    platform on the spot side of that line.
--
-- 2. Quantity is split into total / available / frozen rather than a single
--    number that gets decremented. A listing does not remove goods from the
--    owner, it reserves them; when the listing expires the reservation is
--    released. Keeping the three figures lets the platform answer "how much do
--    I own" and "how much can I still offer" separately, and makes an
--    over-release detectable instead of silently double-counted.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- t_commodity_category: platform-maintained catalogue tree.
-- path is a materialised ancestor chain ('/1/7/23/') so a subtree query is a
-- prefix match instead of a recursive walk.
-- -----------------------------------------------------------------------------
CREATE TABLE t_commodity_category (
    id          BIGINT       PRIMARY KEY,
    parent_id   BIGINT       NOT NULL DEFAULT 0,
    code        VARCHAR(64)  NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    level       SMALLINT     NOT NULL DEFAULT 1,
    path        VARCHAR(256) NOT NULL DEFAULT '',
    sort_order  INT          NOT NULL DEFAULT 0,
    -- Field definitions a commodity in this category must supply,
    -- e.g. [{"key":"cu_content","label":"铜含量","type":"number","unit":"%"}]
    spec_schema JSONB        NOT NULL DEFAULT '[]'::jsonb,
    unit        VARCHAR(16)  NOT NULL DEFAULT '吨',
    status      SMALLINT     NOT NULL DEFAULT 1,
    remark      VARCHAR(256),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by  BIGINT,
    updated_by  BIGINT,
    deleted     SMALLINT     NOT NULL DEFAULT 0
);

COMMENT ON TABLE  t_commodity_category IS 'Commodity catalogue tree maintained by the platform.';
COMMENT ON COLUMN t_commodity_category.path IS 'Materialised ancestor path, e.g. /1/7/23/ for prefix queries.';
COMMENT ON COLUMN t_commodity_category.spec_schema IS 'Specification fields required for commodities in this category.';

CREATE UNIQUE INDEX uk_category_code ON t_commodity_category (code) WHERE deleted = 0;
CREATE INDEX idx_category_parent ON t_commodity_category (parent_id) WHERE deleted = 0;
CREATE INDEX idx_category_path ON t_commodity_category (path text_pattern_ops) WHERE deleted = 0;

SELECT attach_updated_at_trigger('t_commodity_category');

-- -----------------------------------------------------------------------------
-- t_warehouse: designated delivery warehouses (指定交收仓库).
-- Goods live here, not on the platform. The platform records where they are;
-- it never holds them.
-- -----------------------------------------------------------------------------
CREATE TABLE t_warehouse (
    id           BIGINT       PRIMARY KEY,
    code         VARCHAR(32)  NOT NULL,
    name         VARCHAR(128) NOT NULL,
    short_name   VARCHAR(64),
    province     VARCHAR(32),
    city         VARCHAR(32),
    address      VARCHAR(256),
    contact_name VARCHAR(64),
    contact_phone VARCHAR(32),
    status       SMALLINT     NOT NULL DEFAULT 1,
    remark       VARCHAR(256),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by   BIGINT,
    updated_by   BIGINT,
    deleted      SMALLINT     NOT NULL DEFAULT 0
);

COMMENT ON TABLE t_warehouse IS 'Designated delivery warehouse. Goods are stored here, not by the platform.';

CREATE UNIQUE INDEX uk_warehouse_code ON t_warehouse (code) WHERE deleted = 0;
CREATE INDEX idx_warehouse_status ON t_warehouse (status) WHERE deleted = 0;

SELECT attach_updated_at_trigger('t_warehouse');

-- -----------------------------------------------------------------------------
-- t_inventory_note: electronic inventory note (电子库存单).
--
-- This is the trading subject, not a document of title. See the header note.
--
-- status: 0=draft, 1=pending review, 2=in stock, 3=fully frozen,
--         4=partially frozen, 5=delivered, 6=cancelled
-- -----------------------------------------------------------------------------
CREATE TABLE t_inventory_note (
    id                 BIGINT         PRIMARY KEY,
    note_no            VARCHAR(32)    NOT NULL,
    enterprise_id      BIGINT         NOT NULL,
    category_id        BIGINT         NOT NULL,
    warehouse_id       BIGINT         NOT NULL,
    commodity_name     VARCHAR(128)   NOT NULL,
    brand              VARCHAR(64),
    origin             VARCHAR(64),
    spec               JSONB          NOT NULL DEFAULT '{}'::jsonb,
    total_quantity     NUMERIC(18,3)  NOT NULL,
    available_quantity NUMERIC(18,3)  NOT NULL,
    frozen_quantity    NUMERIC(18,3)  NOT NULL DEFAULT 0,
    unit               VARCHAR(16)    NOT NULL DEFAULT '吨',
    -- Expected to be zero or a positive remainder; kept for auditability.
    quality_report_key VARCHAR(256),
    production_date    DATE,
    status             SMALLINT       NOT NULL DEFAULT 1,
    -- Optimistic lock. Every quantity change goes through
    -- UPDATE ... WHERE id = ? AND version = ?, so two concurrent freezes
    -- cannot both succeed against the same available quantity.
    version            INT            NOT NULL DEFAULT 0,
    remark             VARCHAR(512),
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by         BIGINT,
    updated_by         BIGINT,
    deleted            SMALLINT       NOT NULL DEFAULT 0,

    -- The invariant that makes the three quantity columns trustworthy.
    CONSTRAINT ck_inventory_quantity_balance
        CHECK (available_quantity + frozen_quantity = total_quantity),
    CONSTRAINT ck_inventory_quantity_non_negative
        CHECK (total_quantity >= 0 AND available_quantity >= 0 AND frozen_quantity >= 0)
);

COMMENT ON TABLE  t_inventory_note IS
    'Electronic inventory note (电子库存单). A digital record of goods held in a '
    'designated warehouse. It is NOT a warehouse receipt and confers no title.';
COMMENT ON COLUMN t_inventory_note.available_quantity IS 'Free to list or sell.';
COMMENT ON COLUMN t_inventory_note.frozen_quantity IS 'Reserved by an active listing or order.';
COMMENT ON COLUMN t_inventory_note.version IS 'Optimistic lock for concurrent quantity changes.';

CREATE UNIQUE INDEX uk_inventory_note_no ON t_inventory_note (note_no) WHERE deleted = 0;
CREATE INDEX idx_inventory_owner ON t_inventory_note (enterprise_id, status) WHERE deleted = 0;
CREATE INDEX idx_inventory_category ON t_inventory_note (category_id) WHERE deleted = 0;
CREATE INDEX idx_inventory_warehouse ON t_inventory_note (warehouse_id) WHERE deleted = 0;
-- Partial index for the quantity check: only in-stock notes can be frozen.
CREATE INDEX idx_inventory_available ON t_inventory_note (enterprise_id)
    WHERE deleted = 0 AND status IN (2, 3, 4);

SELECT attach_updated_at_trigger('t_inventory_note');

-- -----------------------------------------------------------------------------
-- t_freeze_record: one table for both goods and money.
--
-- A listing freezes goods; an order freezes money. Both are the same shape:
-- "this much of that thing is reserved for this reason, until released or
-- consumed". Splitting them into two tables would duplicate the state machine
-- and the release logic for no benefit.
--
-- entity_type: INVENTORY (goods) or FUND (money)
-- status:      FROZEN, RELEASED, CONSUMED
-- -----------------------------------------------------------------------------
CREATE TABLE t_freeze_record (
    id            BIGINT        PRIMARY KEY,
    freeze_no     VARCHAR(32)   NOT NULL,
    enterprise_id BIGINT        NOT NULL,
    entity_type   VARCHAR(16)   NOT NULL,
    entity_id     BIGINT        NOT NULL,
    -- Exactly one of these is populated: quantity for goods, amount for money.
    quantity      NUMERIC(18,3),
    amount        NUMERIC(19,4),
    biz_type      VARCHAR(32)   NOT NULL,
    biz_id        BIGINT,
    status        VARCHAR(16)   NOT NULL DEFAULT 'FROZEN',
    reason        VARCHAR(256),
    released_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by    BIGINT,
    updated_by    BIGINT,

    CONSTRAINT ck_freeze_entity_type CHECK (entity_type IN ('INVENTORY', 'FUND')),
    CONSTRAINT ck_freeze_status CHECK (status IN ('FROZEN', 'RELEASED', 'CONSUMED')),
    -- A freeze must reserve something, and reserve it in the right unit.
    CONSTRAINT ck_freeze_payload CHECK (
        (entity_type = 'INVENTORY' AND quantity IS NOT NULL AND quantity > 0 AND amount IS NULL)
     OR (entity_type = 'FUND'      AND amount   IS NOT NULL AND amount   > 0 AND quantity IS NULL)
    )
);

COMMENT ON TABLE  t_freeze_record IS
    'A reservation of goods or money. One table for both: same lifecycle, same release path.';
COMMENT ON COLUMN t_freeze_record.biz_type IS 'What caused the freeze, e.g. LISTING, ORDER.';
COMMENT ON COLUMN t_freeze_record.entity_id IS 'Inventory note id for INVENTORY, account id for FUND.';

CREATE UNIQUE INDEX uk_freeze_no ON t_freeze_record (freeze_no);
CREATE INDEX idx_freeze_entity ON t_freeze_record (entity_type, entity_id, status);
CREATE INDEX idx_freeze_biz ON t_freeze_record (biz_type, biz_id);
CREATE INDEX idx_freeze_owner ON t_freeze_record (enterprise_id, status, created_at DESC);

SELECT attach_updated_at_trigger('t_freeze_record');

-- -----------------------------------------------------------------------------
-- Seed catalogue and one warehouse, so a fresh database is usable immediately.
-- Codes are stable; names can be edited by operators later.
-- -----------------------------------------------------------------------------
INSERT INTO t_commodity_category (id, parent_id, code, name, level, path, sort_order, unit, spec_schema)
VALUES
    (1001, 0,    'NFJSC', '有色金属', 1, '/1001/', 10, '吨',
     '[]'::jsonb),
    (1002, 1001, 'DIANTONG', '电解铜', 2, '/1001/1002/', 10, '吨',
     '[{"key":"cu_content","label":"铜含量","type":"number","unit":"%","required":true},
       {"key":"standard","label":"执行标准","type":"string","required":false}]'::jsonb),
    (1003, 1001, 'LVING', '铝锭', 2, '/1001/1003/', 20, '吨',
     '[{"key":"al_content","label":"铝含量","type":"number","unit":"%","required":true}]'::jsonb),
    (1004, 1001, 'XINDING', '锌锭', 2, '/1001/1004/', 30, '吨',
     '[{"key":"zn_content","label":"锌含量","type":"number","unit":"%","required":true}]'::jsonb),
    (1005, 0,    'XNY', '新能源材料', 1, '/1005/', 20, '吨', '[]'::jsonb),
    (1006, 1005, 'TSSL', '碳酸锂', 2, '/1005/1006/', 10, '吨',
     '[{"key":"li_content","label":"锂含量","type":"number","unit":"%","required":true},
       {"key":"grade","label":"级别","type":"string","required":true}]'::jsonb),
    (1007, 1005, 'QYHL', '氢氧化锂', 2, '/1005/1007/', 20, '吨',
     '[{"key":"li_content","label":"锂含量","type":"number","unit":"%","required":true}]'::jsonb),
    (1008, 0,    'XJS', '小金属', 1, '/1008/', 30, '千克', '[]'::jsonb),
    (1009, 1008, 'JINGYIN', '精铟', 2, '/1008/1009/', 10, '千克',
     '[{"key":"in_content","label":"铟含量","type":"number","unit":"%","required":true}]'::jsonb);

INSERT INTO t_warehouse (id, code, name, short_name, province, city, address, contact_name, contact_phone, status)
VALUES
    (2001, 'WH-HZ-01', '杭州金属材料交割仓', '杭州仓', '浙江省', '杭州市',
     '杭州市萧山区临江工业园区', '张仓管', '13900000001', 1),
    (2002, 'WH-SH-01', '上海有色金属交割仓', '上海仓', '上海市', '上海市',
     '上海市宝山区罗泾镇', '李仓管', '13900000002', 1);
