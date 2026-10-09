-- 成功台账与业务数据同事务提交；不写入“成功”本地标记作为提交依据。
CREATE TABLE t_dataset_import (
    dataset VARCHAR(32) NOT NULL,
    rule_version VARCHAR(32) NOT NULL,
    phase VARCHAR(16) NOT NULL,
    source_hash CHAR(64) NOT NULL,
    records_hash CHAR(64) NOT NULL,
    batch VARCHAR(64) NOT NULL,
    base_time VARCHAR(32) NOT NULL,
    row_count INT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'SUCCESS',
    committed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (dataset, rule_version, phase),
    CONSTRAINT ck_dataset_success CHECK (status = 'SUCCESS' AND row_count > 0)
);

CREATE TABLE t_dataset_record (
    table_name VARCHAR(64) NOT NULL,
    record_id BIGINT NOT NULL,
    business_key VARCHAR(256) NOT NULL,
    dataset VARCHAR(32) NOT NULL,
    rule_version VARCHAR(32) NOT NULL,
    phase VARCHAR(16) NOT NULL,
    scenario_id VARCHAR(64) NOT NULL,
    record_hash CHAR(64) NOT NULL,
    PRIMARY KEY (table_name, record_id),
    UNIQUE KEY uk_dataset_business_key (table_name, business_key),
    CONSTRAINT fk_dataset_record_import FOREIGN KEY (dataset, rule_version, phase)
        REFERENCES t_dataset_import (dataset, rule_version, phase)
);
