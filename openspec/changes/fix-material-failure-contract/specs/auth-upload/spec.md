# Spec Delta

## ADDED Requirements

### Requirement: 材料上传错误反馈
系统 MUST 在上传弹窗保持打开期间，于弹窗内部展示本次上传请求的脱敏失败信息，并允许教师在不重新进入上传流程的情况下核对或更换所选文件。

#### Scenario: 上传请求被拒绝
- **WHEN** 教师在上传弹窗提交文件且服务端返回校验或提交错误
- **THEN** 前端 MUST 保持上传弹窗打开并在弹窗内显示错误信息
- **AND** 前端 MUST 保留所选文件，允许教师直接更换文件或再次提交
- **AND** 错误信息 MUST 具有可被辅助技术识别的警报语义

#### Scenario: 更换文件或退出上传流程
- **WHEN** 教师在一次上传失败后选择新文件、成功上传或关闭上传弹窗
- **THEN** 前端 MUST 清除该次上传错误，防止过期信息影响下一次操作

### Requirement: 教学材料文件访问
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

## REMOVED Requirements

### Requirement: 教学材料在线查看与下载

**Reason**: 该 Requirement 混入了旧同步上传流程的非法 UTF-8 请求内拒绝和解析失败整体回滚场景，与当前异步上传 Requirement 冲突；文件访问职责迁移到新的“教学材料文件访问”Requirement。

**Migration**: 查看、下载、跨班隐藏、无原文件和元数据脱敏行为由“教学材料文件访问”完整承接；请求内校验、主事务补偿和后台解析失败统一以“教学材料上传与知识库入库”为准，API 无需迁移。
