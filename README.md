# CampusClaw

- **价值主张：** 把分散的校本教学材料沉淀为按班级隔离、可持续复用的教研知识资产。
- **核心场景：** 教师上传和管理教学材料，配置技能、智能体与 MCP 工具；同班师生可检索材料，并在可恢复的私人会话中获得基于材料或工具结果的流式回答。
- **本次不做：** 模型自主选择任意工具的开放式 Agent、作业提交与批改、注册与找回密码、SSO、多校多租户及生产级高可用。

## 技术栈

- 前端：Vue 3、Vite、Vue Router、Vitest
- 后端：Java 17、Spring Boot 3、Spring Security、Spring Data JPA、Flyway
- 数据：MySQL 8（正文、状态与 ngram 全文索引）、Qdrant（向量与编号 payload）
- 工具服务：Python 3.13 标准库 HTTP Server、JSON-RPC 2.0 MCP 子集
- 运行：Docker Compose、Nginx

## 当前实现与规约状态

当前页面分为“讲义与检索”(`/materials`)、“助手配置”(`/assistants`，仅教师) 和“助手对话”(`/conversations`)。MCP 服务、工具与调用审计位于助手配置页的“MCP 工具”页签；侧栏的“审计与统计”等课程后续入口仍待建设。

截至 2026-10-28，MCP、Markdown 切分和 RAG 采信三个 change 均已验收通过、同步至 `openspec/specs/` 并归档，当前无活动 change 或遗留未完成验收项。详细实现范围与验收记录见 [项目状态](docs/project-status.md)。

## 从零启动

需要 Docker Desktop 或兼容的 Docker Engine，并确保 8080 端口可用。

1. 复制环境变量模板：

   ```powershell
   Copy-Item .env.example .env
   ```

2. 编辑 `.env`，替换全部 `replace-*` 占位值。默认关闭演示数据初始化；仅在确实需要创建演示账号、讲义、助手和技能时，才设置 `DEMO_SEED_ENABLED=true` 并配置 `DEMO_SEED_PASSWORD`。
3. 构建并启动：

   ```powershell
   docker compose up --build
   ```

4. 等待 MySQL、Qdrant、MCP 示例服务和后端通过健康检查、前端容器运行后访问 <http://localhost:8080>。应用健康检查地址为 <http://localhost:8080/health>；它检查应用与数据库，不代表所有外部模型和工具均可用。
5. 停止服务但保留数据：

   ```powershell
   docker compose down
   ```

MySQL 数据、上传文件和 Qdrant 向量分别保存在 `mysql-data`、`uploads-data`、`qdrant-data` 命名 volume 中。不要使用 `docker compose down -v`，除非明确要删除全部演示数据。

Embedding 与 Chat 使用 OpenAI-compatible HTTP API。`.env` 中必须设置各自的 base URL、API key、模型名以及实际 embedding 维度；Qdrant collection 已存在但维度或距离不是 Cosine 时，后端会明确拒绝启动，不会自动删除或重建已有数据。

Compose 还会启动仅在内部网络可见的 `mcp-demo:8090`，不会把端口发布到宿主机。示例服务读取 `.env` 中的 `MCP_AUTH_TOKEN`；教师登记该服务时提交相同值，后端加密保存并在调用时解密为 Bearer Header。后端启动时必须取得 `MCP_TOKEN_ENCRYPTION_KEY`；该值应由 `openssl rand -base64 32` 等方式生成，且不能与 JWT 密钥复用。

更新既有 `.env` 时，应从 `.env.example` 合并新增变量，尤其是五项 `MCP_*` 配置，保留原有口令和模型配置。`DEMO_SEED_ENABLED=false` 可以继续使用；MCP 示例服务由 Compose 独立启动，教师登记服务、连接发现和绑定工具均不依赖种子开关。关闭种子且数据库为空时不会创建演示账号，需先在演示环境完成一次种子初始化。

## 使用 DBeaver 查看数据库

Compose 仅将 MySQL 暴露到宿主机回环地址，不会监听局域网网卡。DBeaver 新建 MySQL 连接时使用：

