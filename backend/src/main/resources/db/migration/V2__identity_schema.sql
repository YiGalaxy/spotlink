-- =============================================================================
-- V2 Identity schema: enterprise (tenant root), user, role, permission, audit.
--
-- Multi-tenancy model: every business row belongs to exactly one enterprise.
-- The enterprise id is ALWAYS taken from the authenticated principal, never
-- from a request parameter, so a caller cannot read another tenant's data by
-- tampering with an id.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- t_enterprise: the tenant root. A trading company on the platform.
-- status: 0=pending review, 1=approved, 2=rejected, 3=frozen, 4=closed
-- -----------------------------------------------------------------------------
CREATE TABLE t_enterprise (
    id                         BIGINT       PRIMARY KEY,
    enterprise_code            VARCHAR(32)  NOT NULL,
    name                       VARCHAR(128) NOT NULL,
    short_name                 VARCHAR(64),
    unified_social_credit_code VARCHAR(32)  NOT NULL,
    legal_person               VARCHAR(64),
    contact_name               VARCHAR(64),
    contact_phone              VARCHAR(32),
    contact_email              VARCHAR(128),
    province                   VARCHAR(32),
    city                       VARCHAR(32),
    address                    VARCHAR(256),
    -- Trader seat code issued by the platform once the enterprise is approved.
    trader_code                VARCHAR(16),
    status                     SMALLINT     NOT NULL DEFAULT 0,
    -- Uploaded qualification documents: [{type, name, objectKey, uploadedAt}]
    qualifications             JSON         NOT NULL DEFAULT (JSON_ARRAY()),
    margin_account_id          BIGINT,
    registered_at              DATETIME(6),
    approved_at                DATETIME(6),
    approved_by                BIGINT,
    reject_reason              VARCHAR(512),
    remark                     VARCHAR(512),
    created_at                 DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                 DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by                 BIGINT,
    updated_by                 BIGINT,
    deleted                    SMALLINT     NOT NULL DEFAULT 0
);

ALTER TABLE t_enterprise COMMENT = 'Trading company (tenant root).';
ALTER TABLE t_enterprise MODIFY COLUMN trader_code VARCHAR(16) NULL COMMENT 'Trading seat code, unique per approved enterprise.';
ALTER TABLE t_enterprise MODIFY COLUMN margin_account_id BIGINT NULL COMMENT 'FK to the enterprise margin account, set when the account is opened.';

CREATE UNIQUE INDEX uk_enterprise_code   ON t_enterprise (enterprise_code);
CREATE UNIQUE INDEX uk_enterprise_uscc   ON t_enterprise (unified_social_credit_code);
CREATE UNIQUE INDEX uk_enterprise_trader ON t_enterprise (trader_code);
CREATE INDEX        idx_enterprise_status ON t_enterprise (status);
CREATE FULLTEXT INDEX idx_enterprise_name_trgm ON t_enterprise (name) WITH PARSER ngram;

CREATE TRIGGER trg_t_enterprise_updated_at
    BEFORE UPDATE ON t_enterprise
    FOR EACH ROW
    SET NEW.updated_at = NOW(6);

-- -----------------------------------------------------------------------------
-- t_user: platform account. enterprise_id is NULL for platform operators.
-- user_type: 1=enterprise user, 2=platform operator, 3=super admin
-- status:    0=disabled, 1=active, 2=locked
-- -----------------------------------------------------------------------------
CREATE TABLE t_user (
    id            BIGINT       PRIMARY KEY,
    enterprise_id BIGINT,
    username      VARCHAR(64)  NOT NULL,
    password      VARCHAR(128) NOT NULL,
    real_name     VARCHAR(64),
    phone         VARCHAR(32),
    email         VARCHAR(128),
    user_type     SMALLINT     NOT NULL DEFAULT 1,
    status        SMALLINT     NOT NULL DEFAULT 1,
    last_login_at DATETIME(6),
    last_login_ip VARCHAR(64),
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by    BIGINT,
    updated_by    BIGINT,
    deleted       SMALLINT     NOT NULL DEFAULT 0
);

ALTER TABLE t_user COMMENT = 'Platform user account. enterprise_id is NULL for platform operators.';
ALTER TABLE t_user MODIFY COLUMN password VARCHAR(128) NOT NULL COMMENT 'BCrypt hash, never a plaintext or reversible value.';

