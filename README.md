# 现货通 SpotLink

以大宗现货交易为数据场景的 AI 顾问学习项目，按[实施计划](docs/SpotLink实施计划-2026-10-08.md)逐功能建设。目标包括 Spring AI 与 LangChain 两套独立顾问、可信业务工具、知识库与 RAG，以及相同条件下的效果评测。

已实现独立仓库、后端与前端骨架、源码构建镜像、启动/停止/状态检查和日志入口。当前推进至 C05，后续数据导入、双引擎与界面重设计仍按计划实施。

首次运行 `start.cmd -Build -NoPause`，脚本在本项目生成被忽略的 `.env`，随机配置数据库、缓存及 JWT 密钥，并等待四个服务健康。前端地址为 `http://127.0.0.1:18080`，后端健康检查为 `http://127.0.0.1:18081/actuator/health`。重复启动保留配置及数据。`status.cmd -NoPause` 检查实际就绪状态；`stop.cmd -NoPause` 停止本项目并保留数据卷；`scripts/logs.ps1 -Service backend -Tail 100` 查看脱敏前应谨慎保管的本地日志。

当前本地演示初始化暂时建立 `seller01`、`buyer01`、`admin`、`auditor01`、`pending01` 账号，密码 `Admin@123`；后续 C09 改为显式容器导入。聊天模型尚未完成配置契约，未配置密钥时不具备真实回答能力，向量默认关闭。启动成功仅代表业务服务就绪。

`check.cmd -Task C03 -Mode task -NoPause` 执行纯单元测试与隔离 MySQL/Redis 集成测试；C04 执行接口测试和类型/构建；C05 验证独立 Compose 项目首次、重复及失败启动、实际版本和停止后数据保留。浏览器人工验收已检查首页、未登录库存跳转及合成账号登录后的返回路径。检查产物保存在 `.local/`，普通检查不调用真实模型。

在项目根目录执行 `check.cmd -Task C01 -Mode task -NoPause` 验证工程规则；`check.cmd -Mode full -NoPause` 回归已登记范围。未实现的任务和检查明确返回非零，普通检查不调用真实模型。执行 `scripts/project-context.ps1` 查看脱敏环境状态。详见[开发规范](docs/开发规范.md)。

项目依赖统一使用 Docker。宿主仅需 Git、Docker Desktop、Windows PowerShell 与浏览器。运行数据与本机密钥保存在被 Git 忽略的 `.local/`、`.env` 或独立 Docker 数据卷中。

旧项目只作参考，代码按职责逐项审阅后复用，建设范围和来源见[来源记录](docs/来源记录.md)。默认只做本地提交，不推送或部署到公网。
