# Tasks

## 1. 配置与模块骨架

- [x] 1.1 扩展 `AppProperties` 与 `application.yml`，声明 Qdrant、嵌入、对话、超时、查询长度和默认检索参数，敏感项不提供可运行默认值；验证：执行 `mvnw.cmd -DskipTests compile` 成功，缺少必需配置的配置绑定测试明确失败且日志不含密钥。
- [x] 1.2 在后端建立 `retrieval`、`gateway` 和扩展后的 `knowledge` 职责目录及最小接口，保持现有包风格并为主要入口添加意图注释；验证：执行 `mvnw.cmd -DskipTests compile` 成功，目录中不存在未使用的占位实现或硬编码凭据。
- [x] 1.3 定义检索、索引重建和问答请求/响应 DTO，以及统一的 400、401、403、404、503 错误映射；验证：执行 DTO/Controller validation 测试，空查询、非法模式、非法 limit、未登录和依赖故障分别得到规定状态码。

## 2. MySQL 模型与迁移

- [x] 2.1 新增 Flyway V3 migration，为 `materials` 增加索引状态字段并创建 `knowledge_chunks`、外键、唯一键、班级/状态索引和 ngram FULLTEXT；验证：在空 MySQL 8.4 上执行 migration 后运行 `SHOW CREATE TABLE knowledge_chunks`，确认外键、`(material_id, chunk_index)` 唯一键与 `WITH PARSER ngram` 存在。
- [x] 2.2 在同一 migration 创建不依赖材料外键的 `vector_cleanup_jobs`，包含待删除 point ID、状态、重试次数和时间字段；验证：删除材料后清理任务仍可独立保留，且表中不保存正文、向量或密钥。
- [x] 2.3 新增材料索引状态映射、`KnowledgeChunk`、`VectorCleanupJob` 实体与 Repository，所有读取正文的查询都要求 `classId` 和 READY 状态；验证：执行 Repository 测试，A 班查询无法返回 B 班或 FAILED/INDEXING 切片。
- [x] 2.4 为 MySQL Compose 和 Testcontainers 设置 `ngram_token_size=2`，保持 `ddl-auto=validate`；验证：执行数据库上下文测试，Hibernate 校验通过，中文两字词可由全文索引命中。
- [ ] 2.5 扩展 `MaterialView` 返回 `indexStatus`、`indexStrategy`、`indexedAt`、`processingStage`、稳定 `failureCode` 和 `retryable`，不返回内部异常详情或存储路径；验证：材料 API 集成测试覆盖正常与失败状态，响应不包含 `storedPath`、`indexError`、堆栈、供应商原文或服务地址。
- [ ] 2.6 新增后续 Flyway migration 持久化 `processing_stage`、`failure_code` 和 `retryable`，不修改已经发布的 V3；验证：已有数据库从 V3 升级和空数据库全量迁移均成功，历史 READY/FAILED 数据获得兼容默认值。

## 3. 确定性切分

- [x] 3.1 实现统一的 Unicode code-point 文本游标、`ChunkDraft` 和策略参数校验；验证：执行 `ChunkingServiceTest`，emoji/补充平面字符不被拆开，偏移使用半开区间且可重建预处理后片段。
- [x] 3.2 实现 `auto` 策略的 800 字窗口、80 字重叠和空行/换行/句末优先断点；验证：单元测试覆盖短文本、超长无断点文本、多段中文、重叠和每次循环必定前进。
- [x] 3.3 实现 `custom` 策略的 100～2000 最大长度、0%～50% 重叠、三种断点枚举和非法参数拒绝；验证：参数化测试覆盖上下界、硬切、缺失断点、空切片与越界参数，非法配置不产生 ChunkDraft。
- [x] 3.4 实现只作用于切分输入的 URL/邮箱删除与连续空白合并；验证：单元测试确认处理后切片变化，而原 `knowledge_entries.body_text` 和材料文件字节保持不变。
- [x] 3.5 实现 `hierarchy` 的一至三级 Markdown 标题分章、标题保留及超长章节自动窗口回退；验证：单元测试覆盖无标题退化、连续标题、章节保留和超长章节。
- [x] 3.6 将同一材料的多个知识条目按原顺序切分并分配材料级连续 `chunkIndex`，偏移仍绑定来源条目；验证：单元测试使用两个 knowledge entry，确认 chunkIndex 连续、sourceEntryId 正确且偏移不会跨条目伪造。

## 4. 外部网关

