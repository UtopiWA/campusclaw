"""生成不依赖第三方模板库的脱敏 HTML 报告。"""

from __future__ import annotations

from datetime import datetime, timezone
from html import escape
from pathlib import Path

from common.models import CaseResult, CleanupResult


def write_report(report_dir: Path, run_id: str, base_url: str,
                 results: list[CaseResult], cleanups: list[CleanupResult]) -> Path:
    """写入单文件报告；所有动态内容都先进行 HTML 转义。"""
    report_dir.mkdir(parents=True, exist_ok=True)
    path = report_dir / f"apitest-{run_id}.html"
    passed = sum(1 for result in results if result.passed)
    rows = "".join(
        "<tr>"
        f"<td>{escape(result.name)}</td><td><code>{escape(result.method)}</code></td>"
        f"<td><code>{escape(result.path)}</code></td>"
        f"<td class={'ok' if result.passed else 'bad'}>{'通过' if result.passed else '失败'}</td>"
        f"<td>{result.duration_ms}</td><td>{escape(result.detail)}</td></tr>"
        for result in results
    )
    cleanup_rows = "".join(
        "<tr>"
        f"<td>{escape(item.name)}</td>"
        f"<td class={'ok' if item.passed else 'bad'}>{'通过' if item.passed else '失败'}</td>"
        f"<td>{escape(item.detail)}</td></tr>"
        for item in cleanups
    ) or '<tr><td colspan="3">无清理操作</td></tr>'
    generated = datetime.now(timezone.utc).isoformat()
    document = f"""<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>CampusClaw 接口测试 {escape(run_id)}</title>
<style>
body{{font-family:system-ui,sans-serif;margin:32px;color:#1c2530}}table{{border-collapse:collapse;width:100%;margin:16px 0 32px}}
th,td{{border:1px solid #d9dde3;padding:8px;text-align:left;vertical-align:top}}th{{background:#f5f6f8}}code{{word-break:break-all}}
.ok{{color:#16794b;font-weight:700}}.bad{{color:#b42318;font-weight:700}}.summary{{padding:12px;background:#f5f6f8;border-radius:8px}}
</style></head><body>
<h1>CampusClaw 接口回归报告</h1>
<p class="summary">运行标识：<code>{escape(run_id)}</code>　服务：<code>{escape(base_url)}</code>　结果：{passed}/{len(results)} 通过　生成时间：{escape(generated)}</p>
<h2>接口用例</h2><table><thead><tr><th>名称</th><th>方法</th><th>路径</th><th>结果</th><th>耗时(ms)</th><th>断言</th></tr></thead><tbody>{rows}</tbody></table>
<h2>清理与恢复</h2><table><thead><tr><th>操作</th><th>结果</th><th>说明</th></tr></thead><tbody>{cleanup_rows}</tbody></table>
</body></html>"""
    path.write_text(document, encoding="utf-8")
    return path
