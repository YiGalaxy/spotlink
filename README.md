# 现货通 SpotLink

现货通 SpotLink 是一个面向大宗商品现货交易场景的 **AI 应用练手项目**。项目的重点不是把交易平台功能做得尽可能多，而是把一个能读取业务数据、调用业务工具、执行知识检索并返回可追溯答案的 AI 顾问跑通。

交易模块为 AI 顾问提供真实的数据和业务上下文：库存、挂牌、订单、合同、行情和待办都来自后端服务。顾问只负责理解问题和选择工具，权限校验、数据查询和业务规则仍由 Java 服务执行。

## AI 能力概览

| 能力 | 实现 | 练习重点 |
|---|---|---|
| 工具调用 Agent | Spring AI `ChatClient` + `@Tool` | 让模型选择工具，Java 负责执行和校验 |
| 私有数据问答 | 企业范围从登录上下文获取 | 防止模型参数越权和跨企业数据泄露 |
| 规则问答 RAG | BGE-M3 向量召回 + MySQL ngram 全文召回 + 加权 RRF | 混合检索、出处返回和故障降级 |
| 合同审查 | Java 规则决定是否开放合同正文工具 | 控制敏感内容发送范围 |
| Prompt Caching | 稳定系统提示词与调用方信息拆成两个 system block | 复用跨用户稳定前缀 |
| 工具调用轨迹 | AOP 记录工具名、参数和结果 | 让回答可核对、可诊断 |
| 本地 Embedding | Ollama 提供 BGE-M3 | 不依赖云端嵌入服务，服务异常时退化为关键词检索 |

## AI 请求链路

```text
用户问题
   |
   v
AdvisorController
   |
   v
ConversationService  -- 读取会话历史和当前用户
   |
   v
AdvisorAgent
   |-- 稳定系统提示词 + 当前调用方信息
   |-- 按问题决定本轮可用工具
   |-- Spring AI 执行模型与工具调用循环
   |
   +--> 业务工具：库存 / 挂牌 / 订单 / 合同 / 行情 / 待办
   |
   +--> 知识工具：BGE-M3 向量检索 + ngram 全文检索
   |
   v
工具轨迹 + 最终答案 + Token 用量
   |
   v
前端会话页面
```

## 关键实现

### 1. Agent 与工具调用

`backend/src/main/java/com/bulk/trade/advisor/agent/AdvisorAgent.java` 使用 Spring AI 构建顾问。模型可以调用当前注册的 14 个工具方法，工具分布在以下类中：

- `AdvisorTools`：企业信息和团队成员
- `InventoryAdvisorTools`：我的库存及库存汇总
- `ListingAdvisorTools`：我的挂牌和市场挂牌
- `OrderAdvisorTools`：我的订单及订单详情
- `ContractAdvisorTools`：我的合同列表
- `MarketAdvisorTools`：行情和价格趋势
- `TaskAdvisorTools`：跨模块待办
- `KnowledgeAdvisorTools`：平台规则检索
- `ContractReviewTools`：合同正文读取

工具不是直接访问数据库。每个工具调用现有 Service，沿用事务、权限和数据范围规则。模型只能选择工具，不能绕过 Service 自己拼 SQL 或指定其他企业的 ID。

### 2. 租户隔离和敏感数据边界

工具方法不接收 `enterpriseId` 作为用户输入，而是从当前登录上下文取得企业范围。这样可以避免模型通过参数把查询指向其他企业。

- 行情和公开挂牌可以读取平台范围数据
- 库存、订单、合同只读取当前企业数据
- 资金余额没有注册为 AI 工具，顾问不会读取账户余额
- 工具结果和知识文档都被视为数据，不能改变模型规则
- 前端路由守卫只改善体验，真正的鉴权由 Spring Security 和业务 Service 执行

### 3. 合同审查触发

合同正文包含更敏感的业务内容，不默认加入每一轮工具列表。`ContractReviewTrigger` 在 Java 中同时检查：

1. 用户是否提到合同或合同编号
2. 用户是否表达审查、审核、条款、风险等意图

只有两个条件都满足时，`get_contract_detail` 才会开放给模型。这个判断在提示词发送前完成，不交给模型自行决定。

### 4. RAG 混合检索

规则文档存储在 MySQL 的文档和分块表中。检索流程如下：

1. Ollama 的 BGE-M3 为问题生成 1024 维向量
2. Java 对已嵌入分块计算余弦相似度，取语义候选
3. MySQL ngram `FULLTEXT` 查询取关键词候选
4. `KnowledgeService` 使用加权 Reciprocal Rank Fusion 合并两路排名
5. 返回段落正文、文档编号和标题，交给顾问组织答案

Embedding 服务不可用时，向量召回为空，知识库仍可使用关键词召回。当前向量检索是 Java 内存扫描，适合项目内的小规模规则语料；大规模场景应替换为向量数据库或带向量索引的数据库方案。

### 5. Prompt Caching

`SystemPromptBuilder` 将系统提示词拆为两个块：

- 稳定块：角色、业务规则、工具使用规则和输出格式
- 动态块：当前用户、企业和权限范围

