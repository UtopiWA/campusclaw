# Spec Delta

## Purpose

定义仓库内可独立执行的 Python 接口回归套件，使关键接口能够按 URL 定位脚本、一键运行、联合核对 HTTP 响应与 MySQL 状态，并生成不泄露凭据且不提交到 Git 的 HTML 报告。

## ADDED Requirements

### Requirement: 接口脚本按 URL 和方法组织
系统 MUST 在仓库根目录提供 `apitest/`，并为每个受测接口使用一个按 URL 层级组织、以 HTTP 方法命名的 Python 脚本；公共认证、请求、断言、数据库访问和清理能力 MAY 由共享模块复用。

#### Scenario: 从接口定位测试脚本
- **WHEN** 开发者需要查找 `POST /api/auth/login` 或 `POST /api/conversations/{id}/messages` 的接口测试
- **THEN** 仓库 MUST 分别提供形如 `apitest/cases/api/auth/login/POST.py` 和 `apitest/cases/api/conversations/_id/messages/POST.py` 的独立脚本
- **AND** 动态路径脚本 MUST 从运行上下文取得实际资源标识，不得依赖写死的数据库自增 ID

#### Scenario: 接口脚本复用公共能力
- **WHEN** 多个接口需要 Bearer Token、MySQL 查询、唯一测试数据或结果记录
- **THEN** 脚本 MUST 使用 `apitest/` 内的公共模块复用这些能力
- **AND** 每个接口脚本 MUST 仍能明确标识其对应的方法、路径和接口级断言

### Requirement: 一键执行与 HTML 报告
系统 MUST 提供 `apitest/run_all.py` 一键执行入口，按稳定顺序运行接口脚本，并为本次运行生成汇总 HTML 报告。

#### Scenario: 运行全部接口测试
- **WHEN** 开发者在已启动的本地 Compose 环境执行 `python apitest/run_all.py`
- **THEN** 运行器 MUST 完成环境检查、登录、依赖数据准备、全部用例执行和仅针对本次测试数据的清理
- **AND** 进程退出码 MUST 在全部通过时为 0，在任一环境检查或用例失败时为非 0

#### Scenario: 生成可读且可忽略的报告
- **WHEN** 接口测试运行结束
- **THEN** `apitest/reports/` 下的 HTML 报告 MUST 至少列出方法、路径、通过或失败、耗时和失败断言
- **AND** 报告目录 MUST 被 Git 忽略，报告与控制台输出 MUST NOT 包含完整 Bearer Token、数据库密码、模型密钥或上游响应正文

### Requirement: 联合验证响应与持久化状态
接口测试 MUST 不只断言 HTTP 状态码，还必须核对关键响应字段；对会写入数据库的接口，测试 MUST 使用只读查询确认 MySQL 中的归属、字段和状态与响应一致。

#### Scenario: 配置接口返回成功
- **WHEN** 教师接口成功创建或修改技能、角色提示词或智能体绑定
- **THEN** 对应用例 MUST 断言响应中的标识、配置字段和绑定结果
- **AND** 用例 MUST 查询数据库确认记录属于登录教师所在班级且持久化字段与响应一致

#### Scenario: 会话流式消息完成
- **WHEN** 学生创建会话并成功发送一条流式消息
- **THEN** 用例 MUST 验证 SSE 正常顺序为 `meta`、零到多条 `delta`、`done`，且拼接后的增量正文与响应语义一致
- **AND** 用例 MUST 查询数据库确认会话属于当前用户和班级，用户消息与 `COMPLETED` 助手消息均已保存，最终正文、技能和引用与流式结果一致

### Requirement: 课程要求的最小接口覆盖
接口套件 MUST 至少覆盖登录、本班检索、提示词与技能配置、创建会话以及发送一条流式消息，并使用登录接口签发的 Bearer Token 调用受保护接口。

#### Scenario: 执行最小回归集合
- **WHEN** `run_all.py` 在具备教师、学生和 READY 测试材料的环境中运行
- **THEN** 套件 MUST 覆盖 `POST /api/auth/login`、`POST /api/retrieval/search`、智能体提示词与技能配置接口、`POST /api/conversations` 和 `POST /api/conversations/{id}/messages`
- **AND** 检索 MUST 断言结果属于登录用户当前班级，配置 MUST 验证教师权限，会话 MUST 验证学生归属与 Bearer 认证

#### Scenario: 本机配置和秘密管理
- **WHEN** 接口套件需要服务地址、测试账号、数据库连接或模型配置
- **THEN** 这些值 MUST 从本机环境或未提交的 `.env` 读取，并在缺失时以脱敏错误快速失败
- **AND** 仓库中的脚本、示例、报告和文档 MUST NOT 硬编码真实凭据
