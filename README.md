# 现货通 SpotLink

> 有色金属大宗商品的现货挂牌交易平台

买卖双方企业通过"挂牌 / 摘牌 / 协议交易"三种方式成交，平台承担登记、审核与见证职责。

> **这是现货交易，不是期货。** 没有集中竞价、没有杠杆、没有每日盯市、没有撮合引擎。
> 价格由买卖双方一对一订立——**挂牌是要约，摘牌是承诺**。整个代码库的形状都是由这条法律定性决定的。

---

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Java 21 · Spring Boot 3.5 · MyBatis-Plus · Spring Security 6 |
| AI | **Spring AI 1.0** + Anthropic 模型（端点可配置） |
| 数据库 | PostgreSQL 16 + **pgvector** · Flyway |
| 缓存 | Redis 7 |
| 前端 | React 19 · TypeScript · Vite · Ant Design 5 · ECharts |
| 嵌入模型 | BGE-M3（Ollama 本地部署） |
| 部署 | Docker Compose |

---

## 快速开始

### 1. 基础设施

```bash
docker compose up -d
```

PostgreSQL → `localhost:15432`（库 `bulk_trade`，用户 `bulk`）
Redis → `localhost:16379`

> 刻意使用非默认端口，避免与本机已装的 PostgreSQL 服务和其它容器冲突。

### 2. 嵌入模型（RAG 需要，可选）

```bash
ollama pull bge-m3
```

没有这一步平台照常运行，只是知识库检索退化为纯关键词匹配。管理页会显示待补算的分块数。

### 3. 后端

```bash
cd backend
mvn spring-boot:run
```

Flyway 启动时自动建表并写入种子数据。

- 服务地址：http://localhost:8081
- 接口文档：http://localhost:8081/swagger-ui.html

### 4. 前端

```bash
cd frontend
npm install
npm run dev
```

http://localhost:5173

### 5. 开发账号（密码统一 `Admin@123`）

| 账号 | 角色 | 说明 |
|---|---|---|
| `admin` | 平台运营 | 无租户归属 |
| `seller01` | 卖方 | 已审核，席位 T0001，**有库存和挂牌** |
| `buyer01` | 买方 | 已审核，席位 T0002 |
| `pending01` | 卖方 | 企业待审核，登录被拦截 |

---

## 功能

| 模块 | 能力 |
|---|---|
| 认证与租户 | JWT、企业审核、多租户隔离 |
| 品类与仓库 | 品类树、指定交收仓库 |
| 电子库存单 | 入库、编辑、注销、三量口径（总量/可用/冻结） |
| 挂牌交易 | 卖方挂牌、买方挂牌、摘牌、撤牌、有效期自动失效 |
| 订单 | 六态状态机、完整流转轨迹 |
| 合同 | 按订单快照起草、双方签署生效 |
| 资金 | 账户三口径、不可变流水、保证金冻结解冻 |
| 行情 | 成交均价曲线、成交量、实时推送（SSE） |
| AI 顾问 | 工具调用 Agent + RAG 知识库检索 + 合同审查 |

---

## 六个关键设计

1. **挂牌是要约，摘牌是承诺** —— 所以没有撮合引擎。集中竞价是期货市场的特征，一对一的合同订立才是现货。
2. **电子库存单不是仓单** —— 仓单在《民法典》里是物权凭证，可质押可背书转让。拿物权凭证当标准化交易标的，是现货平台被认定为变相期货的典型路径。
3. **数量是三个数** —— `total / available / frozen`，数据库约束保证三者守恒。挂牌不拿走货，只预留。
4. **冻结一张表管两种东西** —— 商品冻结和资金冻结生命周期相同，共用一张表和一套服务。
5. **乐观锁防并发** —— 每次数量变动都是 `UPDATE ... WHERE id = ? AND version = ?`，抢不到的拿到零行结果并被要求重试。
6. **ID 序列化为字符串** —— 雪花 ID 是 19 位，JavaScript 安全整数只有 16 位，用数字传输会让前端回传的 id 变成另一个值。

---

## 项目结构

```
backend/src/main/java/com/bulk/trade/
├── shared/       共享内核：响应封装、异常、安全、配置
├── identity/     企业、用户、角色、认证
├── commodity/    品类树
├── warehouse/    交收仓库
├── inventory/    电子库存单
├── trading/      挂牌、摘牌、订单状态机
├── contract/     合同与签署
├── settlement/   资金账户、流水、冻结
├── marketdata/   行情聚合与 SSE 推送
├── knowledge/    知识库与混合检索（RAG）
├── advisor/      AI 顾问（Spring AI Tool Calling）
└── bootstrap/    开发环境种子数据
```

模块按**业务能力**划分，不按技术分层。

---

## 数据库约定

1. **主键**：雪花 ID，应用生成
2. **金额**：`NUMERIC(19,4)`；**数量**：`NUMERIC(18,3)`。禁止 `float` / `double`
3. **审计**：`created_at / updated_at / created_by / updated_by / deleted`，`updated_at` 由触发器维护
4. **迁移脚本一经执行不可修改**，改结构就新增 `V{n}__xxx.sql`

---

## 多租户规则

> 企业 ID **永远**从登录态读取，**绝不**从请求参数读取。

```java
Long enterpriseId = SecurityUtils.currentEnterpriseId();   // 正确
Long enterpriseId = request.getParameter("enterpriseId");  // 错误：越权
```

AI 顾问的工具同样遵守这条：**没有工具接受企业 ID 参数**，所以模型无法指定别的企业，提示注入也做不到。

---

## 文档

| 文档 | 内容 |
|---|---|
| [docs/steps/](docs/steps/) | 各阶段实现解读与面试考点 |
| [docs/adr/](docs/adr/) | 架构决策记录 |

---

## 已知的简化与取舍

诚实记录，避免演示时被问住：

- **资金为模拟实现**。真实平台由第三方清算机构（如大宗商品清算通）划转，平台不持有资金池，也不需要支付牌照。
- **摘牌即转移货权**。真实平台应在合同生效时才转移，摘牌仅作预留。当前做法让货物在任意时刻只存在一处，便于核对，代价是"已摘牌"与"已签约"之间存在一个真实系统会补上的缺口。
- **AI 端点为兼容网关**。代码按 Anthropic 官方 SDK 写，`base-url` 与模型走配置。prompt caching 的策略已按官方语义实现，但真实效果需官方 API 才能验证。
- **未做**：竞价专区、物流运单、发票、质量异议流程、磅差结算。
