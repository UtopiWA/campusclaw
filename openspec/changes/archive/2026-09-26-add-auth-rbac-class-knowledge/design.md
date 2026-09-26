# Design

## Context

CampusClaw 当前是仅含 README 与 OpenSpec 规划文件的绿地项目，尚无业务代码。变更动机见 `proposal.md`，对外行为契约见 `specs/auth-upload/spec.md`。首个实现需要同时建立前后端应用、身份与授权、班级隔离、文件处理、MySQL 持久化和可复现运行方式，因此必须先固定跨模块边界与失败处理。

## Goals / Non-Goals

**Goals:**

- 使用 Vue 前端与 Spring Boot REST 后端实现登录、材料列表、教师上传以及同班查看/下载的最小闭环。
- 让所有授权和班级隔离在后端集中执行，客户端不能决定角色或数据归属。
- 使文件、材料记录和知识条目在成功与失败路径下保持一致。
- 提供双班级种子、可重复初始化、Docker Compose 启动、持久化和健康检查，以支持确定性验收。
- 建立可复用的应用壳和视觉令牌，使后续教师/学生页面在导航、顶栏、颜色、间距和组件状态上保持一致。

**Non-Goals:**

- 本设计不引入检索、向量数据库、大模型、聊天助手、作业业务或技能运行时。
- 不处理 PDF、Office、图片和音视频，只解析 UTF-8 `.txt` 与 `.md`。
- 不设计多校租户、管理员代管、开放注册、SSO 或生产级高可用。

## Decisions

### Decision 1: Vue 3 单页应用、Spring Boot 3 REST API 与 MySQL 8

前端使用 Vue 3、Vite 和 Vue Router；后端使用 Java 17、Spring Boot 3、Maven、Spring Web、Spring Security、Spring Data JPA 和 MySQL Connector/J。后端只提供 REST API 与健康检查，不承担页面模板渲染。

生产 Compose 中由 Nginx 托管 Vue 构建产物，并将 `/api` 和 `/health` 同源反向代理到后端。浏览器仅访问前端服务，避免额外引入跨域凭据配置；本地开发由 Vite proxy 提供相同路径语义。

选择理由：前后端职责清晰，符合指定技术栈；同源代理可让基于 Cookie 的会话和 CSRF 防护保持简单、可测试。

备选方案：由 Spring Boot 打包 Vue 静态资源可减少一个运行容器，但会耦合前后端构建和发布；JWT 会增加撤销、刷新与前端安全存储复杂度，因此首版不采用。

建议目录：

```text
frontend/
├── package.json
├── vite.config.js
└── src/
    ├── main.js
    ├── router/
    ├── services/
    └── views/
        ├── LoginView.vue
        └── MaterialsView.vue
backend/
├── pom.xml
└── src/
    ├── main/java/.../
    │   ├── config/       # Spring Security、Cookie、CSRF
    │   ├── auth/         # 登录、退出、当前用户
    │   ├── material/     # 材料查询、修改、删除与上传
    │   ├── knowledge/    # 文本解析与分段
    │   └── persistence/  # 实体、Repository、种子初始化
    ├── main/resources/
    │   ├── application.yml
    │   └── db/migration/
    └── test/
```

### Decision 2: Spring Security 服务端会话、CSRF 与 BCrypt

登录接口通过 Spring Security 验证账号密码，认证成功后使用服务端 `HttpSession`，浏览器仅持有不透明的 `JSESSIONID` Cookie。会话 Principal 只保存用户 ID；每次受保护请求均从 MySQL 重新加载用户有效状态、角色和 `class_id`，避免陈旧会话成为授权真相源。

Cookie 设置 `HttpOnly`、`SameSite=Lax`，HTTPS 部署时启用 `Secure`。写接口保持 Spring Security CSRF 防护：前端先请求 `GET /api/auth/csrf`，再通过 `X-XSRF-TOKEN` 请求头提交令牌。登录失败统一返回通用错误，不区分账号不存在和密码错误。

密码使用 Spring Security `BCryptPasswordEncoder` 生成和验证哈希。MySQL 用户名、密码以及启用演示种子时所需的种子口令必须通过环境变量提供；缺失必需配置时后端启动失败，不提供硬编码默认凭据。