- [x] 4.1 实现 OpenAI-compatible `EmbeddingGateway` 的批量请求、Authorization、模型选择、超时和维度/数量校验；验证：mock HTTP 测试覆盖成功、401、超时、条数不符和维度不符，异常与日志均不包含 API key 或完整正文。
- [x] 4.2 实现 Qdrant collection 启动校验：不存在时按余弦和配置维度创建，存在但距离或维度不符时失败且不删除数据；验证：Qdrant 网关测试覆盖创建、复用和不兼容 collection 三条路径。
- [x] 4.3 实现 Qdrant 批量 upsert、按会话班级查询、0.35 阈值和按 point/material 删除；验证：网关测试断言 payload 只有规定编号、filter 含正确 `class_id`、正文未进入 Qdrant，删除只影响目标点。
- [x] 4.4 实现非流式 OpenAI-compatible `ChatGateway`，只接收服务端构造的消息；验证：mock HTTP 测试覆盖成功、空响应、超时和供应商错误，确认客户端 system 内容与密钥未透传。
- [x] 4.5 为外部网关建立统一脱敏 503 异常和有限重试策略，禁止在请求线程无限重试；验证：故障测试确认响应不含 URL 凭据、Authorization、正文或供应商原始错误，调用次数符合上限。

## 5. 索引生命周期与一致性

- [x] 5.1 实现材料索引状态机和独立的短事务操作：INDEXING、PENDING chunk、READY、FAILED；验证：`IndexingServiceTest` 覆盖合法转换和重复调用，检索 Repository 在非 READY 状态返回空。
- [x] 5.2 实现默认索引流程：逐条知识正文切分、持久化取得 chunk ID、批量嵌入、Qdrant upsert 后标记 READY；验证：集成测试断言 Qdrant point ID、MySQL chunk ID 和 payload `chunk_id` 相同，payload 不含正文。
- [x] 5.3 将索引放在材料和知识条目提交之后，索引失败不得回滚已提交原文件与正文；验证：模拟嵌入失败时材料、原文件和知识条目保留，FAILED 切片不可检索且无半成品向量。
- [ ] 5.4 在应用就绪后以有界批量异步补齐 PENDING、陈旧 INDEXING 以及种子知识条目的 AUTO 索引，不在启动线程同步处理，也不无条件重跑 FAILED；验证：应用可在积压或网关缓慢时先完成启动，连续恢复不增加 chunk/point 数量，永久失败在重启后不再自动执行。
- [x] 5.5 实现教师 `POST /api/materials/{id}/index/rebuild`，接收三种策略并按本次参数先移除旧索引再重建；验证：集成测试确认旧 point ID 消失、新策略生效，非法配置返回 400 且现有索引不被替换。
- [x] 5.6 为重建接口执行教师角色与 `id + authenticated classId` 查询；验证：学生请求返回 403，A 班教师请求 B 班或不存在材料均返回相同 404，目标索引未变化。
- [x] 5.7 将材料删除接入 `vector_cleanup_jobs`，提交后立即清理并由启动/定时任务重试失败项；验证：模拟 Qdrant 删除失败后材料和 MySQL chunks 已删除、job 保留，重试成功后 point 与 job 消失，残留 point 不能通过回表形成命中。
- [x] 5.8 将上传改为先提交原文件与 PENDING 材料，再由有界 Spring 执行器异步解析知识条目并建立索引；解析失败标记 FAILED；验证：Controller 测试确认 HTTP 201 不等待解析，后台服务测试覆盖成功、非法 UTF-8、重复执行和失败状态。原子认领、失败分类与非阻塞恢复由后续条目补齐。
- [ ] 5.9 为后台处理增加数据库条件更新式原子认领、陈旧 INDEXING 恢复和队列拒绝后的 PENDING 再调度；验证：并发触发上传回调、恢复扫描和手动重试时只有一个任务进入解析，队列饱和后材料最终可被恢复且不会重复创建 entry/chunk/point。
- [ ] 5.10 持久化 PARSING/INDEXING 失败阶段、稳定 failure code 与 retryable，并实现仅教师可用的 `POST /api/materials/{id}/processing/retry`；验证：非法 UTF-8 为不可重试且不随重启重复执行，临时依赖失败可重新排队，学生为 403、跨班为 404，索引重建对无知识条目材料给出明确拒绝。
- [ ] 5.11 将上传文件保存改为受大小限制的流式临时文件复制和原子移动，请求线程不解析正文；验证：Controller 时延不受解析/嵌入 mock 延迟影响，文件保存失败仍执行既有补偿且不遗留数据库记录或临时文件。

## 6. 三种检索与 API

