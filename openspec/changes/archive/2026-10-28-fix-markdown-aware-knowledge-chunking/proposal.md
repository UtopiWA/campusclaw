# Change: 修复 Markdown 材料的语义切分

## Why

当前 `TextParser` 把每个空行都当成不可跨越的知识条目边界，第二级切片又不会跨知识条目合并正文。常见的 Markdown 块公式因此会把“说明—`$$...$$`—结论”拆成多个极短切片；普通分段、疏松列表和含空行的代码围栏也有相同问题。HIERARCHY 的标题正则还会把代码或公式围栏里的 `# ...` 误判为真实标题。

这会降低单个检索切片的语义完整性，并可能污染标题路径。修复目标是让一级正文条目尊重 Markdown 章节与围栏边界，再由既有二级策略执行长度、重叠和 offset 规则。

## What Changes

- 将一级文本解析从“每个空行一个 entry”调整为“Markdown 标题开启新节、节内相邻块在 1000 code points 内合并”。
- 识别反引号/波浪号代码围栏及 `$$...$$`、`\[...\]` 块公式围栏，围栏内部空行不产生 entry 边界。
- 超长单块仍按 Unicode code point 安全硬切，保持知识条目长度上限。
- HIERARCHY 标题扫描忽略代码与块公式围栏内部的伪标题。
- 增加数学第 20 题形态、围栏伪标题、Unicode 上限与兼容行为的聚焦测试。

## Capabilities

### Modified Capabilities

- `knowledge-retrieval`：Markdown 正文先形成语义连续的知识条目，再按既有 auto/custom/hierarchy 规则产生检索切片。

## Impact

- 修改后端 `TextParser` 和 `ChunkingService`，不修改数据库结构、HTTP API、检索排序或向量 payload。
- 新上传材料直接采用新规则；已持久化旧知识条目的材料不会被索引重建隐式重解析，需要删除后重新上传，避免改变“重建只消费已有 entry”的既有契约。

