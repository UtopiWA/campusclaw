# Spec Delta

## Purpose

为 CampusClaw 增加按登录班级隔离、能够从每条命中回到原材料的知识检索与受限问答能力，并确保系统只在存在可靠资料依据时生成回答，在无依据或外部依赖失败时给出明确且可验证的结果。

## ADDED Requirements

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
- **THEN** 系统 MUST 按标题章节生成切片并在对应切片中保留标题
- **AND** 超过自动窗口上限的章节 MUST 再按自动窗口规则切分

#### Scenario: 预处理不改写原文
- **WHEN** 自定义切分启用删除 URL/邮箱或合并连续空白的预处理
- **THEN** 预处理 MUST 只影响送去切分和嵌入的文本
- **AND** 原文件与已持久化知识正文 MUST 保持不变，切片偏移 MUST 明确相对于预处理后的文本

### Requirement: 索引建立与重建生命周期
系统 MUST 在材料及知识条目成功持久化后建立检索索引，MUST 为历史和种子材料幂等补索引，并允许教师按本次指定策略重建本班材料索引。

#### Scenario: 新上传材料建立索引
- **WHEN** 教师成功上传合法材料且材料与知识条目已经提交
- **THEN** 系统 MUST 为正文生成切片、嵌入并建立可检索索引
- **AND** 索引中的材料、知识条目和班级关联 MUST 与已提交数据一致

#### Scenario: 启动时补齐缺失索引
- **WHEN** 应用发现已有材料或种子知识条目没有可用切片且外部索引依赖可用
- **THEN** 系统 MUST 使用默认 `auto` 策略幂等建立索引
- **AND** 重复执行补索引 MUST NOT 产生重复的切片或向量

#### Scenario: 教师重建本班材料索引
- **WHEN** 教师为本班材料提交合法的重建请求
- **THEN** 系统 MUST 删除该材料的旧切片和旧向量，再按本次策略创建新索引
- **AND** 完成后不得保留旧切片主键对应的可检索向量

#### Scenario: 学生不能重建索引
- **WHEN** 学生调用任何材料索引重建接口
- **THEN** 系统 MUST 返回 HTTP 403
- **AND** 现有切片、向量、材料及知识正文 MUST 保持不变

#### Scenario: 跨班重建被隐藏
- **WHEN** 教师使用其他班级材料标识请求重建索引
- **THEN** 系统 MUST 返回 HTTP 404
- **AND** 响应 MUST NOT 泄露目标材料是否存在，目标材料索引 MUST 保持不变

### Requirement: 正文与向量可验证关联
系统 MUST 以 MySQL 中的切片正文作为检索摘录的唯一真相源，并在 Qdrant 中只保存定长向量和定位所需编号；向量点、MySQL 切片和向量 payload MUST 使用同一个切片标识关联。

#### Scenario: 双存储记录正确关联
- **WHEN** 一条切片完成嵌入和向量写入
- **THEN** Qdrant point ID、MySQL 切片 ID 和 payload `chunk_id` MUST 相同
- **AND** payload MUST 包含正确的 `class_id`、`material_id`、`knowledge_entry_id` 和 `chunk_index`，MUST NOT 包含切片正文

#### Scenario: 嵌入失败保留已上传材料
- **WHEN** 材料和知识条目已提交，但切片嵌入或向量写入失败
- **THEN** 原材料和知识正文 MUST 保留，对应索引 MUST 标记为失败且 MUST NOT 参与任何检索
- **AND** Qdrant MUST NOT 留下该次失败产生的半成品向量，教师 MUST 能稍后重建索引

#### Scenario: 删除材料清理检索数据
- **WHEN** 教师成功删除本班材料
- **THEN** 该材料的切片 MUST 不再从 MySQL 返回，关联向量 MUST 被删除或进入可重试清理流程
- **AND** 即使向量清理尚在重试，任何残留点也 MUST 因回表找不到本班切片而不能出现在检索结果中

### Requirement: 三种检索模式与排序
系统 MUST 允许已登录教师和学生使用 `keyword`、`vector` 或 `hybrid` 模式检索本班可用切片；未指定模式时 MUST 使用 `hybrid`。

#### Scenario: 关键字检索原词
- **WHEN** 用户以 `keyword` 模式查询正文中存在的词语
- **THEN** 系统 MUST 只使用 MySQL 全文索引返回本班就绪切片并按全文相关度降序排列
- **AND** 该请求 MUST NOT 调用嵌入网关或 Qdrant

#### Scenario: 向量检索同义表达
- **WHEN** 用户以 `vector` 模式提交与正文含义相近但用词不同的查询
- **THEN** 系统 MUST 嵌入问句并按本班条件查询向量索引，再回 MySQL 读取切片正文
- **AND** 余弦相似度低于 0.35 的候选 MUST 被过滤，其余候选 MUST 按余弦相似度降序排列

