# Tasks: 接入 MCP 工具、调用审计与对话可视化

> 所有任务在实现和对应验证命令通过前保持未勾选。按依赖顺序执行；不得先实现前端假数据再回填后端契约。

## 1. 数据模型与兼容迁移

- [x] 1.1 新增 V6 Flyway migration：创建 `mcp_servers`、`mcp_tools`、`mcp_tool_calls`，落实班级唯一键、工具缓存级联删除、审计限制删除、状态检查、字段长度、索引和可空会话关联。
  - 验证：`mvn -f .\backend\pom.xml "-Dtest=McpSchemaMigrationTest" test`
- [x] 1.2 将 `skills.tool_name` 迁移为非空 `tool_refs`，回填现有数据后删除旧列；为 `messages` 增加可空 `tool_calls_json`，并覆盖 V5 升级与空库全量迁移。
  - 验证：`mvn -f .\backend\pom.xml "-Dtest=McpSchemaMigrationTest" test`
- [x] 1.3 新增 MCP 服务、缓存和审计 JPA 模型/仓储，Token 只保存 AES-256-GCM 版本化密文；更新 `Skill`、`Message` 及 DTO，所有资源查询显式携带当前 `class_id`，审计服务外键使用限制删除。
  - 验证：`mvn -f .\backend\pom.xml "-Dtest=McpSchemaMigrationTest,CampusClawIntegrationTest" test`

## 2. 独立 MCP 服务与部署

- [x] 2.1 在 `mcp-server/` 实现 `/health` 和受 Bearer Token 保护的 `/mcp`，支持 JSON-RPC 2.0 `initialize`、`tools/list`、`tools/call` 及规范错误响应。
  - 验证：`python -m unittest discover -s mcp-server/tests -p "test_protocol.py" -v`
- [x] 2.2 实现至少 `formula_hint`、`unit_convert` 两个确定性工具及输入 Schema 校验，确保工具可由 `tools/list` 发现并可被真实调用。
  - 验证：`python -m unittest discover -s mcp-server/tests -p "test_tools.py" -v`
- [x] 2.3 增加 MCP Dockerfile、Compose 内网服务、Python 健康检查、`MCP_AUTH_TOKEN`、`MCP_TOKEN_ENCRYPTION_KEY`、`MCP_ALLOWED_HOSTS` 和超时环境变量示例；不向浏览器暴露 MCP 端口，不把真实凭据写入仓库或镜像。
  - 验证 1：`docker compose config`
  - 验证 2：`docker compose build mcp-demo`

## 3. MCP 服务管理、连接与审计读取

- [x] 3.1 实现教师专用的服务列表、新增、更新、断开和删除接口；按允许主机列表规范化 URL，以 AES-256-GCM 加解密 Token，空 Token 保留原值，响应只给 `hasAuth`，学生为 403、跨班/不存在为 404。
  - 验证：`mvn -f .\backend\pom.xml "-Dtest=McpServerControllerTest,McpServerServiceTest" test`
- [x] 3.2 实现不跟随重定向、带连接/读取超时且无自动重试的 MCP HTTP 客户端，校验 JSON-RPC ID、结果结构、工具名和 Schema。
  - 验证：`mvn -f .\backend\pom.xml "-Dtest=McpHttpClientTest" test`
- [x] 3.3 实现事务外 `initialize → tools/list` 探测和配置版本校验；成功整体替换缓存，失败断开并清空缓存，分别映射 409、502、503。
  - 验证：`mvn -f .\backend\pom.xml "-Dtest=McpServerServiceTest" test`
- [x] 3.4 实现工具缓存查看和本班审计列表，默认 50、上限 100；删除前检查审计并把并发外键冲突稳定映射为 409。
  - 验证：`mvn -f .\backend\pom.xml "-Dtest=McpServerControllerTest,McpServerServiceTest,McpSchemaMigrationTest" test`

## 4. 多工具技能配置

- [x] 4.1 扩展技能解析器，支持 `knowledge_search` 与 `工具名@服务名`，接受中英文逗号、顿号、分号、竖线和换行分隔，按首次出现顺序去重并执行单项/总长度限制。
  - 验证：`mvn -f .\backend\pom.xml "-Dtest=SkillTextParserTest" test`
- [x] 4.2 在技能创建/更新事务中校验所有 MCP 引用均属于本班已连接服务的已发现工具；不存在、跨班、断开和未发现统一返回 400 且旧技能保持不变。
  - 验证：`mvn -f .\backend\pom.xml "-Dtest=SkillToolReferenceValidationTest" test`
- [x] 4.3 更新技能响应和种子数据，保持现有 `knowledge_search` 内置检索语义，并确保服务改名、断开或删除不会静默改写技能正文。
  - 验证：`mvn -f .\backend\pom.xml "-Dtest=AgentConfigurationServiceTest,CampusClawIntegrationTest" test`

## 5. 确定性工具调用与安全处理

- [x] 5.1 实现按所选技能引用顺序串行调用的编排器和明确的工具参数适配器；跳过 `knowledge_search`，不使用模型选工具、造参数、并行调用或自动重试。
  - 验证：`mvn -f .\backend\pom.xml "-Dtest=McpToolOrchestratorTest" test`
