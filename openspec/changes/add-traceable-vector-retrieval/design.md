# Design

## Context

变更动机见 `proposal.md`，行为契约见 `specs/knowledge-retrieval/spec.md`。当前后端是 Java 17 / Spring Boot 3 单体 REST 服务：`MaterialService` 在一个 MySQL 事务中写入材料和 `knowledge_entries`，并用文件补偿保持原文件一致；`TextParser` 已按 Unicode code point 校验 UTF-8 文本并把正文拆成一个或多个知识条目。认证会话只保存用户 ID，每次请求重新加载角色和 `class_id`。前端是 Vue 3 单页应用，统一通过 `AppShell`、Vue Router 和 `apiRequest` 访问同源 API。

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
- 不替换现有 Session/CSRF 认证、材料文件补偿、Vue 应用壳或 MySQL 主数据模型。
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

### Decision 5: 先提交原材料，再以独立状态机同步建立索引

上传编排分成两个明确边界：

1. 现有 `MaterialService.upload` 完成文件、材料和 `knowledge_entries` 的原子提交；这一阶段失败仍按 `auth-upload` 回滚。
2. 提交后由索引服务同步尝试建立索引。上传响应仍为 HTTP 201，并返回 `indexStatus`；索引失败写为 `FAILED`，不删除已成功上传的材料和知识正文。

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

启动补索引在 Demo seed 事务完成后运行，只处理 `PENDING` 或缺少切片的材料，并使用 `auto`。单个材料失败只标记该材料，不能阻止应用为关键字 API 和管理页面启动；collection 配置不兼容属于系统配置错误，仍使启动失败。

选择理由：把远程调用放进 MySQL 事务会长时间持锁且无法回滚 Qdrant；完全异步队列超出课程范围。同步后置索引让状态容易验收，又保持上传主数据可靠。

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
- 有命中：服务端生成 system prompt，为每条资料编号，输入标题、chunk ID 和正文；Chat 只能基于编号资料简短回答。服务端将常见全角/中文括号编号规范化为半角 `[n]`，清理越界编号，并以相同顺序返回带摘录的 citations；
- 模型遗漏编号：在已有候选时最多追加一次“不增加事实、只补有效编号”的修订调用；若仍无有效编号，则丢弃不可核验回答，返回明确说明和本次候选片段，不把无引用自由回答伪装为成功；
- Chat 故障：返回 503，不伪装成无资料，也不退回自由回答。

本课非流式；历史只帮助理解指代，检索仍只使用当前问题。模型不得获得向量、Qdrant 地址、其他班候选或客户端 system 消息。

选择理由：问答与检索共享候选和权限，避免出现“搜索无结果但聊天能越界找到”的第二套路径。备选让模型调用搜索工具会扩大 Agent 与提示注入范围，留待后续课程。

### Decision 9: 服务端授权与前端可见性分别负责安全和体验

`/api/retrieval/search`、`/api/ask` 允许任意已登录教师/学生；`POST /api/materials/{id}/index/rebuild` 仅教师。所有材料级操作都以 `id + currentUser.classId` 查询，跨班返回 404。CSRF 延续现有策略，所有 POST 均由 `apiRequest` 自动携带 token。

前端 `/search` 使用 `meta.requiresAuth`，AppShell 新增“知识检索”有效导航项，保留其他待建设项。结果卡使用现有红色令牌、卡片和反馈样式，链接到材料查看能力；教师看到索引状态和重建操作，学生隐藏该操作，但隐藏按钮不构成授权。

选择理由：复用现有安全边界能降低遗漏。备选由前端携带 class ID 或只隐藏按钮均不具备安全性，拒绝采用。

### Decision 10: 测试按纯算法、HTTP 契约和真实基础设施分层

- 纯单元测试：三种切分、Unicode/重叠/偏移、参数校验、RRF、确定性排序、prompt 构造和无命中短路。
- 网关测试：使用本地 mock HTTP server 验证认证头、批量维度校验、超时、脱敏错误和 Chat 非流式响应，不访问真实课程密钥。
- Spring/Testcontainers 集成测试：MySQL 8.4 + ngram，必要时增加 Qdrant Testcontainer 或轻量 mock gateway，验证 migration、全文检索、双班级回表、角色和 API 状态码。
- 前端 Vitest：三种模式、空结果/503、引用、学生与教师操作、路由守卫和 Blob/材料跳转不回归。
- Compose 验收：真实 MySQL/Qdrant 持久化、Qdrant 无宿主机端口、关闭 Qdrant 后 keyword 可用而 vector/hybrid 为 503。

测试配置使用固定维度的确定性假嵌入器，禁止真实模型调用进入自动测试。

## Risks / Trade-offs

- [MySQL ngram 配置与测试环境不一致] → Compose 和 Testcontainers 显式设置 token size 2，并用中文原词集成测试验证 migration 与查询。
- [向量模型更换导致维度或相似度分布变化] → collection 启动校验维度；本 change 固定 0.35，换模型前必须新 change 重新评估阈值和迁移。
- [同步索引使上传响应变慢] → 主数据先提交、嵌入批量发送并设置严格超时；失败返回材料与 FAILED 状态。真正异步任务留待后续。
- [重建先删旧索引造成短暂无结果] → 材料标记 INDEXING，检索只读 READY；失败明确展示并允许重试，不用旧索引冒充最新策略。
- [MySQL 已提交而 Qdrant 部分写入] → 记录本批 point ID、失败时删除并把切片标记 FAILED；检索还必须 READY 回表。
- [材料删除后 Qdrant 暂时残留] → 持久化 cleanup job 重试，回表失败的 point 永不进入响应。
- [模型输出引用不存在、使用全角格式或完全遗漏] → 规范化明确的括号编号并剔除越界值；无有效编号时只做一次受约束修订，仍失败则丢弃模型答案并返回可直接核对的候选片段。
- [前端展示模型或材料正文导致 XSS] → 摘录与回答均作为文本渲染，不使用 `v-html`，延续 Markdown 不执行原则。
- [外部错误泄露密钥或正文] → 网关异常统一映射为脱敏 503；供应商响应只在受控调试中截断记录且不含 Authorization。

## Migration Plan

1. 先部署数据库 migration：增加材料索引状态、`knowledge_chunks`、ngram 全文索引和 `vector_cleanup_jobs`；旧材料为 `PENDING`，不影响现有接口。
2. 部署 Qdrant volume 与后端网关配置，在启动时创建或校验 `campusclaw_chunks`；Qdrant 不映射宿主机端口。
3. 部署后端切分、索引、检索和问答 API。启动补索引逐材料执行，失败只标记材料，不删除原数据。
4. 部署前端检索页、导航、索引状态和教师重建入口，再更新 README 中原有非目标说明。
5. 验证两班级、三模式、无依据、网关故障、重建和持久化后再开放入口。

回滚应用时，旧版本会忽略新增表和可空材料字段，现有材料 API 仍可运行；先停止新检索入口，再回滚应用。不要在普通回滚中删除 Qdrant volume 或新表。若确认废弃本能力，另建 change 规划数据清理 migration，而不是手工删除索引。
