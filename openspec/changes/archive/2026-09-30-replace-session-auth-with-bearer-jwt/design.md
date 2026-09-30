# Design

## Context

当前登录接口将只含用户 ID 的 Principal 写入 `HttpSession`，浏览器依靠 `JSESSIONID` Cookie 自动携带身份；写请求另需先获取 CSRF Token。业务层已经在每次请求中按用户 ID 回查 MySQL，因此改成 JWT 后可保留现有角色和班级授权模型，只替换认证凭据的传输与恢复方式。

## Goals / Non-Goals

**Goals:**

- 所有受保护 API 使用 `Authorization: Bearer <JWT>`，后端保持完全无状态。
- 登录失败、过期令牌、伪造令牌和缺少令牌统一返回不泄露细节的 HTTP 401。
- JWT 只携带用户 ID；账号启用状态、角色和班级仍以数据库当前值为准。
- 前端刷新当前标签页后可从 `sessionStorage` 恢复令牌并重新调用 `/api/auth/me`。

**Non-Goals:**

- 不实现 Refresh Token、黑名单、即时单令牌撤销和跨标签页共享登录。
- 不将 JWT 暴露到 URL、日志、Local Storage 或响应 Cookie。

## Decisions

### Decision 1: 使用 Spring Security Resource Server 与 Nimbus JWT

后端引入 `spring-boot-starter-oauth2-resource-server`。登录成功后使用 `JwtEncoder` 生成 HS256 JWT；资源服务器使用同一 HMAC 密钥的 `JwtDecoder` 验证签名、签发者和过期时间。安全策略使用 `SessionCreationPolicy.STATELESS`，禁用 `HttpSession` 安全上下文、表单登录、HTTP Basic 和 CSRF。

JWT claims 仅包含：

- `sub`: 数据库用户 ID；
- `iss`: 固定为 `campusclaw`；
- `iat`、`exp`: 签发与过期时间。

不写入用户名、密码、角色、班级或任何业务正文。JWT 转换器把 `sub` 转为现有最小 Principal，`CurrentUserService` 继续逐请求读取数据库。

选择理由：使用 Spring Security 已维护的 JWT 验证链路，避免手写签名、Base64 和时钟校验。HMAC 适合当前单后端 Compose 范围，部署只需一个服务端密钥。

### Decision 2: 登录响应显式返回令牌和用户

`POST /api/auth/login` 返回：

```json
{
  "tokenType": "Bearer",
  "accessToken": "...",
  "expiresAt": "...",
  "user": { "id": 1, "username": "teacher-a", "role": "teacher", "classId": 1, "className": "班级 A" }
}
```

错误凭据仍返回统一 HTTP 401。`GET /api/auth/me` 只在 Bearer Token 有效时返回用户视图。`POST /api/auth/logout` 保留为幂等 204 端点，但服务端不保存或撤销令牌；前端无论该请求结果如何都清除本地令牌。

### Decision 3: 前端使用 sessionStorage

API 客户端通过小型令牌存取函数读写 `sessionStorage`，并在每次请求上设置 Bearer Header。选用 `sessionStorage` 而非 `localStorage`，使令牌在标签页关闭后自然消失并减少长期暴露窗口；页面刷新时仍能恢复当前标签页登录。

登录请求本身不附加令牌。收到任意 401 后清除令牌和共享用户状态，并触发现有未授权事件。由于浏览器不会自动附加 Authorization Header，后端不再需要 Cookie 场景的 CSRF Token 流程。

### Decision 4: 配置与失败边界

新增 `AUTH_JWT_SECRET` 和可选 `AUTH_JWT_TTL`。密钥必须至少 32 个 UTF-8 字节且不提供可运行默认值；缺失或过短时应用启动失败。有效期默认 8 小时，必须为正值。Compose 和 `.env.example` 只提供安全占位符，不提交真实密钥。

JWT 验证错误、过期或格式错误统一返回 `{"error":"Authentication required"}`，不得回显令牌、签名异常或解析详情。常规日志不得输出 Authorization Header。

## Risks / Trade-offs

- [JWT 在到期前无法被服务端单独撤销] → 使用较短有效期，账号禁用仍会因逐请求回查数据库立即生效；令牌撤销列表留给后续需求。
- [sessionStorage 中令牌受 XSS 影响] → 前端继续禁止 `v-html` 渲染不可信正文，并避免第三方脚本；相比 localStorage 缩短令牌驻留时间。
- [HMAC 密钥泄露可伪造令牌] → 密钥仅由环境变量注入、至少 32 字节、不进入浏览器和日志，并支持通过重启服务轮换。
- [切换后旧 Session 立即失效] → 接受所有用户重新登录；数据库和业务数据不受影响。

## Migration Plan

1. 添加 JWT 配置与依赖，先通过配置绑定和令牌单元测试。
2. 将 Spring Security 与登录控制器改为无状态 Bearer 模式。
3. 调整 Vue API 客户端、登录恢复和登出逻辑。
4. 将集成测试从 MockHttpSession 改为 Authorization Header，验证 Cookie/CSRF 不再是认证条件。
5. 更新 Compose、`.env.example`、README 和 OpenSpec 相关表述。

回滚时恢复原安全配置和前端 Cookie/CSRF 客户端；数据库无需回滚。已签发 JWT 在旧版本中不会被接受，用户重新登录即可。

