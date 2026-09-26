# Tasks

## 1. 建立 Vue 与 Spring Boot 工程骨架

- [x] 1.1 在 `frontend/` 创建 Vue 3、Vite、Vue Router 工程及登录页、材料页和 API service 骨架；验证：执行 `npm ci && npm run build` 成功生成前端产物。
- [x] 1.2 在 `backend/` 创建 Java 17、Maven Wrapper、Spring Boot 3 工程，引入 Spring Web、Spring Security、Spring Data JPA、Validation、Flyway 和 MySQL 驱动；验证：执行 `./mvnw test` 成功且 Maven 编译目标为 Java 17。
- [x] 1.3 实现环境变量配置，包含 MySQL URL/账号/密码、上传目录、上传大小、Cookie Secure 开关和演示种子开关/口令；验证：缺少必需数据库配置或启用种子但缺少口令时，后端启动失败并给出明确错误。
- [x] 1.4 配置 Vite 开发代理和统一的前端 API 客户端，使 `/api` 与 `/health` 保持同源路径；验证：开发模式请求被代理到后端，生产构建中不包含硬编码后端主机或敏感配置。

## 2. 实现 MySQL 数据模型与可验收种子

- [x] 2.1 编写 Flyway 迁移，创建 `classes`、`users`、`materials`、`knowledge_entries`、`assignments`、`assistants`、`skills` 表及外键、唯一约束和班级组合索引；验证：对空 MySQL 8 实例执行迁移后检查 schema、InnoDB、`utf8mb4`、外键和索引均符合设计。
- [x] 2.2 创建 JPA 实体与 Repository，配置 `ddl-auto=validate`，并确保所有班级资源映射显式包含 `classId`；验证：Spring Boot 启动时完成映射校验且不隐式创建或修改表。
- [x] 2.3 实现受 `DEMO_SEED_ENABLED` 控制的幂等初始化，创建班级 A/B、教师 A、学生 A1、学生 B1及可区分的材料、知识条目、作业、助手和技能数据；验证：连续启动两次后固定种子数量不增加，已有上传数据不被删除。
- [x] 2.4 使用 `BCryptPasswordEncoder` 哈希环境变量提供的演示口令；验证：MySQL 中不存在明文密码，正确口令可通过 BCrypt 校验，错误口令不能通过。

## 3. 实现 Spring Security 会话与 Vue 登录流程

- [x] 3.1 配置 Spring Security 服务端 `HttpSession`、`JSESSIONID` Cookie、统一 401/403 JSON 响应及会话固定攻击防护；验证：认证前后检查 Cookie 属性和状态码，确认 Cookie 为 `HttpOnly`、`SameSite=Lax` 且 HTTPS 配置下启用 `Secure`。
- [x] 3.2 实现 `POST /api/auth/login` 与 `GET /api/auth/me`，会话 Principal 只保存用户 ID，每次请求从 MySQL 重新读取角色和班级；验证：教师和学生可用正确凭据登录，错误凭据返回统一失败信息，修改数据库角色/班级后下次请求使用最新值。
- [x] 3.3 保持 Spring Security CSRF 防护并实现 `GET /api/auth/csrf`，让 Vue API 客户端为写请求发送 `X-XSRF-TOKEN`；验证：缺少或伪造令牌的写请求被拒绝，携带有效令牌的合法请求能够继续处理。
- [x] 3.4 实现 Vue 登录页、当前用户状态和受保护路由守卫；验证：匿名访问 `/materials` 跳转 `/login`，登录后按角色显示本班页面，且前端在任何 401 响应后清理状态并回到登录页。
- [x] 3.5 实现 `POST /api/auth/logout` 并联动清理前端状态；验证：退出后旧会话不能继续访问受保护 API 或前端路由。

## 4. 落实角色权限与班级隔离

- [x] 4.1 为材料列表和单条材料实现只暴露带 `classId` 条件的 Repository/Service 查询；验证：教师 A、学生 A1 只能获得 A 班数据，学生 B1 只能获得 B 班数据。
- [x] 4.2 对跨班对象访问采用统一 404，并拒绝或忽略请求 DTO 中额外提交的 `class_id`；验证：A 班账号使用已知 B 班 ID 读取、修改或删除均得到 404，响应不泄露对象是否存在，伪造班级不能改变归属。
- [x] 4.3 使用 Spring Security 方法或路由授权实现教师写权限和学生只读权限；验证：教师可修改或删除本班材料，学生调用上传、修改或删除接口均返回 403，MySQL 和文件系统无变化。
- [x] 4.4 在 Vue 材料页按角色显示教师上传/管理入口或学生只读界面；验证：分别以教师和学生浏览页面，并以直接 API 请求确认后端授权不依赖按钮隐藏或路由守卫。

## 5. 实现材料上传与知识库入库

- [x] 5.1 实现 UTF-8 `.txt`/`.md` 解析和确定性的段落切分，过滤空段并限制单条知识文本长度；验证：运行 JUnit 单元测试，输出顺序稳定、无空条目且每条不超过 1000 字符。
- [x] 5.2 实现扩展名、空文件、UTF-8 编码和 2 MiB 默认大小上限校验，并使用临时目录与 UUID 存储名；验证：非法扩展名、空文件、非 UTF-8 文件和超限文件均返回明确 4xx，且不产生 MySQL 记录或残留文件。
- [x] 5.3 实现仅教师可用的 `POST /api/materials/upload`，通过 `@Transactional` 写入材料与知识条目，以当前用户班级和用户 ID 为准并完成文件原子落盘；验证：成功上传返回 201 和材料 ID，同班列表立即可见，数据库关联和最终文件正确。
- [x] 5.4 实现材料元数据修改与带文件隔离补偿的删除流程；验证：教师修改本班材料后列表反映新值，删除成功后材料、知识条目和文件均不存在，删除事务回滚时文件能够恢复。
- [x] 5.5 为解析失败、MySQL 写入失败、事务提交失败和文件移动失败实现回滚与清理；验证：通过故障注入运行集成测试后，相关表和上传目录均不留下半成品。

