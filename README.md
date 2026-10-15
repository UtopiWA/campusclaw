# CampusClaw

- **价值主张：** 把分散的校本教学材料沉淀为按班级隔离、可持续复用的教研知识资产。
- **核心场景：** 教师上传和管理教学材料，配置技能与智能体；同班师生可检索材料，并在可恢复的私人会话中获得带引用的流式回答。
- **本次不做：** 模型自主选择任意工具的开放式 Agent、作业提交与批改、注册与找回密码、SSO、多校多租户及生产级高可用。

## 技术栈

- 前端：Vue 3、Vite、Vue Router、Vitest
- 后端：Java 17、Spring Boot 3、Spring Security、Spring Data JPA、Flyway
- 数据：MySQL 8（正文、状态与 ngram 全文索引）、Qdrant（向量与编号 payload）
- 运行：Docker Compose、Nginx

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

4. 等待 MySQL、Qdrant、后端和前端健康后访问 <http://localhost:8080>。健康检查地址为 <http://localhost:8080/health>。
5. 停止服务但保留数据：

   ```powershell
   docker compose down
   ```

MySQL 数据、上传文件和 Qdrant 向量分别保存在 `mysql-data`、`uploads-data`、`qdrant-data` 命名 volume 中。不要使用 `docker compose down -v`，除非明确要删除全部演示数据。

Embedding 与 Chat 使用 OpenAI-compatible HTTP API。`.env` 中必须设置各自的 base URL、API key、模型名以及实际 embedding 维度；Qdrant collection 已存在但维度或距离不是 Cosine 时，后端会明确拒绝启动，不会自动删除或重建已有数据。

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

本地开发需要 Java 17、Maven 3.9+、Node.js 22+ 和 MySQL 8。先设置以下后端环境变量：

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
| `POST /api/ask` | 固定 hybrid 前 4 条依据的问答与引用 |
| `GET/POST /api/skills` | 列出技能；教师从结构化原文创建技能 |
| `GET/PUT/DELETE /api/skills/{id}` | 读取、更新或由教师删除本班技能 |
| `GET/POST /api/assistants` | 列出智能体；教师创建完整智能体配置 |
| `GET/PUT/DELETE /api/assistants/{id}` | 读取、原子替换或删除无历史消息的智能体；删除时自动清理零消息会话，真实历史仍返回 409 |
| `GET/POST /api/conversations` | 列出本人会话或选择已启用智能体创建会话 |
| `GET/DELETE /api/conversations/{id}` | 恢复或删除本人会话；删除会级联清理其消息 |
| `POST /api/conversations/{id}/messages` | 提交问题并返回 UTF-8 SSE 回答 |
| `GET /health` | 无需登录的应用与数据库健康检查 |

匿名 API 请求返回 401，角色不足返回 403，跨班资源返回不泄露存在性的 404。

检索的 `query` 为 1～1000 个 Unicode 字符，`limit` 为 1～20。keyword 仅依赖 MySQL；vector 和 hybrid 在嵌入或 Qdrant 不可用时返回脱敏 503。无命中始终返回 200、空 `hits` 与“资料中未找到相关内容”；问答在此分支不会调用 Chat。回答只保留实际引用的候选，并按正文首次出现顺序从 1 连续编号；前端可直接展开引用摘录，也可跳转到材料页突出目标切片及相邻上下文。Qdrant payload 不保存正文，point ID、payload `chunk_id` 与 MySQL chunk ID 保持一致。

## 智能体配置与流式会话

教师可在 `/assistants` 通过技能名称、触发关键词/适用条件、资料范围、行为说明和工具复选项维护技能，并可直接在列表启停技能；前端将表单组装为既有技能文本，服务端仍负责完整解析与校验。名称包含“解题引导”且条件声明习题类别时，服务端用固定题目特征补充确定性命中，并强制只追问或给出至多一步提示，不输出最终答案。智能体同时配置名称、角色提示词、技能和 READY 材料绑定，启用/停用放在列表与编辑、删除并列。没有历史消息的智能体可删除，零消息空会话会自动清理；已有真实会话时应停用以保留历史。学生只能读取已启用智能体摘要，不能看到提示词和绑定。

`/conversations` 是教师和学生共用的持久化助手对话入口。会话固定当前用户、班级和智能体；每轮提问会重新读取教师最新绑定，但单轮开始后使用不可变快照。用户消息先提交，随后只在绑定材料内执行检索。没有可靠依据时不调用模型，服务端以相同 SSE 流程返回固定无依据答复；有候选时，最终落库前仅保留正文实际采用的片段并按首次出现顺序从 `[1]` 重新编号，模型完全漏标时只补一条文本最匹配依据。SSE 解帧器按事件等待页面消费，正文按 Unicode 字符逐帧绘制，最终引用只在 `done` 后展示。历史会话可由本人在侧边栏删除。直接片段检索位于 `/materials` 的“讲义与检索”页面；旧 `/search` 会跳转到该页面。

正常 SSE 协议为：

```text
event: meta   data: {status, skillName, citations}
event: delta  data: {text}                 # 零到多次，只含新增文本
event: done   data: {messageId}             # 助手消息事务提交后发送
```

流开始后失败时不发送 `done`：已有正文会尽力保存为 `FAILED` 并发送脱敏 `error`，没有正文则不创建空助手消息。前端保留部分回答并提供重试；完成后与刷新时均从服务端详情恢复事实状态。旧 `/api/ask` 仍保持非流式、无命中不调用 Chat 的原语义。

API 文档位于 <http://localhost:8080/swagger-ui/index.html>，OpenAPI JSON 位于 <http://localhost:8080/v3/api-docs>。前端 Nginx 会把这些路径显式代理到 Springdoc，而不会交给 Vue 路由；两条文档路径允许匿名访问，所有 `/api/**` 业务接口仍要求 Bearer JWT。

## 接口回归测试

`apitest/` 提供按 URL 和 HTTP 方法组织的 Python 接口脚本，覆盖登录、本班检索、提示词/技能、会话创建和 SSE 消息。套件会对照关键响应字段与 MySQL 状态，并在结束时恢复原智能体配置、清理带本轮唯一标识的临时技能和会话。

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
- 作业和审计等尚未实现菜单明确显示“待建设”，不作为空链接。
- `.md` 在线查看只展示源文本，不执行用户文件中的 HTML。
- “讲义与检索”页同时承载直接片段检索和材料查看/下载；教师在同页看到上传、重命名、重建和删除操作。
- 智能体配置页使用结构化技能表单和列表启停开关；“助手对话”使用服务端会话列表、独立滚动消息区、固定底部输入器以及移动端抽屉。

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

后端集成测试使用 Testcontainers 启动真实 MySQL 8，因此运行完整测试时 Docker 必须可用。Docker 不可用时只能验证不依赖数据库的单元、MockMvc 和网关测试，不能据此宣称 V5 迁移或端到端会话已经通过。

## 安全说明

- `.env`、数据库凭据和演示口令不得提交。
- 密码只以 BCrypt 哈希形式写入数据库。
- 受保护 API 必须携带 `Authorization: Bearer <token>`；令牌只保存于当前标签页的 `sessionStorage`，不得写入 URL 或日志。
- 生产环境必须设置 `DEMO_SEED_ENABLED=false`、使用 HTTPS，并通过 `AUTH_JWT_SECRET` 提供至少 32 字节的独立随机密钥。
- 演示账号仅用于本地或课堂验收，不应进入生产数据。
