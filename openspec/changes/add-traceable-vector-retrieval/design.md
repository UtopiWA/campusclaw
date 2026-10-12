# Design

## Context

变更动机见 `proposal.md`，行为契约见 `specs/knowledge-retrieval/spec.md`。当前后端是 Java 17 / Spring Boot 3 单体 REST 服务：`MaterialService` 在一个 MySQL 事务中写入材料和 `knowledge_entries`，并用文件补偿保持原文件一致；`TextParser` 已按 Unicode code point 校验 UTF-8 文本并把正文拆成一个或多个知识条目。客户端使用 Bearer JWT，服务端从令牌取得用户 ID 后仍会从数据库加载最新角色和 `class_id`。前端是 Vue 3 单页应用，统一通过 `AppShell`、Vue Router 和 `apiRequest` 访问同源 API。

本次需要新增 MySQL ngram 全文索引、Qdrant、两个 OpenAI-compatible 服务端网关和问答页面，且不能让任何外部依赖故障回滚已经成功提交的材料文件与知识正文。现有 `auth-upload` 主规格继续有效；新的检索状态是材料持久化后的后置生命周期。

## Goals / Non-Goals

**Goals:**

- 在不改变现有登录、上传、查看、下载和班级隔离契约的情况下，为所有知识正文建立可重建的二级检索切片。
- 让关键字、向量和混合检索共享同一套班级边界、就绪状态和可追溯响应。
- 把正文唯一保留在 MySQL；Qdrant 只承担向量近邻检索，命中后必须带班级回表。
- 使上传、重建、删除和外部服务失败后的 MySQL/Qdrant 状态可解释、可恢复、无跨班泄露。
- 提供可用测试替身验证嵌入、Qdrant 与对话交互，无需在每个单元测试中连接真实外部网关。

**Non-Goals:**

- 不把本迭代演进为异步任务平台、通用 Agent 编排、流式聊天或生产级分布式事务系统。
- 不替换现有 Bearer JWT 认证、材料文件补偿、Vue 应用壳或 MySQL 主数据模型。
- 不在首版支持多嵌入模型并存、在线无损重建、语义分句、重排模型或全文搜索专用集群。

## Decisions

### Decision 1: 保持 Spring Boot 单体，按职责增加 retrieval 子模块

后端在现有应用内新增以下逻辑边界，包名可在实现时按既有风格微调，但职责不得混合：

```text
knowledge/   文本解码、三种切分策略、预处理与偏移
retrieval/   索引编排、三种检索、RRF、API 与结果 DTO
gateway/     Embedding、Qdrant、Chat 的服务端客户端及传输 DTO
persistence/ Material 索引状态、KnowledgeChunk、向量清理任务及 Repository
```

前端新增 `/search` 路由和检索页面，导航项对教师、学生均可用；材料页继续负责文件生命周期，教师的索引状态与重建入口可复用材料行或检索页中的管理区域。

选择理由：当前规模不需要拆微服务，单体能直接复用 `CurrentUserService`、事务和 Repository 班级查询。备选方案是增加独立检索服务，但会引入服务认证、跨服务数据一致性和额外部署边界，不适合本次课程范围。

### Decision 2: `knowledge_entries` 保留来源，新增材料级索引状态和二级切片表

新增 Flyway migration，给 `materials` 增加：

- `index_status`：`PENDING`、`INDEXING`、`READY`、`FAILED`，旧数据初始化为 `PENDING`；
- `index_strategy`：最近一次请求的 `AUTO`、`CUSTOM` 或 `HIERARCHY`；
- `index_error`：可空、限长且脱敏的失败摘要；
- `indexed_at`：最近一次成功完成时间。

为异步处理增加可持久化、可展示但不泄露内部异常的诊断字段；已存在的 V3 不原地修改，实现时用后续 Flyway migration 演进：

- `processing_stage`：`QUEUED`、`PARSING`、`INDEXING` 或 `COMPLETED`，失败时保留最后阶段；
- `failure_code`：可空的稳定枚举，例如 `INVALID_TEXT`、`DEPENDENCY_UNAVAILABLE`、`PROCESSING_ERROR`；
- `retryable`：失败后是否允许教师直接重新处理，不能用原始异常字符串代替该判断。

