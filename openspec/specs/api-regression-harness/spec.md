# api-regression-harness Specification

## Purpose
定义仓库内可独立执行的 Python 接口回归套件，使关键接口能够按 URL 定位脚本、一键运行、联合核对 HTTP 响应与 MySQL 状态，并生成不泄露凭据且不提交到 Git 的 HTML 报告。

## Requirements

### Requirement: 接口脚本按 URL 和方法组织
系统 MUST 在仓库根目录提供 `apitest/`，并为每个受测接口使用一个按 URL 层级组织、以 HTTP 方法命名的 Python 脚本；公共认证、请求、断言、数据库访问和清理能力 MAY 由共享模块复用。

#### Scenario: 从接口定位测试脚本
- **WHEN** 开发者需要查找 `POST /api/auth/login` 或 `POST /api/conversations/{id}/messages` 的接口测试
- **THEN** 仓库 MUST 分别提供形如 `apitest/cases/api/auth/login/POST.py` 和 `apitest/cases/api/conversations/_id/messages/POST.py` 的独立脚本
- **AND** 动态路径脚本 MUST 从运行上下文取得实际资源标识，不得依赖写死的数据库自增 ID

#### Scenario: 接口脚本复用公共能力
- **WHEN** 多个接口需要 Bearer Token、MySQL 查询、唯一测试数据或结果记录
- **THEN** 脚本 MUST 使用 `apitest/` 内的公共模块复用这些能力
- **AND** 每个接口脚本 MUST 仍能明确标识其对应的方法、路径和接口级断言

### Requirement: 一键执行与 HTML 报告
系统 MUST 提供 `apitest/run_all.py` 一键执行入口，按稳定顺序运行接口脚本，并为本次运行生成汇总 HTML 报告。

#### Scenario: 运行全部接口测试
- **WHEN** 开发者在已启动的本地 Compose 环境执行 `python apitest/run_all.py`
- **THEN** 运行器 MUST 完成环境检查、登录、依赖数据准备、全部用例执行和仅针对本次测试数据的清理
- **AND** 进程退出码 MUST 在全部通过时为 0，在任一环境检查或用例失败时为非 0

#### Scenario: 生成可读且可忽略的报告
- **WHEN** 接口测试运行结束
- **THEN** `apitest/reports/` 下的 HTML 报告 MUST 至少列出方法、路径、通过或失败、耗时和失败断言
- **AND** 报告目录 MUST 被 Git 忽略，报告与控制台输出 MUST NOT 包含完整 Bearer Token、数据库密码、模型密钥或上游响应正文

### Requirement: 联合验证响应与持久化状态
接口测试 MUST 不只断言 HTTP 状态码，还必须核对关键响应字段；对会写入数据库的接口，测试 MUST 使用只读查询确认 MySQL 中的归属、字段和状态与响应一致。

#### Scenario: 配置接口返回成功
- **WHEN** 教师接口成功创建或修改技能、角色提示词或智能体绑定
- **THEN** 对应用例 MUST 断言响应中的标识、配置字段和绑定结果
- **AND** 用例 MUST 查询数据库确认记录属于登录教师所在班级且持久化字段与响应一致

#### Scenario: 会话流式消息完成
- **WHEN** 学生创建会话并成功发送一条流式消息
- **THEN** 用例 MUST 验证 SSE 正常顺序为 `meta`、零到多条 `delta`、`done`，且拼接后的增量正文与响应语义一致
- **AND** 用例 MUST 查询数据库确认会话属于当前用户和班级，用户消息与 `COMPLETED` 助手消息均已保存，最终正文、技能和引用与流式结果一致

### Requirement: 课程要求的最小接口覆盖
接口套件 MUST 至少覆盖登录、本班检索、提示词与多工具技能配置、MCP 服务管理和发现、创建会话、发送一条流式消息以及读取工具审计，并使用登录接口签发的 Bearer Token 调用受保护接口。