- Host：`127.0.0.1`
- Port：`.env` 中的 `MYSQL_HOST_PORT`，默认 `3306`
- Database：`.env` 中的 `MYSQL_DATABASE`，默认 `campusclaw`
- Username：`.env` 中的 `MYSQL_USER`，默认 `campusclaw`
- Password：`.env` 中的 `MYSQL_PASSWORD`

连接前需保持 `mysql` 服务运行。若本机 3306 已被占用，可修改 `.env` 中的 `MYSQL_HOST_PORT`，然后重新执行 `docker compose up -d mysql`。

## 演示账号

三个账号共同使用 `.env` 中的 `DEMO_SEED_PASSWORD`：

| 账号 | 角色 | 班级 |
| --- | --- | --- |
| `teacher-a` | 教师 | 班级 A |
| `student-a1` | 学生 | 班级 A |
| `student-b1` | 学生 | 班级 B |

教师可以管理本班材料、技能和智能体绑定；临时材料处理失败时还可以重新提交。学生可以查看、下载、检索和使用已启用智能体，但不能执行管理操作。任何客户端提交的 `class_id` 都不会成为授权依据，数据范围只取 Bearer JWT 对应用户在数据库中的最新班级；跨班按 ID 管理统一返回 404，跨班检索返回空结果。

系统种子材料只包含知识条目，没有对应的物理原文件，页面会将查看和下载按钮标记为不可用。教师新上传的 `.txt`/`.md` 文件可正常查看和下载。

上传请求只负责流式保存原文件和创建 `PENDING/QUEUED` 材料，随后立即返回；解析、切分、嵌入和写入 Qdrant 在有界后台执行器中完成。页面以退避轮询展示 `QUEUED`、`PARSING`、`INDEXING`、`READY` 或 `FAILED`。应用就绪后的恢复调度会重新提交遗留 `PENDING` 和陈旧 `INDEXING`，不会自动重跑确定为永久失败的材料。

## 本地开发

本地开发需要 Java 17、Maven 3.9+、Node.js 22+、MySQL 8 和可访问的 Qdrant。默认 Compose 不向宿主机发布 Qdrant 端口，以下 `localhost:6333` 假设你已另行提供本机可访问的 Qdrant；若全部使用 Compose，直接采用前面的启动方式。先设置以下后端环境变量：

```powershell
$env:DB_URL = "jdbc:mysql://localhost:3306/campusclaw?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC"
$env:DB_USERNAME = "campusclaw"
$env:DB_PASSWORD = "本地数据库密码"
$env:DEMO_SEED_ENABLED = "false"
$env:DEMO_SEED_PASSWORD = "本地演示口令"
$env:AUTH_JWT_SECRET = "至少32字节的本地随机JWT签名密钥"
$env:AUTH_JWT_TTL = "PT8H"
$env:SERVER_PORT = "8081"
$env:QDRANT_URL = "http://localhost:6333"
$env:QDRANT_COLLECTION = "campusclaw_chunks"
$env:EMBEDDING_BASE_URL = "https://你的兼容服务/v1"
$env:EMBEDDING_API_KEY = "本地密钥"
$env:EMBEDDING_MODEL = "嵌入模型名"
$env:EMBEDDING_DIMENSION = "1024"
$env:CHAT_BASE_URL = "https://你的兼容服务/v1"
$env:CHAT_API_KEY = "本地密钥"
$env:CHAT_MODEL = "对话模型名"
$env:CHAT_REASONING_EFFORT = "none"
$env:MCP_TOKEN_ENCRYPTION_KEY = "Base64 编码的 32 字节随机密钥"
$env:MCP_ALLOWED_HOSTS = "localhost"
$env:MCP_CONNECT_TIMEOUT = "3s"
$env:MCP_READ_TIMEOUT = "10s"
Set-Location backend
.\mvnw.cmd spring-boot:run
```

`CHAT_REASONING_EFFORT=none` 会让支持该 OpenAI-compatible 扩展的服务跳过不会展示的私有推理阶段；若供应商不接受该字段，可将变量显式设置为空，使网关省略它。

另开终端启动前端：

```powershell
Set-Location frontend
npm.cmd install
npm.cmd run dev
```