新增 `knowledge_chunks`：

| 字段 | 约束与用途 |
| --- | --- |
| `id` | BIGINT 自增主键，同时作为 Qdrant point ID |
| `material_id` | 外键，材料删除时级联删除 |
| `knowledge_entry_id` | 外键，指回切分来源 |
| `class_id` | 外键及所有查询的首要过滤条件 |
| `chunk_index` | 材料范围内稳定递增的展示顺序 |
| `chunk_text` | MySQL 中唯一的切片正文与摘录来源 |
| `start_offset` / `end_offset` | 相对于该知识条目预处理后文本的 Unicode code-point 半开区间 |
| `index_status` | `PENDING`、`READY` 或 `FAILED` |
| `index_error` | 可空、限长且脱敏的切片失败摘要 |
| `created_at` / `updated_at` | 审计和故障定位 |

约束包含 `(material_id, chunk_index)` 唯一键、`(class_id, index_status, material_id)` 普通索引和 `FULLTEXT(chunk_text) WITH PARSER ngram`。Compose 为 MySQL 配置 `ngram_token_size=2`，Testcontainers 使用相同参数，避免本地与测试排序基础不同。

当前一次上传可能生成多个 `knowledge_entries`。索引时按原 `chunk_index` 顺序逐条处理，每个条目独立预处理和切分，再为整个材料分配连续的 `knowledge_chunks.chunk_index`；偏移仍相对于各自 `knowledge_entry_id` 的处理文本，从而不虚构跨条目原文坐标。无需迁移或合并现有知识正文。

选择理由：直接把向量状态塞进现有 `knowledge_entries` 无法表达一个正文对应多个窗口；把正文只放 Qdrant 则会破坏关键字独立可用和数据库班级回表。使用单独二级切片可保留第一迭代数据与外键。

### Decision 3: 切分以 Unicode code point 计数，策略对象先校验后执行

统一输出 `ChunkDraft(text, sourceEntryId, chunkIndex, startOffset, endOffset)`。所有长度与偏移按 Unicode code point 而非 Java UTF-16 `char` 计算，避免拆开代理对。

- `auto`：最大 800、重叠 80；在窗口末端向前寻找空行、换行、`。！？.!?`，找不到才硬切。下一窗口从上一个末端向前 80 个 code point 开始，必须保证前进以避免死循环。
- `custom`：`maxLength` 为 100～2000，`overlapRatio` 为 0～0.5；断点枚举为 `NEWLINE`、`BLANK_LINE`、`SENTENCE`，不接收任意正则表达式。预处理选项仅为 `removeLinksAndEmails` 和 `collapseWhitespace`。
- `hierarchy`：识别行首一至三级 Markdown 标题，标题与其章节正文同属一块；超长章节复用 `auto`。没有标题时退化为 `auto`。

重叠切片的偏移允许相交。预处理后的文本及偏移不会写回 `knowledge_entries`。切分器保持纯函数，使用参数化单元测试覆盖中文、emoji、极短文本、无断点、连续标题和非法配置。

选择理由：开放自定义正则会带来 ReDoS 和不可重复切分风险；Java `char` 偏移对 emoji 不可靠。备选的语义切分需要额外模型调用，不在本次范围。

### Decision 4: 外部网关使用 Spring `RestClient` 和显式配置，不在首版引入 SDK

后端使用 Spring 自带 HTTP 客户端封装三个接口：

- `EmbeddingGateway`：OpenAI-compatible `/embeddings`，输入一批文本并校验每条向量维度；
- `QdrantGateway`：Qdrant REST API，负责 collection 校验/创建、批量 upsert、按班级查询和按 point/material 删除；
- `ChatGateway`：OpenAI-compatible `/chat/completions`，非流式返回简短文本。

配置项：

```text
QDRANT_URL
QDRANT_COLLECTION=campusclaw_chunks
EMBEDDING_BASE_URL
EMBEDDING_API_KEY
EMBEDDING_MODEL
EMBEDDING_DIMENSION
CHAT_BASE_URL
CHAT_API_KEY
CHAT_MODEL
RETRIEVAL_CONNECT_TIMEOUT
RETRIEVAL_READ_TIMEOUT
```

