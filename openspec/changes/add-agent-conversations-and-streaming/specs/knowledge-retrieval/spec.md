## MODIFIED Requirements

### Requirement: 材料正文切分
系统 MUST 从已持久化的知识正文生成独立检索切片，MUST 支持 `auto`、`custom` 和 `hierarchy` 三种策略，并且 MUST NOT 因切分或预处理修改原文件及知识正文。

#### Scenario: 默认自动切分
- **WHEN** 新材料或待补索引材料未指定切分策略
- **THEN** 系统 MUST 使用 `auto` 策略，以最多 800 个 Unicode 字符和 80 个字符重叠生成非空切片
- **AND** 系统 MUST 优先在空行、换行或句末边界断开

#### Scenario: 自定义切分参数
- **WHEN** 教师选择 `custom` 策略并提供 100 至 2000 的最大长度、0% 至 50% 的重叠比例及受支持的断点标识
- **THEN** 系统 MUST 按本次参数生成切片，无可用断点时 MUST 在最大长度处切开
- **AND** 超出范围、缺少必需标识或会导致空切片的参数 MUST 返回 HTTP 400 且 MUST NOT 替换现有索引

#### Scenario: 按 Markdown 标题切分
- **WHEN** 教师对包含 `#`、`##` 或 `###` 标题的正文选择 `hierarchy` 策略
- **THEN** 系统 MUST 按材料内知识条目的原顺序维护标题层级，并把当前标题路径附加到对应正文切片
- **AND** 超过自动窗口上限的章节 MUST 在计入标题前缀后再按自动窗口规则切分，最终切片不得超过 800 个 Unicode code points

#### Scenario: 连续标题后才出现正文
- **WHEN** `#` 标题后紧跟 `##` 或 `###` 标题，标题与正文因空行解析为不同知识条目
- **THEN** 系统 MUST 更新标题层级但 MUST NOT 为任一纯标题建立独立检索切片
- **AND** 后续正文切片 MUST 携带适用的连续标题路径，切片的来源条目和 offset 仍定位实际正文

#### Scenario: 标题后没有有效正文
- **WHEN** 一个标题区段直到下一标题或材料结尾只包含空白、Markdown 标题、分隔线或不含字母与数字的排版符号
- **THEN** 系统 MUST NOT 为该区段建立低信息切片

#### Scenario: 预处理不改写原文
- **WHEN** 自定义切分启用删除 URL/邮箱或合并连续空白的预处理
- **THEN** 预处理 MUST 只影响送去切分和嵌入的文本
- **AND** 原文件与已持久化知识正文 MUST 保持不变，切片偏移 MUST 明确相对于预处理后的文本

#### Scenario: 其他策略保持兼容
- **WHEN** 教师使用 `auto` 或 `custom` 策略重建索引
- **THEN** 系统 MUST 保持既有窗口、重叠、预处理和 offset 行为，不应用 HIERARCHY 标题上下文规则