#### Scenario: 执行最小回归集合
- **WHEN** `run_all.py` 在具备教师、学生、READY 测试材料和可访问测试 MCP 服务的环境中运行
- **THEN** 套件 MUST 覆盖 `POST /api/auth/login`、`POST /api/retrieval/search`、智能体与技能配置、`GET|POST /api/mcp/servers`、`PUT|DELETE /api/mcp/servers/{id}`、`POST /api/mcp/servers/{id}/connect`、`POST /api/mcp/servers/{id}/disconnect`、`GET /api/mcp/servers/{id}/tools`、`GET /api/mcp/tool-calls`、`POST /api/conversations` 和 `POST /api/conversations/{id}/messages`
- **AND** 检索、MCP 配置、工具缓存、审计和会话 MUST 验证当前班级归属，配置 MUST 验证教师权限，流式会话 MUST 验证学生归属与 Bearer 认证

#### Scenario: 本机配置和秘密管理
- **WHEN** 接口套件需要服务地址、测试账号、数据库连接、模型配置或 MCP Token
- **THEN** 这些值 MUST 从本机环境或未提交的 `.env` 读取，并在缺失时以脱敏错误快速失败
- **AND** 仓库中的脚本、示例、报告和文档 MUST NOT 硬编码真实凭据

### Requirement: MCP 调用联合验证与安全清理
接口套件 MUST 联合验证 MCP HTTP 响应、SSE 工具摘要和 MySQL 中的服务、工具与审计状态，并只清理本次运行创建且允许安全删除的数据。

#### Scenario: 连接工具并完成一次流式调用
- **WHEN** 教师创建测试 MCP 服务并连接，保存引用 `formula_hint@服务名` 的技能，学生用绑定该技能的智能体提问
- **THEN** 用例 MUST 验证连接后工具缓存被整体写入，SSE 顺序为带 `toolCalls` 的 `meta`、零到多条 `delta`、`done`，且对应工具状态为 `ok`
- **AND** 用例 MUST 只读查询 MySQL，确认助手消息工具快照和本班审计的用户、会话、服务、工具引用及状态与 SSE 一致

#### Scenario: 只有工具结果仍生成回答
- **WHEN** 测试智能体没有可命中的材料，但 MCP 工具返回通过安全处理的可用结果
- **THEN** 用例 MUST 验证 `citations` 为空、`toolCalls` 保留来源且出现对话网关生成的正文
- **AND** 正文 MUST 不含无效材料编号，审计 MUST 记录 `ok`

#### Scenario: 连接失败不留下半状态
- **WHEN** 教师使用错误 Token 或不可用地址连接测试服务
- **THEN** 用例 MUST 验证接口返回 HTTP 502，服务处于断开状态且工具缓存为空
- **AND** 错误响应和 HTML 报告 MUST NOT 包含提交的 Token、内部异常栈或完整远端响应

#### Scenario: 工具失败不阻断材料回答
- **WHEN** 技能引用的服务已断开或远端工具返回错误，但绑定材料仍能可靠命中
- **THEN** 用例 MUST 验证 `toolCalls` 为 `denied` 或 `error`，同时流仍正常完成并保留材料引用
- **AND** 可归属的失败调用 MUST 写入同班审计，报告不得把它误记为工具成功

#### Scenario: 注入结果不得成为生成依据
- **WHEN** 测试工具返回命中已知指令注入规则的内容且本轮没有材料引用
- **THEN** 用例 MUST 验证危险原文未出现在 SSE、消息快照或审计中，且系统走固定无依据分支
- **AND** 用例 MUST 验证对话网关未因该结果被调用

#### Scenario: 权限和班级隔离
- **WHEN** 学生调用任一 MCP 管理接口，或另一班教师使用已知服务 ID 读取、修改、连接、删除和查看工具
- **THEN** 用例 MUST 分别验证 HTTP 403 和与随机不存在 ID 一致的 HTTP 404
- **AND** MySQL 中目标服务、缓存、Token 摘要和审计数量 MUST 保持不变

#### Scenario: 有审计的服务无法清理
- **WHEN** 回归流程已为测试服务产生调用审计后尝试删除该服务
- **THEN** 用例 MUST 验证 HTTP 409 且服务与审计保留
- **AND** 清理器 MUST 改为断开服务并保留该有审计样本，或使用明确标识的独立无审计服务验证成功删除，不能绕过外键清理历史

#### Scenario: 重复连接覆盖缓存
- **WHEN** 同一测试服务成功连接两次且第二次工具清单发生可控变化
- **THEN** 用例 MUST 验证缓存反映第二次完整清单而不是两次结果的并集
- **AND** `server_id + tool_name` MUST 无重复记录，服务仍保持已连接