- [x] 6.1 实现带 `class_id`、材料/切片 READY 条件的 MySQL ngram 关键字查询并返回全文分与名次；验证：真实 MySQL 集成测试用中文原词命中本班，B 班和 FAILED 切片不返回，Embedding/Qdrant mock 调用次数为 0。
- [x] 6.2 实现向量检索：问句嵌入、Qdrant 会话班级过滤、0.35 阈值以及按同一班级和 READY 状态回表；验证：测试中同义改写命中、低分被过滤、伪造 payload 或跨班 point 被回表丢弃。
- [x] 6.3 实现纯函数 RRF，使用 `k=60`、缺席路不计分和稳定并列规则；验证：`ReciprocalRankFusionTest` 覆盖双路命中、单路命中、重复 chunk、并列和输入顺序变化，结果确定且不直接相加原始分数。
- [x] 6.4 实现 hybrid 编排和默认模式，限制 query 1～1000 code points、limit 默认 10 且范围 1～20；验证：服务测试断言两路先过滤再融合，省略模式使用 hybrid，非法 query/limit 返回 400 且不调用外部网关。
- [x] 6.5 实现 `POST /api/retrieval/search` 和可追溯 hit DTO，显式接收但丢弃客户端 `class_id`/`classId`；验证：MockMvc 测试确认响应包含材料/entry/chunk/偏移/摘录/适用分数与名次，不含路径、向量、Qdrant 地址或其他班候选。
- [x] 6.6 实现无命中与依赖故障语义；验证：无候选返回 200、空 `hits` 和固定句，Qdrant 停止时 keyword 仍返回而 vector/hybrid 返回脱敏 503，任何模式都不伪造分数。
- [x] 6.7 完成检索认证与跨班集成测试；验证：未登录为 401，A 班两次查询 B 班专有内容（含伪造 B 班 `class_id`）均为 200 空 hits，MySQL、Qdrant 和回表记录的过滤值均为 A 班。

## 7. 有依据的问答

- [x] 7.1 实现 `/api/ask` DTO 和服务端 prompt builder，只接受 1～1000 字 question 与最近 6 条 user/assistant history，丢弃 system、资料、模型参数和 class_id；验证：单元测试确认 prompt 只含服务端规则、本班候选与允许历史。
- [ ] 7.2 复用 hybrid 固定取前 4 条，并按有效引用在回答中的首次出现顺序重排实际使用的候选；服务端必须同步改写回答正文、`citation.number` 与 citations 数组；验证：覆盖只引用候选 `[4]` 得到 `[1]`、`[4][2][4]` 得到 `[1][2][1]`、越界编号和同一候选重复引用。
- [x] 7.3 实现无命中短路和 Chat 故障分支；验证：无命中返回 200、固定句和空 citations 且 Chat 调用次数为 0；有候选但 Chat 故障返回 503，不退回自由回答。
- [x] 7.4 完成问答认证、跨班与提示注入集成测试；验证：未登录为 401，A 班问题与伪造 system/class_id 不能使 prompt 或响应出现 B 班正文，API 响应不含向量和密钥。
- [x] 7.5 兼容全角/中文括号引用并处理模型完全遗漏编号：最多修订一次，仍失败则丢弃不可核验回答并返回候选依据；验证：`AskServiceTest` 覆盖格式规范化、越界清理、修订成功和安全降级。
- [ ] 7.6 在构造新一轮 prompt 前清除历史 assistant 文本中的旧 `[n]` 引用标记，但不修改页面可见历史；验证：上一轮 `[1]` 不会被当作本轮候选，用户正文中的普通数字和其他文本不被误删。

## 8. Vue 检索与索引界面