选择理由：服务端会话适合当前单实例演示环境，可利用 Spring Security 的成熟认证、会话固定攻击防护和 CSRF 机制，同时避免在浏览器中保存 Bearer Token。

备选方案：Spring Session JDBC 可让会话跨后端重启保留，但本 change 只要求业务数据持久化，用户在后端重启后重新登录可以接受；多实例时再引入集中会话存储。

### Decision 3: Flyway 管理 MySQL 结构，JPA 映射领域数据

MySQL 8 使用 InnoDB 与 `utf8mb4`。Flyway 负责版本化建表和索引迁移，Spring Data JPA 负责实体映射与事务，`spring.jpa.hibernate.ddl-auto=validate` 只校验映射，不在运行时隐式修改结构。

| 表 | 关键字段与约束 |
| --- | --- |
| `classes` | `id`、唯一 `name` |
| `users` | `id`、唯一 `username`、`password_hash`、`role`、`class_id` |
| `materials` | `id`、`class_id`、`title`、`stored_path`、`uploaded_by`、时间戳 |
| `knowledge_entries` | `id`、`material_id`、`class_id`、`chunk_index`、`body_text` |
| `assignments` | `id`、`class_id`、标题；本 change 仅建结构/种子 |
| `assistants` | `id`、`class_id`、名称；本 change 仅建结构/种子 |
| `skills` | `id`、`assistant_id`、`class_id`、名称；本 change 仅建结构/种子 |

外键和唯一约束由数据库执行；班级资源的必要组合索引以 `class_id` 开头。`knowledge_entries.class_id` 与材料保持冗余，便于所有知识数据查询直接施加班级条件；写入时由同一 MySQL 事务保证一致。

选择理由：Flyway 让结构演进可审查、可复现，JPA 减少基础 CRUD 样板，同时保留显式的班级限定 Repository 方法。

材料表通过后续 Flyway 迁移增加可空的 `file_size_bytes`。新上传文件写入准确字节数；无物理原文件的演示种子保留为空。前端只接收 `hasFile` 与 `fileSizeBytes`，不得接收 `stored_path`。

备选方案：MyBatis 能更直观地控制 SQL，但当前领域规模较小；每班独立数据库虽然物理隔离更强，却显著增加迁移和连接管理成本。

### Decision 4: Repository 强制班级条件，跨班资源统一返回 404

班级资源 Repository 必须提供同时包含资源 ID 与会话 `class_id` 的查询方法，例如 `findByIdAndClassId`；列表查询同样必须带 `classId`。禁止在先读取任意对象后再仅靠控制器比较班级。

写入使用后端当前用户的 `class_id` 和用户 ID。请求 DTO 不接受 `class_id`；若请求仍携带该字段，反序列化校验应拒绝或忽略，但不得改变归属。跨班详情、修改和删除统一返回 404。Spring Security 角色检查和业务层班级条件共同生效；Vue 隐藏按钮和路由守卫只改善体验，不构成授权。

选择理由：让查询本身带上资源 ID 与班级条件，可降低漏过滤和资源枚举风险，并让列表、详情与写操作遵循同一边界。

备选方案：先按 ID 查询再在 Java 中比较班级更容易在异常或日志中泄露敏感字段，因此不采用。

### Decision 5: 上传使用临时文件、MySQL 事务与文件补偿

上传流程：

```text
POST /api/materials/upload
  → Spring Security 验证登录与 TEACHER 角色
  → 校验扩展名、大小、非空与 UTF-8
  → 以随机名写入 uploads/.tmp/
  → 解析文本并按非空段落/长度上限切分
  → @Transactional
      INSERT materials
      INSERT knowledge_entries
      将临时文件原子移动到 uploads/<class_id>/<uuid>.<ext>
    COMMIT
  → 返回 201 和 material_id
```

文件名仅用于标题建议，存储路径使用后端生成的 UUID；不得拼接用户提供的路径。默认最大文件大小为 2 MiB，并允许通过后端配置调整。解析器先按空行分段，再将超长段落切到不超过 1000 个字符；空白材料拒绝入库。

