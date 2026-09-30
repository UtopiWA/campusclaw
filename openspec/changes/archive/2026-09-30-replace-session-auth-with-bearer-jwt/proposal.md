# Proposal

## Why

CampusClaw 当前通过 `JSESSIONID` Cookie 保存服务端 `HttpSession`，写请求还依赖 CSRF Cookie 与请求头。用户希望改为显式令牌认证，以便前后端边界更清晰，并让 API 调用统一通过 `Authorization: Bearer <token>` 表达身份。

## What Changes

- 登录成功后签发有过期时间的 HMAC-SHA256 JWT，响应同时返回当前用户视图。
- Spring Security 改为无状态资源服务器；受保护 API 只接受 Bearer Token，不创建或读取 `HttpSession`。
- JWT 只保存稳定用户 ID，不保存角色或班级；每次请求仍从 MySQL 重新加载账号启用状态、角色和 `classId`。
- Vue 将访问令牌保存在当前浏览器标签页的 `sessionStorage`，统一为 API 请求附加 `Authorization` 请求头。
- 移除 CSRF 获取流程和 Cookie 凭据发送；登出清除前端令牌和身份状态。
- 增加 JWT 密钥与有效期配置、Compose 示例和对应自动化测试。

## Capabilities

### Modified Capabilities

- `auth-upload`: 将账号密码登录后的认证凭据从服务端 Cookie/Session 改为无状态 Bearer JWT，角色授权、班级隔离和业务接口权限保持不变。

### New Capabilities

（无。）

## Impact

- 后端将引入 Spring Security OAuth2 Resource Server/Jose 支持，并调整安全配置、登录响应和当前用户解析。
- 前端 API 客户端、登录状态恢复和登出流程将改为令牌模式。
- 部署必须提供至少 32 字节的 JWT HMAC 密钥；现有数据库结构和业务数据无需迁移。
- 现有依赖 Cookie/Session 与 CSRF 的后端、前端测试需要同步改为 Bearer Token。

## Non-goals

- 不实现 Refresh Token、令牌轮换、服务端撤销列表或多设备会话管理。
- 不接入 OAuth、OIDC、SSO 或第三方身份提供商。
- 不把角色、班级或业务权限固定进 JWT，也不削弱现有服务端授权和班级过滤。
- 不将访问令牌写入持久化数据库、普通 Cookie、日志或 URL。

