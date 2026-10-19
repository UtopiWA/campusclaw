# mcp-tool-integration Specification

## ADDED Requirements

### Requirement: 最小 MCP HTTP 服务与部署
系统 MUST 在仓库内提供可由 Compose 启动的独立 MCP HTTP 服务，以 Bearer Token 保护 JSON-RPC 2.0 的 `initialize`、`tools/list` 和 `tools/call`，并提供无需认证的健康检查。

#### Scenario: 启动并发现内置工具
- **WHEN** Compose 使用已配置的 `MCP_AUTH_TOKEN` 启动 MCP 服务，后端携带正确 Token 依次调用 `initialize` 和 `tools/list`
- **THEN** 服务 MUST 返回合法 JSON-RPC 2.0 响应和至少两个实际可调用的工具定义
- **AND** 每个工具名 MUST 匹配 `[a-z][a-z0-9_]{0,31}`，并包含说明和合法 JSON Schema

#### Scenario: 调用确定性工具
- **WHEN** 客户端携带正确 Token 和满足 Schema 的参数调用已列出的工具
- **THEN** 服务 MUST 返回与输入对应的确定性结果
- **AND** 响应 MUST NOT 包含 Token、环境变量或服务内部异常栈

#### Scenario: MCP 凭据缺失或错误
- **WHEN** 客户端调用 `/mcp` 时没有 Bearer Token 或 Token 不匹配
- **THEN** 服务 MUST 返回 HTTP 401
- **AND** 日志和响应 MUST NOT 输出期望 Token 或实际提交值

#### Scenario: 健康检查不泄露能力和凭据
- **WHEN** Compose 或匿名客户端访问 `GET /health`
- **THEN** 服务 MUST 返回可用于健康判断的成功响应
- **AND** 响应 MUST NOT 包含工具参数、Token 或环境配置

### Requirement: 班级级 MCP 服务管理
系统 MUST 允许教师在当前班级创建、读取、修改、断开和删除 MCP 服务配置，并只从认证上下文确定角色与 `class_id`；Token MUST 只用于服务端持久化和出站认证。

#### Scenario: 教师创建 MCP 服务
- **WHEN** 教师提交班内唯一的合法名称、可规范化的 HTTP 或 HTTPS MCP 地址和非空 Token
- **THEN** 系统 MUST 创建当前班级的断开状态服务并返回 HTTP 201
- **AND** 响应 MUST 包含标识、名称、规范化地址、状态和 `hasAuth=true`，但 MUST NOT 包含 Token 或密文

#### Scenario: 加密保存 MCP Token
- **WHEN** 教师创建服务或为服务提交新 Token
- **THEN** 系统 MUST 使用环境提供的 32 字节密钥、随机 IV 和 AES-256-GCM 版本化信封加密后持久化
- **AND** 数据库值 MUST NOT 等于明文 Token，相同 Token 的两次写入 MUST 因随机 IV 产生不同密文

#### Scenario: Token 加密配置不可用
- **WHEN** 应用启动时加密密钥缺失或非法，或连接时既有 Token 密文无法通过完整性校验
- **THEN** 系统 MUST 分别拒绝启动或对连接请求返回脱敏的 HTTP 503
- **AND** 系统 MUST NOT 使用未加密 Token、空 Token 或损坏内容尝试远端连接

#### Scenario: 修改服务并保留或替换 Token
- **WHEN** 教师修改本班服务的名称或地址，并将 Token 留空或提交新 Token
- **THEN** 空 Token MUST 保留原值，非空 Token MUST 替换原值
- **AND** 配置版本 MUST 递增，服务 MUST 变为断开状态且旧工具缓存 MUST 被清空

#### Scenario: 拒绝非法或重复配置
- **WHEN** 教师提交不匹配 `[a-z][a-z0-9_-]{0,31}` 的服务名、班内重复名称、含凭据/查询/片段的 URL、非 HTTP(S) URL、不在 `MCP_ALLOWED_HOSTS` 的目标或超长字段
- **THEN** 系统 MUST 返回 HTTP 400 和可读字段错误
- **AND** 服务配置、Token、连接状态和工具缓存 MUST 保持不变

#### Scenario: 学生管理 MCP 服务
- **WHEN** 学生尝试列出、创建、修改、连接、断开、删除 MCP 服务，或查看工具和审计
- **THEN** 系统 MUST 返回 HTTP 403
- **AND** 服务、工具、技能和审计 MUST 保持不变

