# 第 2 课验收记录

验收日期：2026-09-23  
运行环境：Docker Compose、MySQL 8.4、后端容器 Temurin Java 17.0.20、宿主机端口 8080。

## 自动化验收

| 验收项 | 命令 | 实际结果 |
| --- | --- | --- |
| 后端单元与集成测试 | `backend/mvnw.cmd test` | 7 项测试全部通过；Testcontainers 启动真实 MySQL 8.4 |
| 前端单元测试 | `npm test -- --run` | 3 个测试文件、6 项测试全部通过 |
| 前端生产构建 | `npm run build` | 构建成功，生成 Vite 产物 |
| Compose 构建与启动 | `docker compose up --build -d` | frontend、backend、mysql 均成功启动并通过健康依赖 |
| OpenSpec 严格校验 | `openspec validate add-auth-rbac-class-knowledge --strict` | 退出码 0 |

## 运行态端到端验收

| 场景与请求 | 预期 | 实际 |
| --- | --- | --- |
| 匿名 `GET /health` | 200 JSON，无需登录 | 200，`{"status":"ok"}` |
| MySQL 停止后 `GET /health` | 503，不跳转登录页 | 503；恢复 MySQL 后后端重新变为 healthy |
| 教师 A、学生 A1、学生 B1 登录 | 建立各自会话并返回正确角色、班级 | 三个账号均成功；教师为 `teacher`，两名学生为 `student` |
| 教师 A `POST /api/materials/upload` 上传 UTF-8 Markdown | 201，生成材料和知识条目 | 201；同班列表立即可见 |
| 学生 A1 查询材料列表 | 可见教师 A 新上传材料 | 可见 |
| 学生 B1 查询材料列表 | 不可见 A 班新上传材料 | 不可见 |
| 教师 A 按已知 B 班材料 ID 读取 | 统一返回 404 | 404 |
| 学生 A1 尝试上传合法 Markdown | 403，数据库与文件不变 | 403，无新增记录 |
| 教师 A 上传 `.json` 文件 | 400，数据库与文件不留残余 | 400，返回仅支持 `.txt`/`.md`，无新增记录 |
| 教师 A 删除验收材料 | 材料、知识条目和文件一并删除 | 204；记录和文件均已清理 |
| 检查会话 Cookie | `HttpOnly`、`SameSite=Lax` | 两项均满足；CSRF Cookie 可由前端读取 |
| `docker compose down` 后重新 `up -d` | 用户、上传记录、知识条目和文件仍存在 | 登录成功，验收材料记录及上传文件均保留 |

持久化验证完成后已删除临时验收材料；命名卷保留种子数据，上传卷中无验收残留文件。

## 2026-09-26 增量验收：文件查看、下载与界面统一

本次先补充 proposal、design、spec、tasks 与 OpenSpec 项目配置，再实现文件能力和视觉重构。原 change 因新增未完成任务从 `archive/` 恢复为活动状态，未再次归档。

| 验收项 | 命令或方式 | 实际结果 |
| --- | --- | --- |
| 后端单元与集成测试 | `mvn.cmd test` | 8 项全部通过；Testcontainers 使用真实 MySQL 8.4，V1/V2 迁移成功 |
| 前端单元测试 | `npm.cmd test -- --run` | 3 个测试文件、8 项测试全部通过 |
| 前端生产构建 | `npm.cmd run build` | Vite 构建成功，共转换 34 个模块 |
| Compose 重建 | `docker compose up --build -d` | 前后端镜像构建成功，MySQL 与后端通过健康检查，前端启动成功 |
| OpenSpec 严格校验 | `openspec.cmd validate add-auth-rbac-class-knowledge --strict` | 退出码 0 |
| 浏览器桌面验收 | Playwright，1440 × 1000 | 232px 侧栏、60px 顶栏、页面概览与材料卡片正常，无横向溢出 |
| 浏览器紧凑验收 | Playwright，814 × 900 | 侧栏收缩至 76px，5 个导航项均保留可访问名称，无横向溢出 |
| 浏览器手机验收 | Playwright，390 × 844 | 侧栏隐藏，上传、搜索、材料操作纵向重排，主要操作均可见 |

文件闭环实测：

- 教师上传临时 Markdown 返回 HTTP 201，列表立即显示文件名、155 B 大小、查看和下载入口。
- 在线查看响应在 `<pre>` 中保留 `#` 等 Markdown 源文本，没有生成或执行标题 HTML。
- 下载触发的建议文件名为原文件名 `campusclaw-visual-check.md`。
- 同班师生成功路径、匿名 401、跨班 404、无原文件 404、`storedPath` 不泄露均由后端集成测试覆盖。
- 临时验收材料最后通过页面删除，材料记录、知识条目与原文件均无残留。
