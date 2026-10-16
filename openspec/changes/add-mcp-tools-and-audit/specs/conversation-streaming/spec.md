# conversation-streaming Specification Delta

## MODIFIED Requirements

### Requirement: 智能体限定材料检索与连续引用
系统 MUST 在当前班级内只检索会话智能体当前绑定的 READY 材料，使用既有混合检索取得至多 4 条可靠切片，并在全部过滤完成后从 1 连续建立本轮引用；材料检索为空 MUST NOT 抹除同轮安全可用的 MCP 工具结果。

#### Scenario: 只命中绑定材料
- **WHEN** 本班绑定和未绑定材料都包含与问题相关的 READY 切片
- **THEN** 本轮检索和引用 MUST 只包含智能体绑定材料
- **AND** 未绑定材料即使相关度更高也 MUST NOT 出现在模型上下文、SSE 或消息历史中

#### Scenario: 技能限制资料范围
- **WHEN** 被选技能具有非空资料范围
- **THEN** 系统 MUST 在绑定材料候选中只保留标题包含该范围文本的材料
- **AND** 空正文和不匹配材料 MUST 在编号前移除

#### Scenario: 连续编号和正文长度
- **WHEN** 过滤后存在一条或多条可靠切片
- **THEN** 系统 MUST 按最终顺序从 `[1]` 连续编号，每条送入模型的正文最多 800 个 Unicode 字符
- **AND** 引用 MUST 包含编号、材料标识、标题、切片标识、切片序号和可直接展示的摘录，编号 MUST 与数组位置一致

#### Scenario: 没有绑定材料或没有可靠切片
- **WHEN** 智能体未绑定材料或过滤后没有可靠切片，但所选技能的 MCP 调用产生至少一项安全可用结果
- **THEN** 系统 MUST 保持 `citations=[]`，并 MAY 使用工具结果调用对话模型
- **AND** 系统 MUST NOT 回退到本班全部材料、其他班级材料或低于既有阈值的候选

#### Scenario: 材料和工具均无可用依据
- **WHEN** 智能体未绑定材料或过滤后没有可靠切片，且本轮没有安全可用工具结果
- **THEN** 系统 MUST NOT 调用对话模型，并 MUST 以流式协议返回固定无依据答复和空引用
- **AND** 工具失败或拒绝摘要 MAY 显示，但 MUST NOT 被当作生成依据

### Requirement: 可信消息组装与无依据回答
系统 MUST 由服务端构造本轮系统消息、材料数据和工具数据，将智能体角色、至多一个技能及固定安全边界放入系统消息，将学生问题、历史、编号材料及安全工具结果作为用户/助手上下文，并把上传内容和外部工具输出视为数据而不是可执行指令。

#### Scenario: 有依据时组装消息
- **WHEN** 本轮检索返回可靠切片，且 MCP 调用产生安全可用结果
- **THEN** 系统消息 MUST 要求优先依据可信数据并仅对材料使用有效 `[n]`，用户消息 MUST 分区包含当前问题、编号材料和带来源标签的工具结果
- **AND** 流式生成期间的有效编号 MUST 指向本轮 `meta` 候选；完成后的最终编号 MUST 指向持久化消息中经过滤与重编号的同序材料引用

#### Scenario: 只有材料依据
- **WHEN** 本轮存在可靠切片，但没有配置 MCP 工具或所有工具结果均失败、拒绝或被安全过滤
- **THEN** 系统 MUST 仅使用编号材料调用对话模型
- **AND** 工具失败 MUST NOT 移除材料、终止主回答或扩大检索范围

#### Scenario: 只有工具依据
- **WHEN** 本轮材料引用为空，但至少存在一项安全可用的 MCP 工具结果
- **THEN** 系统 MUST 使用工具数据调用对话模型，并保持 `citations=[]`
- **AND** 服务端 MUST 移除正文中的伪造 `[n]` 材料编号，工具来源 MUST 只通过安全的 `toolCalls` 摘要表达

#### Scenario: 无依据时固定回答
- **WHEN** 本轮材料引用为空且没有安全可用工具结果
- **THEN** 系统 MUST NOT 调用流式对话网关，并 MUST 返回“资料中未找到相关内容”
- **AND** 对外正文 MUST 不含 `[n]` 形式编号，材料引用 MUST 为空且不得编造工具结果或出处

#### Scenario: 有依据但模型遗漏引用编号
- **WHEN** 本轮存在可靠材料候选，但模型完成正文没有任何有效 `[n]`
- **THEN** 服务端 MUST 只在本轮材料候选内按回答与摘录的文本重合度选择最匹配的一条，同分时选择检索排名更前者
- **AND** 服务端 MUST 在最终正文末尾补 `[1]` 并只持久化该条引用，不得展示全部候选或跨出智能体绑定范围

#### Scenario: 上传内容尝试改变系统行为
- **WHEN** 技能说明、材料标题、材料正文或工具结果包含忽略规则、泄露信息、执行脚本或调用其他外部工具等指令
- **THEN** 系统 MUST 将这些内容放在不可信数据边界内，不得据此扩大权限、改变 system prompt 或触发额外工具
- **AND** 前端 MUST 以不可执行文本展示模型正文、引用和工具摘要

#### Scenario: 客户端提交控制字段
- **WHEN** 客户端在提问中提交系统消息、角色、班级、用户、智能体覆盖值、技能、材料范围或工具调用参数
- **THEN** 系统 MUST 忽略或拒绝这些控制字段，并只使用服务端读取的会话、配置与参数适配器

