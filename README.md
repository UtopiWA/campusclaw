# CampusClaw

- **价值主张：** 把分散的校本教学材料沉淀为按班级隔离、可持续复用的教研知识资产。
- **核心场景：** 教师上传和管理教学材料，系统生成可追溯切片并写入向量索引；同班师生可查看、下载、按关键词/语义/混合模式检索，并基于检索依据问答。
- **本次不做：** 通用自由对话、作业提交与批改、注册与找回密码、SSO、多校多租户及生产级高可用。

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

2. 编辑 `.env`，替换全部 `replace-*` 占位值。演示环境需保留 `DEMO_SEED_ENABLED=true` 并设置 `DEMO_SEED_PASSWORD`。
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

教师可以上传、查看、下载、重命名、删除和按三种策略重建本班材料索引；学生可以查看、下载和检索，但不能执行管理操作。任何客户端提交的 `class_id` 都会被忽略，数据范围只取登录会话班级；跨班按 ID 管理统一返回 404，跨班检索返回空结果。

系统种子材料只包含知识条目，没有对应的物理原文件，页面会将查看和下载按钮标记为不可用。教师新上传的 `.txt`/`.md` 文件可正常查看和下载。

## 本地开发

本地开发需要 Java 17、Maven 3.9+、Node.js 22+ 和 MySQL 8。先设置以下后端环境变量：

```powershell
$env:DB_URL = "jdbc:mysql://localhost:3306/campusclaw?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC"
$env:DB_USERNAME = "campusclaw"
$env:DB_PASSWORD = "本地数据库密码"
$env:DEMO_SEED_ENABLED = "true"
$env:DEMO_SEED_PASSWORD = "本地演示口令"
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
Set-Location backend
.\mvnw.cmd spring-boot:run
```

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
| `GET /api/auth/csrf` | 获取写请求 CSRF 令牌 |
| `POST /api/auth/login` | 登录并建立服务端会话 |
| `GET /api/auth/me` | 获取当前用户、角色和班级 |
| `POST /api/auth/logout` | 退出并失效会话 |
| `GET /api/materials` | 查询当前班级材料 |
| `POST /api/materials/upload` | 教师上传 `.txt`/`.md` 材料 |
| `GET /api/materials/{id}/content` | 同班师生以 UTF-8 纯文本在线查看原文件 |
| `GET /api/materials/{id}/download` | 同班师生下载原文件 |
| `PATCH /api/materials/{id}` | 教师修改本班材料标题 |
| `DELETE /api/materials/{id}` | 教师删除本班材料及知识条目 |
| `POST /api/materials/{id}/index/rebuild` | 教师以 AUTO/CUSTOM/HIERARCHY 重建索引 |
| `POST /api/retrieval/search` | keyword/vector/hybrid 可追溯检索，默认 hybrid |
| `POST /api/ask` | 固定 hybrid 前 4 条依据的问答与引用 |
| `GET /health` | 无需登录的应用与数据库健康检查 |

匿名 API 请求返回 401，角色不足返回 403，跨班资源返回不泄露存在性的 404。

检索的 `query` 为 1～1000 个 Unicode 字符，`limit` 为 1～20。keyword 仅依赖 MySQL；vector 和 hybrid 在嵌入或 Qdrant 不可用时返回脱敏 503。无命中始终返回 200、空 `hits` 与“资料中未找到相关内容”；问答在此分支不会调用 Chat。Qdrant payload 不保存正文，point ID、payload `chunk_id` 与 MySQL chunk ID 保持一致。

## 前端界面约定

已登录页面统一复用 `frontend/src/components/AppShell.vue`，全局颜色、圆角、阴影、间距和响应式断点集中在 `frontend/src/styles.css` 的设计令牌中。后续新增助手、作业、审计或学情页面时，应继续使用共享侧边栏、班级顶栏、页面标题区、卡片、按钮和 `BaseModal`，避免创建另一套主题。

- 主色：深红 `#94070a`；当前导航使用浅红选中态。
- 桌面端：232px 侧边栏、60px 顶栏、浅灰工作区与白色内容卡片。
- 中等宽度：侧边栏收缩为 76px 图标栏，图标保留可访问名称。
- 手机端：隐藏侧栏，保留移动品牌顶栏，内容和操作纵向排列。
- 尚未实现的菜单明确显示“待建设”，不作为空链接。
- `.md` 在线查看只展示源文本，不执行用户文件中的 HTML。

## 验证

```powershell
Set-Location frontend
npm.cmd ci
npm.cmd test -- --run
npm.cmd run build

Set-Location ..\backend
.\mvnw.cmd test

Set-Location ..
docker compose --env-file .env.example config
openspec validate --specs --strict
```

后端集成测试使用 Testcontainers 启动真实 MySQL 8，因此运行测试时 Docker 必须可用。

## 安全说明

- `.env`、数据库凭据和演示口令不得提交。
- 密码只以 BCrypt 哈希形式写入数据库。
- 基于 Cookie 的写请求必须携带 CSRF 令牌；不要全局关闭 Spring Security CSRF。
- 生产环境必须设置 `DEMO_SEED_ENABLED=false`、使用 HTTPS，并设置 `COOKIE_SECURE=true`。
- 演示账号仅用于本地或课堂验收，不应进入生产数据。
