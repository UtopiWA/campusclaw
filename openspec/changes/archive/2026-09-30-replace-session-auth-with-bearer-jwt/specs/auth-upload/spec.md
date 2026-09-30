# Spec Delta

## Purpose

将 CampusClaw 的登录凭据从服务端 Cookie/Session 改为显式 Bearer JWT，同时保持数据库实时授权、角色权限和班级数据隔离。

## MODIFIED Requirements

### Requirement: 用户登录与会话
系统 MUST 支持教师和学生使用账号密码登录。登录成功后，系统 MUST 签发有过期时间的 Bearer JWT 且 MUST NOT 建立服务端认证会话；后续请求必须通过有效令牌确定当前用户，并从数据库读取最新角色和所属班级。

#### Scenario: 教师和学生使用有效账号登录
- **WHEN** 教师或学生使用有效用户名和密码登录
- **THEN** 系统 MUST 返回 Bearer 访问令牌、过期时间和当前用户视图
- **AND** 携带该令牌的后续请求 MUST 能确定用户标识及数据库当前角色和班级
- **AND** 响应 MUST NOT 设置用于认证的 Session Cookie

#### Scenario: 错误凭据登录失败
- **WHEN** 用户提交不存在的账号或错误密码
- **THEN** 系统 MUST 返回 HTTP 401 且 MUST NOT 签发访问令牌
- **AND** 错误响应 MUST NOT 暴露账号是否存在、密码哈希或其他敏感信息

#### Scenario: 未登录访问受保护资源
- **WHEN** 客户端不携带有效 Bearer Token 访问受保护页面或 API
- **THEN** 系统 MUST 返回 HTTP 401
- **AND** 响应 MUST NOT 包含令牌内容、解析细节、班级数据或业务正文

#### Scenario: 伪造或过期令牌
- **WHEN** 客户端携带签名无效、格式错误或已过期的 Bearer Token 访问受保护 API
- **THEN** 系统 MUST 返回与未携带令牌相同的 HTTP 401
- **AND** 响应 MUST NOT 回显令牌、签名错误或解析详情

#### Scenario: 用户退出登录
- **WHEN** 已登录用户执行退出操作
- **THEN** 前端 MUST 清除保存的访问令牌和用户状态
- **AND** 未再次登录的后续请求 MUST 不再携带该令牌并被视为未认证

### Requirement: 教师和学生角色权限
系统 MUST 在服务端根据有效 Bearer Token 对应用户的数据库当前角色授权。教师可上传和管理本班教学材料；学生仅可读取本班材料，学生发起任何材料写请求均必须被拒绝。

#### Scenario: 教师上传本班材料
- **WHEN** 携带有效令牌且数据库当前角色为 `teacher` 的用户提交合法材料
- **THEN** 系统 MUST 允许请求进入材料上传与知识库入库流程
- **AND** 新记录的上传者和班级 MUST 来自令牌用户的数据库当前记录

#### Scenario: 学生上传材料被拒绝
- **WHEN** 携带有效令牌且数据库当前角色为 `student` 的用户调用材料上传接口
- **THEN** 系统 MUST 返回 HTTP 403
- **AND** 目标数据库记录、文件和索引 MUST 保持不变

#### Scenario: 学生调用其他材料写接口
- **WHEN** 携带有效令牌且数据库当前角色为 `student` 的用户调用修改、删除或索引重建接口
- **THEN** 系统 MUST 返回 HTTP 403
- **AND** 目标材料、知识条目、文件和索引 MUST 保持不变

#### Scenario: 教师管理本班材料
- **WHEN** 携带有效令牌且数据库当前角色为 `teacher` 的用户修改或删除本班材料
- **THEN** 系统 MUST 完成对应操作
- **AND** 材料记录、关联知识条目、索引和文件状态 MUST 保持一致

#### Scenario: 令牌内不得固化业务权限
- **WHEN** 用户的角色、班级或启用状态在令牌有效期内被数据库修改
- **THEN** 下一次请求 MUST 使用修改后的数据库值
- **AND** 系统 MUST NOT 继续信任签发时的角色或班级快照

### Requirement: 班级数据隔离
班级 MUST 是服务端强制执行的数据边界。所有班级数据读写 MUST 使用有效令牌所标识用户的数据库当前 `classId`；客户端在令牌 claims、请求头、URL、查询参数、表单或 JSON 中提供的班级标识不得扩大访问范围。

#### Scenario: 材料列表只返回本班数据
- **WHEN** 班级 A 用户携带有效令牌请求材料、检索或问答数据
- **THEN** 所有数据库查询和外部索引过滤 MUST 使用该用户数据库当前所属的班级 A
- **AND** 响应 MUST NOT 包含班级 B 的材料、正文、切片、文件路径或标识

#### Scenario: 跨班按 ID 访问被隐藏
- **WHEN** 班级 A 用户携带有效令牌使用班级 B 资源 ID 请求详情、文件或写操作
- **THEN** 系统 MUST 返回 HTTP 404
- **AND** 响应 MUST NOT 泄露目标资源是否存在或其任何字段

#### Scenario: 客户端伪造班级标识
- **WHEN** 客户端修改 JWT payload、伪造签名或额外提交与数据库不同的用户、角色或 `class_id`
- **THEN** 无效签名 MUST 返回 HTTP 401，有效令牌请求 MUST 忽略客户端业务身份字段
- **AND** 所有读取与写入 MUST 仍限制在数据库中该用户的当前班级和角色

### Requirement: 密码与敏感配置安全
系统 MUST 使用安全单向密码哈希存储和验证密码；数据库凭据、演示种子口令与 JWT 签名密钥 MUST 仅通过服务端环境变量注入，不得进入浏览器、日志或版本库。

#### Scenario: 数据库中不存在明文密码
- **WHEN** 操作者直接检查预置用户的密码字段
- **THEN** 字段值 MUST NOT 等于任何预置账号的明文密码
- **AND** 字段值 MUST 是密码哈希库生成的可验证格式

#### Scenario: 登录使用哈希验证
- **WHEN** 用户分别使用正确密码和错误密码尝试登录
- **THEN** 正确密码 MUST 通过 BCrypt 验证并签发访问令牌
- **AND** 错误密码 MUST 验证失败且不得签发任何令牌

#### Scenario: 缺少必需的敏感配置
- **WHEN** 应用启动时缺少数据库凭据、启用种子但缺少种子口令、未提供 JWT 签名密钥或密钥少于 32 字节
- **THEN** 应用 MUST 明确启动失败
- **AND** 应用 MUST NOT 使用硬编码、随机临时或不安全默认签名密钥

#### Scenario: 令牌和密钥不泄露
- **WHEN** 用户查看 API 响应、前端构建产物或常规日志
- **THEN** 除登录成功响应中的访问令牌外，内容 MUST NOT 包含完整 JWT、Authorization Header 或签名密钥
- **AND** JWT payload MUST NOT 包含密码、角色、班级或业务正文