模型密钥没有可运行默认值；日志只记录服务类别、HTTP 状态、耗时和相关材料/任务标识，不记录 Authorization、正文、向量或完整供应商响应。Qdrant collection 使用余弦距离；不存在时按 `EMBEDDING_DIMENSION` 创建，存在但维度或距离不一致时启动失败，绝不自动删除重建。

选择理由：REST 契约简单且避免额外 SDK 版本耦合。备选 Qdrant Java SDK 可减少 DTO，但增加依赖且不改善课程所需行为；以后可在不改 spec 的情况下替换。

### Decision 5: 先提交原材料，再以有界后台任务异步解析并建立索引

上传编排分成两个明确边界：

1. `MaterialService.upload` 只校验文件名、扩展名、非空和大小，以流式复制将原文件原子提交并写入材料元数据，初始 `indexStatus=PENDING`、`processingStage=QUEUED`；文件或材料写入失败仍在请求内回滚。当前 2 MiB 上限保持不变，但请求线程不得为解析而读取完整正文。
2. HTTP 201 在主事务提交后立即返回。Controller 随后把 `materialId + classId` 交给 Spring 有界任务执行器，不把文件正文、用户令牌或请求对象带入后台线程。
3. 后台任务先以条件更新原子认领 `PENDING` 材料；只有认领者可以进入 `PARSING`，避免上传调度、恢复扫描和手动重试并发生成重复知识条目。任务按班级限定重新加载材料与原文件，解析 UTF-8 正文并在短事务中写入 `knowledge_entries`，随后切换到 `INDEXING` 并复用索引状态机完成切分、嵌入和 Qdrant 写入。
4. 解析或索引失败写为 `FAILED` 并保留原文件，同时记录稳定的失败阶段、失败代码和 `retryable`。非法 UTF-8 等永久输入错误不得在每次启动时自动重试；外部依赖或临时处理错误可由教师通过 `POST /api/materials/{id}/processing/retry` 重新排队。该接口与“使用已有知识条目重建切片”的索引重建接口保持不同语义。
5. 执行器拒绝任务时材料保持 `PENDING/QUEUED`。应用就绪后启动的有界恢复扫描按小批量重新提交 `PENDING` 和超过超时阈值的 `INDEXING` 材料，扫描本身不得同步解析材料或阻塞应用就绪。已有知识条目的种子材料跳过文件解析直接补索引。

索引/重建流程：

```text
按 material_id + session class_id 授权
→ 将材料标为 INDEXING
→ 删除旧 Qdrant points，再删除旧 knowledge_chunks
→ 计算切片并在短事务中写入 PENDING 行以取得主键
→ 批量调用 embedding
→ 用 chunk id 写入 Qdrant，payload 不含正文
→ 短事务将切片和材料标为 READY
```

任何嵌入或 upsert 失败都会尝试删除本次 point ID，并将本次切片和材料标为 `FAILED`。检索只读取材料与切片均为 `READY` 的行，因此不会看到部分批次。重建先移除旧索引符合课件要求，代价是失败后旧索引不再可用；教师可重试恢复。

恢复调度在 Demo seed 事务完成且应用可提供管理页面后运行：仅提交 `PENDING` 或被判定为中断的陈旧 `INDEXING` 材料，不扫描并重跑所有 `FAILED` 材料。调度使用固定批量与间隔，单个材料失败不能阻止应用启动；collection 配置不兼容属于系统配置错误，仍使启动失败。前端只在页面可见且存在处理中材料时轮询，使用退避和最长等待；达到上限后停止自动轮询并保留手动刷新。

选择理由：把解析和远程调用留在上传线程会让 HTTP 响应时间受文件大小与模型网关影响；把远程调用放进 MySQL 事务还会长时间持锁且无法回滚 Qdrant。有界进程内任务足以满足课程单实例部署，同时通过数据库状态和非阻塞恢复调度避免把内存队列当作唯一事实源。

### Decision 6: 材料删除使用持久化清理任务，残留向量必须回表失效