## 6. 提供三服务 Compose 与健康检查

- [x] 6.1 编写前端多阶段 `Dockerfile` 与 Nginx 配置，托管 Vue SPA 并代理 `/api`、`/health`；验证：构建前端镜像后，直接访问和刷新 `/login`、`/materials` 均返回 SPA，代理请求到达后端。
- [x] 6.2 编写后端 Java 17 多阶段 `Dockerfile`，使用 Maven 构建并以 Java 17 运行时启动 Spring Boot；验证：检查构建日志和容器中的 `java -version`，均显示 Java 17，后端内部监听 8080。
- [x] 6.3 编写 `docker-compose.yml` 与 `.env.example`，定义 frontend、backend、mysql 三个服务、健康依赖、MySQL 数据卷和上传文件卷；验证：执行 `docker compose config` 成功，输出中不含真实凭据且端口、环境变量和 volumes 符合设计。
- [x] 6.4 实现公开的 `GET /health` 并检查 MySQL 可用性，配置 MySQL 与后端健康检查；验证：健康时返回 200 JSON 且无需会话，MySQL 不可用时后端健康状态失败而不是跳转登录页。
- [x] 6.5 验证从零构建、启动和持久化；验证：执行 `docker compose up --build` 后可通过宿主机 8080 登录，在不删除 volumes 的情况下执行 `down` 再 `up`，用户、材料、知识条目和文件仍然存在。
- [x] 6.6 完善 README 的 Java 17、Node.js、Compose 启动步骤、环境变量、演示账号、主要入口、验收命令和生产环境禁用演示种子的说明；验证：按 README 在干净环境完成一次启动与核心场景验收，无需额外口头步骤。

## 7. 完成自动化验收与规格收口

- [x] 7.1 使用 JUnit 5、Spring Boot Test 与 Testcontainers MySQL 建立后端测试，覆盖认证、CSRF、角色权限、班级隔离、上传校验、知识入库、回滚和健康检查；验证：执行 `./mvnw test` 全部通过且测试连接的是真实 MySQL 容器。
- [x] 7.2 使用 Vitest 与 Vue Test Utils 覆盖登录状态、路由守卫、角色界面和 API 错误处理；验证：执行 `npm test -- --run` 与 `npm run build` 均通过。
- [x] 7.3 按教师 A、学生 A1、学生 B1 执行端到端验收，覆盖同班可见、跨班不可见、学生写入被拒绝、合法上传可见和非法上传无残留；验证：记录每个场景的请求、预期结果和实际结果并全部通过。
- [x] 7.4 对变更执行 OpenSpec 严格校验；验证：`openspec validate add-auth-rbac-class-knowledge --strict` 退出码为 0，且本课所有任务在实际完成前保持未勾选状态。

## 8. 补全材料文件读取能力

- [x] 8.1 通过新 Flyway 迁移为材料补充可空文件大小元数据，并在上传时写入实际字节数；验证：既有数据库可从 V1 平滑迁移，JPA `validate` 通过，新上传列表返回准确的 `fileSizeBytes`，种子材料返回 `hasFile=false`。
- [x] 8.2 实现同班可用的 `GET /api/materials/{id}/content` 与 `GET /api/materials/{id}/download`；验证：教师和学生得到与上传内容一致的响应，查看为 UTF-8 纯文本 `inline`，下载为带安全原文件名的 `attachment`，响应不含存储路径。
- [x] 8.3 覆盖文件读取安全边界；验证：匿名访问返回 401，跨班 ID、无物理文件和缺失文件均返回 404，且读取前通过规范化上传根目录与普通文件检查。
- [x] 8.4 在 Vue 材料页实现查看弹窗和下载操作；验证：同班上传文件可在弹窗按纯文本查看并下载，`hasFile=false` 的材料明确禁用文件操作，学生仍看不到任何写按钮。

## 9. 对齐示例 Demo 并固化前端视觉体系

- [x] 9.1 建立共享 `AppShell`、基础图标/模态框组件和全局 CSS 设计令牌；验证：侧边栏、顶栏、工作区、按钮、卡片、反馈和弹窗均由共享实现承载，未实现导航明确标为待建设。
- [x] 9.2 重构材料页为标题主操作、概览、搜索工具栏和材料列表结构，并以统一弹窗承载上传、查看和重命名；验证：教师完成上传/查看/下载/重命名/删除闭环，学生完成搜索/查看/下载只读闭环。
- [x] 9.3 美化登录页并完成响应式与可访问性处理；验证：桌面与窄屏均可登录和使用材料页，收缩导航仍有可访问名称，键盘可操作表单和模态框。

## 10. 增量验证与规约收口

- [x] 10.1 扩展 Spring Boot 集成测试，覆盖查看/下载成功、班级隔离、无文件和元数据不泄露；验证：`backend/mvnw.cmd test` 全部通过。
- [x] 10.2 扩展 Vitest 测试，覆盖教师/学生文件操作与 Blob/文本响应；验证：`npm test -- --run` 与 `npm run build` 全部通过。
- [x] 10.3 对更新后的变更执行 OpenSpec 严格校验并更新 README、验收记录；验证：规约校验退出码为 0，文档列出查看/下载接口和统一视觉约定，本节任务仅在对应验证真实通过后勾选。
