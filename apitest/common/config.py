"""读取本机环境并构造脱敏的接口测试配置。"""

from __future__ import annotations

from dataclasses import dataclass
import os
from pathlib import Path


def _read_dotenv(path: Path) -> dict[str, str]:
    """解析项目所需的简单 KEY=VALUE 文件，不覆盖进程环境变量。"""
    values: dict[str, str] = {}
    if not path.is_file():
        return values
    for raw_line in path.read_text(encoding="utf-8-sig").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
            value = value[1:-1]
        values[key] = value
    return values


def _positive_number(values: dict[str, str], key: str, default: str, cast):
    raw = os.environ.get(key, values.get(key, default))
    try:
        result = cast(raw)
    except (TypeError, ValueError) as exception:
        raise ValueError(f"{key} 必须是有效数字") from exception
    if result <= 0:
        raise ValueError(f"{key} 必须大于 0")
    return result


@dataclass(frozen=True)
class TestConfig:
    """集中保存运行参数；秘密仅驻留内存，不进入报告。"""

    project_root: Path
    base_url: str
    teacher_username: str
    teacher_password: str
    student_username: str
    student_password: str
    db_host: str
    db_port: int
    db_name: str
    db_user: str
    db_password: str
    mcp_auth_token: str
    mcp_base_url: str
    http_timeout: float
    stream_timeout: float

    @classmethod
    def load(cls) -> "TestConfig":
        """以进程环境优先、仓库 `.env` 回退的顺序加载配置。"""
        project_root = Path(__file__).resolve().parents[2]
        values = _read_dotenv(project_root / ".env")

        def value(key: str, default: str = "") -> str:
            return os.environ.get(key, values.get(key, default)).strip()

        seed_password = value("DEMO_SEED_PASSWORD")
        teacher_password = value("APITEST_TEACHER_PASSWORD", seed_password)
        student_password = value("APITEST_STUDENT_PASSWORD", seed_password)
        required = {
            "APITEST_TEACHER_PASSWORD 或 DEMO_SEED_PASSWORD": teacher_password,
            "APITEST_STUDENT_PASSWORD 或 DEMO_SEED_PASSWORD": student_password,
            "MYSQL_DATABASE": value("MYSQL_DATABASE", "campusclaw"),
            "MYSQL_USER": value("MYSQL_USER", "campusclaw"),
            "MYSQL_PASSWORD": value("MYSQL_PASSWORD"),
            "MCP_AUTH_TOKEN": value("MCP_AUTH_TOKEN"),
        }
        missing = [name for name, item in required.items() if not item]
        if missing:
            raise ValueError("缺少接口测试配置：" + "、".join(missing))

        return cls(
            project_root=project_root,
            base_url=value("APITEST_BASE_URL", "http://127.0.0.1:8080").rstrip("/"),
            teacher_username=value("APITEST_TEACHER_USERNAME", "teacher-a"),
            teacher_password=teacher_password,
            student_username=value("APITEST_STUDENT_USERNAME", "student-a1"),
            student_password=student_password,
            db_host=value("APITEST_DB_HOST", "127.0.0.1"),
            db_port=_positive_number(values, "MYSQL_HOST_PORT", "3306", int),
            db_name=required["MYSQL_DATABASE"],
            db_user=required["MYSQL_USER"],
            db_password=required["MYSQL_PASSWORD"],
            mcp_auth_token=required["MCP_AUTH_TOKEN"],
            mcp_base_url=value("APITEST_MCP_BASE_URL", "http://mcp-demo:8090"),
            http_timeout=_positive_number(values, "APITEST_HTTP_TIMEOUT", "20", float),
            stream_timeout=_positive_number(values, "APITEST_STREAM_TIMEOUT", "180", float),
        )

    def secret_values(self) -> list[str]:
        """返回需要从异常和报告中统一移除的非空秘密。"""
        return list({
            self.teacher_password,
            self.student_password,
            self.db_password,
            self.mcp_auth_token,
        } - {""})
