# Tasks

## 1. JWT 配置与后端认证

- [x] 1.1 添加 Spring Security Resource Server/Jose 依赖和独立 JWT 配置，要求至少 32 字节环境密钥并提供正数有效期；验证：执行配置绑定测试，合法配置可创建编解码器，缺失或过短密钥明确失败。
- [x] 1.2 实现只包含用户 ID、签发者和时间 claims 的 HS256 Token service；验证：单元测试确认令牌可验证、过期或篡改令牌被拒绝，claims 不包含角色、班级、用户名或正文。
- [x] 1.3 将 Spring Security 改为 `STATELESS` Bearer 认证并移除 Session/CSRF 认证链路；验证：MockMvc 测试确认无令牌、伪造令牌为 401，有效令牌可访问，响应不设置 `JSESSIONID`，写请求不再要求 CSRF。
- [x] 1.4 调整登录、当前用户与登出接口：登录返回令牌元数据和用户视图，当前用户从 token subject 回查数据库，登出保持幂等 204；验证：集成测试覆盖正确/错误凭据、数据库角色或班级变更即时生效、账号禁用以及统一 401。

## 2. Vue Bearer 客户端

- [x] 2.1 将 API 客户端改为从 `sessionStorage` 读取令牌并设置 Authorization Header，删除 CSRF 和 Cookie credentials 流程；验证：Vitest 确认普通读写和 multipart 请求均携带 Bearer Header，登录请求不携带旧令牌，401 会清除令牌。
- [x] 2.2 调整登录、身份恢复和登出状态管理，保存登录响应令牌、刷新后调用 `/me`、退出时清除令牌；验证：Vitest 覆盖登录保存、刷新恢复、退出清理和标签页无令牌时的匿名状态。

## 3. 配置、文档与回归

- [x] 3.1 更新 `application.yml`、Compose、`.env.example` 与 README，声明 `AUTH_JWT_SECRET` 和有效期，删除 Session/CSRF 操作说明；验证：执行 `docker compose --env-file .env.example config --quiet` 成功且示例不含真实秘密。
- [x] 3.2 将后端集成测试从 MockHttpSession/CSRF 改为 Bearer Token，并复核检索、材料、问答的角色及班级隔离不变；验证：执行 `mvn test` 全部通过，覆盖 MySQL 与 Qdrant Testcontainers。
- [x] 3.3 运行前端测试、生产构建和 OpenSpec 严格校验；验证：`npm.cmd test -- --run`、`npm.cmd run build` 与 `openspec.cmd validate replace-session-auth-with-bearer-jwt --strict` 均退出码为 0。
