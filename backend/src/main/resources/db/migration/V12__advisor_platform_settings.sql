CREATE TABLE t_advisor_model_settings (
    id TINYINT PRIMARY KEY,
    enabled BOOLEAN NOT NULL,
    base_url VARCHAR(512) NOT NULL,
    model VARCHAR(128) NOT NULL,
    encrypted_api_key TEXT NULL,
    max_tokens INT NOT NULL,
    timeout_seconds INT NOT NULL,
    token_parameter VARCHAR(32) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT ck_advisor_settings_singleton CHECK (id = 1),
    CONSTRAINT ck_advisor_settings_tokens CHECK (max_tokens BETWEEN 64 AND 32768),
    CONSTRAINT ck_advisor_settings_timeout CHECK (timeout_seconds BETWEEN 5 AND 300)
) COMMENT='平台默认模型配置；API Key 使用独立密钥 AES-GCM 加密';

INSERT INTO t_permission (id,parent_id,code,name,perm_type,path,sort_order) VALUES
    (9007,0,'admin:advisor','模型配置',1,'/admin/model',70),
    (9041,9007,'admin:advisor:write','修改及测试平台模型',2,NULL,10);

INSERT INTO t_role_permission (id,role_id,permission_id)
SELECT 7140000000000000000 + ROW_NUMBER() OVER (ORDER BY p.id), r.id, p.id
FROM t_role r JOIN t_permission p ON p.code IN ('admin:advisor','admin:advisor:write')
WHERE r.code='PLATFORM_ADMIN';

INSERT INTO t_role_permission (id,role_id,permission_id)
SELECT 7150000000000000000 + ROW_NUMBER() OVER (ORDER BY p.id), r.id, p.id
FROM t_role r JOIN t_permission p ON p.code='admin:advisor'
WHERE r.code='PLATFORM_AUDITOR';