新增 `vector_cleanup_jobs`，保存任务 ID、材料/班级 ID、待删除 point ID 集合、状态、重试次数和时间，不外键关联材料。删除材料前在同一 MySQL 事务记录清理任务并收集 point ID；材料事务提交后立即尝试 Qdrant 删除，成功即删除/完成任务，失败由启动扫描和定时任务重试。

即使 Qdrant 暂时残留 point，向量检索也必须以 `point ID + session class_id + READY` 回 MySQL；材料级联删除后回表为空，残留点不会形成命中或泄露。任务内容不保存正文或向量。

选择理由：MySQL 与 Qdrant 无共同事务。仅记录日志在重启后会永久遗留；先删 Qdrant 再删 MySQL又会在数据库回滚时丢失仍有效索引。持久化清理任务以少量结构换取可恢复的一致性。

### Decision 7: 三种检索共享标准化候选，RRF 只融合名次

检索请求统一为：

```json
{
  "query": "要检索的一句话",
  "mode": "hybrid",
  "limit": 10,
  "class_id": 999
}
```

`query` 去首尾空白后长度为 1～1000 code points；`mode` 省略时为 `hybrid`；`limit` 默认 10、范围 1～20。为验证伪造班级场景，DTO 显式接收 `class_id`/`classId` 但业务层永远丢弃，避免全局 unknown-property 校验把安全场景提前变成 400。

接口固定为 `POST /api/retrieval/search`：

- `keyword` 使用原生 SQL `MATCH(chunk_text) AGAINST (... IN NATURAL LANGUAGE MODE)`，附加会话 `class_id`、材料/切片 `READY` 条件，只保留正分并排序；
- `vector` 嵌入问句，Qdrant filter 必须等于会话 `class_id`，阈值 0.35；point 返回后再以相同班级、READY 状态批量回表，按 Qdrant 顺序恢复；
- `hybrid` 分别得到已过滤列表，再计算 `1 / (60 + rank)` 之和。并列时依次按 RRF、最佳单路名次、`chunk_id` 升序保证确定性。

响应不返回 query vector：

```json
{
  "query": "...",
  "mode": "hybrid",
  "message": null,
  "hits": [{
    "materialId": 1,
    "materialTitle": "...",
    "knowledgeEntryId": 2,
    "chunkId": 3,
    "chunkIndex": 0,
    "startOffset": 0,
    "endOffset": 120,
    "excerpt": "...",
    "keywordScore": 4.2,
    "keywordRank": 1,
    "vectorScore": 0.81,
    "vectorRank": 2,
    "finalScore": 0.0325,
    "rank": 1
  }]
}
```

不适用的分数/名次为 `null`。无候选为 200、空 `hits` 和固定 `message`；空/超长查询为 400；嵌入或 Qdrant 故障使 vector/hybrid 返回 503，keyword 不受影响。

选择理由：直接相加全文分与余弦分没有共同尺度；RRF 对模型和语料分数尺度较稳健。备选归一化加权需要标定语料与模型，留待后续。

### Decision 8: `/api/ask` 复用 hybrid，不另建检索算法

请求字段为 `question`（1～1000 code points）、可选 `history`（只接受最近至多 6 条 `user`/`assistant` 消息）和兼容但被丢弃的 `class_id`。任何客户端 `system` role、资料字段和模型参数都不进入服务端 prompt。

服务端对当前 `question` 调用 hybrid、固定 `limit=4`：

- 无命中：返回 200 `{answer: "资料中未找到相关内容", citations: []}`，不调用 Chat；
- 有命中：服务端生成 system prompt，为候选资料临时编号，输入标题和受限正文；Chat 只能基于编号资料简短回答。服务端将常见全角/中文括号编号规范化为半角 `[n]` 并清理越界编号，再按编号在回答中首次出现的顺序生成“候选号 → 展示号”映射，同时改写回答正文与 citations；
- 模型遗漏编号：在已有候选时最多追加一次“不增加事实、只补有效编号”的修订调用；若仍无有效编号，则丢弃不可核验回答，返回明确说明和本次候选片段，不把无引用自由回答伪装为成功；
- Chat 故障：返回 503，不伪装成无资料，也不退回自由回答。

