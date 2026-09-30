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
- [x] 2.5 扩展 `MaterialView` 返回 `indexStatus`、`indexStrategy` 和 `indexedAt`，不返回内部错误详情或存储路径；验证：材料 API 集成测试断言新增状态存在，响应仍不包含 `storedPath`、`indexError` 或服务地址。

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
- [x] 5.3 将索引放在现有材料/知识条目提交之后，并让上传响应携带最终 READY 或 FAILED 状态；验证：模拟嵌入失败时上传仍返回 HTTP 201，材料、原文件和知识条目存在，FAILED 切片不可检索且无半成品向量。
- [x] 5.4 在 Demo seed 完成后幂等补齐旧材料和种子知识条目的 AUTO 索引；验证：连续运行两次补索引，第二次不增加 chunk 或 point 数量，单材料失败不阻止应用启动。
- [x] 5.5 实现教师 `POST /api/materials/{id}/index/rebuild`，接收三种策略并按本次参数先移除旧索引再重建；验证：集成测试确认旧 point ID 消失、新策略生效，非法配置返回 400 且现有索引不被替换。
- [x] 5.6 为重建接口执行教师角色与 `id + session classId` 查询；验证：学生请求返回 403，A 班教师请求 B 班或不存在材料均返回相同 404，目标索引未变化。
- [x] 5.7 将材料删除接入 `vector_cleanup_jobs`，提交后立即清理并由启动/定时任务重试失败项；验证：模拟 Qdrant 删除失败后材料和 MySQL chunks 已删除、job 保留，重试成功后 point 与 job 消失，残留 point 不能通过回表形成命中。

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
- [x] 7.2 复用 hybrid 固定取前 4 条并生成顺序一致的 citations，校验模型输出引用编号；验证：服务测试确认 `[1]`、`[2]` 与 citations 顺序和材料入口一致，不存在编号不会生成伪造 citation。
- [x] 7.3 实现无命中短路和 Chat 故障分支；验证：无命中返回 200、固定句和空 citations 且 Chat 调用次数为 0；有候选但 Chat 故障返回 503，不退回自由回答。
- [x] 7.4 完成问答认证、跨班与提示注入集成测试；验证：未登录为 401，A 班问题与伪造 system/class_id 不能使 prompt 或响应出现 B 班正文，API 响应不含向量和密钥。

## 8. Vue 检索与索引界面

- [x] 8.1 新增 `/search` 受保护路由、检索 API service 和 AppShell“知识检索”有效导航项，教师/学生共用；验证：Router/Vitest 测试确认未登录重定向、登录角色均可进入、当前导航状态正确。
- [x] 8.2 实现检索页的问题输入、keyword/vector/hybrid 模式选择、提交、加载和查询参数校验，复用现有按钮、反馈和设计令牌；验证：组件测试覆盖默认 hybrid、三模式请求、重复提交禁用和 400 提示。
- [x] 8.3 实现命中卡片，展示标题、切片序号、偏移、文本摘录、模式相关分数/名次和材料入口，所有内容按文本渲染；验证：组件测试用含 HTML 的摘录确认没有 `v-html` 执行，材料入口指向当前用户可访问的内容。
- [x] 8.4 实现空 hits、503 与问答 citations 的不同视觉状态；验证：组件测试确认固定无依据说明、可重试故障和编号引用不会相互混淆，引用顺序与回答编号一致。
- [x] 8.5 在材料界面显示 PENDING/INDEXING/READY/FAILED，并为教师增加三策略重建表单，学生不显示管理入口；验证：教师组件测试发出带 CSRF 的重建请求，学生组件无按钮，后端学生请求仍由集成测试确认 403。
- [ ] 8.6 补充窄屏、键盘和可访问名称样式；验证：执行前端测试并人工在桌面、76px 侧栏和手机宽度检查输入、模式、摘录和材料入口均可操作且无水平遮挡。

## 9. Compose、配置与文档

- [x] 9.1 在 `docker-compose.yml` 增加不映射宿主机端口的 Qdrant、健康检查、持久化 volume 和 backend 健康依赖，并配置 MySQL ngram token size；验证：执行 `docker compose config --quiet` 成功，解析配置仅暴露 frontend/MySQL 明确需要的宿主机端口。
- [x] 9.2 更新 `.env.example`，列出 Qdrant、Embedding、Chat、模型、维度和超时变量的安全占位值，不放真实密钥；验证：执行 `docker compose --env-file .env.example config` 成功，并用秘密扫描/人工检查确认没有真实 token、密码或供应商凭据。
- [ ] 9.3 更新 README 的功能边界、架构、启动配置、三种模式、重建、故障语义和验收命令，删除“本学期不做检索问答”的过时表述；验证：按 README 从空环境启动后能完成登录、索引、三模式查询、无依据问答和停止/重启持久化检查。

## 10. 综合验证与规约收尾

- [x] 10.1 运行后端纯单元和网关测试；验证：`mvnw.cmd -Dtest=ChunkingServiceTest,ReciprocalRankFusionTest,GatewayClientTest test` 全部通过且不访问真实模型服务。
- [x] 10.2 运行后端 MySQL/Qdrant 集成测试；验证：`mvnw.cmd test` 全部通过，覆盖 migration、两班隔离、索引失败、删除清理、三检索模式、503 和问答短路。
- [x] 10.3 运行前端测试与生产构建；验证：`npm.cmd test -- --run` 和 `npm.cmd run build` 均退出码为 0，既有登录、材料查看/下载测试无回归。
- [ ] 10.4 执行 Compose 人工验收：原词、同义改写、hybrid 排序、跨班伪造、无依据、Qdrant 停机、教师重建和重启持久化；验证：记录每项 HTTP/页面现象，并确认数据库正文、Qdrant payload 与 point/chunk ID 关系符合 spec。
- [x] 10.5 对 change 执行严格校验并复核文档一致性；验证：`openspec.cmd validate add-traceable-vector-retrieval --strict` 退出码为 0，proposal、design、spec、README 与实际行为无冲突，且本文件只在对应验证通过后逐项改为 `[x]`。
