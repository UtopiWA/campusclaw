"""定义运行结果与清理结果。"""

from dataclasses import dataclass


@dataclass(frozen=True)
class CaseResult:
    name: str
    method: str
    path: str
    passed: bool
    duration_ms: int
    detail: str


@dataclass(frozen=True)
class CleanupResult:
    name: str
    passed: bool
    detail: str