引用编号是每次回答内的展示序号：严格按返回 `citations` 的数组位置从 1 连续编号，与数据库 `chunk_index`、`chunk_id` 和材料内位置解耦。若模型只引用候选 `[4]`，最终正文和唯一引用都必须显示 `[1]`；若引用顺序为 `[4][2][4]`，最终必须为 `[1][2][1]`。前端只渲染服务端 `citation.number`，不得再次独立推导编号。

本课非流式；历史只帮助理解指代，检索仍只使用当前问题。服务端在把历史助手消息加入 prompt 前移除旧的 `[n]` 引用标记，防止上一轮编号与本轮候选编号混淆；可见对话记录本身保持不变。模型不得获得向量、Qdrant 地址、其他班候选或客户端 system 消息。

选择理由：问答与检索共享候选和权限，避免出现“搜索无结果但聊天能越界找到”的第二套路径。备选让模型调用搜索工具会扩大 Agent 与提示注入范围，留待后续课程。

### Decision 9: 服务端授权与前端可见性分别负责安全和体验

`/api/retrieval/search`、`/api/ask` 允许任意持有有效 Bearer JWT 的教师/学生；`POST /api/materials/{id}/index/rebuild` 和 `POST /api/materials/{id}/processing/retry` 仅教师。所有材料级操作都以 `id + currentUser.classId` 查询，跨班返回 404；所有受保护请求均由 `apiRequest` 自动携带 Bearer token。

前端 `/search` 使用 `meta.requiresAuth`，AppShell 新增“知识检索”有效导航项，保留其他待建设项。页面使用类似 GPT 网页端的单列对话布局：页面占满应用壳剩余视口，消息区独立滚动，输入器固定在该区域底部并适配 `100dvh`、安全区和手机软键盘。依据问答/直接检索收纳为紧凑模式控件，只有直接检索时才展示 keyword/vector/hybrid 次级选项。空状态展示引导问题，非流式请求期间展示明确等待状态，失败显示在对应消息旁并可原地重试。

每条助手消息在正文后直接展开顺序引用片段。回答正文使用安全文本分段把有效 `[n]` 渲染为按钮，不使用 `v-html`；点击编号滚动并聚焦同一消息下的引用卡。引用卡的“查看引用上下文”携带 `materialId + chunkId` 跳转 `/materials`，材料页读取查询参数、自动打开对应材料，并通过按 `materialId + chunkId + classId` 授权的上下文接口展示被选切片及相邻上下文；该跳转不依赖摘录字符串搜索，也不把预处理偏移冒充原文件绝对坐标。完整原文件查看和下载仍使用既有接口。

前端只把最近至多 6 条 user/assistant 文本作为后续问答历史发送；引用卡和直接检索结果不回传模型。切换模式不会清空已显示对话，刷新页面后不承诺持久化会话。结果与引用使用现有红色设计令牌和安全纯文本渲染；排序分数和单路名次默认收进“检索详情”。材料页上传、重试、重建、删除使用各自忙碌状态，不能因一个后台请求禁用无关操作。教师看到索引阶段、脱敏失败原因、重新处理和重建操作；学生隐藏管理操作，但隐藏按钮不构成授权。

选择理由：复用现有安全边界能降低遗漏。备选由前端携带 class ID 或只隐藏按钮均不具备安全性，拒绝采用。

### Decision 10: 测试按纯算法、HTTP 契约和真实基础设施分层

- 纯单元测试：三种切分、Unicode/重叠/偏移、参数校验、RRF、确定性排序、prompt 构造、历史引用清理、引用子集重排和无命中短路。
- 网关测试：使用本地 mock HTTP server 验证认证头、批量维度校验、超时、脱敏错误和 Chat 非流式响应，不访问真实课程密钥。
- Spring/Testcontainers 集成测试：MySQL 8.4 + ngram，必要时增加 Qdrant Testcontainer 或轻量 mock gateway，验证 migration、全文检索、双班级回表、任务原子认领、队列拒绝/中断恢复、永久失败不自动重试、角色和 API 状态码。
- 前端 Vitest：三种模式、空结果/503、消息级重试、引用编号按钮、材料/切片深链接、学生与教师操作、路由守卫和 Blob/材料跳转不回归。
- Compose 验收：先显式重建前后端镜像，再验证真实 MySQL/Qdrant 持久化、上传响应时延与页面可操作性、Qdrant 无宿主机端口，以及关闭 Qdrant 后 keyword 可用而 vector/hybrid 为 503。

