# 现货通 SpotLink

有色金属大宗商品的**现货挂牌交易平台**。买卖双方企业通过「挂牌 / 摘牌」成交，
平台承担登记、审核与见证职责。

> **这是现货交易，不是期货。** 没有集中竞价、没有杠杆、没有每日盯市、没有撮合引擎。
> 价格由买卖双方一对一订立——**挂牌是要约，摘牌是承诺**。整个代码库的形状都是由
> 这条法律定性决定的。

---

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Java 21 · Spring Boot 3.5 · MyBatis-Plus · Spring Security 6 |
| AI | Spring AI 1.0 · 工具调用 Agent + RAG |
| 数据库 | MySQL 8.4 · Flyway |
| 缓存 | Redis 7 |
| 前端 | React 19 · TypeScript · Vite · Ant Design 5 · ECharts |
| 嵌入模型 | BGE-M3（Ollama 本地部署，可选） |
| 部署 | Docker Compose |

---

## 快速开始

```bash
# 1. 基础设施（MySQL → 13306，Redis → 16379）
docker compose up -d

# 2. 后端（Flyway 自动建表，启动时写入种子账号）
cd backend && mvn spring-boot:run          # http://localhost:8081
                                           # 接口文档 /swagger-ui.html

# 3. 前端
cd frontend && npm install && npm run dev  # http://localhost:5173
```

**演示数据是可选的，但推荐**——不跑的话数据库里只有 3 家企业 4 个账号，看不到市场。

```bash
cd scripts
for f in generate-demo-data generate-inflight-orders generate-rich-catalogue \
         backfill-demo-contracts topup-seller-inventory; do
  docker exec -i spotlink-mysql mysql --default-character-set=utf8mb4 \
    -ubulk -pbulk_trade_2026 bulk_trade < "$f.sql"
done
```

脚本都是幂等的，跑几遍不会重复。**`--default-character-set=utf8mb4` 不能省**——
容器里的 mysql 客户端默认 latin1，不加会把中文写成双重编码。

**RAG 需要本地嵌入模型**（`ollama pull bge-m3`）。没有它平台照常运行，只是知识库
检索退化为纯关键词匹配。

---

## 开发账号

密码统一 `Admin@123`。

| 账号 | 角色 | 权限 |
|---|---|---|
| `admin` | 平台管理员 | 运营后台全部（11 项） |
| `auditor01` | 平台审计员 | 只读：概览、企业、订单、审计日志（4 项） |
| `seller01` | 卖方 | 已审核，席位 T0001，**有库存和挂牌** |
| `buyer01` | 买方 | 已审核，席位 T0002 |
| `pending01` | 卖方 | 企业待审核，**登录即被拦截**（`20005`） |

演示交易流程用 `seller01` / `buyer01`：`admin` 不隶属任何企业，`pending01` 进不去。

---

## 功能

| 模块 | 能力 |
|---|---|
| 认证与租户 | JWT、企业审核、多租户隔离 |
| 品类与仓库 | 品类树、指定交收仓库 |
| 电子库存单 | 入库、编辑、注销、三量口径（总量/可用/冻结） |
| 挂牌交易 | 卖方挂牌、买方挂牌、摘牌、撤牌、两种成交模式、有效期自动失效 |
| 订单 | 六态状态机、按角色区分的进度提示、完整流转轨迹 |
| 合同 | 按订单快照起草、双方签署生效 |
| 资金 | 账户三口径、不可变流水、保证金冻结解冻 |
| 行情 | 成交均价曲线、成交量、实时推送（SSE） |
| 待办 | 跨模块汇总，按企业定向推送（SSE） |
| AI 顾问 | 工具调用 Agent（14 个工具）+ RAG 混合检索 + 合同审查 |
| 运营后台 | 概览、企业审核、订单查询、用户与权限、审计日志、知识库 |

**无需登录即可浏览**：首页、行情、挂牌大厅。

---

## 项目结构

```
backend/src/main/java/com/bulk/trade/
├── shared/       共享内核：响应封装、异常、安全、权限、审计
├── identity/     企业、用户、角色、权限、认证
├── commodity/    品类树
├── warehouse/    交收仓库
├── inventory/    电子库存单
├── trading/      挂牌、摘牌、订单状态机、待办
├── contract/     合同与签署
├── settlement/   资金账户、流水、冻结
├── marketdata/   行情聚合与 SSE 推送
├── knowledge/    知识库与混合检索（RAG）
├── advisor/      AI 顾问（Spring AI Tool Calling）
├── publicapi/    无需登录的公开数据
├── admin/        运营后台
└── bootstrap/    开发环境种子数据

frontend/src/
├── api/         接口封装
├── pages/       页面
├── layouts/     主布局与后台布局
├── router/      路由与守卫
└── utils/       表格、通知等
```

模块按**业务能力**划分，不按技术分层。

---

## 作者

个人作品集项目 · **别太在亿啦**