任一步骤失败时抛出异常回滚 MySQL 事务，并删除本次产生的临时文件或最终文件。删除材料时先将文件移动到隔离临时位置，在数据库事务提交后删除隔离文件；若事务回滚则恢复文件。材料列表只查询 MySQL，不扫描上传目录。

选择理由：MySQL 事务不能覆盖文件系统，临时文件、同盘原子移动、数据库事务与明确补偿是当前范围内可验证的折中。

备选方案：对象存储和后台解析队列更适合大文件及生产负载，但超出首版范围。

### Decision 6: SPA 路由与 REST API 约定

Vue 路由：

- `/login`：登录页。
- `/materials`：本班材料列表与教师管理页。
- 其他受保护路由在加载前调用当前用户接口；未认证时跳转 `/login`。

后端接口：

- `GET /api/auth/csrf`：获取 CSRF 令牌。
- `POST /api/auth/login`：建立服务端会话。
- `GET /api/auth/me`：返回当前用户的必要身份、角色和班级展示信息。
- `POST /api/auth/logout`：清除会话。
- `GET /api/materials`：返回本班材料 JSON。
- `POST /api/materials/upload`：仅教师可用的 multipart 上传接口。
- `GET /api/materials/{id}`：按会话班级读取单条材料；跨班统一 404。
- `GET /api/materials/{id}/content`：按会话班级读取可在线查看的原文件字节，统一以 UTF-8 `text/plain` 和 `inline` 响应，Markdown 不执行渲染。
- `GET /api/materials/{id}/download`：按会话班级下载原文件，使用 `attachment` 和安全的原文件名。
- `PATCH /api/materials/{id}`：仅教师按会话班级修改允许编辑的材料元数据。
- `DELETE /api/materials/{id}`：仅教师按会话班级删除材料、关联知识条目和文件。
- `GET /health`：无需登录，成功时返回 HTTP 200 JSON。

匿名 API 请求统一返回 401 JSON，角色不足返回 403 JSON。前端路由守卫根据 401 跳转登录页，但任何业务数据都必须在后端认证成功后才可返回。

查看与下载均属于只读能力，教师和学生都可使用。Service 必须先以资源 ID 与会话 `class_id` 联合查询，再解析后端生成的相对存储路径；跨班、没有原文件或文件已不存在统一返回 404。响应不得包含绝对路径或相对 `stored_path`。文件仅允许来自配置的上传根目录，读取前再次执行规范化边界检查并拒绝非普通文件。由于文件上限为 2 MiB，首版使用内存字节响应以确保控制器返回前完成一致性检查。

### Decision 7: 幂等初始化和双班级验收数据

Flyway 只负责确定性 schema 迁移；受 `DEMO_SEED_ENABLED` 控制的 Spring 初始化组件负责幂等创建班级 A/B、教师 A、学生 A1、学生 B1，以及两班标题明显不同的材料与知识条目。唯一键与按自然键查询避免重复，初始化不得删除用户上传数据。

启用演示种子时必须从环境变量读取演示口令，再使用 BCrypt 生成哈希；数据库和源码均不保存明文密码字段。README 只记录本地演示账号、环境变量设置方式和生产环境禁用种子的要求。

选择理由：schema 迁移与环境相关的演示数据分离，可保持 Flyway 可重复部署，同时避免在迁移脚本中固化口令。

### Decision 8: 三服务 Compose、持久化与健康检查

项目提供前后端 `Dockerfile`、`docker-compose.yml` 和 `.env.example`：

- `frontend` 使用 Node 构建 Vue，再由 Nginx 提供静态文件并代理后端路径；宿主机默认通过 8080 端口访问。
- `backend` 使用 Maven 构建 Java 17 应用，再以 Java 17 运行时启动，内部监听 8080。
- `mysql` 使用 MySQL 8，字符集为 `utf8mb4`，凭据由 `.env` 注入。
- MySQL 数据使用命名 volume，上传目录使用独立持久化 volume；容器重建不得丢失两类数据。
- MySQL 健康后再启动后端，后端健康后再对外提供完整应用；Nginx 将 `/health` 代理到后端。
- `.env.example` 只列变量名和本地生成说明，不包含真实凭据；`.env` 加入 `.gitignore`。