#### Scenario: 混合检索融合名次
- **WHEN** 用户以 `hybrid` 模式提交查询
- **THEN** 系统 MUST 分别执行关键字和向量检索并先应用各自绝对过滤条件
- **AND** 系统 MUST 使用 `k = 60` 的 Reciprocal Rank Fusion 合并名次，不得直接相加全文分与余弦分，某一路缺席时不得为该路计分

#### Scenario: 查询内容为空
- **WHEN** 用户提交空字符串、全空白查询或超过配置上限的查询
- **THEN** 系统 MUST 返回 HTTP 400
- **AND** 系统 MUST NOT 调用嵌入、向量或对话网关

#### Scenario: 所选模式无可靠候选
- **WHEN** 所选检索模式应用班级、状态和相关度过滤后没有候选
- **THEN** 系统 MUST 返回 HTTP 200、空 `hits` 和“资料中未找到相关内容”
- **AND** 系统 MUST NOT 用低于阈值或其他班级的切片填充结果

### Requirement: 可追溯检索结果
每条检索命中 MUST 能够定位到当前班级的一份材料和一个 MySQL 切片，并提供足以解释排序和打开原材料的信息，但 MUST NOT 暴露服务端路径、原始向量或其他班级数据。

#### Scenario: 命中返回出处与摘录
- **WHEN** 检索返回一条命中
- **THEN** 结果 MUST 包含材料标识、材料标题、知识条目标识、切片标识、切片序号、起止偏移、MySQL 正文摘录和最终名次
- **AND** 结果 MUST 提供适用于所选模式的关键字分数/名次、向量分数/名次或 RRF 分数，使用户能够回到原材料核对

#### Scenario: 响应不泄露内部数据
- **WHEN** 用户获得检索或问答响应
- **THEN** 响应 MUST NOT 包含文件存储路径、Qdrant 内部地址、模型密钥、完整查询向量或其他班级候选

### Requirement: 检索班级隔离与认证
检索和问答 MUST 使用认证会话中的 `class_id` 作为唯一班级来源，并在关键字查询、向量查询和向量回表三个边界执行服务端过滤。

#### Scenario: 未登录调用检索或问答
- **WHEN** 未携带有效会话的客户端调用检索、重建或问答接口
- **THEN** 系统 MUST 返回 HTTP 401
- **AND** 响应 MUST NOT 包含切片、材料标题、引用或外部服务信息

#### Scenario: 客户端伪造班级编号
- **WHEN** A 班用户在查询参数、请求体或请求头中提交 B 班 `class_id`
- **THEN** 系统 MUST 忽略该值并继续使用会话中的 A 班标识
- **AND** 关键字检索、Qdrant 过滤和 MySQL 回表 MUST 全部限定为 A 班

#### Scenario: 查询只存在于其他班的内容
- **WHEN** A 班用户检索一个仅存在于 B 班就绪切片中的词或语义
- **THEN** 系统 MUST 返回 HTTP 200 和空 `hits`，问答 MUST 返回固定无依据句子和空引用
- **AND** 响应 MUST NOT 通过 403、404、标题、分数或摘录泄露 B 班资料存在

### Requirement: 外部依赖故障语义
系统 MUST 区分不依赖向量服务的关键字检索与依赖嵌入/Qdrant 的向量能力，并在依赖故障时返回真实状态而不是伪造结果或分数。

#### Scenario: Qdrant 不可用时关键字检索降级可用
- **WHEN** Qdrant 不可用且用户执行 `keyword` 检索
- **THEN** 系统 MUST 继续从 MySQL 返回符合条件的关键字结果
- **AND** 响应 MUST NOT 声称执行了向量检索

#### Scenario: 向量依赖不可用
- **WHEN** 嵌入网关或 Qdrant 不可用且用户执行 `vector` 或 `hybrid` 检索
- **THEN** 系统 MUST 返回 HTTP 503 和不包含内部凭据的明确错误
- **AND** 系统 MUST NOT 把关键字结果伪装成完整向量或混合结果

### Requirement: 有依据的简短问答
系统 MUST 通过 `POST /api/ask` 先对当前问题执行本班混合检索并最多选择前 4 条可靠切片，只有存在切片时才允许服务端对话网关生成简短回答。

#### Scenario: 有依据时生成带引用回答
- **WHEN** 当前问题的混合检索返回至少一条可靠切片且对话网关可用
- **THEN** 系统 MUST 只把材料标题、切片编号、正文和用户问题作为资料上下文交给模型
- **AND** 系统 MUST 将 `【1】`、`［1］`、`（1）` 等明确编号格式规范化为 `[1]`，并移除超出候选范围的编号
- **AND** 回答中的 `[1]`、`[2]` 等编号 MUST 与响应 `citations` 的顺序一致，每条引用 MUST 包含可直接展示的材料标题、切片位置和文本摘录