Spring AI 配置使用 `SYSTEM_ONLY` 和多块 system caching。这样稳定前缀可以跨用户复用，动态身份信息不会进入共享缓存。当前兼容网关不一定返回可验证的缓存命中数据，因此项目只实现了正确的配置和边界，没有虚构性能收益。

### 6. 可追溯的工具轨迹

`ToolCallRecordingAspect` 通过 AOP 拦截 `@Tool` 方法，记录工具名称、输入和输出。轨迹会随 AI 消息返回，便于用户核对“答案来自哪个数据源”，也便于排查模型选错工具或业务查询失败的问题。

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Java 21 · Spring Boot 3.5 · Spring MVC · MyBatis-Plus |
| AI | Spring AI 1.0 · Anthropic ChatClient · Tool Calling |
| RAG | Ollama · BGE-M3 · MySQL ngram FULLTEXT · Java cosine similarity |
| 安全 | Spring Security 6 · JWT · 企业范围权限 |
| 数据 | MySQL 8.4 · Flyway · Redis 7 |
| 前端 | React 19 · TypeScript · Vite · Ant Design 5 |
| 本地环境 | Docker Compose |

## 项目结构

```text
backend/src/main/java/com/bulk/trade/
├── advisor/       AI 顾问：Agent、Prompt、工具、会话和调用轨迹
├── knowledge/     知识库：文档分块、Embedding、混合检索
├── identity/      用户、企业、角色、权限和 JWT
├── inventory/     电子库存单，作为库存工具的数据源
├── trading/       挂牌、摘牌、订单和待办
├── contract/      合同快照和签署
├── marketdata/    成交行情和 SSE 推送
├── settlement/    模拟账户、流水和冻结
└── shared/        API 响应、异常、安全上下文和基础设施

frontend/src/
├── pages/         AI 顾问、知识库和交易页面
├── api/           HTTP API 封装
├── hooks/         SSE 和查询刷新
├── router/        路由和前端守卫
└── store/         认证状态
```

## 快速启动

### 环境要求

- JDK 21
- Maven 3.9+
- Node.js 20+
- Docker Desktop
- AI 对话模型的 API Key（Anthropic 或兼容网关）
- Ollama 和 BGE-M3（启用 RAG 向量检索时需要）

### 启动基础设施

```bash
docker compose up -d
```

MySQL 映射到 `13306`，Redis 映射到 `16379`。后端启动时会由 Flyway 自动执行数据库迁移。

### 配置 AI 服务

推荐使用环境变量，不要把真实密钥写入仓库文件：

```powershell
$env:BULK_ADVISOR_API_KEY = "你的 API Key"
$env:BULK_ADVISOR_BASE_URL = "https://api.anthropic.com"
$env:BULK_ADVISOR_MODEL = "claude-opus-5"

# 可选：启用本地 BGE-M3
ollama pull bge-m3
$env:BULK_EMBEDDING_ENABLED = "true"
```

没有配置 AI Key 时，平台仍可启动，`/api/advisor/status` 会报告顾问不可用；没有 Ollama 时，知识库会降级为关键词检索。

### 启动后端和前端

```bash
cd backend
mvn spring-boot:run
```

后端地址：`http://localhost:8081`

接口文档：`http://localhost:8081/swagger-ui.html`

另开终端启动前端：

```bash
cd frontend
npm install
npm run dev
```

前端地址：`http://localhost:5173`

## AI 功能验证

开发账号密码统一为 `Admin@123`：

| 账号 | 用途 |
|---|---|
| `seller01` | 验证企业私有库存、挂牌、订单和顾问问答 |
| `buyer01` | 验证另一企业的数据隔离和市场查询 |
| `admin` | 验证知识库统计、关键词检索和补算 Embedding |

建议按下面顺序验证：

1. 使用 `seller01` 登录，打开 AI 顾问页面。
2. 询问“我的库存有哪些”“我有什么待办”，确认模型调用对应工具。
3. 询问某个品类的市场价格，确认返回行情工具结果和统计周期。
4. 询问平台规则，确认返回知识段落出处。
5. 询问“帮我审查合同 CT...”，确认只有这类请求才读取合同正文。
6. 使用 `buyer01` 查询 `seller01` 的订单，确认服务端拒绝跨企业数据访问。
7. 使用 `admin` 打开知识库页面，执行关键词检索或补算待处理向量。

也可以直接检查顾问状态：

```text
GET /api/advisor/status
```

该接口只返回端点、模型、Spring AI 状态和已注册工具名，不返回 API Key。

## 验证命令

```bash
cd backend
mvn test

cd ../frontend
npm run build
```

## 当前边界

- AI 回复当前通过普通 HTTP 完整返回，尚未实现 Token 级流式输出。
- 向量检索使用 Java 扫描，适用于当前小规模规则库，不适合直接扩展到大规模语料。
- 结算模块是模拟账户和流水，没有连接真实银行或清算系统。
- Prompt Caching 依赖模型提供商和网关支持，项目不把未验证的命中率当作功能结果。
- AI 顾问是业务辅助工具，不提供投资建议，也不预测价格。