#### Scenario: 按编号访问其他班级服务
- **WHEN** 教师使用不存在或其他班级的服务编号执行读取、修改、连接、断开、删除或查看工具
- **THEN** 系统 MUST 返回与真实不存在一致的 HTTP 404
- **AND** 响应 MUST NOT 泄露目标是否存在、所属班级、地址或连接状态

### Requirement: 原子工具发现与连接状态
系统 MUST 在数据库事务外完成 MCP 网络探测，先完整验证 `initialize` 和 `tools/list`，再以配置版本校验将工具清单整体写入当前班级缓存；失败时 MUST 断开服务并清空旧缓存。

#### Scenario: 连接成功并整体替换工具缓存
- **WHEN** 教师连接本班服务，远端在超时内返回合法初始化响应和完整工具清单，且探测期间配置版本未变化
- **THEN** 系统 MUST 在一个短事务中删除旧缓存、写入全部新工具并标记服务已连接
- **AND** 删除旧缓存 MUST 在插入可能同名的新工具前实际提交到当前事务的数据库操作序列
- **AND** 教师随后读取工具列表 MUST 只得到此次发现的工具和 Schema

#### Scenario: 连接期间配置被修改
- **WHEN** 网络探测开始后，同一服务的名称、地址或 Token 被另一请求修改
- **THEN** 连接请求 MUST 在写入前发现配置版本变化并返回 HTTP 409
- **AND** 旧探测结果 MUST NOT 覆盖新配置或把服务标记为已连接

#### Scenario: 连接超时或协议非法
- **WHEN** 远端超时、返回非成功 HTTP、JSON-RPC 错误、非法 JSON 或任一非法工具定义
- **THEN** 连接接口 MUST 返回脱敏的 HTTP 502，服务 MUST 保持断开且缓存 MUST 为空
- **AND** 系统 MUST NOT 部分保存工具清单、跟随重定向或自动重试

#### Scenario: 主数据库在连接收尾时不可用
- **WHEN** 远端探测成功但本地数据库无法提交连接状态和工具缓存
- **THEN** 系统 MUST 返回 HTTP 503 且不得声称连接成功
- **AND** 后续读取 MUST NOT 观察到部分替换的工具缓存

#### Scenario: 教师主动断开服务
- **WHEN** 教师断开本班已连接 MCP 服务
- **THEN** 系统 MUST 标记服务断开并清空其工具缓存
- **AND** 服务配置、Token 和历史审计 MUST 保留

### Requirement: MCP 服务删除与审计保留
系统 MUST 在 MCP 服务没有调用审计时允许教师删除服务并级联删除工具缓存，在存在任意调用审计时拒绝删除并保留全部记录。

#### Scenario: 删除没有审计的服务
- **WHEN** 教师删除本班没有调用审计的 MCP 服务
- **THEN** 系统 MUST 删除服务并级联删除其工具缓存
- **AND** 其他服务、审计和技能文本 MUST 保持不变，技能中的旧引用后续 MUST 按不可用处理

#### Scenario: 拒绝删除已有审计的服务
- **WHEN** 教师删除至少存在一条调用审计的本班 MCP 服务
- **THEN** 系统 MUST 返回 HTTP 409 和建议断开服务的可读说明
- **AND** 服务、Token、工具缓存和全部审计 MUST 保持不变

#### Scenario: 删除检查期间产生审计
- **WHEN** 删除请求完成应用层预检查后，并发工具调用为该服务写入审计
- **THEN** 数据库限制 MUST 阻止服务被删除，系统 MUST 将冲突映射为 HTTP 409
- **AND** 新审计及其服务归属 MUST 保持完整

### Requirement: 工具调用安全边界
系统 MUST 按选中技能的规范化 MCP 引用顺序串行调用工具，使用确定性参数适配器，并为每项调用生成 `ok`、`denied` 或 `error` 的安全摘要；单项失败 MUST NOT 中止后续工具、材料检索或主回答。

#### Scenario: 顺序调用多个可用工具
- **WHEN** 选中技能依次引用多个属于当前班级、已连接且已发现的 MCP 工具
- **THEN** 系统 MUST 跳过内置 `knowledge_search` 并按技能中的首次出现顺序逐个调用 MCP 工具
- **AND** 每次调用 MUST 使用服务端适配器从当前问题构造参数，不得让模型生成参数或自动重试