- [x] 8.1 新增 `/search` 受保护路由、检索 API service 和 AppShell“知识检索”有效导航项，教师/学生共用；验证：Router/Vitest 测试确认未登录重定向、登录角色均可进入、当前导航状态正确。
- [x] 8.2 实现检索页的问题输入、keyword/vector/hybrid 模式选择、提交、加载和查询参数校验，复用现有按钮、反馈和设计令牌；验证：组件测试覆盖默认 hybrid、三模式请求、重复提交禁用和 400 提示。
- [ ] 8.3 完善命中卡片：以当前结果位置编号并展示标题、摘录和材料入口，分数、偏移及单路名次默认收纳到可折叠“检索详情”，所有内容按文本渲染；验证：含 HTML 的摘录不执行，默认界面不被内部检索字段挤占。
- [ ] 8.4 实现空 hits、503 与问答 citations 的不同视觉状态，并把请求失败关联到对应消息、提供保留原模式的原地重试；验证：固定无依据说明、依赖故障和编号引用不会混淆，重试不要求重新输入且不清空其他消息。
- [ ] 8.5 在材料界面展示 QUEUED/PARSING/INDEXING/READY/FAILED、脱敏失败代码与 retryable；教师按状态获得重新处理或三策略重建操作，学生不显示管理入口；验证：教师组件测试携带 Bearer token 发出正确请求，学生组件无按钮，后端学生请求仍返回 403。
- [ ] 8.6 补充窄屏、键盘和可访问名称样式；验证：执行前端测试并人工在桌面、76px 侧栏和手机宽度检查输入、模式、摘录和材料入口均可操作且无水平遮挡。
- [x] 8.7 将问答 citations 改为内联依据卡片，直接展示编号、标题、切片位置和安全文本摘录，全文入口保持可选；验证：组件测试确认无需跳转即可看到摘录，HTML 字符串不被执行且窄屏不横向溢出。
- [ ] 8.8 将知识检索页完善为占满剩余视口的连续消息流和独立滚动区，输入器在桌面、`100dvh` 与手机软键盘下固定可达；把问答/直接检索及三种检索模式收纳为紧凑控件；验证：组件测试与人工检查覆盖长对话自动滚动、多轮历史、两种提交模式、加载状态和窄屏无横向遮挡。
- [ ] 8.9 安全分段渲染回答正文中的有效 `[n]` 为可点击按钮，点击后滚动并聚焦同一消息的引用卡；材料入口改为携带 `materialId + chunkId`；验证：不使用 `v-html`，重复编号定位同一卡片，正文与卡片均显示服务端 `citation.number`。
- [ ] 8.10 新增按 `materialId + chunkId + authenticated classId` 读取引用上下文的 API，并让材料页消费查询参数、自动打开材料和突出目标切片及有限相邻上下文；验证：本班链接可定位，chunk 不属于 material、跨班和不存在均为 404，完整原文件仍可查看/下载。
- [ ] 8.11 将材料页上传、重新处理、重建、删除拆分为独立忙碌状态；状态轮询仅在页面可见时运行，采用退避、最长等待和手动刷新；验证：孤立 PENDING 不会无限高频轮询，一个操作不会禁用其他材料的无关操作。

## 9. Compose、配置与文档

- [x] 9.1 在 `docker-compose.yml` 增加不映射宿主机端口的 Qdrant、健康检查、持久化 volume 和 backend 健康依赖，并配置 MySQL ngram token size；验证：执行 `docker compose config --quiet` 成功，解析配置仅暴露 frontend/MySQL 明确需要的宿主机端口。
- [x] 9.2 更新 `.env.example`，列出 Qdrant、Embedding、Chat、模型、维度和超时变量的安全占位值，不放真实密钥；验证：执行 `docker compose --env-file .env.example config` 成功，并用秘密扫描/人工检查确认没有真实 token、密码或供应商凭据。
- [ ] 9.3 更新 README 的功能边界、架构、启动配置、三种模式、重建、故障语义和验收命令，删除“本学期不做检索问答”的过时表述；验证：按 README 从空环境启动后能完成登录、索引、三模式查询、无依据问答和停止/重启持久化检查。

## 10. 综合验证与规约收尾

- [x] 10.1 运行后端纯单元和网关测试；验证：`mvnw.cmd -Dtest=ChunkingServiceTest,ReciprocalRankFusionTest,GatewayClientTest test` 全部通过且不访问真实模型服务。
- [ ] 10.2 运行后端 MySQL/Qdrant 集成测试；验证：`mvnw.cmd test` 全部通过，覆盖 V3 后续迁移、两班隔离、任务认领/恢复、永久失败、索引失败、引用子集重排、引用上下文、删除清理、三检索模式、503 和问答短路。
- [ ] 10.3 运行前端测试与生产构建；验证：`npm.cmd test -- --run` 和 `npm.cmd run build` 均退出码为 0，覆盖独立滚动区、紧凑控件、消息级重试、服务端引用编号、深链接与轮询停止，既有登录、材料查看/下载测试无回归。
- [ ] 10.4 先执行 `docker compose up -d --build backend frontend` 确保运行镜像包含当前源码，再执行 Compose 人工验收：上传 HTTP 201 不等待解析且页面可操作、状态恢复、永久失败、原词、同义改写、hybrid 排序、引用子集重排与跳转、跨班伪造、无依据、Qdrant 停机、教师重试/重建和重启持久化；验证：记录每项 HTTP/页面现象，并确认数据库正文、Qdrant payload 与 point/chunk ID 关系符合 spec。
- [ ] 10.5 对 change 执行严格校验并复核文档一致性；验证：`openspec.cmd validate add-traceable-vector-retrieval --strict` 退出码为 0，proposal、design、spec、README 与实际行为无冲突，且本文件只在对应验证通过后逐项改为 `[x]`。
