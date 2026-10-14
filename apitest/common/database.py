"""封装接口测试所需的 MySQL 查询和受限清理操作。"""

from __future__ import annotations

from collections.abc import Sequence

import pymysql
from pymysql.cursors import DictCursor

from common.config import TestConfig


class DatabaseClient:
    """每次操作使用短连接，避免 SSE 等待期间长期占用数据库连接。"""

    def __init__(self, config: TestConfig):
        self._config = config

    def _connect(self):
        return pymysql.connect(
            host=self._config.db_host,
            port=self._config.db_port,
            user=self._config.db_user,
            password=self._config.db_password,
            database=self._config.db_name,
            charset="utf8mb4",
            autocommit=True,
            cursorclass=DictCursor,
            connect_timeout=max(1, int(self._config.http_timeout)),
        )

    def query_one(self, sql: str, params: Sequence | None = None) -> dict | None:
        """执行只读查询并返回第一行。"""
        with self._connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(sql, params or ())
                return cursor.fetchone()

    def query_all(self, sql: str, params: Sequence | None = None) -> list[dict]:
        """执行只读查询并返回全部行。"""
        with self._connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(sql, params or ())
                return list(cursor.fetchall())

    def cleanup_delete(self, sql: str, params: Sequence) -> int:
        """只允许执行带精确条件的 DELETE，调用方必须先核对测试标识。"""
        normalized = " ".join(sql.strip().upper().split())
        if not normalized.startswith("DELETE FROM ") or " WHERE " not in normalized:
            raise ValueError("清理操作只允许带 WHERE 条件的 DELETE")
        with self._connect() as connection:
            with connection.cursor() as cursor:
                return cursor.execute(sql, params)

    def ping(self) -> None:
        """验证数据库连接可用。"""
        row = self.query_one("SELECT 1 AS ok")
        if not row or row.get("ok") != 1:
            raise RuntimeError("MySQL 健康检查失败")
