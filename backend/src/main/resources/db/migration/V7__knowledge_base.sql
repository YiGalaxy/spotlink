-- =============================================================================
-- V7 Knowledge base for retrieval-augmented answers.
--
-- Answers about platform rules ("保证金比例是多少", "怎么提现") live in
-- documents, not in tables. Retrieval is what lets the advisor answer them
-- with a citation instead of guessing.
--
-- Chunks carry both a vector and a trigram index. Vector search finds
-- paraphrases; trigram search finds exact terms. Chinese needs the second one
-- badly: "保证金" and "履约担保金" are semantically close but lexically
-- distinct, and conversely a question naming a specific document ("交收管理办法
-- 第几条") is answered by the literal string, not by embedding proximity.
-- Neither method alone covers both.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- t_knowledge_doc: a source document.
-- -----------------------------------------------------------------------------
CREATE TABLE t_knowledge_doc (
    id          BIGINT       PRIMARY KEY,
    doc_code    VARCHAR(64)  NOT NULL,
    title       VARCHAR(256) NOT NULL,
    category    VARCHAR(64)  NOT NULL DEFAULT 'RULE',
    source      VARCHAR(256),
    version     VARCHAR(32)  NOT NULL DEFAULT 'v1',
    status      SMALLINT     NOT NULL DEFAULT 1,
    remark      VARCHAR(512),
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by  BIGINT,
    updated_by  BIGINT,
    deleted     SMALLINT     NOT NULL DEFAULT 0
);

ALTER TABLE t_knowledge_doc COMMENT = 'Source document for retrieval. Rules, guides, standards.';

CREATE UNIQUE INDEX uk_knowledge_doc_code ON t_knowledge_doc (doc_code);
CREATE INDEX idx_knowledge_doc_category ON t_knowledge_doc (category);

CREATE TRIGGER trg_t_knowledge_doc_updated_at
    BEFORE UPDATE ON t_knowledge_doc
    FOR EACH ROW
    SET NEW.updated_at = NOW(6);

-- -----------------------------------------------------------------------------
-- t_knowledge_chunk: one retrievable passage and its embedding.
--
-- Embedding dimension is 1024, which is what BGE-M3 produces. Stored as a
-- fixed-width vector rather than a flexible one so the index can be built;
-- changing models means a migration, which is the correct amount of friction
-- for something that silently changes every stored vector's meaning.
-- -----------------------------------------------------------------------------
CREATE TABLE t_knowledge_chunk (
    id          BIGINT       PRIMARY KEY,
    doc_id      BIGINT       NOT NULL,
    chunk_index INT          NOT NULL,
    content     TEXT         NOT NULL,
    -- Nullable because a chunk can be stored before its embedding is computed;
    -- ingestion is a two-step process and a half-embedded corpus is still
    -- searchable by keyword.
    embedding   BLOB         NULL,
    token_count INT          NOT NULL DEFAULT 0,
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

ALTER TABLE t_knowledge_chunk COMMENT = 'A retrievable passage with its embedding.';
ALTER TABLE t_knowledge_chunk MODIFY COLUMN embedding BLOB NULL COMMENT 'BGE-M3 output, 1024 dimensions. Null until embedded.';

CREATE UNIQUE INDEX uk_knowledge_chunk ON t_knowledge_chunk (doc_id, chunk_index);
CREATE INDEX idx_knowledge_chunk_doc ON t_knowledge_chunk (doc_id);
-- Trigram index for keyword recall. Chinese has no whitespace tokenisation, so
-- PostgreSQL's built-in text search is close to useless here; trigrams work on
-- character sequences and handle CJK without a dictionary.
CREATE FULLTEXT INDEX idx_knowledge_chunk_content_trgm ON t_knowledge_chunk (content) WITH PARSER ngram;

-- -----------------------------------------------------------------------------
-- Seed the platform rules the advisor should be able to cite.
-- -----------------------------------------------------------------------------
INSERT INTO t_knowledge_doc (id, doc_code, title, category, source) VALUES
 (4001, 'RULE-TRADING',  '交易管理办法', 'RULE', '平台规则'),
 (4002, 'RULE-DELIVERY', '交收管理办法', 'RULE', '平台规则'),
 (4003, 'RULE-RISK',     '风险控制管理办法', 'RULE', '平台规则'),
 (4004, 'GUIDE-MARGIN',  '保证金与资金说明', 'GUIDE', '平台帮助中心');

INSERT INTO t_knowledge_chunk (id, doc_id, chunk_index, content) VALUES
 (4101, 4001, 0,
  '挂牌分为卖方挂牌和买方挂牌。挂牌是交易商通过交易平台预先公布要买卖商品的详细情况，包括商品名称、'
  '生产厂家、品牌、商品质量、价格、数量、交货地点、交提货方式等要素，经交易中心审核后发布买卖信息。'
  '卖方挂牌的，应当以经过审核的电子库存单作为标的。'),

 (4102, 4001, 1,
  '摘牌是指通过挂牌交易系统对挂牌方发出的要约进行应约（承诺）的行为。摘牌即视为买卖双方达成交易，'
  '系统自动生成订单。摘牌数量不得超过挂牌剩余数量。交易商不得摘取自己发布的挂牌。'),

 (4103, 4001, 2,
  '协议交易是指交易双方自行约定交易的商品、品牌、数量、价格、交货地点、交提货方式等交易要素，'
  '并在交易中心完成合同签订、货款清结算、货物交收的行为。协议交易默认保证金为零，'
  '但交易双方可自行约定并在订单中设置买方或卖方保证金比例。'),

 (4104, 4001, 3,
  '交易时间为每个交易日的上午 09:00 至 16:45。挂牌未能成交的，交易中心在当日交易结束后，'
  '通知指定交收仓库或第三方资金清算机构，对冻结的现货商品或挂牌保证金进行解冻。'),

 (4105, 4002, 0,
  '结算重量以实际过磅重量为准，而非合同约定重量。实际过磅重量与合同重量的差额称为磅差。'
  '磅差率在合同约定的溢短装范围内时，按实际重量结算；超出约定范围时，'
  '系统不自动结算，需由买卖双方协商处理。默认溢短装范围为 ±3%。'),

 (4106, 4002, 1,
  '买方应在收到货物后 7 日内提出质量异议，逾期未提出的视为验收合格。'
  '提出质量异议的，买卖双方应协商解决；协商不成的，可申请第三方质检机构复检。'
  '复检结论为最终结论，复检费用由责任方承担。'),

 (4107, 4003, 0,
  '交易商参与交易应当缴纳保证金。卖方挂牌的，交易中心与第三方仓单公示平台审核其电子库存单信息，'
  '审核通过后通知指定交收仓库对现货商品进行冻结，冻结完成后方可挂牌。'
  '买方挂牌的，应当缴纳挂牌保证金，由第三方资金清算机构对保证金进行冻结。'),

 (4108, 4003, 1,
  '成交保证金的缴纳比例、时间及调整等事项，由交易中心决定并公告。'
  '买方挂牌的，挂牌保证金可自动转为成交保证金，不足部分应及时补足。'
  '如交易商账户资金不足导致保证金冻结失败的，交易协议不予订立。'),

 (4109, 4004, 0,
  '平台不持有交易资金。所有资金通过第三方资金清算机构（大宗商品清算通）划转，'
  '由上海清算所会同现货清算成员银行为实体企业提供大额实时、跨行跨境的资金清算结算服务。'
  '平台的角色是登记与见证，不是交易对手，也不是资金池。'),

 (4110, 4004, 1,
  '资金账户分为总额、可用余额和冻结余额三个口径。保证金冻结时，资金总额不变，'
  '只是从可用余额转入冻结余额。订单取消或挂牌失效时，冻结金额解冻回到可用余额。'
  '只有订单交收完成时，货款才真正从买方账户划出至卖方账户。'),

 (4111, 4004, 2,
  '电子库存单是货物在指定交收仓库的数字化凭证，用于记录货物的品种、规格、数量与存放地点，'
  '其本身不构成物权凭证，不可转让、不可质押。平台不使用「仓单」作为交易标的，'
  '以避免与标准化合约交易产生混淆。');
