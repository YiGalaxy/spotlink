-- 向量只对同一服务、模型、维度、版本及原文有效；旧无来源向量必须重建。
ALTER TABLE t_knowledge_chunk
    ADD COLUMN embedding_fingerprint VARCHAR(64) NULL,
    ADD COLUMN embedding_dimensions INT NULL,
    ADD COLUMN embedding_content_hash VARCHAR(64) NULL,
    ADD COLUMN embedded_at DATETIME(6) NULL;