测试配置使用固定维度的确定性假嵌入器，禁止真实模型调用进入自动测试。

## Risks / Trade-offs

- [MySQL ngram 配置与测试环境不一致] → Compose 和 Testcontainers 显式设置 token size 2，并用中文原词集成测试验证 migration 与查询。
- [向量模型更换导致维度或相似度分布变化] → collection 启动校验维度；本 change 固定 0.35，换模型前必须新 change 重新评估阈值和迁移。
- [进程退出导致内存中的解析任务丢失] → 材料先以 PENDING 持久化，应用就绪后异步扫描 PENDING 和陈旧 INDEXING 并幂等继续；执行器使用有界队列，拒绝调度时保留 PENDING 而不伪报完成。
- [恢复扫描重跑永久失败或拖慢应用启动] → 只异步提交 PENDING 和陈旧 INDEXING，小批量调度且不在启动线程解析；FAILED 由稳定失败码和 retryable 控制教师操作。
- [同一材料被上传调度、恢复扫描和手动重试同时处理] → 以条件更新原子认领，每个状态版本只允许一个工作者进入解析。
- [异步解析后才发现 UTF-8 非法] → 上传成功只代表原文件已安全保存；后台解析失败将材料标为 FAILED，列表明确展示失败且不生成知识条目或可检索切片。
- [重建先删旧索引造成短暂无结果] → 材料标记 INDEXING，检索只读 READY；失败明确展示并允许重试，不用旧索引冒充最新策略。
- [MySQL 已提交而 Qdrant 部分写入] → 记录本批 point ID、失败时删除并把切片标记 FAILED；检索还必须 READY 回表。
- [材料删除后 Qdrant 暂时残留] → 持久化 cleanup job 重试，回表失败的 point 永不进入响应。
- [模型输出引用不存在、使用全角格式或完全遗漏] → 规范化明确的括号编号并剔除越界值；无有效编号时只做一次受约束修订，仍失败则丢弃模型答案并返回可直接核对的候选片段。
- [模型只引用候选子集导致正文与卡片编号错位] → 服务端按首次出现顺序重写回答和 citations，前端只显示响应中的 number，并覆盖仅引用 `[4]` 和乱序重复引用测试。
- [材料深链接只有 materialId，进入列表后无法定位] → 同时携带 chunkId，由材料页按权限获取并聚焦权威切片上下文，不依赖摘录字符串匹配。
- [前端展示模型或材料正文导致 XSS] → 摘录与回答均作为文本渲染，不使用 `v-html`，延续 Markdown 不执行原则。
- [外部错误泄露密钥或正文] → 网关异常统一映射为脱敏 503；供应商响应只在受控调试中截断记录且不含 Authorization。

## Migration Plan

1. 先部署数据库 migration：增加材料索引状态、`knowledge_chunks`、ngram 全文索引和 `vector_cleanup_jobs`；旧材料为 `PENDING`，不影响现有接口。
2. 部署 Qdrant volume 与后端网关配置，在启动时创建或校验 `campusclaw_chunks`；Qdrant 不映射宿主机端口。
3. 部署后端异步解析、原子任务认领、非阻塞恢复、失败分类、教师重试、切分、索引、检索和问答 API。失败只标记材料，不删除原文件。
4. 部署 GPT 风格检索对话页、服务端顺序引用、引用上下文深链接、导航、索引状态和教师重试/重建入口，再更新 README 中原有非目标说明。
5. 显式重建前后端运行镜像，验证两班级、异步上传、恢复与失败、三模式、引用子集、上下文跳转、无依据、网关故障、重建和持久化后再开放入口。

回滚应用时，旧版本会忽略新增表和可空材料字段，现有材料 API 仍可运行；先停止新检索入口，再回滚应用。不要在普通回滚中删除 Qdrant volume 或新表。若确认废弃本能力，另建 change 规划数据清理 migration，而不是手工删除索引。