#### Scenario: 确定性构造演示工具参数
- **WHEN** 编排器调用 `formula_hint` 或问题包含可规范化的数学表达式并调用 `calculator`
- **THEN** `formula_hint` MUST 接收至多 500 个 Unicode code point 的当前问题作为 `query`，`calculator` MUST 接收代码从纯表达式、固定自然语言外壳或受控中文幂/根号/运算词形态中完整提取并规范化的 `expression`
- **AND** “计算2的20次幂” MUST 规范化为 `2 ** 20`；演示计算器 MUST 只允许绝对值不超过 100 的乘方指数
- **AND** 超长、混入非表达式文本或包含白名单外语法的输入 MUST 产生 `denied`，不得把问题交给模型补参数或执行任意代码

#### Scenario: 服务断开或适配器拒绝调用
- **WHEN** 引用对应的服务存在但已断开，或参数无法通过服务端适配器校验
- **THEN** 系统 MUST 为该引用生成 `denied` 摘要并写入一条审计
- **AND** 系统 MUST 继续处理后续工具和本轮问答

#### Scenario: 工具在调用时已不存在
- **WHEN** 技能保存后服务被删除、重命名或缓存更新导致引用无法定位合法服务与工具
- **THEN** 系统 MUST 生成不泄露跨班信息的 `denied` 摘要，并 MUST NOT 发起网络请求
- **AND** 没有合法服务主键时 MUST NOT 伪造审计记录，主问答 MUST 继续

#### Scenario: 远端调用失败
- **WHEN** 工具调用超时、返回非成功 HTTP、JSON-RPC 错误、远端工具错误或非法响应
- **THEN** 系统 MUST 生成 `error` 摘要并为可定位的服务写入一条审计
- **AND** 摘要和审计 MUST NOT 包含 Token、完整参数、原始响应、内部地址或异常栈

#### Scenario: 不同班级存在同名服务和工具
- **WHEN** 两个班级分别配置相同名称的服务与工具并各自发起对话
- **THEN** 每次解析、调用和审计 MUST 只使用认证用户当前 `class_id` 下的记录
- **AND** 任一班级的 Token、工具缓存、结果和审计 MUST NOT 出现在另一班级响应中

### Requirement: 不可信工具结果过滤
系统 MUST 将 MCP 输出视为不可信数据，在进入对话上下文前限制长度并检测已知指令注入模式；只有未被拒绝的安全结果才可作为生成依据。

#### Scenario: 安全结果进入用户数据上下文
- **WHEN** 工具成功返回不超过限制且未命中注入模式的文本
- **THEN** 系统 MUST 将最多 1200 个 Unicode code point 的结果连同服务和工具来源标签放入用户数据上下文
- **AND** 结果 MUST NOT 被拼接进 system prompt，调用摘要 MUST 标记为 `ok`

#### Scenario: 结果包含已知指令注入
- **WHEN** 工具结果大小写无关地命中“忽略前述/此前指令”、“ignore previous instructions”、“你现在是”或伪造 `system:` 前缀
- **THEN** 系统 MUST 用固定安全提示整体替换结果并将其标记为不可用于生成
- **AND** 危险原文 MUST NOT 进入模型上下文、SSE、消息快照、审计或应用日志

#### Scenario: 工具结果超过长度限制
- **WHEN** 可解析工具文本超过 1200 个 Unicode code point
- **THEN** 系统 MUST 按 Unicode code point 安全截断后再执行注入检测
- **AND** 截断 MUST NOT 破坏字符或扩大可见内容边界

### Requirement: 教师查看工具调用审计
系统 MUST 允许教师按时间倒序查看当前班级的 MCP 工具调用审计，默认返回 50 条且单次最多 100 条，并只返回安全字段。

#### Scenario: 教师查看本班审计
- **WHEN** 教师请求审计列表且未指定数量
- **THEN** 系统 MUST 按创建时间倒序返回当前班级最多 50 条记录
- **AND** 每条记录 MUST 包含工具引用、调用用户 ID 与用户名、可选会话、状态、安全摘要、可空毫秒耗时和时间；成功结果摘要最多 200 个 Unicode code point，任一详情最多 512 个 Unicode code point，且不得包含 Token、Base URL、完整参数或原始结果

#### Scenario: 审计数量超过上限
- **WHEN** 教师请求超过 100 条审计或提交非法分页参数
- **THEN** 系统 MUST 将合法数量限制在 100 以内或对非法值返回 HTTP 400
- **AND** 响应 MUST 始终只包含当前班级记录

#### Scenario: 会话删除后读取审计
- **WHEN** 用户删除曾产生 MCP 调用的自己的会话
- **THEN** 会话和消息 MUST 按既有规则删除，但 MCP 调用审计 MUST 保留
- **AND** 审计中的历史会话标识 MAY 保留为不可导航的关联值