Vite 将 `/api` 和 `/health` 代理到 `http://localhost:8081`，无需配置跨域凭据。

## 主要接口

| 方法与路径 | 用途 |
| --- | --- |
| `POST /api/auth/login` | 登录并签发 Bearer JWT |
| `GET /api/auth/me` | 获取当前用户、角色和班级 |
| `POST /api/auth/logout` | 幂等退出端点；客户端删除访问令牌 |
| `GET /api/materials` | 查询当前班级材料 |
| `POST /api/materials/upload` | 教师上传 `.txt`/`.md` 材料 |
| `GET /api/materials/{id}/content` | 同班师生以 UTF-8 纯文本在线查看原文件 |
| `GET /api/materials/{id}/download` | 同班师生下载原文件 |
| `GET /api/materials/{id}/chunks/{chunkId}/context` | 同班师生读取引用切片及有限相邻上下文 |
| `PATCH /api/materials/{id}` | 教师修改本班材料标题 |
| `DELETE /api/materials/{id}` | 教师删除本班材料及知识条目 |
| `POST /api/materials/{id}/index/rebuild` | 教师以 AUTO/CUSTOM/HIERARCHY 重建索引 |
| `POST /api/materials/{id}/processing/retry` | 教师重新提交可重试的失败材料 |
| `POST /api/retrieval/search` | keyword/vector/hybrid 可追溯检索，默认 hybrid |
| `POST /api/ask` | hybrid 排名后通过问答采信门的至多 4 条依据，非流式问答与引用 |
| `GET/POST /api/skills` | 列出技能；教师从结构化原文创建技能 |
| `GET/PUT/DELETE /api/skills/{id}` | 读取、更新或由教师删除本班技能 |
| `GET/POST /api/assistants` | 列出智能体；教师创建完整智能体配置 |
| `GET/PUT/DELETE /api/assistants/{id}` | 读取、原子替换或删除无历史消息的智能体；删除时自动清理零消息会话，真实历史仍返回 409 |
| `GET/POST /api/conversations` | 列出本人会话或选择已启用智能体创建会话 |
| `GET/DELETE /api/conversations/{id}` | 恢复或删除本人会话；删除会级联清理其消息 |
| `POST /api/conversations/{id}/messages` | 提交问题并返回 UTF-8 SSE 回答 |
| `GET/POST /api/mcp/servers` | 教师列出或登记本班 MCP 服务 |
| `PUT/DELETE /api/mcp/servers/{id}` | 教师修改服务，或删除没有审计记录的服务 |
| `POST /api/mcp/servers/{id}/connect` | 依次执行 initialize 与 tools/list 并原子替换工具缓存 |
| `POST /api/mcp/servers/{id}/disconnect` | 断开服务并清空已发现工具缓存 |
| `GET /api/mcp/servers/{id}/tools` | 查看最近一次成功发现的工具及输入 Schema |
| `GET /api/mcp/tool-calls` | 查看本班最近的脱敏工具调用审计，默认 50 条、最多 100 条 |
| `GET /health` | 无需登录的应用与数据库健康检查 |

匿名 API 请求返回 401，角色不足返回 403，跨班资源返回不泄露存在性的 404。

检索的 `query` 为 1～1000 个 Unicode 字符，`limit` 为 1～20。keyword 仅依赖 MySQL；vector 和 hybrid 在嵌入或 Qdrant 不可用时返回脱敏 503。无命中始终返回 200、空 `hits` 与“资料中未找到相关内容”；问答在此分支不会调用 Chat。回答只保留实际引用的候选，并按正文首次出现顺序从 1 连续编号；前端可直接展开引用摘录，也可跳转到材料页突出目标切片及相邻上下文。Qdrant payload 不保存正文，point ID、payload `chunk_id` 与 MySQL chunk ID 保持一致。

## 材料切分与问答采信

上传的 UTF-8 文本先按 Markdown 一级到三级标题分节，同节的说明、公式与段落在 1000 个 Unicode code point 内装入 `knowledge_entries`，再由 AUTO/CUSTOM/HIERARCHY 生成检索 chunks。代码围栏和独立成行的 `$$`、`\[`/`\]` 会保护内部空行与伪标题；长度上限仍可能切开超长内容。已有材料的“重建索引”只重建 chunks，不重新解析 entries；旧材料要采用新解析规则，需要重新上传。