- [x] 5.2 实现 `ok|denied|error` 分类和逐调用审计；断开/适配拒绝/远端错误可归属时各写一条，不存在服务时只生成安全摘要并继续主流程。
  - 验证：`mvn -f .\backend\pom.xml "-Dtest=McpToolOrchestratorTest" test`
- [x] 5.3 实现 1200 Unicode code point 限长、已知注入模式检测、整体替换和来源标签；确保原始危险内容、Token、参数、响应头及异常栈不进入日志、审计、SSE 或消息。
  - 验证：`mvn -f .\backend\pom.xml "-Dtest=McpToolResultSanitizerTest,McpToolOrchestratorTest" test`

## 6. 流式会话集成与消息恢复

- [x] 6.1 在用户消息落库后、首个 SSE 事件前执行材料检索与 MCP 调用；按“材料或安全工具任一可用”决定是否调用 Chat，保持 `/api/ask` 原行为。
  - 验证：`mvn -f .\backend\pom.xml "-Dtest=AgentConversationServiceTest,AskServiceTest" test`
- [x] 6.2 扩展模型消息组装，将安全工具结果作为带来源的用户数据；仅工具生成时保持空引用并移除伪造 `[n]`，工具失败时允许材料回答继续。
  - 验证：`mvn -f .\backend\pom.xml "-Dtest=AgentPromptBuilderTest,ConversationCitationNormalizerTest" test`
- [x] 6.3 扩展 SSE `meta.toolCalls` 并在无 MCP 引用时省略；在完成或失败助手消息中持久化安全快照，使详情重载恢复相同摘要。
  - 验证：`mvn -f .\backend\pom.xml "-Dtest=ConversationStreamingServiceTest,ConversationServiceTest" test`

## 7. 教师配置与对话前端

- [x] 7.1 在现有 `/assistants` 教师页增加 MCP 服务列表、创建/编辑、连接/断开、工具查看、删除冲突和审计区域，Token 提交后清空且绝不回显。
  - 验证：`npm --prefix frontend test -- --run src/views/AgentConfigView.test.js`
- [x] 7.2 扩展技能表单和文本导入，显示内置工具及本班已连接 MCP 工具复选项，保留有序多引用并展示不可用引用的可修复错误。
  - 验证：`npm --prefix frontend test -- --run src/views/AgentConfigView.test.js`
- [x] 7.3 扩展 SSE 解码和消息组件，在首个正文字符前显示工具来源与状态，并在 `done` 重载、刷新及失败消息中恢复持久化快照。
  - 验证：`npm --prefix frontend test -- --run src/services/sse.test.js src/views/ConversationsView.test.js`
- [x] 7.4 验证学生路由保护、窄屏布局、键盘操作与安全文本渲染，复用现有 `AppShell` 和设计令牌。
  - 验证：`npm --prefix frontend test -- --run src/router/index.test.js src/views/ConversationsView.test.js src/components/AppShell.test.js`

## 8. OpenAPI、接口回归与部署验收

- [x] 8.1 为 MCP Controller/DTO 和 SSE 工具摘要补全 Springdoc 契约，覆盖 Bearer 认证、教师权限、字段约束和 400/401/403/404/409/502/503。
  - 验证：`mvn -f .\backend\pom.xml "-Dtest=OpenApiEndpointTest" test`
- [x] 8.2 按 URL/方法增加 MCP 接口脚本和公共断言，覆盖成功连接、重复连接、错误 Token、学生 403、跨班 404、工具调用、注入过滤、审计与删除 409。
  - 验证：`python -m unittest discover -s apitest/tests -v`
- [ ] 8.3 在已启动的本地 Compose 环境运行全量接口回归，核对 SSE、MySQL 状态和脱敏 HTML 报告；只清理本次创建且允许删除的数据。
  - 验证：`python apitest/run_all.py`
- [x] 8.4 更新 README、`.env.example` 和部署说明，记录 MCP Token、加密密钥、允许主机、超时、内部地址、密钥轮换限制、Swagger、启动顺序和故障排查，不写入真实凭据。
  - 验证 1：`docker compose config`
  - 验证 2：`rg -n "MCP_AUTH_TOKEN|MCP_TOKEN_ENCRYPTION_KEY|MCP_ALLOWED_HOSTS|MCP_CONNECT_TIMEOUT|MCP_READ_TIMEOUT|swagger-ui" README.md .env.example docker-compose.yml`

## 9. 学习文档与最终检查

- [x] 9.1 按 `learning/AGENTS.md` 复核文档结构，新增或拆分适合的 MCP 管理 flow、确定性调用 topic、连接/发现 module 和调试 walkthrough，并同步总览与进度；不得只在旧笔记追加摘要。
  - 验证：`rg -n "MCP|工具调用|审计|连接发现" ..\learning`
- [x] 9.2 运行后端、MCP 服务和前端测试，确认 Class03～Class05 回归仍通过。
  - 验证 1：`mvn -f .\backend\pom.xml test`
  - 验证 2（`mcp-server/`）：`python -m unittest discover -s tests -v`
  - 验证 3（`frontend/`）：`npm test -- --run`
  - 验证 4（`frontend/`）：`npm run build`
- [x] 9.3 对 change 执行严格校验并人工核对最终实现与已确认的纯工具生成、审计删除、camelCase `toolCalls`、无重试和快照持久化契约一致。
  - 验证：`openspec validate add-mcp-tools-and-audit --strict`
