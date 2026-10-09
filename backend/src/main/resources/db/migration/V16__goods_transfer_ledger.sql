-- 成交货权与物理交收分开记录；新成交的买方库存待交收期间受限。
ALTER TABLE t_inventory_note ADD CONSTRAINT ck_inventory_status CHECK (status BETWEEN 0 AND 7);

CREATE TABLE t_goods_transfer (
    id BIGINT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    seller_id BIGINT NOT NULL,
    buyer_id BIGINT NOT NULL,
    source_note_id BIGINT NOT NULL,
    target_note_id BIGINT NOT NULL,
    source_freeze_id BIGINT NOT NULL,
    target_freeze_id BIGINT NOT NULL,
    quantity DECIMAL(18,3) NOT NULL,
    unit VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'TRANSFERRED',
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_goods_transfer_order(order_id),
    UNIQUE KEY uk_goods_transfer_target(target_note_id),
    INDEX idx_goods_transfer_source(source_note_id),
    CONSTRAINT fk_goods_transfer_order FOREIGN KEY(order_id) REFERENCES t_order(id),
    CONSTRAINT fk_goods_transfer_source FOREIGN KEY(source_note_id) REFERENCES t_inventory_note(id),
    CONSTRAINT fk_goods_transfer_target FOREIGN KEY(target_note_id) REFERENCES t_inventory_note(id),
    CONSTRAINT fk_goods_transfer_source_freeze FOREIGN KEY(source_freeze_id) REFERENCES t_freeze_record(id),
    CONSTRAINT fk_goods_transfer_target_freeze FOREIGN KEY(target_freeze_id) REFERENCES t_freeze_record(id),
    CONSTRAINT ck_goods_transfer_parties CHECK(seller_id <> buyer_id AND source_note_id <> target_note_id),
    CONSTRAINT ck_goods_transfer_quantity CHECK(quantity > 0),
    CONSTRAINT ck_goods_transfer_status CHECK(status IN ('TRANSFERRED','DELIVERED','REVERSED'))
);
