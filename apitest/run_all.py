"""发现并执行全部接口脚本，最终生成 HTML 报告。"""

from __future__ import annotations

import importlib.util
from pathlib import Path
import sys
from time import perf_counter

APITEST_ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(APITEST_ROOT))
if hasattr(sys.stdout, "reconfigure"):
    # Windows 非交互终端可能仍使用本地代码页，显式统一为 UTF-8 便于 CI 收集。
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")

from common.assertions import require_equal  # noqa: E402
from common.config import TestConfig  # noqa: E402
from common.context import TestContext  # noqa: E402
from common.models import CaseResult  # noqa: E402
from common.report import write_report  # noqa: E402


def _load_cases() -> list:
    """从 URL 目录加载大写 HTTP 方法脚本，并按显式顺序执行。"""
    modules = []
    for index, path in enumerate(sorted((APITEST_ROOT / "cases").rglob("*.py"))):
        if path.name.startswith("_") or path.stem.upper() != path.stem:
            continue
        spec = importlib.util.spec_from_file_location(f"apitest_case_{index}", path)
        if spec is None or spec.loader is None:
            raise RuntimeError(f"无法加载接口脚本：{path.relative_to(APITEST_ROOT)}")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        for attribute in ("ORDER", "NAME", "METHOD", "PATH", "run"):
            if not hasattr(module, attribute):
                raise RuntimeError(f"接口脚本缺少 {attribute}：{path.relative_to(APITEST_ROOT)}")
        modules.append(module)
    return sorted(modules, key=lambda module: (module.ORDER, module.PATH, module.METHOD))


def _check_environment(context: TestContext) -> None:
    """在业务用例前同时确认代理入口与 MySQL 可访问。"""
    health = context.http.request("GET", "/health")
    require_equal(health.status, 200, "健康检查应返回 200")
    context.db.ping()


def main() -> int:
    """执行所有脚本和逆序清理，并用退出码表示整体结果。"""
    try:
        config = TestConfig.load()
        context = TestContext(config)
        cases = _load_cases()
    except Exception as exception:  # noqa: BLE001 - 启动错误需转换为稳定 CLI 输出
        print(f"[FAIL] 接口测试无法启动：{exception}")
        return 2

    results: list[CaseResult] = []
    start = perf_counter()
    try:
        _check_environment(context)
        results.append(CaseResult("环境检查", "GET", "/health + MySQL", True,
                                  round((perf_counter() - start) * 1000), "HTTP 与数据库连接正常"))
        blocked = False
        for case in cases:
            if blocked:
                result = CaseResult(case.NAME, case.METHOD, case.PATH, False, 0, "跳过：前置接口用例失败")
                results.append(result)
                print(f"[SKIP] {result.method} {result.path} - {result.detail}")
                continue
            case_start = perf_counter()
            try:
                case.run(context)
                result = CaseResult(case.NAME, case.METHOD, case.PATH, True,
                                    round((perf_counter() - case_start) * 1000),
                                    "关键响应字段与数据库状态一致")
            except Exception as exception:  # noqa: BLE001 - 用例失败需进入报告并继续清理
                result = CaseResult(case.NAME, case.METHOD, case.PATH, False,
                                    round((perf_counter() - case_start) * 1000),
                                    context.sanitize(str(exception)))
            results.append(result)
            print(f"[{'PASS' if result.passed else 'FAIL'}] {result.method} {result.path} - {result.detail}")
            if not result.passed:
                blocked = True
    except Exception as exception:  # noqa: BLE001 - 环境失败也必须生成报告
        results.append(CaseResult("环境检查", "GET", "/health + MySQL", False,
                                  round((perf_counter() - start) * 1000),
                                  context.sanitize(str(exception))))
        print(f"[FAIL] 环境检查 - {results[-1].detail}")
        for case in cases:
            results.append(CaseResult(case.NAME, case.METHOD, case.PATH, False, 0,
                                      "跳过：环境检查失败"))

    cleanups = context.run_cleanups()
    for item in cleanups:
        print(f"[{'PASS' if item.passed else 'FAIL'}] CLEANUP {item.name} - {item.detail}")
    report = write_report(APITEST_ROOT / "reports", context.run_id, config.base_url, results, cleanups)
    print(f"HTML 报告：{report.relative_to(config.project_root)}")
    return 0 if results and all(item.passed for item in results) and all(item.passed for item in cleanups) else 1


if __name__ == "__main__":
    raise SystemExit(main())
