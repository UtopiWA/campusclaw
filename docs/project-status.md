# 当前项目状态

最后核对：2026-10-29。本文件记录个人项目至 Class06 的代码与规约基线，供后续增量接续。

## 实现范围

已实现 Bearer JWT、教师/学生角色与服务端班级隔离、材料管理与异步索引、keyword/vector/hybrid 检索、问答采信、技能与智能体配置、私人持久化流式会话、MCP 管理/调用/审计、OpenAPI 以及 Python 接口回归工具。

前端使用 `/materials`、教师 `/assistants` 和 `/conversations`，旧 `/search` 重定向到材料页。作业目前只有早期数据骨架；发布、提交、批改、教师复核、错题、学习诊断与计划等业务尚未形成完整链路。MCP 调用审计已可用，不等于全站审计与学情统计已完成。

## 归档与验收状态

| Change | 任务状态 | 归档记录 |
| --- | --- | --- |
| MCP 工具与审计 | 31/31，验收通过 | [归档说明](../openspec/changes/archive/2026-10-28-add-mcp-tools-and-audit/README.md) |
| Markdown 感知切分 | 8/8，验收通过；MySQL/Qdrant 集成测试 18 项通过，无失败/错误/跳过 | [归档说明](../openspec/changes/archive/2026-10-28-fix-markdown-aware-knowledge-chunking/README.md) |
| RAG 采信收紧 | 7/7，验收通过 | [归档说明](../openspec/changes/archive/2026-10-28-tighten-rag-evidence-acceptance/README.md) |

三个 change 均于 2026-10-28 同步归档，当前无活动 change 或遗留未完成验收项。规范性行为以 [主规格目录](../openspec/specs/) 为准，归档目录用于追溯；归档不代表整个 Demo 或后续团队扩展已经完成。

## 验证与兼容边界

- 验证范围与执行命令见各 change 的归档说明；主规格严格校验与同步一致性检查通过。
- RAG 阈值未经人工标注集校准，显式引用也不做逐事实蕴含校验。
- 纯工具回答可没有材料引用；材料和工具都不可用才走生成前固定拒答。
- 本次收敛了旧检索规格的单页聊天描述，主规格统一为材料页直接检索与私人助手会话分离；会话零重合拒答也明确属于流式完成后的正文归一化。
- 旧 entries 不会因重建 chunks 自动重解析；原文件、知识条目与检索切片是不同层次。
- `/health` 当前只检查数据库；JSON 请求链日志、完整学习业务等后续要求不属于本次归档实现范围。
