# 可复现数据

Git 保存 `rules`、`schemas`、`sql`、`coverage` 与生成器，运行实体与 SQL 全部写入 `.local/data/<规则版本>/<批次>/`。Node 22 工具容器只读挂载源码，只有 `.local` 可写，不安装宿主依赖。

当前定义三个虚构企业、一个交收仓库、电解铜品类、双方全可用库存、五个演示账号与平台角色，以及中文原文/可复现知识分块。账号 BCrypt 编码、事务台账、重复跳过与一键入口见[数据初始化](../docs/数据初始化.md)。生成不代表已导入。完整 demo 在 C37 补全以前明确拒绝生成，acceptance 的非法输入仅保存为请求预期，不能插入数据库。

在根目录执行：

```powershell
docker compose -p spotlink-next-tools --project-directory . -f ops/compose.tools.yml run --rm data-tools node data/generators/generate.mjs --dataset minimal --batch minimal-v1-20261009
docker compose -p spotlink-next-tools --project-directory . -f ops/compose.tools.yml run --rm data-tools node data/generators/generate.mjs --verify .local/data/v1/minimal-v1-20261009
docker compose -p spotlink-next-tools --project-directory . -f ops/compose.tools.yml run --rm data-tools node data/generators/generate.mjs --batch minimal-v1-20261009 --resume
check.cmd -Task C06 -Mode task -NoPause
```

生成器输出 records JSON/JSONL、审阅 SQL、人工覆盖关联及数值 expected、运行 manifest。清单包含规则/生成器版本、所有重建输入的内容散列、seed、时间规则/锚、schema 依赖、表数量及每个文件的 SHA-256/字节数/记录数。ID 与小数均用字符串和整数运算，数量三位，金额/单价规则四位。

固定回归包使用 UTC ISO 时钟。未来 demo 首次固定时钟，恢复沿用 manifest 时间锚。批次存在时默认拒绝覆盖，`--resume` 核验完整文件和规则后沿用；任何 seed/时钟/规模变化须使用新批次。中断遗留 `.lock` 时先确认对应生成进程已经停止，再处理该批次锁；生成成功靠原子重命名，不以半成品作为可导入包。

SQL 由同一列白名单与 records 渲染供核对；实际导入器使用参数化模板绑定值，不执行审阅 SQL。生成器校验编号、ID、关联、单位与守恒；未知包、非法参数、版本/文件/规则不一致均返回 4。后续每个业务节点同步扩充场景和预期，当前不宣称完整类型覆盖。
