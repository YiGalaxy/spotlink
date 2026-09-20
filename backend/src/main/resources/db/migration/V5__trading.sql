-- =============================================================================
-- V5 Trading: listings, orders and the state transitions between them.
--
-- The legal shape of a trade on this platform is offer and acceptance, not
-- matching. A listing (挂牌) is an offer published by one party; accepting it
-- (摘牌) is an acceptance. That framing is deliberate: a central matching
-- engine that discovers prices is what makes an exchange a futures exchange,
-- whereas two named parties agreeing one contract at a time is ordinary spot
-- trade. The schema is shaped to make the second thing easy and the first
-- thing absent.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- t_listing: an offer to sell, or a request to buy.
--
-- side: SELL (卖方挂牌) or BUY (买方挂牌)
-- price_type: FIXED (挂单价) or NEGOTIABLE (面议)
-- status: OPEN, PARTIALLY_FILLED, FILLED, CLOSED, EXPIRED
-- -----------------------------------------------------------------------------
CREATE TABLE t_listing (
    id                 BIGINT         PRIMARY KEY,
    listing_no         VARCHAR(32)    NOT NULL,
    enterprise_id      BIGINT         NOT NULL,
    side               VARCHAR(8)     NOT NULL,

    category_id        BIGINT         NOT NULL,
    commodity_name     VARCHAR(128)   NOT NULL,
    brand              VARCHAR(64),
    origin             VARCHAR(64),
    spec               JSONB          NOT NULL DEFAULT '{}'::jsonb,

    quantity           NUMERIC(18,3)  NOT NULL,
    -- Decremented as trade happens; a listing can be taken in several parts.
    remaining_quantity NUMERIC(18,3)  NOT NULL,
    unit               VARCHAR(16)    NOT NULL DEFAULT '吨',

    price              NUMERIC(19,4),
    price_type         VARCHAR(16)    NOT NULL DEFAULT 'NEGOTIABLE',

    warehouse_id       BIGINT,
    delivery_method    VARCHAR(16)    NOT NULL DEFAULT 'SELF_PICKUP',
    payment_terms      VARCHAR(32)    NOT NULL DEFAULT 'MARGIN_THEN_BALANCE',

    -- Goods for a SELL listing: frozen when the listing is published, released
    -- when it closes. Null for BUY listings, which reserve money instead.
    freeze_id          BIGINT,

    valid_until        TIMESTAMPTZ    NOT NULL,
    status             VARCHAR(24)    NOT NULL DEFAULT 'OPEN',

    version            INT            NOT NULL DEFAULT 0,
    remark             VARCHAR(512),

    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by         BIGINT,
    updated_by         BIGINT,
    deleted            SMALLINT       NOT NULL DEFAULT 0,

    CONSTRAINT ck_listing_side CHECK (side IN ('SELL', 'BUY')),
    CONSTRAINT ck_listing_price_type CHECK (price_type IN ('FIXED', 'NEGOTIABLE')),
    CONSTRAINT ck_listing_status
        CHECK (status IN ('OPEN', 'PARTIALLY_FILLED', 'FILLED', 'CLOSED', 'EXPIRED')),
    CONSTRAINT ck_listing_delivery CHECK (delivery_method IN ('SELF_PICKUP', 'DELIVERED')),
    -- A fixed-price listing must carry a price; a negotiable one must not
    -- pretend to have one.
    CONSTRAINT ck_listing_price_present
        CHECK ((price_type = 'FIXED' AND price IS NOT NULL AND price > 0)
            OR (price_type = 'NEGOTIABLE' AND price IS NULL)),
    CONSTRAINT ck_listing_remaining
        CHECK (remaining_quantity >= 0 AND remaining_quantity <= quantity)
);

COMMENT ON TABLE  t_listing IS
    'An offer to sell (挂牌) or a request to buy. Publishing it is making an offer.';
COMMENT ON COLUMN t_listing.remaining_quantity IS 'Still open to acceptance; a listing can be taken in parts.';
COMMENT ON COLUMN t_listing.freeze_id IS 'Goods freeze backing a SELL listing, released when it closes.';

CREATE UNIQUE INDEX uk_listing_no ON t_listing (listing_no) WHERE deleted = 0;
CREATE INDEX idx_listing_market ON t_listing (status, side, category_id) WHERE deleted = 0;
CREATE INDEX idx_listing_owner ON t_listing (enterprise_id, status, created_at DESC) WHERE deleted = 0;
-- Expiry sweeps scan open listings by deadline.
CREATE INDEX idx_listing_expiry ON t_listing (valid_until) WHERE deleted = 0 AND status IN ('OPEN', 'PARTIALLY_FILLED');

