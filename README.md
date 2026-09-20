# 大宗商品现货交易平台

有色金属等大宗商品的**现货挂牌交易平台**。买卖双方企业通过"挂牌 / 摘牌 / 协议交易"三种方式成交，平台承担登记、审核与见证职责。

> 定位说明：这是**现货**交易，不是期货。没有集中竞价、没有杠杆、没有每日盯市。
> 价格由买卖双方一对一订立（挂牌 = 要约，摘牌 = 承诺），这是合规架构的基础。

---

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Java 21 · Spring Boot 3.5 · MyBatis-Plus · Spring Security 6 |
| 数据库 | PostgreSQL 16 · Flyway（全量版本化迁移） |
| 缓存 | Redis 7 |
| 前端 | React 19 · TypeScript · Vite · Ant Design 5 |
| 部署 | Docker Compose |

---

## 快速开始

### 1. 启动基础设施

```bash
docker compose up -d
```

PostgreSQL → `localhost:15432`（库 `bulk_trade`，用户 `bulk`）
Redis → `localhost:16379`

> 刻意使用非默认端口，避免与本机已装的 PostgreSQL 服务和其它容器冲突。

### 2. 启动后端

```bash
cd backend
mvn spring-boot:run
```

Flyway 会在启动时自动建表。首次启动会创建开发账号。

- 服务地址：http://localhost:8081
- 接口文档：http://localhost:8081/swagger-ui.html
- 健康检查：http://localhost:8081/actuator/health

### 3. 开发账号（密码统一为 `Admin@123`）

| 账号 | 角色 | 说明 |
|---|---|---|
| `admin` | 平台运营 | 无租户归属，可跨企业查看 |
| `seller01` | 卖方 | 已审核企业，交易席位 T0001 |
| `buyer01` | 买方 | 已审核企业，交易席位 T0002 |
| `pending01` | 卖方 | 企业待审核，登录会被拦截 |

---

## 项目结构

```
backend/src/main/java/com/bulk/trade/
├── shared/        共享内核：响应封装、异常体系、安全、配置
├── identity/      企业、用户、角色、权限、认证
├── commodity/     品类树与商品规格        （阶段 2）
├── inventory/     电子库存单              （阶段 2）
├── trading/       挂牌、摘牌、协议交易、订单（阶段 3）
├── contract/      合同与电子签章          （阶段 3）
├── settlement/    保证金、冻结、流水、对账  （阶段 4）
├── logistics/     交收、磅单              （阶段 4）
├── marketdata/    行情聚合与 SSE 推送      （阶段 5）
└── advisor/       AI 顾问                 （阶段 6）
```

模块按**业务能力**划分，不按技术分层。

---

## 数据库约定

1. **主键**：雪花 ID，由应用生成，不用数据库自增序列
2. **金额**：`NUMERIC(19,4)`，禁止 `float` / `double`
3. **审计**：每张业务表都有 `created_at / updated_at / created_by / updated_by / deleted`，`updated_at` 由触发器维护

**迁移脚本一经执行不可修改**，需要改结构就新增 `V{n}__xxx.sql`。

---

## 多租户规则

> 企业 ID **永远**从登录态（JWT）读取，**绝不**从请求参数读取。

```java
Long enterpriseId = SecurityUtils.currentEnterpriseId();   // 正确
Long enterpriseId = request.getParameter("enterpriseId");  // 错误：越权
```

越权防护靠架构约束，不靠开发者自觉。

---

## 文档

| 文档 | 内容 |
|---|---|
| [docs/steps/](docs/steps/) | 各阶段实现解读（含面试考点） |
| [docs/adr/](docs/adr/) | 架构决策记录 |

---

## 开发进度

- [x] 阶段 1 · 工程骨架、数据库迁移、认证授权
- [ ] 阶段 2 · 品类、电子库存单、冻结模型
- [ ] 阶段 3 · 挂牌、摘牌、订单、合同
- [ ] 阶段 4 · 保证金、资金流水、交收结算
- [ ] 阶段 5 · 行情聚合与实时推送
- [ ] 阶段 6 · AI 顾问
