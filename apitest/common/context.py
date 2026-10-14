"""维护跨接口共享状态、运行标识及安全清理回调。"""

from __future__ import annotations

from collections.abc import Callable
from datetime import datetime, timezone
import re
import secrets
from typing import Any

from common.config import TestConfig
from common.database import DatabaseClient
from common.http_client import HttpClient
from common.models import CleanupResult


class TestContext:
    """保存本轮令牌和资源 ID；这些值不会直接写入报告。"""

    def __init__(self, config: TestConfig):
        self.config = config
        self.http = HttpClient(config.base_url, config.http_timeout, config.stream_timeout)
        self.db = DatabaseClient(config)
        stamp = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")
        self.run_id = f"{stamp}-{secrets.token_hex(3)}"
        self.state: dict[str, Any] = {}
        self._secrets = config.secret_values()
        self._cleanups: list[tuple[str, Callable[[], None]]] = []

    def add_secret(self, value: str | None) -> None:
        """登记运行时令牌，供报告统一脱敏。"""
        if value:
            self._secrets.append(value)

    def add_cleanup(self, name: str, action: Callable[[], None]) -> None:
        """按创建顺序登记，收尾时逆序执行。"""
        self._cleanups.append((name, action))

    def run_cleanups(self) -> list[CleanupResult]:
        """尽力执行全部清理，单项失败不会阻止后续恢复。"""
        results: list[CleanupResult] = []
        for name, action in reversed(self._cleanups):
            try:
                action()
                results.append(CleanupResult(name, True, "已完成"))
            except Exception as exception:  # noqa: BLE001 - 清理阶段必须继续其余回调
                results.append(CleanupResult(name, False, self.sanitize(str(exception))))
        return results

    def sanitize(self, message: str) -> str:
        """移除已知秘密及疑似 JWT，避免异常进入控制台或 HTML 报告。"""
        safe = message or "未提供错误信息"
        for value in sorted(set(self._secrets), key=len, reverse=True):
            if value:
                safe = safe.replace(value, "[REDACTED]")
        safe = re.sub(r"\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b", "[REDACTED_JWT]", safe)
        return safe[:1000]