SELECT attach_updated_at_trigger('t_listing');

-- -----------------------------------------------------------------------------
-- t_order: the result of accepting a listing.
--
-- status machine (one-way except for the cancel branches):
--   PENDING_CONFIRM -> CONFIRMED -> CONTRACTED -> DELIVERING -> COMPLETED
--                   -> CANCELLED (before CONFIRMED)
--                   -> CANCELLED (after CONFIRMED, releases freezes)
-- -----------------------------------------------------------------------------
CREATE TABLE t_order (
    id                BIGINT         PRIMARY KEY,
    order_no          VARCHAR(32)    NOT NULL,
    listing_id        BIGINT,
    buyer_id          BIGINT         NOT NULL,
    seller_id         BIGINT         NOT NULL,
    -- Who placed this order. Both parties have an order row? No: one order,
    -- two sides, so either party can read it and the tenant filter is a
    -- two-column match rather than one.
    category_id       BIGINT         NOT NULL,
    commodity_name    VARCHAR(128)   NOT NULL,
    spec              JSONB          NOT NULL DEFAULT '{}'::jsonb,

    quantity          NUMERIC(18,3)  NOT NULL,
    unit              VARCHAR(16)    NOT NULL DEFAULT '吨',
    price             NUMERIC(19,4)  NOT NULL,
    -- Stored rather than recomputed: the price and quantity are what was agreed,
    -- and a later change to either must not silently restate history.
    amount            NUMERIC(19,4)  NOT NULL,

    warehouse_id      BIGINT,
    delivery_method   VARCHAR(16)    NOT NULL DEFAULT 'SELF_PICKUP',
    payment_terms     VARCHAR(32)    NOT NULL DEFAULT 'MARGIN_THEN_BALANCE',

    -- Goods freeze held on the seller side, money freeze on the buyer side.
    goods_freeze_id   BIGINT,
    margin_freeze_id  BIGINT,

    status            VARCHAR(24)    NOT NULL DEFAULT 'PENDING_CONFIRM',
    contract_id       BIGINT,

    confirmed_at      TIMESTAMPTZ,
    cancelled_at      TIMESTAMPTZ,
    cancel_reason     VARCHAR(256),

    version           INT            NOT NULL DEFAULT 0,
    remark            VARCHAR(512),

    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by        BIGINT,
    updated_by        BIGINT,
    deleted           SMALLINT       NOT NULL DEFAULT 0,

    CONSTRAINT ck_order_status CHECK (status IN (
        'PENDING_CONFIRM', 'CONFIRMED', 'CONTRACTED', 'DELIVERING', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_order_parties_differ CHECK (buyer_id <> seller_id),
    CONSTRAINT ck_order_quantity CHECK (quantity > 0),
    CONSTRAINT ck_order_amount CHECK (amount >= 0)
);

COMMENT ON TABLE  t_order IS
    'One contract-to-be between two named parties. Either side can read it, which '
    'is why the tenant column is a pair of party ids rather than an owner id.';
COMMENT ON COLUMN t_order.amount IS 'quantity * price, stored rather than recomputed.';

CREATE UNIQUE INDEX uk_order_no ON t_order (order_no) WHERE deleted = 0;
CREATE INDEX idx_order_buyer ON t_order (buyer_id, status, created_at DESC) WHERE deleted = 0;
CREATE INDEX idx_order_seller ON t_order (seller_id, status, created_at DESC) WHERE deleted = 0;
CREATE INDEX idx_order_listing ON t_order (listing_id) WHERE deleted = 0;

SELECT attach_updated_at_trigger('t_order');

-- -----------------------------------------------------------------------------
-- t_order_status_log: append-only record of every transition.
--
-- A status column tells you where an order is. It cannot tell you how it got
-- there, who moved it, or whether a transition happened twice. When two parties
-- disagree about what was agreed, that history is the only evidence.
-- -----------------------------------------------------------------------------
CREATE TABLE t_order_status_log (
    id          BIGINT      PRIMARY KEY,
    order_id    BIGINT      NOT NULL,
    from_status VARCHAR(24),
    to_status   VARCHAR(24) NOT NULL,
    operator_id BIGINT,
    operator    VARCHAR(64),
    reason      VARCHAR(256),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE t_order_status_log IS
    'Append-only order transition trail. Never updated, never deleted.';

CREATE INDEX idx_order_log ON t_order_status_log (order_id, id);