CREATE UNIQUE INDEX uk_user_username   ON t_user (username);
CREATE INDEX        idx_user_enterprise ON t_user (enterprise_id);

CREATE TRIGGER trg_t_user_updated_at
    BEFORE UPDATE ON t_user
    FOR EACH ROW
    SET NEW.updated_at = NOW(6);

-- -----------------------------------------------------------------------------
-- t_role: platform roles have enterprise_id = NULL (shared), company roles are
-- scoped to the owning enterprise.
-- -----------------------------------------------------------------------------
CREATE TABLE t_role (
    id            BIGINT       PRIMARY KEY,
    enterprise_id BIGINT,
    code          VARCHAR(64)  NOT NULL,
    name          VARCHAR(64)  NOT NULL,
    description   VARCHAR(256),
    is_system     TINYINT(1)   NOT NULL DEFAULT 0,
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by    BIGINT,
    updated_by    BIGINT,
    deleted       SMALLINT     NOT NULL DEFAULT 0
);

ALTER TABLE t_role COMMENT = 'Role definition. enterprise_id NULL means a platform-wide role.';

CREATE UNIQUE INDEX uk_role_code ON t_role ((COALESCE(enterprise_id, 0)), code);
CREATE INDEX        idx_role_enterprise ON t_role (enterprise_id);

CREATE TRIGGER trg_t_role_updated_at
    BEFORE UPDATE ON t_role
    FOR EACH ROW
    SET NEW.updated_at = NOW(6);

-- -----------------------------------------------------------------------------
-- t_permission: platform-defined permission catalogue (not tenant scoped).
-- perm_type: 1=menu, 2=button, 3=api
-- -----------------------------------------------------------------------------
CREATE TABLE t_permission (
    id         BIGINT       PRIMARY KEY,
    parent_id  BIGINT       NOT NULL DEFAULT 0,
    code       VARCHAR(128) NOT NULL,
    name       VARCHAR(64)  NOT NULL,
    perm_type  SMALLINT     NOT NULL DEFAULT 1,
    path       VARCHAR(256),
    sort_order INT          NOT NULL DEFAULT 0,
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted    SMALLINT     NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_permission_code ON t_permission (code);
CREATE INDEX        idx_permission_parent ON t_permission (parent_id);

CREATE TRIGGER trg_t_permission_updated_at
    BEFORE UPDATE ON t_permission
    FOR EACH ROW
    SET NEW.updated_at = NOW(6);

-- -----------------------------------------------------------------------------
-- Join tables
-- -----------------------------------------------------------------------------
CREATE TABLE t_user_role (
    id         BIGINT      PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    role_id    BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE UNIQUE INDEX uk_user_role ON t_user_role (user_id, role_id);
CREATE INDEX        idx_user_role_role ON t_user_role (role_id);

CREATE TABLE t_role_permission (
    id            BIGINT      PRIMARY KEY,
    role_id       BIGINT      NOT NULL,
    permission_id BIGINT      NOT NULL,
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE UNIQUE INDEX uk_role_permission ON t_role_permission (role_id, permission_id);
CREATE INDEX        idx_role_permission_perm ON t_role_permission (permission_id);

-- -----------------------------------------------------------------------------
-- t_audit_log: append-only record of every state-changing operation.
-- Written by an AOP aspect, never updated or deleted.
-- -----------------------------------------------------------------------------
CREATE TABLE t_audit_log (
    id            BIGINT       PRIMARY KEY,
    enterprise_id BIGINT,
    user_id       BIGINT,
    username      VARCHAR(64),
    module        VARCHAR(64)  NOT NULL,
    action        VARCHAR(64)  NOT NULL,
    target_type   VARCHAR(64),
    target_id     BIGINT,
    before_data   JSON,
    after_data    JSON,
    ip            VARCHAR(64),
    user_agent    VARCHAR(512),
    success       TINYINT(1)   NOT NULL DEFAULT 1,
    error_message VARCHAR(1024),
    cost_ms       BIGINT,
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

ALTER TABLE t_audit_log COMMENT =
    'Append-only audit trail. Rows are never updated or deleted. High-volume and
     out of scope for the soft-delete/updated_at conventions used elsewhere.';

CREATE INDEX idx_audit_enterprise_time ON t_audit_log (enterprise_id, created_at DESC);
CREATE INDEX idx_audit_target          ON t_audit_log (target_type, target_id);
CREATE INDEX idx_audit_user_time       ON t_audit_log (user_id, created_at DESC);
