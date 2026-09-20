-- =============================================================================
-- V10 The permission catalogue, and the platform roles that use it.
--
-- V2 created t_permission, t_role, t_user_role and t_role_permission and then
-- nothing ever wrote to them: no entity, no mapper, no row. The scaffolding was
-- real and empty, which is the worst of both — it looks like an authorization
-- system and enforces nothing. `@EnableMethodSecurity` was on for the same
-- length of time with no `@PreAuthorize` anywhere to justify it.
--
-- This fills the catalogue and the two roles that ship with the platform. The
-- code that reads them lands with it.
--
-- Codes are `resource:action` strings held as authorities, not roles. A role is
-- a bag of codes and nothing more. There are deliberately no `perm_type = 3`
-- (api) rows: an api code would restate the `@PreAuthorize` expression it
-- shadows, and two descriptions of one rule drift.
-- =============================================================================

-- ---------------------------------------------------------------- menus
-- perm_type = 1. `path` is the console route this code guards. It is
-- documentation rather than a driver: the frontend declares its own routes
-- because React Router needs them statically, and this column is what the
-- role-editing screen shows a human so they can see what they are granting.
INSERT INTO t_permission (id, parent_id, code, name, perm_type, path, sort_order) VALUES
    (9001, 0, 'admin:overview',   '平台概览',   1, '/admin/overview',    10),
    (9002, 0, 'admin:enterprise', '企业审核',   1, '/admin/enterprises', 20),
    (9003, 0, 'admin:order',      '订单查询',   1, '/admin/orders',      30),
    (9004, 0, 'admin:user',       '用户与权限', 1, '/admin/users',       40),
    (9005, 0, 'admin:audit',      '审计日志',   1, '/admin/audit',       50),
    (9006, 0, 'admin:knowledge',  '知识库',     1, '/admin/knowledge',   60);

-- ---------------------------------------------------------------- buttons
-- perm_type = 2. These are the ones worth splitting out from their menu: an
-- auditor holds every `admin:*` view code and no action code, which is how
-- "read-only operator" stops being a promise and becomes a fact the server
-- enforces.
INSERT INTO t_permission (id, parent_id, code, name, perm_type, sort_order) VALUES
    (9011, 9002, 'admin:enterprise:review', '企业审核通过/驳回', 2, 10),
    (9012, 9002, 'admin:enterprise:freeze', '企业冻结/解冻',     2, 20),
    (9021, 9004, 'admin:user:status',       '账号启用/禁用',     2, 10),
    (9022, 9004, 'admin:user:role',         '分配用户角色',      2, 20),
    (9031, 9006, 'admin:knowledge:embed',   '补算知识库向量',    2, 10);

-- ---------------------------------------------------------------- roles
-- enterprise_id NULL means a platform-wide role rather than a tenant's own.
INSERT INTO t_role (id, enterprise_id, code, name, description, is_system) VALUES
    (9100, NULL, 'PLATFORM_ADMIN',   '平台管理员', '运营后台全部权限',     TRUE),
    (9101, NULL, 'PLATFORM_AUDITOR', '平台审计员', '只读：概览与审计日志', TRUE);

-- Granted by pattern rather than by a hand-typed list of ids. A literal list is
-- a typo away from silently granting nothing, and the failure is invisible —
-- the role looks configured and its holder can do nothing.
--
-- The id bases are in the 7.1e18 range rather than anything rounder: BIGINT
-- stops at 9.22e18, and 9.2e18 onwards silently overflows. Silently is the
-- problem — MySQL raises an error here only because the value is out of range
-- on insert; a base chosen a little lower would have wrapped and produced
-- negative ids that look like data corruption much later.
INSERT INTO t_role_permission (id, role_id, permission_id)
SELECT 7110000000000000000 + ROW_NUMBER() OVER (ORDER BY p.id),
       r.id, p.id
  FROM t_role r
  JOIN t_permission p ON p.code LIKE 'admin:%'
 WHERE r.code = 'PLATFORM_ADMIN';

-- The auditor holds the views and the audit log, and nothing that changes
-- anything. Deliberately no `admin:knowledge`: reading the rulebook is not the
-- same as being trusted with the corpus the assistant answers from.
INSERT INTO t_role_permission (id, role_id, permission_id)
SELECT 7120000000000000000 + ROW_NUMBER() OVER (ORDER BY p.id),
       r.id, p.id
  FROM t_role r
  JOIN t_permission p ON p.code IN ('admin:overview', 'admin:enterprise', 'admin:order', 'admin:audit')
 WHERE r.code = 'PLATFORM_AUDITOR';

-- ---------------------------------------------------------------- backfill
-- Accounts that already exist as platform operators and hold no role would
-- otherwise need hand-written SQL to reach the console. On a fresh database
-- this matches nothing; development accounts are granted by
-- DevelopmentDataInitializer, which is the only thing that knows they exist.
INSERT INTO t_user_role (id, user_id, role_id)
SELECT 7130000000000000000 + ROW_NUMBER() OVER (ORDER BY u.id),
       u.id, r.id
  FROM t_user u
  JOIN t_role r ON r.code = 'PLATFORM_ADMIN'
 WHERE u.user_type = 2
   AND u.deleted = 0
   AND NOT EXISTS (SELECT 1 FROM t_user_role ur
                    WHERE ur.user_id = u.id AND ur.role_id = r.id);

-- ---------------------------------------------------------------- constraints
-- Both join tables were empty until this migration, so the foreign keys are
-- free to add now and would not be later. They guard hard deletes and typo'd
-- ids — the failure they need to catch here, because the rows above are the
-- first hand-written ids these tables have ever held.
ALTER TABLE t_user_role
    ADD CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES t_user (id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES t_role (id) ON DELETE CASCADE;

ALTER TABLE t_role_permission
    ADD CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES t_role (id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES t_permission (id) ON DELETE CASCADE;

ALTER TABLE t_permission
    ADD CONSTRAINT ck_permission_type CHECK (perm_type IN (1, 2, 3));

-- The status range the code already assumes. Added here because the console is
-- about to start writing this column, and until now nothing did.
ALTER TABLE t_enterprise
    ADD CONSTRAINT ck_enterprise_status CHECK (status BETWEEN 0 AND 4);

-- ---------------------------------------------------------------- audit
-- The console's main query is "the most recent N actions", with no tenant
-- filter — an operator reads the whole platform. All three existing indexes
-- lead with a column the console does not filter on, so that query was a full
-- scan plus a sort.
CREATE INDEX idx_audit_time ON t_audit_log (created_at DESC);
CREATE INDEX idx_audit_module_time ON t_audit_log (module, created_at DESC);

-- The V2 comment claimed the table was "written by an AOP aspect". There was no
-- such aspect and nothing ever wrote a row. The claim is now half true — the
-- writer exists — but what a reader cannot guess is the semantics, so that is
-- what the comment says instead of a class name.
ALTER TABLE t_audit_log COMMENT =
    'Append-only audit trail. Never updated, never deleted, and written only after the business transaction commits, so a rolled-back action leaves no trace. No updated_at or deleted columns by design: the soft-delete conventions used elsewhere do not apply to an immutable log.';

ALTER TABLE t_audit_log
    MODIFY COLUMN enterprise_id BIGINT NULL COMMENT 'The tenant the row is about; NULL for platform-level actions.',
    MODIFY COLUMN before_data JSON NULL COMMENT 'Target state before the action, when the caller supplied it.',
    MODIFY COLUMN after_data JSON NULL COMMENT 'Target state after the action, when the caller supplied it.';