#### Scenario: 既有依据问答保持原语义
- **WHEN** 用户调用既有 `POST /api/ask` 且本班混合检索没有可靠切片
- **THEN** 系统 MUST 继续返回固定无依据文案和空引用，且 MUST NOT 调用对话网关或 MCP 工具
- **AND** 新流式会话的工具生成行为 MUST NOT 改变该接口

## ADDED Requirements

### Requirement: 工具调用元数据与消息快照
系统 MUST 将本轮工具调用的安全摘要作为可选 camelCase `toolCalls` 写入 SSE `meta` 和最终助手消息快照，使实时页面与会话重载得到一致结果。

#### Scenario: 在正文前发送工具调用状态
- **WHEN** 所选技能包含一个或多个 MCP 引用并完成本轮调用
- **THEN** 第一条 SSE `meta` MUST 在任何 `delta` 前包含按配置顺序排列的 `toolCalls`
- **AND** 每项 MUST 只包含工具引用、`ok|denied|error` 状态和安全摘要，不得包含 Token、Base URL、完整参数、原始响应或异常栈

#### Scenario: 未配置 MCP 工具时保持兼容
- **WHEN** 所选技能没有 MCP 引用，或没有选中技能
- **THEN** SSE `meta` MUST 省略 `toolCalls` 字段
- **AND** 既有 `status`、`skillName`、`citations`、`delta` 和 `done` 语义 MUST 保持不变

#### Scenario: 完成后重新加载工具摘要
- **WHEN** 流式回答正常完成并持久化助手消息，随后前端在 `done` 后或页面刷新时重新读取会话详情
- **THEN** 消息详情 MUST 返回与本轮 SSE `meta` 等价的安全 `toolCalls` 快照
- **AND** 页面 MUST 在对应助手正文之前恢复工具名称、来源和状态，不得依赖浏览器内存

#### Scenario: 流开始后失败仍保存已发生调用
- **WHEN** `meta` 已发送且后续模型流或消息持久化发生可恢复失败
- **THEN** 系统 MUST 按既有失败消息规则尽力保存已发生的安全工具摘要和失败状态
- **AND** 会话详情 MUST NOT 把该轮工具调用显示为正常完成的回答，也不得保存原始工具输出

#### Scenario: 前端展示工具失败而继续回答
- **WHEN** `toolCalls` 包含 `denied` 或 `error`，但材料或其他工具仍足以生成正文
- **THEN** 页面 MUST 在第一段正文前展示失败状态，并继续逐段渲染主回答
- **AND** 页面 MUST NOT 将局部工具失败升级为整轮失败或向用户显示内部错误细节

### Requirement: MCP 管理与技能选择界面
教师配置界面 MUST 在现有 `/assistants` 页面提供 MCP 服务、发现工具和审计管理，并让技能编辑器只选择当前班级已连接服务的已发现工具；学生 MUST 看不到管理入口。

#### Scenario: 教师完成服务连接和技能绑定
- **WHEN** 教师在配置页新增 MCP 服务、提交 Token、连接成功并编辑技能
- **THEN** 页面 MUST 展示连接状态、已发现工具以及 `工具名@服务名` 复选项
- **AND** Token 在提交后 MUST 被清空且不可回显，技能保存 MUST 使用服务端定义的文本格式

#### Scenario: 教师查看工具和审计
- **WHEN** 教师选择本班 MCP 服务或打开审计列表
- **THEN** 页面 MUST 展示安全的工具说明、输入 Schema 摘要和最近调用状态
- **AND** 页面 MUST NOT 展示 Token、内部错误栈、完整调用参数或原始工具结果

#### Scenario: 有审计的服务删除失败
- **WHEN** 教师在页面删除已有调用审计的 MCP 服务且后端返回 HTTP 409
- **THEN** 页面 MUST 保留服务并展示建议断开的可理解提示
- **AND** 页面 MUST NOT 乐观移除服务、工具或审计

#### Scenario: 学生直接访问配置路由
- **WHEN** 学生直接访问 `/assistants` 或尝试渲染 MCP 管理组件
- **THEN** 前端 MUST 沿用既有教师路由保护并隐藏全部管理控件
- **AND** 后端管理接口仍 MUST 独立返回 HTTP 403

### Requirement: MCP OpenAPI 契约
系统 MUST 在现有 Springdoc 文档中声明 MCP 服务管理、连接、工具、审计接口及 SSE `toolCalls` Schema，并使文档的认证、错误码和响应字段与实际行为一致。

#### Scenario: 查看 MCP 接口文档
- **WHEN** 开发者打开 `/swagger-ui/index.html` 或读取 `/v3/api-docs`
- **THEN** 文档 MUST 列出 MCP 管理接口、Bearer 认证、教师角色限制、请求响应 DTO 和 `400|401|403|404|409|502|503` 语义
- **AND** 文档示例 MUST NOT 包含真实 Token、内部服务地址或调用结果

#### Scenario: 查看流式工具元数据结构
- **WHEN** 开发者在 OpenAPI 中查看发送会话消息的 SSE 契约
- **THEN** 文档 MUST 描述 `meta.toolCalls` 为可选有序数组及其安全字段
- **AND** 文档 MUST 说明没有 MCP 引用时该字段省略，材料引用与工具摘要使用不同字段
