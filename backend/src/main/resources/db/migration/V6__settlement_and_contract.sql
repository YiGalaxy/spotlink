-- =============================================================================
-- V6 Fund accounts, immutable flow ledger, and contracts.
--
-- Two ideas here.
--
-- 1. A balance is not a number that gets edited. It is the sum of a ledger.
--    Every movement writes a flow row; the account's balance columns are a
--    cached total kept for speed, never the source of truth. When the two
--    disagree, the ledger is right and the cache is wrong — which is a
--    reconciliation job, not a data loss.
--
-- 2. Signing is what turns an agreement into an obligation. The order state
--    machine refuses to move from CONFIRMED to DELIVERING without passing
--    through CONTRACTED, and this migration is what makes CONTRACTED reachable.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- t_fund_account: one margin/settlement account per enterprise.
--
-- available + frozen = balance, enforced by a check constraint, for the same
-- reason the inventory note enforces its own: an accounting slip should be
-- rejected on write, not discovered during settlement.
-- -----------------------------------------------------------------------------
CREATE TABLE t_fund_account (
    id                BIGINT         PRIMARY KEY,
    account_no        VARCHAR(32)    NOT NULL,
    enterprise_id     BIGINT         NOT NULL,
    balance           DECIMAL(19,4)  NOT NULL DEFAULT 0,
    available_balance DECIMAL(19,4)  NOT NULL DEFAULT 0,
    frozen_balance    DECIMAL(19,4)  NOT NULL DEFAULT 0,
    currency          VARCHAR(8)     NOT NULL DEFAULT 'CNY',
    status            SMALLINT       NOT NULL DEFAULT 1,
    version           INT            NOT NULL DEFAULT 0,
    created_at        DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by        BIGINT,
    updated_by        BIGINT,
    deleted           SMALLINT       NOT NULL DEFAULT 0,

    CONSTRAINT ck_account_balance_balance
        CHECK (available_balance + frozen_balance = balance),
    CONSTRAINT ck_account_non_negative
        CHECK (balance >= 0 AND available_balance >= 0 AND frozen_balance >= 0)
);

ALTER TABLE t_fund_account COMMENT = 'Enterprise fund account. Balances are a cached total; the ledger is the truth.';
ALTER TABLE t_fund_account MODIFY COLUMN frozen_balance DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT 'Reserved as margin for orders in flight.';

CREATE UNIQUE INDEX uk_account_no ON t_fund_account (account_no);
CREATE UNIQUE INDEX uk_account_enterprise ON t_fund_account (enterprise_id);

CREATE TRIGGER trg_t_fund_account_updated_at
    BEFORE UPDATE ON t_fund_account
    FOR EACH ROW
    SET NEW.updated_at = NOW(6);

-- -----------------------------------------------------------------------------
-- t_fund_flow: append-only ledger.
--
-- direction: IN or OUT, from the enterprise's point of view.
-- biz_type:  RECHARGE, WITHDRAW, MARGIN_FREEZE, MARGIN_RELEASE,
--            PAYMENT, REFUND
--
-- balance_after is written at the time of the movement so a statement can be
-- printed without replaying the whole ledger, while still being checkable by
-- replaying it.
-- -----------------------------------------------------------------------------
CREATE TABLE t_fund_flow (
    id            BIGINT         PRIMARY KEY,
    flow_no       VARCHAR(32)    NOT NULL,
    account_id    BIGINT         NOT NULL,
    enterprise_id BIGINT         NOT NULL,
    direction     VARCHAR(8)     NOT NULL,
    biz_type      VARCHAR(32)    NOT NULL,
    amount        DECIMAL(19,4)  NOT NULL,
    balance_after DECIMAL(19,4)  NOT NULL,
    biz_id        BIGINT,
    remark        VARCHAR(512),
    created_at    DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by    BIGINT,

    CONSTRAINT ck_flow_direction CHECK (direction IN ('IN', 'OUT')),
    CONSTRAINT ck_flow_amount CHECK (amount > 0)
);

ALTER TABLE t_fund_flow COMMENT =
    'Append-only ledger. Rows are never updated or deleted; a correction is a new row.';
