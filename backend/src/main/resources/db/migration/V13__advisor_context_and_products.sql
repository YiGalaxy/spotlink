ALTER TABLE t_ai_conversation ADD COLUMN context_note VARCHAR(2000) NULL COMMENT '用户维护的采购需求，仅属于该会话';
ALTER TABLE t_ai_message ADD COLUMN products_json JSON NULL COMMENT '由服务端挂牌工具生成的商品卡片';
