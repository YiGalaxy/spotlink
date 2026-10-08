# 现货通 SpotLink

以大宗现货交易为数据场景的 AI 顾问学习项目，按[实施计划](docs/SpotLink实施计划-2026-10-08.md)逐功能建设。目标包括 Spring AI 与 LangChain 两套独立顾问、可信业务工具、知识库与 RAG，以及相同条件下的效果评测。

已实现独立仓库、后端与前端骨架、源码构建镜像、启动/停止/状态检查、日志入口及确定性数据生成。C07 已完成令牌用途和账号/企业缓存隔离；按用户要求先行完成商城首页、登录与共享导航重设计，登录默认返回商城。C08 已完成基础库存规则与验收，后续 C09 导入、顾问页面与双引擎继续按计划实施。

首页参考淘宝的搜索、分类和商品浏览结构，使用本项目的品牌与品类示意图。搜索、分类、挂牌详情、行情与数量来自实际接口。暂无挂牌时显示空状态。工作台通过顶部独立入口访问。设计说明见[界面规范](design/设计规范.md)和[页面行为](design/页面设计.md)。

`powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/auth-runtime.tests.ps1 -Mall` 在独立数据库验证真实发布、搜索、分类、详情定位、订单登录返回、键盘导航和菜单关闭、320/390/768/1024px 宽度及服务失败重试。截图与日志仅保存在 `.local`；不向日常数据库写入验收商品。

`check.cmd -Task C06 -Mode task -NoPause` 在只读源码挂载的 Node 工具容器内验证数据确定性、文件损坏拒绝、规则兼容、关联/单位/守恒与恢复，并实际生成/核验被 Git 忽略的数据和 SQL。生成和首次数据库导入分开，当前尚未导入此数据包；命令和范围见[数据说明](data/README.md)。

首次运行 `start.cmd -Build -NoPause`，脚本在本项目生成被忽略的 `.env`，随机配置数据库、缓存及 JWT 密钥，并等待四个服务健康。前端地址为 `http://127.0.0.1:18080`，后端健康检查为 `http://127.0.0.1:18081/actuator/health`。重复启动保留配置及数据。`status.cmd -NoPause` 检查实际就绪状态；`stop.cmd -NoPause` 停止本项目并保留数据卷；`scripts/logs.ps1 -Service backend -Tail 100` 查看脱敏前应谨慎保管的本地日志。

当前本地演示初始化暂时建立 `seller01`、`buyer01`、`admin`、`auditor01`、`pending01` 账号，密码 `Admin@123`；后续 C09 改为显式容器导入。聊天模型尚未完成配置契约，未配置密钥时不具备真实回答能力，向量默认关闭。启动成功仅代表业务服务就绪。

`check.cmd -Task C03 -Mode task -NoPause` 执行纯单元测试与隔离 MySQL/Redis 集成测试；C04 执行接口测试和类型/构建；C05 验证独立 Compose 项目首次、重复及失败启动、实际版本和停止后数据保留。浏览器人工验收已检查首页、未登录库存跳转及合成账号登录后的返回路径。检查产物保存在 `.local/`，普通检查不调用真实模型。

在项目根目录执行 `check.cmd -Task C01 -Mode task -NoPause` 验证工程规则；`check.cmd -Mode full -NoPause` 回归已登记范围。未实现的任务和检查明确返回非零，普通检查不调用真实模型。执行 `scripts/project-context.ps1` 查看脱敏环境状态。详见[开发规范](docs/开发规范.md)。

项目依赖统一使用 Docker。宿主仅需 Git、Docker Desktop、Windows PowerShell 与浏览器。运行数据与本机密钥保存在被 Git 忽略的 `.local/`、`.env` 或独立 Docker 数据卷中。

旧项目只作参考，代码按职责逐项审阅后复用，建设范围和来源见[来源记录](docs/来源记录.md)。默认只做本地提交，不推送或部署到公网。

C07 认证回归覆盖 access/refresh 用途、账号/企业状态、对象越权、前端迟到响应与双账号切换。Docker Chromium 通过 localhost 宿主映射运行，结果仅 .local。

C08 库存登记校验叶子品类、启用仓库、单位、数量精度和品类规格；冻结时仅允许修改备注。数量接口使用字符串，库存汇总按单位精确计算，保留中文扩展字段。详见[业务规则](docs/业务规则.md)。执行 check.cmd -Task C08 -Mode task -NoPause 验证库存、数据和浏览器，产物只写 .local。

C18 已使用 gpt-image-2 生成六个品类商品图、商城主图及登录仓储图，压缩成品随源码提供。提示词、来源和散列登记在 design，母图与私有配置不提交。`check.cmd -Task C18 -Mode task -NoPause` 校验本地 WebP、前端构建、六品类匹配、图片失败备用及桌面/手机显示，不发图片请求。