`GET /health` 检查后端进程与 MySQL 可用性，不执行本 change 范围外的依赖探测。

选择理由：三服务结构明确体现 Vue、Spring Boot 和 MySQL 的运行边界，同时通过同源入口简化浏览器会话安全。

备选方案：将 MySQL 数据绑定到宿主目录便于直接查看文件，但数据库文件不适合人工操作；命名 volume 更安全，课程验收通过 SQL 和备份命令检查数据。

### Decision 9: 可复用应用壳与视觉系统

前端以教师示例 Demo 的信息架构为参考，而不复制其 mock 功能。认证后的页面统一使用共享 `AppShell`：桌面端约 232px 固定侧边栏、约 60px 班级顶栏和浅灰主工作区；窄屏收缩侧栏并保留图标的 `aria-label`/`title`。主色使用深红 `#94070a`，选中态使用浅红背景，内容用白色圆角卡片承载。

颜色、阴影、圆角、间距、控件高度和内容宽度都定义为全局 CSS 设计令牌。页面标题区、按钮、状态反馈、材料卡片和模态弹窗复用统一类名或组件；后续页面应组合这些令牌和 `AppShell`，不自行创建另一套主题。未实现的后续菜单可展示为明确的“待建设”状态，但不得伪装成可用链接。

材料页采用“标题与主要操作 → 统计概览 → 搜索工具栏 → 材料列表”的稳定结构。上传、查看、重命名使用统一模态框；查看 Markdown 时使用 `<pre>` 展示源文本，避免将用户文件作为 HTML 执行。下载通过同源认证请求取得 Blob 后触发浏览器保存。

选择理由：共享壳和设计令牌能让后续助手、作业、审计页面沿用一致视觉，同时保留真实系统的账号密码登录和服务端授权，不照搬 Demo 的账号快速切换或纯前端 mock 行为。

## Risks / Trade-offs

- [Java 运行环境不一致可能导致构建或启动失败] → Maven 编译参数、CI 和容器镜像统一固定为 Java 17 LTS。
- [JPA 方法误用可能漏掉班级条件] → 只暴露带 `classId` 的班级资源查询，并以跨班集成测试覆盖读写接口。
- [Cookie 会话面临 CSRF 风险] → 保持 Spring Security CSRF、防护令牌和同源 Nginx 代理，不为写接口全局关闭 CSRF。
- [内存会话在后端重启后失效] → 接受用户重新登录；业务数据和文件独立持久化，未来多实例时再采用 Spring Session。
- [文件系统与数据库无法原子提交] → 使用同盘临时文件、原子移动、MySQL 事务、删除隔离区和异常补偿，并覆盖失败路径。
- [文件查看或下载可能泄露跨班数据、服务端路径或可执行内容] → 联合 `id + class_id` 查询、存储根目录检查、纯文本响应、附件文件名编码，并以集成测试覆盖跨班和无文件场景。
- [后续页面各自实现样式会造成视觉漂移] → 强制复用 `AppShell`、全局设计令牌与基础组件，将页面级样式限制在业务布局。
- [MySQL 增加本地资源与启动依赖] → Compose 健康检查、连接重试和 Testcontainers 统一开发及验收环境。
- [文本分段规则较简单] → 固定规则并保存 `chunk_index`；语义切分和向量化留待后续 RAG change。

## Migration Plan

这是绿地项目，没有旧数据迁移：

1. Apply 阶段按 `tasks.md` 创建 Vue 前端、Spring Boot 后端、Flyway 结构和幂等种子。
2. 使用 MySQL/Testcontainers 验证登录、权限、隔离、CSRF 和上传失败回滚。
3. 使用 `.env.example` 创建本地 `.env`，构建并启动三服务 Compose。
4. 验证 `/health`、预置账号、跨班拒绝以及容器重建后的 MySQL 与文件持久化。

回滚时停止容器并回退前后端镜像；MySQL volume 与上传 volume 在确认不再需要前保持不动。结构发生破坏性变更前必须先执行 MySQL 逻辑备份并备份上传目录。
