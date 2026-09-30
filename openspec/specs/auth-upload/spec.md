# auth-upload Specification

## Purpose
为 CampusClaw 建立可验证的身份、角色和班级数据边界，使教师能够安全地上传本班教学材料并写入知识库，同时让同班师生查看与下载原文件、学生保持只读，为后续检索和智能助手能力提供可信的数据基础。

## Requirements

### Requirement: 用户登录与令牌认证
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

### Requirement: 教学材料上传与知识库入库
教师上传受支持的教学材料后，系统 MUST 安全保存文件、解析文本，并以一致的班级归属写入材料记录和知识条目。成功上传后，本班材料列表 MUST 从数据库读取并展示新记录。

#### Scenario: 教师上传有效文本材料
- **WHEN** 教师上传非空的 UTF-8 `.txt` 或 `.md` 文件且解析成功
- **THEN** 系统 MUST 安全保存文件并创建一条材料记录
- **AND** 系统 MUST 创建至少一条与该材料关联的知识条目
- **AND** 材料和知识条目的 `class_id` MUST 等于令牌用户在数据库中的当前班级
- **AND** 成功响应 MUST 返回 HTTP 201 和新材料标识

#### Scenario: 上传后本班列表可见
- **WHEN** 教师成功上传材料后，本班教师或学生重新请求材料列表
- **THEN** 列表 MUST 显示该材料的标题和可用元数据
- **AND** 另一班级用户的材料列表 MUST NOT 显示该材料

### Requirement: 教学材料在线查看与下载
系统 MUST 允许同班教师和学生查看及下载具有物理原文件的材料。系统 MUST 在读取文件前执行 Bearer Token 认证、班级限定查询和存储路径边界检查，并且 MUST NOT 向客户端暴露服务端存储路径。

#### Scenario: 同班用户在线查看文本材料
- **WHEN** 同班教师或学生请求一份已上传 `.txt` 或 `.md` 材料的在线内容
- **THEN** 系统 MUST 返回原文件的 UTF-8 文本和 HTTP 200
- **AND** 响应 MUST 使用不可执行的 `text/plain` 内容类型和 `inline` 文件处置
- **AND** Markdown 内容 MUST 作为源文本展示而不是作为 HTML 执行

#### Scenario: 同班用户下载原文件
- **WHEN** 同班教师或学生请求下载一份具有物理原文件的材料
- **THEN** 系统 MUST 返回与上传内容字节一致的文件和 HTTP 200
- **AND** 响应 MUST 使用 `attachment` 文件处置和经过安全编码的原文件名
- **AND** 下载操作 MUST NOT 修改材料、知识条目或文件

#### Scenario: 跨班查看或下载被隐藏
- **WHEN** 班级 A 用户使用班级 B 材料的 ID 请求在线内容或下载
- **THEN** 系统 MUST 返回 HTTP 404
- **AND** 响应 MUST NOT 泄露目标材料是否存在、文件名、文件内容或存储路径

#### Scenario: 材料没有可用原文件
- **WHEN** 用户查看材料列表中的演示知识条目，或请求一个没有物理原文件的材料
- **THEN** 列表响应 MUST 通过 `hasFile=false` 明确表示文件操作不可用
- **AND** 查看或下载接口 MUST 返回 HTTP 404
- **AND** 前端 MUST 禁用或隐藏对应文件操作并给出可理解的说明

#### Scenario: 材料元数据不泄露存储位置
- **WHEN** 已登录用户请求材料列表或单条材料元数据
- **THEN** 响应 MAY 包含 `hasFile`、原文件名和文件字节数
- **AND** 响应 MUST NOT 包含绝对路径、相对 `stored_path` 或后端生成的存储文件名

#### Scenario: 不支持或无效的文件被拒绝
- **WHEN** 教师上传非 `.txt`/`.md` 文件、空文件、无法按 UTF-8 解码的文件或超过配置上限的文件
- **THEN** 系统 MUST 返回明确的 HTTP 4xx 错误
- **AND** 材料表、知识条目表和上传目录 MUST NOT 留下该请求产生的记录或文件

