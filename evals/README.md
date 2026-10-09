# 顾问早期基线

本题集验证 `minimal` 导入后的 Spring AI / LangChain 最小闭环。模型使用固定 HTTP 替身，认证、数据库、Redis、工具调用、记忆、接口与页面使用真实实现，不调用远程 Key 或本地模型。

```powershell
.\check.cmd -Task C16 -Mode ai-offline -Engine spring-ai -NoPause
# LangChain 使用相同题集和数据快照，独立 Python 服务真实编排
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/advisor-baseline.tests.ps1 -Engine langchain
```

专用 Compose 项目为 `spotlink-next-eval-early`，配置为 `.local/config/eval-early.env`，端口 58080/58081。入口构建后端镜像，复用受控导入流程，校验数据包及数据库台账后启动替身，再执行 10 条 API 基线与 Chromium 页面检查；结束时停止该项目，保留独立数据卷。它不读取日常 `.env` 的模型凭证。

题目和预期的校验方式保存在 `cases/early-baseline.json` 与 `run.mjs`。库存编号、数量和企业范围从已经校验的生成包读取；汇总使用整数小数运算，避免浮点误差。测试覆盖本企业库存、其他企业隔离、空库存、冻结筛选、精确汇总、原文出处、未知规则、多轮/新会话隔离、采购背景保存/清空、明显攻击不发往模型。

替身只按明确题目选择工具，并复述本次工具输出；它不读取预期答案或数据库。该测试可以发现实际取数、上下文、持久化与页面错误，不能说明真实模型会正确选择工具、理解语言或给出可靠建议。替身返回的 Token 数也不是实际模型费用。两引擎使用同一运行器，报告标注实际 engine；完整真实模型效果和费用对照仍需后续题集。

逐次报告只写入 `.local/evals`，包含数据版本/输入散列、题号、真实工具名、请求数、耗时和断言分类，不保存登录 Token、模型 Key、请求头、答案正文或企业工具结果。截图仅在 `.local/browser`。失败非零退出，报告和已成功前序数据保留；不得把替身正常响应或运行器退出 0 代替全部事实断言通过。

Redis 真实限流同样生效；快速重复运行遇到限流时等待一分钟再运行。库存来自固定导入版本，人工修改后先使用独立验收库，不能重导覆盖用户数据。
