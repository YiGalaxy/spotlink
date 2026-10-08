-- 保留现有行，发现孤立关联时迁移直接失败，由维护人员核对来源后修复。
ALTER TABLE t_inventory_note
    ADD CONSTRAINT fk_inventory_category FOREIGN KEY (category_id) REFERENCES t_commodity_category(id),
    ADD CONSTRAINT fk_inventory_warehouse FOREIGN KEY (warehouse_id) REFERENCES t_warehouse(id);
