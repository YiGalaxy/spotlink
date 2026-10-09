ALTER TABLE t_ai_conversation ADD COLUMN engine VARCHAR(24) NOT NULL DEFAULT 'spring-ai';
ALTER TABLE t_ai_message ADD COLUMN engine VARCHAR(24) NOT NULL DEFAULT 'spring-ai',
    ADD COLUMN run_id VARCHAR(36) NULL;