ALTER TABLE t_fund_flow MODIFY COLUMN balance_after DECIMAL(19,4) NOT NULL COMMENT 'Account balance immediately after this movement, for statements.';

CREATE UNIQUE INDEX uk_flow_no ON t_fund_flow (flow_no);
CREATE INDEX idx_flow_account ON t_fund_flow (account_id, id DESC);
CREATE INDEX idx_flow_enterprise ON t_fund_flow (enterprise_id, created_at DESC);
CREATE INDEX idx_flow_biz ON t_fund_flow (biz_type, biz_id);

-- -----------------------------------------------------------------------------
-- t_contract: the signed agreement behind an order.
--
-- status: DRAFT, PENDING_SIGN, SIGNED, TERMINATED
--
-- Terms are stored as JSONB rather than as columns: they are written once,
-- read whole, and never queried by individual field.
-- -----------------------------------------------------------------------------
CREATE TABLE t_contract (
    id              BIGINT         PRIMARY KEY,
    contract_no     VARCHAR(32)    NOT NULL,
    order_id        BIGINT         NOT NULL,
    buyer_id        BIGINT         NOT NULL,
    seller_id       BIGINT         NOT NULL,
    title           VARCHAR(256)   NOT NULL,
    terms           JSON           NOT NULL DEFAULT (JSON_OBJECT()),
    -- Amount and quantity are duplicated from the order on purpose: a contract
    -- is a snapshot of what was agreed, and must not change if the order is
    -- later corrected.
    quantity        DECIMAL(18,3)  NOT NULL,
    unit            VARCHAR(16)    NOT NULL DEFAULT '吨',
    price           DECIMAL(19,4)  NOT NULL,
    amount          DECIMAL(19,4)  NOT NULL,
    -- Tolerance for weighing variance, in percent. Settlement beyond this is
    -- not automatic.
    weight_tolerance DECIMAL(5,2)  NOT NULL DEFAULT 3.00,

    status          VARCHAR(16)    NOT NULL DEFAULT 'PENDING_SIGN',
    buyer_signed_at  DATETIME(6),
    buyer_signed_by  BIGINT,
    seller_signed_at DATETIME(6),
    seller_signed_by BIGINT,

    terminated_at   DATETIME(6),
    terminate_reason VARCHAR(512),

    created_at      DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by      BIGINT,
    updated_by      BIGINT,
    deleted         SMALLINT       NOT NULL DEFAULT 0,

    CONSTRAINT ck_contract_status
        CHECK (status IN ('DRAFT', 'PENDING_SIGN', 'SIGNED', 'TERMINATED')),
    -- Signed means both sides signed; one signature is not a contract.
    CONSTRAINT ck_contract_signed_complete CHECK (
        status <> 'SIGNED'
        OR (buyer_signed_at IS NOT NULL AND seller_signed_at IS NOT NULL))
);

ALTER TABLE t_contract COMMENT =
    'The signed agreement behind an order. Terms are snapshotted, not referenced.';
ALTER TABLE t_contract MODIFY COLUMN weight_tolerance DECIMAL(5,2) NOT NULL DEFAULT 3.00 COMMENT
    'Allowed weighing variance in percent. Settlement beyond this needs human agreement.';

CREATE UNIQUE INDEX uk_contract_no ON t_contract (contract_no);
CREATE UNIQUE INDEX uk_contract_order ON t_contract (order_id);
CREATE INDEX idx_contract_buyer ON t_contract (buyer_id, status);
CREATE INDEX idx_contract_seller ON t_contract (seller_id, status);

CREATE TRIGGER trg_t_contract_updated_at
    BEFORE UPDATE ON t_contract
    FOR EACH ROW
    SET NEW.updated_at = NOW(6);

-- -----------------------------------------------------------------------------
-- Give the seeded enterprises an account each, so a fresh database can trade.
-- -----------------------------------------------------------------------------
INSERT INTO t_fund_account (id, account_no, enterprise_id, balance, available_balance, frozen_balance, status)
SELECT 3000 + row_number() OVER (ORDER BY id), CONCAT('ACC', enterprise_code), id,
       5000000.0000, 5000000.0000, 0.0000, 1
FROM t_enterprise
WHERE deleted = 0 AND trader_code IS NOT NULL;
