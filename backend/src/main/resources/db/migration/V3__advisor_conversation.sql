-- =============================================================================
-- V3 Advisor conversations.
--
-- Conversations are scoped to a USER, not to an enterprise. A chat transcript
-- is personal working context; a colleague sharing the same company should not
-- see what questions another account asked.
--
-- Messages carry no soft-delete flag. They are removed with their conversation
-- and are never edited, so an updated_at/deleted pair would only add noise.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- t_ai_conversation
-- -----------------------------------------------------------------------------
CREATE TABLE t_ai_conversation (
    id              BIGINT       PRIMARY KEY,
    enterprise_id   BIGINT,
    user_id         BIGINT       NOT NULL,
    title           VARCHAR(128) NOT NULL DEFAULT '新对话',
    message_count   INT          NOT NULL DEFAULT 0,
    last_message_at DATETIME(6),
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by      BIGINT,
    updated_by      BIGINT,
    deleted         SMALLINT     NOT NULL DEFAULT 0
);

ALTER TABLE t_ai_conversation COMMENT = 'AI advisor chat session, owned by one user.';
ALTER TABLE t_ai_conversation MODIFY COLUMN title VARCHAR(128) NOT NULL DEFAULT '新对话' COMMENT 'Derived from the first user message; renaming is allowed.';

-- The list query is always "my conversations, most recent first".
CREATE INDEX idx_ai_conversation_owner
    ON t_ai_conversation (user_id, last_message_at DESC);

CREATE TRIGGER trg_t_ai_conversation_updated_at
    BEFORE UPDATE ON t_ai_conversation
    FOR EACH ROW
    SET NEW.updated_at = NOW(6);

-- -----------------------------------------------------------------------------
-- t_ai_message
--
-- The tool-call trail is stored as JSONB rather than in its own table: it is
-- only ever read back as a whole, to render under one answer, and never
-- queried across rows. A child table would add a join and buy nothing.
-- -----------------------------------------------------------------------------
CREATE TABLE t_ai_message (
    id                    BIGINT      PRIMARY KEY,
    conversation_id       BIGINT      NOT NULL,
    enterprise_id         BIGINT,
    user_id               BIGINT,
    role                  VARCHAR(16) NOT NULL,
    content               TEXT        NOT NULL,
    tool_calls            JSON,
    iterations            INT,
    input_tokens          BIGINT,
    output_tokens         BIGINT,
    cache_read_tokens     BIGINT,
    cache_creation_tokens BIGINT,
    model                 VARCHAR(64),
    created_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

ALTER TABLE t_ai_message COMMENT = 'One turn of an advisor conversation. Append-only.';
ALTER TABLE t_ai_message MODIFY COLUMN role VARCHAR(16) NOT NULL COMMENT 'user or assistant.';
ALTER TABLE t_ai_message MODIFY COLUMN tool_calls JSON NULL COMMENT 'Tool invocations behind an assistant answer: [{name,input,output}].';
ALTER TABLE t_ai_message MODIFY COLUMN cache_read_tokens BIGINT NULL COMMENT
    'Prompt-cache read count reported by the API. Used to verify caching actually works.';

-- History is always read in insertion order for one conversation. The id is a
-- snowflake, so ordering by it is both chronological and index-friendly.
CREATE INDEX idx_ai_message_conversation ON t_ai_message (conversation_id, id);
