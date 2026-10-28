# fix-markdown-aware-knowledge-chunking

归档日期：2026-10-28。Markdown 标题分节、节内装箱、代码/公式围栏识别及 HIERARCHY 伪标题过滤已实现，材料正文切分增量已同步至主规格。任务清单 8/8，验收通过。

## 验收结果

- 解析/切分聚焦测试与非容器后端回归通过。
- 2026-10-28 容器集成测试通过：`CampusClawIntegrationTest` 17 项、`QdrantIntegrationTest` 1 项，共 18 项，无失败、错误或跳过；Maven 退出码 0。

仓库根目录执行命令：

```powershell
backend\mvnw.cmd -f backend\pom.xml "-Dapi.version=1.44" "-Dtest=CampusClawIntegrationTest,QdrantIntegrationTest" test
```

环境为 Docker Engine 29.8.2、Testcontainers 1.20.3；API 参数用于兼容本机 Docker，不修改项目默认或全局配置。测试使用独立的 MySQL 8.4 与 Qdrant 1.15.4 容器。MySQL 类的模型/向量网关使用替身，Qdrant 类访问真实测试容器；结果由 `backend/target/surefire-reports/` 中两个测试类的 XML 核对，不代表真实模型端到端验收。

旧材料不会被隐式重解析，采用新一级解析需要重新上传。
