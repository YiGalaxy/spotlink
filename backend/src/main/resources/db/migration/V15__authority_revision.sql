-- 授权版本与角色变更在同一事务提交；迟到的旧缓存只会写入旧版本键。
CREATE TABLE t_authority_revision (
    id TINYINT NOT NULL PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB;
INSERT INTO t_authority_revision (id, revision) VALUES (1, 0);