直接搜索与生成回答使用不同门槛：

| 配置 | 默认值 | 作用 |
| --- | --- | --- |
| `RETRIEVAL_VECTOR_THRESHOLD` | `0.35` | 向量宽召回；直接搜索可展示这些候选 |
| `RETRIEVAL_ANSWER_STRONG_VECTOR_THRESHOLD` | `0.55` | 问答接受强向量候选，无须 keyword 同时命中 |
| `RETRIEVAL_ANSWER_CORROBORATED_VECTOR_THRESHOLD` | `0.45` | 问答接受 keyword 与 vector 同一 chunk 双路命中的中等向量候选 |
| `RETRIEVAL_RRF_K` | `60` | hybrid 的名次融合平滑常数，不是相关性门槛 |

`/api/ask` 与智能体材料检索在 RRF 排名后先过滤弱候选，再取至多四条；keyword 单路不作为生成依据。配置要求 `宽召回阈值 <= 双路阈值 <= 强向量阈值`，错误顺序会拒绝启动。上述值是未经本项目标注集校准的经验起点，不能保证每条显式引用在语义上都支持回答；当前没有问题重写、reranker 或逐事实蕴含核验。

## MCP 工具配置、凭据与排障

教师在 `/assistants` 的“MCP 工具”页签登记服务。Base URL 只接受 `http`/`https`，不能包含用户信息、查询参数或片段，目标主机还必须精确出现在 `MCP_ALLOWED_HOSTS` 中；后端会将地址规范化为以 `/mcp` 结尾。开发环境若单独启动 Python 服务，可先设置 `MCP_AUTH_TOKEN` 和可选的 `MCP_PORT`，运行 `python mcp-server/server.py`，再把允许主机和登记地址改为本机地址。Compose 环境应使用内部地址 `http://mcp-demo:8090`。

示例服务实际提供“公式提示”(`formula_hint`) 和“计算器”(`calculator`) 两个工具。连接成功只会发现工具，使用时还需创建并启用技能、选择工具，再绑定到智能体。若只需数学计算，技能仅选择 `calculator@服务名`；同时选择 `knowledge_search` 才会在命中该技能后检索绑定讲义。

例如，在绑定计算器技能的智能体中询问“计算2的20次幂”或“请计算 sqrt(81) + 2 ** 5”。共享表达式适配器为技能兜底匹配并构造 `{expression}`，Python 服务以受限算术 AST 求值；模型仅根据工具结果组织回答。前者的工具结果为 `1048576`，后者为 `41`。多个技能同分时仍按技能 ID 选一项，因此应从“AI 执行过程”确认实际技能，再从正文前工具摘要和教师审计确认调用，不能只看回答是否正确。

Token 只在创建或显式替换时提交。后端以随机 12 字节 IV 和 AES-256-GCM 保存 `v1:` 版本密文，接口只返回 `hasAuth`。编辑时 Token 留空会保留原值。`MCP_TOKEN_ENCRYPTION_KEY` 不能直接轮换：当前版本没有批量重加密流程；更换密钥前必须先用旧密钥解密并重新加密所有已有凭据，否则连接会以脱敏的 503 失败。

连接操作不持有数据库长事务：先读取配置快照，再在事务外执行 `initialize` 与 `tools/list`，最后校验配置版本并在短事务中整体替换缓存。客户端不跟随重定向、不自动重试；连接/读取超时分别由 `MCP_CONNECT_TIMEOUT` 和 `MCP_READ_TIMEOUT` 控制。常见问题如下：

- 启动时报加密密钥错误：确认密钥是 Base64 编码后恰好 32 字节，而不是长度为 32 的普通字符串。
- 登记返回 400：检查服务名格式、URL 中是否带查询参数，以及主机是否在 `MCP_ALLOWED_HOSTS`。
- 连接返回 502：确认 MCP 容器健康、登记 Token 与 `MCP_AUTH_TOKEN` 一致，并检查服务是否返回约定版本的合法 JSON-RPC 结果。
- 连接返回 409：网络探测期间配置已修改；重新发起连接即可。
- 删除返回 409：该服务已有审计记录，设计上必须保留；可改为断开服务。
- Swagger 位于 <http://localhost:8080/swagger-ui/index.html>，MCP 路径、错误码和 `toolCalls` 结构也可从 <http://localhost:8080/v3/api-docs> 查看。

