"""验证 MCP 服务登记、权限、凭据保护、连接发现与错误 Token。"""

from common.assertions import require, require_equal, require_list, require_mapping

ORDER = 25
NAME = "登记并连接 MCP 服务"
METHOD = "POST"
PATH = "/api/mcp/servers"


def run(context) -> None:
    teacher = context.state["teacher"]
    student = context.state["student"]
    class_id = teacher["user"]["classId"]
    suffix = context.run_id[-6:]
    server_name = f"mcp-{suffix}"
    mcp_root = context.config.mcp_base_url.rstrip("/")
    if mcp_root.endswith("/mcp"):
        mcp_root = mcp_root[:-4]
    catalog_a_url = f"{mcp_root}/catalog-a"
    catalog_b_url = f"{mcp_root}/catalog-b"
    payload = {
        "name": server_name,
        "baseUrl": catalog_a_url,
        "token": context.config.mcp_auth_token,
    }

    forbidden = context.http.request(METHOD, PATH, token=student["token"], json_body=payload)
    require_equal(forbidden.status, 403, "学生不得登记 MCP 服务")

    created = context.http.request(METHOD, PATH, token=teacher["token"], json_body=payload)
    require_equal(created.status, 201, "教师登记 MCP 服务应返回 201")
    body = require_mapping(created.json(), "MCP 服务响应应为 JSON 对象")
    server_id = body.get("id")
    require(isinstance(server_id, int) and server_id > 0, "MCP 服务响应应包含数字 ID")
    require_equal(body.get("name"), server_name, "服务名应保持不变")
    require_equal(body.get("connected"), False, "新服务应处于断开状态")
    require_equal(body.get("hasAuth"), True, "响应只能使用 hasAuth 表示凭据存在")
    require("token" not in body and "authTokenCiphertext" not in body, "响应不得暴露 Token 或密文")

    stored = context.db.query_one(
        "SELECT class_id, auth_token_ciphertext, connected, config_version FROM mcp_servers WHERE id = %s AND class_id = %s",
        (server_id, class_id),
    )
    require(stored is not None, "MCP 服务应写入当前班级")
    require(stored["auth_token_ciphertext"] != context.config.mcp_auth_token, "数据库不得保存明文 Token")
    require(str(stored["auth_token_ciphertext"]).startswith("v1:"), "Token 密文应使用版本化信封")

    duplicate = context.http.request(METHOD, PATH, token=teacher["token"], json_body=payload)
    require_equal(duplicate.status, 400, "班内重名服务应返回 400")

    retained = context.http.request(
        "PUT", f"{PATH}/{server_id}", token=teacher["token"],
        json_body={"name": server_name, "baseUrl": catalog_a_url, "token": ""},
    )
    require_equal(retained.status, 200, "教师应能用空 Token 更新服务并保留原凭据")
    retained_body = require_mapping(retained.json(), "服务更新响应应为对象")
    require_equal(retained_body.get("hasAuth"), True, "空 Token 更新后仍应报告已有凭据")
    require_equal(retained_body.get("connected"), False, "配置更新后服务应保持断开")
    retained_row = context.db.query_one(
        "SELECT auth_token_ciphertext, config_version FROM mcp_servers WHERE id = %s AND class_id = %s",
        (server_id, class_id),
    )
    require(retained_row is not None, "更新后的 MCP 服务必须仍然存在")
    require(retained_row["auth_token_ciphertext"] == stored["auth_token_ciphertext"],
            "空 Token 更新不得替换已有密文")
    require_equal(retained_row["config_version"], stored["config_version"] + 1,
                  "服务配置更新必须递增版本")

    bad_name = f"bad-{suffix}"
    bad_token = f"wrong-{suffix}"
    context.add_secret(bad_token)
    bad = context.http.request(METHOD, PATH, token=teacher["token"], json_body={
        "name": bad_name, "baseUrl": catalog_a_url, "token": bad_token,
    })
    require_equal(bad.status, 201, "错误远端 Token 仍可登记，但不能连接")
    bad_id = require_mapping(bad.json(), "错误凭据服务响应应为对象").get("id")
    failed_connect = context.http.request(
        "POST", f"/api/mcp/servers/{bad_id}/connect", token=teacher["token"]
    )
    require_equal(failed_connect.status, 502, "错误 MCP Token 连接应映射为 502")
    deleted_bad = context.http.request("DELETE", f"/api/mcp/servers/{bad_id}", token=teacher["token"])
    require_equal(deleted_bad.status, 204, "没有审计的错误凭据服务应可删除")

    connected = context.http.request(
        "POST", f"/api/mcp/servers/{server_id}/connect", token=teacher["token"]
    )
    require_equal(connected.status, 200, "正确凭据应完成 MCP 连接发现")
    connected_body = require_mapping(connected.json(), "连接响应应为对象")
    require_equal(connected_body.get("connected"), True, "连接后状态应为已连接")
    require(connected_body.get("toolCount", 0) >= 2, "演示 MCP 应至少发现两个工具")

    first_tools = context.http.request("GET", f"/api/mcp/servers/{server_id}/tools", token=teacher["token"])
    require_equal(first_tools.status, 200, "首次连接后应能读取完整工具缓存")
    first_tool_list = require_list(first_tools.json(), "首次工具响应应为数组")
    require_equal({item.get("name") for item in first_tool_list}, {"formula_hint", "unit_convert"},
                  "完整目录应发现两个确定性工具")

    switched = context.http.request(
        "PUT", f"{PATH}/{server_id}", token=teacher["token"],
        json_body={"name": server_name, "baseUrl": catalog_b_url, "token": ""},
    )
    require_equal(switched.status, 200, "同一服务应能切换到缩减测试目录")
    require_equal(require_mapping(switched.json(), "目录切换响应应为对象").get("connected"), False,
                  "修改目录地址必须断开并清空旧缓存")

    repeated = context.http.request(
        "POST", f"/api/mcp/servers/{server_id}/connect", token=teacher["token"]
    )
    require_equal(repeated.status, 200, "重复连接应完整刷新缓存而不是产生重复记录")
    tools = context.http.request("GET", f"/api/mcp/servers/{server_id}/tools", token=teacher["token"])
    require_equal(tools.status, 200, "教师应能查看已发现工具")
    tool_list = require_list(tools.json(), "工具响应应为数组")
    require_equal({item.get("name") for item in tool_list}, {"formula_hint"},
                  "第二次连接必须以缩减目录整体替换旧缓存，不能保留并集")
    require(all(isinstance(item.get("inputSchema"), dict) for item in tool_list),
            "每个已发现工具都应返回输入 Schema")
    cache_counts = context.db.query_one(
        "SELECT COUNT(*) AS total, COUNT(DISTINCT name) AS distinct_total FROM mcp_tools WHERE server_id = %s",
        (server_id,),
    )
    require(cache_counts is not None and cache_counts["total"] == 1
            and cache_counts["distinct_total"] == 1,
            "重复连接后的缓存只能包含一个不重复的最新工具")
    student_tools = context.http.request("GET", f"/api/mcp/servers/{server_id}/tools", token=student["token"])
    require_equal(student_tools.status, 403, "学生不得查看 MCP 工具缓存")

    # 使用演示数据中的 B 班教师验证已知 ID 与不存在 ID 对外完全同形。
    cross_login = context.http.request(
        "POST", "/api/auth/login",
        json_body={"username": "teacher-b", "password": context.config.teacher_password},
    )
    require_equal(cross_login.status, 200, "B 班教师登录应成功")
    cross_payload = require_mapping(cross_login.json(), "B 班教师登录响应应为对象")
    cross_token = cross_payload.get("accessToken")
    cross_user = require_mapping(cross_payload.get("user"), "B 班教师登录响应应包含用户")
    require(isinstance(cross_token, str) and cross_token, "B 班教师登录响应应包含 Token")
    require_equal(cross_user.get("role"), "teacher", "跨班验证账号必须是教师")
    require(cross_user.get("classId") != class_id, "跨班验证账号必须属于其他班级")
    context.add_secret(cross_token)

    before_server = context.db.query_one(
        "SELECT name, base_url, auth_token_ciphertext, config_version, connected FROM mcp_servers WHERE id = %s",
        (server_id,),
    )
    before_tools = context.db.query_one(
        "SELECT COUNT(*) AS total FROM mcp_tools WHERE server_id = %s", (server_id,)
    )
    maximum = context.db.query_one("SELECT COALESCE(MAX(id), 0) AS max_id FROM mcp_servers")
    missing_id = int(maximum["max_id"]) + 1000
    cross_list = context.http.request("GET", PATH, token=cross_token)
    require_equal(cross_list.status, 200, "其他班教师仍可列出自己的 MCP 服务")
    require(all(item.get("id") != server_id for item in require_list(cross_list.json(), "跨班服务列表应为数组")),
            "其他班服务列表不得泄露目标服务")

    update_payload = {
        "name": f"cross-{suffix}",
        "baseUrl": catalog_b_url,
        "token": "",
    }
    operations = [
        ("PUT", f"/api/mcp/servers/{server_id}", update_payload,
         f"/api/mcp/servers/{missing_id}"),
        ("POST", f"/api/mcp/servers/{server_id}/connect", None,
         f"/api/mcp/servers/{missing_id}/connect"),
        ("POST", f"/api/mcp/servers/{server_id}/disconnect", None,
         f"/api/mcp/servers/{missing_id}/disconnect"),
        ("GET", f"/api/mcp/servers/{server_id}/tools", None,
         f"/api/mcp/servers/{missing_id}/tools"),
        ("DELETE", f"/api/mcp/servers/{server_id}", None,
         f"/api/mcp/servers/{missing_id}"),
    ]
    for method, known_path, body_value, missing_path in operations:
        known = context.http.request(method, known_path, token=cross_token, json_body=body_value)
        missing = context.http.request(method, missing_path, token=cross_token, json_body=body_value)
        require_equal(known.status, 404, f"其他班教师调用 {method} 必须得到 404")
        require_equal(known.status, missing.status, f"跨班与不存在资源的 {method} 状态必须一致")

    after_server = context.db.query_one(
        "SELECT name, base_url, auth_token_ciphertext, config_version, connected FROM mcp_servers WHERE id = %s",
        (server_id,),
    )
    after_tools = context.db.query_one(
        "SELECT COUNT(*) AS total FROM mcp_tools WHERE server_id = %s", (server_id,)
    )
    require(before_server == after_server, "跨班请求不得改变服务配置、Token 密文或连接状态")
    require(before_tools == after_tools, "跨班请求不得改变工具缓存")

    def cleanup_mcp() -> None:
        row = context.db.query_one(
            "SELECT id FROM mcp_servers WHERE id = %s AND class_id = %s AND name = %s",
            (server_id, class_id, server_name),
        )
        if row is None:
            return
        audit = context.db.query_one(
            "SELECT COUNT(*) AS total FROM mcp_tool_calls WHERE server_id = %s AND class_id = %s",
            (server_id, class_id),
        )
        if audit and audit["total"]:
            # 审计是课程要求保留的历史，清理器只能断开服务，不能绕过限制外键删记录。
            disconnected = context.http.request(
                "POST", f"/api/mcp/servers/{server_id}/disconnect", token=teacher["token"]
            )
            require_equal(disconnected.status, 200, "有审计的测试服务应保留并保持断开")
            return
        deleted = context.http.request(
            "DELETE", f"/api/mcp/servers/{server_id}", token=teacher["token"]
        )
        require_equal(deleted.status, 204, "无审计时只应删除本轮创建的 MCP 服务")

    context.add_cleanup("删除无审计服务或断开并保留本轮审计", cleanup_mcp)
    context.state["mcp_server"] = {"id": server_id, "name": server_name}