#### Scenario: 解析或入库失败时回滚
- **WHEN** 文件保存后的解析、材料写入或知识条目写入任一步骤失败
- **THEN** 系统 MUST 回滚该次上传产生的所有数据库变更
- **AND** 系统 MUST 删除临时文件和未关联的最终文件
- **AND** 本班材料列表 MUST NOT 出现不完整记录

### Requirement: 可验收的核心数据与种子
系统 MUST 建立班级、用户、材料、知识条目、作业、助手和技能等核心数据结构，并提供能够验证登录、角色权限和跨班隔离的种子数据。

#### Scenario: 初始化双班级和用户
- **WHEN** 在空数据库上执行初始化或种子流程
- **THEN** 系统 MUST 创建班级 A 和班级 B
- **AND** 系统 MUST 创建教师 A、学生 A1 和学生 B1
- **AND** 教师 A 与学生 A1 MUST 属于班级 A，学生 B1 MUST 属于班级 B

#### Scenario: 初始化可区分的班级材料
- **WHEN** 种子流程完成
- **THEN** 班级 A 和班级 B MUST 各有至少一条标题可区分的材料
- **AND** 材料和知识条目 MUST 具有正确的班级关联
- **AND** 材料、知识条目、作业、助手和技能所需的数据结构 MUST 已创建

#### Scenario: 重复执行初始化
- **WHEN** 操作者在已初始化的数据库上再次执行初始化流程
- **THEN** 流程 MUST 安全完成而不重复创建同一预置账号或班级
- **AND** 已存在的用户上传数据 MUST NOT 被删除

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

### Requirement: Docker Compose 部署与健康检查
系统 MUST 提供 Docker Compose 标准启动方式、持久化数据库与上传目录，并提供无需认证的 `GET /health` 健康检查。

#### Scenario: Compose 启动后可访问
- **WHEN** 操作者按 README 配置环境变量并执行 `docker compose up --build`
- **THEN** 应用容器 MUST 启动且健康检查通过
- **AND** 浏览器 MUST 能访问登录页

#### Scenario: 匿名健康检查成功
- **WHEN** 未携带认证令牌的客户端请求 `GET /health` 且应用可用
- **THEN** 系统 MUST 返回 HTTP 200 和 JSON 健康状态
- **AND** 请求 MUST NOT 被重定向到登录页

#### Scenario: 容器重建后数据保留
- **WHEN** 已存在种子数据和教师上传材料后，操作者执行 `docker compose down` 再执行 `docker compose up` 且未删除持久化目录或 volume
- **THEN** 预置用户 MUST 仍可登录
- **AND** 已上传材料、知识条目和文件 MUST 仍可被本班用户访问

### Requirement: 一致且可扩展的前端视觉框架
系统 MUST 为已登录页面提供可复用的应用壳和设计令牌，使当前材料页与后续教师/学生页面保持一致，并在桌面和窄屏下保持主要操作可用。

#### Scenario: 已登录用户进入材料页
- **WHEN** 教师或学生登录并进入材料页
- **THEN** 页面 MUST 展示角色对应的侧边导航、包含班级与账号信息的顶栏以及浅灰色主工作区
- **AND** 当前材料入口 MUST 使用深红主色与浅红背景形成明确选中态
- **AND** 页面主体 MUST 使用统一的卡片、按钮、反馈和模态框样式

#### Scenario: 后续页面复用视觉框架
- **WHEN** 开发者新增助手、作业、审计或学情页面
- **THEN** 页面 MUST 能复用共享应用壳与全局颜色、圆角、阴影和间距令牌
- **AND** 未实现菜单 MUST 被标记为不可用或待建设，不得表现为可执行但无结果的入口

#### Scenario: 窄屏访问已登录页面
- **WHEN** 视口宽度不足以展示完整桌面侧边栏
- **THEN** 导航 MUST 收缩或重排而不遮挡主要内容
- **AND** 仅显示图标的导航项 MUST 保留可访问名称或提示