推荐启动顺序为 MySQL、Qdrant、`mcp-demo`、backend、frontend；Compose 已通过健康检查依赖表达该顺序。可分别使用 `docker compose logs mcp-demo` 和 `docker compose logs backend` 排查协议端与编排端，但日志不应输出 Token、工具原始响应或完整参数。

## 智能体配置与流式会话

教师可在 `/assistants` 通过技能名称、触发关键词/适用条件、资料范围、行为说明和工具复选项维护技能，并可直接在列表启停技能；前端将表单组装为既有技能文本，服务端仍负责完整解析与校验。名称包含“解题引导”且条件声明习题类别时，服务端用固定题目特征补充确定性命中，并强制只追问或给出至多一步提示，不输出最终答案。智能体同时配置名称、角色提示词、技能和 READY 材料绑定，启用/停用放在列表与编辑、删除并列。没有历史消息的智能体可删除，零消息空会话会自动清理；已有真实会话时应停用以保留历史。学生只能读取已启用智能体摘要，不能看到提示词和绑定。

`/conversations` 是教师和学生共用的持久化助手对话入口。会话固定当前用户、班级和智能体；每轮提问会重新读取教师最新绑定，但单轮开始后使用不可变快照。用户消息先提交，再按选中技能决定是否检索绑定材料并调用工具。已采信材料或安全工具结果任一存在即可生成，两者均无时不调用 Chat，仍通过 SSE 返回固定无依据答复。材料候选在最终落库前按正文采用情况过滤，并按首次出现顺序从 `[1]` 编号；模型完全漏标时，只有存在规范化文本重合才补一条依据，零重合则保存固定拒答与空引用。

正文支持标准 Markdown 和 KaTeX：`$...$`、`\(...\)` 为行内公式，`$$...$$`、`\[...\]` 为块公式；原始 HTML 被禁用。引用卡片只在 `done` 后显示，片段正文一律默认折叠，点击可逐卡片展开当前保存的完整摘录，或查看材料上下文。进入已有会话会定位最新消息，切换或删除会话会清空未发送草稿。历史会话可由本人在侧边栏删除；旧 `/search` 会跳转到讲义与检索页。

正常 SSE 协议为：

```text
event: meta   data: {status, skillName, citations, toolCalls?}
event: delta  data: {text}                 # 零到多次，只含新增文本
event: done   data: {messageId}             # 助手消息事务提交后发送
```

流开始后失败时不发送 `done`：已有正文会尽力保存为 `FAILED` 并发送脱敏 `error`，没有正文则不创建空助手消息。前端保留部分回答并提供重试；完成后与刷新时均从服务端详情恢复事实状态。旧 `/api/ask` 仍保持非流式、无命中不调用 Chat 的原语义。

技能的 `工具` 字段可以包含有序的多个引用：内置检索使用 `knowledge_search`，外部工具使用 `工具名@服务名`。中英文逗号、顿号、分号、竖线和换行都会被规范化并按首次出现顺序去重。保存技能时，所有 MCP 引用必须指向本班已连接服务的已发现工具。未命中技能时默认检索绑定材料；命中技能后仅当其包含 `knowledge_search` 才检索，然后串行调用该技能的 MCP 引用。模型不负责选工具或生成参数，调用也不会自动重试；纯工具依据时 `citations=[]`，正文中的伪 `[n]` 会被移除。

`meta.toolCalls` 仅含 `ref/status/detail` 安全摘要，没有 MCP 引用时整个字段省略。相同摘要会写入助手消息的 `tool_calls_json`，因此 `done` 后重新加载或刷新页面仍能恢复。原始工具输出先按 1200 个 Unicode code point 截断并检查常见指令注入模式；命中后整段替换并标为 `denied`，危险原文不会进入模型、SSE、消息或审计。

