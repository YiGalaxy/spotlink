# 现货通 SpotLink

以大宗现货交易为数据场景的 AI 顾问学习项目，按[实施计划](docs/SpotLink实施计划-2026-10-08.md)逐功能建设。目标包括 Spring AI 与 LangChain 两套独立顾问、可信业务工具、知识库与 RAG，以及相同条件下的效果评测。

已实现独立仓库、文件规则、来源记录、检查入口和中文提交钩子。应用、数据导入和模型尚未实现，当前没有启动入口。

在项目根目录执行 `check.cmd -Task C01 -Mode task -NoPause` 验证工程规则；`check.cmd -Mode full -NoPause` 回归已登记范围。未实现的任务和检查明确返回非零，普通检查不调用真实模型。执行 `scripts/project-context.ps1` 查看脱敏环境状态。详见[开发规范](docs/开发规范.md)。

项目依赖统一使用 Docker。宿主仅需 Git、Docker Desktop、Windows PowerShell 与浏览器。运行数据与本机密钥保存在被 Git 忽略的 `.local/`、`.env` 或独立 Docker 数据卷中。

旧项目只作参考，代码按职责逐项审阅后复用，建设范围和来源见[来源记录](docs/来源记录.md)。默认只做本地提交，不推送或部署到公网。
