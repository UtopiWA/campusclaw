# CampusClaw 接口回归测试

该目录提供第 5 课要求的独立 Python 接口测试。用例按 URL 层级组织，每个受测 HTTP 方法对应一个脚本；`run_all.py` 负责共享登录状态、测试数据和 HTML 报告。

## 前置条件

1. 安装 Python 3.11 或更高版本及依赖：

   ```powershell
   python -m pip install -r apitest/requirements.txt
   ```

2. 使用仓库根目录的 `.env` 启动完整 Compose 环境：

   ```powershell
   docker compose --env-file .env up -d --build
   ```

3. 确认 A 班至少有一份 `READY` 材料和知识切片。接口套件会读取该材料，但不会删除或修改它。

4. `.env` 至少提供 `DEMO_SEED_PASSWORD`、`MYSQL_USER`、`MYSQL_PASSWORD` 和 `MYSQL_DATABASE`。可选配置如下：

   | 变量 | 默认值 | 用途 |
   | --- | --- | --- |
   | `APITEST_BASE_URL` | `http://127.0.0.1:8080` | 前端代理入口 |
   | `APITEST_TEACHER_USERNAME` | `teacher-a` | 教师测试账号 |
   | `APITEST_STUDENT_USERNAME` | `student-a1` | 学生测试账号 |
   | `APITEST_TEACHER_PASSWORD` | 回退到 `DEMO_SEED_PASSWORD` | 教师密码 |
   | `APITEST_STUDENT_PASSWORD` | 回退到 `DEMO_SEED_PASSWORD` | 学生密码 |
   | `APITEST_DB_HOST` | `127.0.0.1` | MySQL 主机 |
   | `APITEST_HTTP_TIMEOUT` | `20` | 普通请求超时秒数 |
   | `APITEST_STREAM_TIMEOUT` | `180` | 流式请求超时秒数 |

## 执行

```powershell
python apitest/run_all.py
```

测试覆盖登录、本班关键字检索、技能创建、智能体提示词和绑定更新、学生越权写入、会话创建以及 SSE 流式消息。每个用例同时检查关键响应字段；涉及持久化的用例还会查询 MySQL。

套件使用唯一运行标识创建临时技能和会话。无论成功或失败，结束阶段都会恢复原智能体配置，并仅删除能够通过 ID、班级、用户及运行标识共同确认的测试数据。生成的报告位于 `apitest/reports/`，该目录不会提交到 Git。