API 文档位于 <http://localhost:8080/swagger-ui/index.html>，OpenAPI JSON 位于 <http://localhost:8080/v3/api-docs>。前端 Nginx 会把这些路径显式代理到 Springdoc，而不会交给 Vue 路由；两条文档路径允许匿名访问，所有 `/api/**` 业务接口仍要求 Bearer JWT。

## 接口回归测试

`apitest/` 提供按 URL 和 HTTP 方法组织的 Python 接口脚本，覆盖登录、本班检索、MCP 登记与发现、学生 403、跨班 404、多工具技能、会话调用、注入过滤、工具快照、审计和删除冲突。套件会对照关键响应字段与 MySQL 状态，并在结束时恢复原智能体配置、清理带本轮唯一标识的临时技能、会话和允许安全删除的 MCP 数据；已有审计的测试服务按契约断开并保留。

完整 Compose 环境启动且 A 班至少有一份 READY 材料后执行：

```powershell
python -m pip install -r apitest/requirements.txt
python apitest/run_all.py
```

配置默认从本机 `.env` 读取；可选的 `APITEST_*` 覆盖项和测试数据边界见 `apitest/README.md`。HTML 报告生成到已被 Git 忽略的 `apitest/reports/`。报告与控制台输出会脱敏，测试脚本不得硬编码真实密码、令牌或模型密钥。

## 前端界面约定

已登录页面统一复用 `frontend/src/components/AppShell.vue`，全局颜色、圆角、阴影、间距和响应式断点集中在 `frontend/src/styles.css` 的设计令牌中。后续新增助手、作业、审计或学情页面时，应继续使用共享侧边栏、班级顶栏、页面标题区、卡片、按钮和 `BaseModal`，避免创建另一套主题。

- 主色：深红 `#94070a`；当前导航使用浅红选中态。
- 桌面端：232px 侧边栏、60px 顶栏、浅灰工作区与白色内容卡片。
- 中等宽度：侧边栏收缩为 76px 图标栏，图标保留可访问名称。
- 手机端：隐藏侧栏，保留移动品牌顶栏，内容和操作纵向排列。
- 作业和全站“审计与统计”等尚未实现菜单明确显示“待建设”，不作为空链接；MCP 调用审计在助手配置页内可用。
- `.md` 在线查看只展示源文本，不执行用户文件中的 HTML。
- “讲义与检索”页同时承载直接片段检索和材料查看/下载；教师在同页看到上传、重命名、重建和删除操作。
- 智能体配置页使用结构化技能表单和列表启停开关；“助手对话”使用服务端会话列表、独立滚动消息区、固定底部输入器以及移动端抽屉。
- MCP 服务/工具与最近调用审计分为独立面板；审计显示时间、真实用户名、中文工具名、状态和实测耗时。协议没有提供 Token usage，页面不估算 Token 消耗。

## 验证

```powershell
Set-Location frontend
npm.cmd ci
npm.cmd test -- --run
npm.cmd run build

Set-Location ..\backend
.\mvnw.cmd test

Set-Location ..
docker compose --env-file .env config --quiet
openspec validate --all --strict

python apitest/run_all.py
```

后端集成测试使用 Testcontainers 启动真实 MySQL 与 Qdrant，因此运行完整测试时 Docker 必须可用，且本机测试进程必须能访问 Docker Engine。Compose 容器运行正常不等于 Testcontainers 已能访问它。当前 Testcontainers 1.20.3 在本机 Docker 29 上需要追加 `"-Dapi.version=1.44"` 才能完成握手。完整验证还包含 V5→V6、空库到 V7 的迁移；验收记录见 [项目状态](docs/project-status.md)。

## 安全说明

- `.env`、数据库凭据和演示口令不得提交。
- 密码只以 BCrypt 哈希形式写入数据库。
- 受保护 API 必须携带 `Authorization: Bearer <token>`；令牌只保存于当前标签页的 `sessionStorage`，不得写入 URL 或日志。
- 生产环境必须设置 `DEMO_SEED_ENABLED=false`、使用 HTTPS，并通过 `AUTH_JWT_SECRET` 提供至少 32 字节的独立随机密钥。
- 演示账号仅用于本地或课堂验收，不应进入生产数据。
