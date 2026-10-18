"""验证 MCP 审计读取、学生越权拒绝和有审计服务的删除冲突。"""

from common.assertions import require, require_equal, require_list, require_mapping

ORDER = 70
NAME = "读取 MCP 审计并保护服务"
METHOD = "GET"
PATH = "/api/mcp/tool-calls"


def run(context) -> None:
    teacher = context.state["teacher"]
    student = context.state["student"]
    server = context.state["mcp_server"]
    conversation_id = context.state["conversation"]["id"]

    forbidden = context.http.request(METHOD, PATH, token=student["token"])
    require_equal(forbidden.status, 403, "学生不得读取 MCP 审计")

    response = context.http.request(METHOD, f"{PATH}?limit=100", token=teacher["token"])
    require_equal(response.status, 200, "教师应能读取本班 MCP 审计")
    rows = require_list(response.json(), "审计响应应为数组")
    matching = [row for row in rows
                if row.get("serverId") == server["id"] and row.get("conversationId") == conversation_id]
    require(len(matching) >= 2, "正常调用和注入边界调用都应产生可读取的审计")
    ok_audit = next((row for row in matching if row.get("status") == "ok"), None)
    denied_audit = next((row for row in matching if row.get("status") == "denied"), None)
    require(ok_audit is not None, "正常工具调用的审计状态应为 ok")
    require(denied_audit is not None, "注入边界调用的审计状态应为 denied")
    for audit in (require_mapping(ok_audit, "成功审计应为对象"),
                  require_mapping(denied_audit, "拒绝审计应为对象")):
        require_equal(audit.get("toolRef"), f"formula_hint@{server['name']}", "审计工具引用应完整")
        require(isinstance(audit.get("detail"), str) and audit.get("detail"), "审计应包含脱敏摘要")
        require("token" not in audit and "baseUrl" not in audit and "arguments" not in audit,
                "审计响应不得包含凭据、地址或完整参数")
        require("ignore previous instructions" not in audit["detail"].lower(),
                "审计不得包含注入探针危险原文")
    require(denied_audit.get("detail") == "工具结果已被安全过滤", "拒绝审计应使用固定安全提示")

    invalid_limit = context.http.request(METHOD, f"{PATH}?limit=101", token=teacher["token"])
    require_equal(invalid_limit.status, 400, "超过审计上限应返回 400")
    conflict = context.http.request(
        "DELETE", f"/api/mcp/servers/{server['id']}", token=teacher["token"]
    )
    require_equal(conflict.status, 409, "存在审计的 MCP 服务不得删除")
    still_there = context.http.request("GET", "/api/mcp/servers", token=teacher["token"])
    require_equal(still_there.status, 200, "删除冲突后服务列表仍应可读")
    require(any(item.get("id") == server["id"] for item in require_list(still_there.json(), "服务列表应为数组")),
            "删除冲突不得乐观移除服务")

    disconnected = context.http.request(
        "POST", f"/api/mcp/servers/{server['id']}/disconnect", token=teacher["token"]
    )
    require_equal(disconnected.status, 200, "有审计服务仍应允许断开")
    require_equal(require_mapping(disconnected.json(), "断开响应应为对象").get("connected"), False,
                  "断开后状态应为 false")