#### Scenario: 模型遗漏引用编号
- **WHEN** 对话网关在已有可靠切片时返回不含任何有效引用编号的回答
- **THEN** 系统 MUST 最多进行一次仅用于补充有效编号且不得增加事实的修订请求
- **AND** 若修订后仍无有效编号，系统 MUST 丢弃不可核验的模型回答，返回明确说明和本次检索到的依据片段，MUST NOT 把无引用回答作为正常答案返回

#### Scenario: 无依据时不调用模型
- **WHEN** 当前问题的混合检索没有可靠切片
- **THEN** 系统 MUST 返回 HTTP 200、“资料中未找到相关内容”和空 `citations`
- **AND** 系统 MUST NOT 调用对话网关或使用模型训练记忆补充回答

#### Scenario: 客户端系统消息不生效
- **WHEN** 客户端在问答请求中提交 `system` 消息、伪造资料或班级编号
- **THEN** 系统 MUST 丢弃这些客户端控制内容，系统提示和资料上下文 MUST 由服务端根据当前会话与检索结果构造
- **AND** 响应 MUST NOT 引用客户端伪造资料或其他班级内容

#### Scenario: 有依据但对话网关不可用
- **WHEN** 混合检索已有可靠切片但对话网关调用失败
- **THEN** 系统 MUST 返回 HTTP 503 和明确错误
- **AND** 系统 MUST NOT 返回无引用的自由生成回答或把失败误报为无资料

### Requirement: 一致的检索界面
系统 MUST 在现有已登录应用壳中为教师和学生提供响应式知识检索页面，支持选择检索模式、提交问题、查看命中及引用、识别空结果和故障，并跳转到可访问的原材料。

#### Scenario: 教师或学生使用检索页面
- **WHEN** 已登录教师或学生进入知识检索页面并提交查询
- **THEN** 页面 MUST 展示当前班级、所选模式、加载状态和检索结果
- **AND** 每条结果 MUST 展示材料标题、切片信息、摘录和材料入口，视觉样式 MUST 复用现有侧边栏、顶栏、卡片、按钮和反馈组件

#### Scenario: 页面直接展示问答依据
- **WHEN** 问答响应包含一条或多条 `citations`
- **THEN** 页面 MUST 在回答下方直接展示每条引用的编号、材料标题、切片位置和文本摘录
- **AND** 用户 MUST 无需跳转材料页即可核对引用片段，材料入口 MAY 作为进一步查看全文的可选操作

#### Scenario: 页面区分空结果与服务故障
- **WHEN** API 分别返回无命中和 HTTP 503
- **THEN** 页面 MUST 对无命中展示固定的资料不足说明，对依赖故障展示可重试的错误状态
- **AND** 页面 MUST NOT 把两种情况都显示为一条伪造命中或普通回答

#### Scenario: 教师和学生看到不同管理操作
- **WHEN** 教师查看本班材料的索引状态
- **THEN** 页面 MUST 允许教师发起重建并展示进行中、就绪或失败状态
- **AND** 学生界面 MUST 不提供重建操作，且服务端仍 MUST 对学生重建请求返回 403

#### Scenario: 窄屏使用检索页面
- **WHEN** 视口不足以横向展示搜索控件和命中元数据
- **THEN** 页面 MUST 重排控件和结果而不遮挡查询、模式选择、摘录和材料入口
- **AND** 图标操作 MUST 保留可访问名称或提示

### Requirement: 检索配置与持久化
系统 MUST 通过服务端环境变量配置 Qdrant、嵌入网关、对话网关、模型和向量维度，MUST 持久化向量数据，并在启动时验证向量维度与 collection 一致；秘密不得进入浏览器或版本库。

#### Scenario: 配置完整时启动
- **WHEN** 操作者按 README 提供 Qdrant、嵌入和对话所需配置并启动 Docker Compose
- **THEN** 后端 MUST 连接或创建余弦距离 collection，且其向量维度 MUST 与嵌入模型配置一致
- **AND** Qdrant 数据 MUST 使用持久化 volume，浏览器入口 MUST 仍只暴露前端代理的业务 API

#### Scenario: 向量维度不一致
- **WHEN** 已有 collection 的向量维度与后端配置不一致
- **THEN** 后端 MUST 明确报告配置错误且 MUST NOT 写入不兼容向量
- **AND** 系统 MUST NOT 静默重建 collection 并删除已有索引

#### Scenario: 敏感配置不泄露
- **WHEN** 用户查看前端构建产物、API 响应或常规应用日志
- **THEN** 其中 MUST NOT 包含嵌入/对话网关密钥、数据库密码或其他真实秘密
- **AND** `.env.example` MUST 仅包含变量名、安全占位值和生成说明
