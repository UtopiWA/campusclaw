"""验证教师创建技能、学生写入拒绝及技能数据库字段。"""

from common.assertions import require, require_equal, require_mapping

ORDER = 30
NAME = "创建解题引导技能"
METHOD = "POST"
PATH = "/api/skills"


def run(context) -> None:
    """创建正常调用与注入探针技能，并登记严格限定的收尾删除。"""
    teacher = context.state["teacher"]
    student = context.state["student"]
    class_id = teacher["user"]["classId"]
    skill_name = f"解题引导-{context.run_id}"
    source_text = (
        f"名称：{skill_name}\n"
        "适用条件：接口回归,检查步骤\n"
        "资料：\n"
        f"工具：knowledge_search,formula_hint@{context.state['mcp_server']['name']}\n"
        "启用：是\n\n"
        "这是接口回归专用技能。只指出下一步应检查的方向，不给出最终答案。"
    )
    forbidden = context.http.request(
        METHOD, PATH, token=student["token"], json_body={"sourceText": source_text}
    )
    require_equal(forbidden.status, 403, "学生创建技能必须被服务端拒绝")

    response = context.http.request(
        METHOD, PATH, token=teacher["token"], json_body={"sourceText": source_text}
    )
    require_equal(response.status, 201, "教师创建合法技能应返回 201")
    payload = require_mapping(response.json(), "创建技能响应应为 JSON 对象")
    skill_id = payload.get("id")
    require(isinstance(skill_id, int) and skill_id > 0, "创建技能响应应包含数字 ID")
    require_equal(payload.get("name"), skill_name, "响应技能名称应保留运行标识")
    require_equal(payload.get("triggerText"), "接口回归,检查步骤", "响应应包含解析后的触发文本")
    expected_tools = ["knowledge_search", f"formula_hint@{context.state['mcp_server']['name']}"]
    require_equal(payload.get("toolRefs"), expected_tools, "技能工具引用应保留配置顺序")
    require_equal(payload.get("enabled"), True, "新技能应处于启用状态")
    require_equal(payload.get("sourceText"), source_text, "教师响应应保留技能原文")

    stored = context.db.query_one(
        """
        SELECT id, class_id, name, trigger_text, material_scope, tool_refs, enabled,
               instructions, source_text
        FROM skills WHERE id = %s AND class_id = %s
        """,
        (skill_id, class_id),
    )
    require(stored is not None, "创建的技能应写入教师当前班级")
    require_equal(stored["name"], skill_name, "MySQL 技能名称应与响应一致")
    require_equal(stored["trigger_text"], payload["triggerText"], "MySQL 触发文本应与响应一致")
    require_equal(stored["tool_refs"], ",".join(expected_tools), "MySQL 工具引用应使用规范化逗号存储")
    require_equal(stored["source_text"], source_text, "MySQL 应保存完整技能原文")

    injection_name = f"注入过滤-{context.run_id}"
    injection_source = (
        f"名称：{injection_name}\n"
        "适用条件：__campusclaw_injection_probe__\n"
        "资料：\n"
        f"工具：formula_hint@{context.state['mcp_server']['name']}\n"
        "启用：是\n\n"
        "仅用于接口回归验证不可信工具结果不会进入模型上下文。"
    )
    injection_response = context.http.request(
        METHOD, PATH, token=teacher["token"], json_body={"sourceText": injection_source}
    )
    require_equal(injection_response.status, 201, "教师应能创建注入边界测试技能")
    injection_payload = require_mapping(injection_response.json(), "注入边界技能响应应为对象")
    injection_id = injection_payload.get("id")
    require(isinstance(injection_id, int) and injection_id > 0, "注入边界技能应包含数字 ID")
    require_equal(injection_payload.get("toolRefs"), [f"formula_hint@{context.state['mcp_server']['name']}"],
                  "注入边界技能只能引用测试公式工具")

    def cleanup_skill() -> None:
        bindings = context.db.query_one(
            "SELECT COUNT(*) AS total FROM assistant_skills WHERE skill_id IN (%s, %s)",
            (skill_id, injection_id),
        )
        require(bindings is not None and bindings["total"] == 0,
                "测试技能仍被智能体绑定，为避免扩大影响不执行删除")
        rows = context.db.query_all(
            "SELECT id FROM skills WHERE class_id = %s AND ((id = %s AND name = %s) OR (id = %s AND name = %s))",
            (class_id, skill_id, skill_name, injection_id, injection_name),
        )
        if rows:
            deleted = context.db.cleanup_delete(
                "DELETE FROM skills WHERE class_id = %s AND ((id = %s AND name = %s) OR (id = %s AND name = %s))",
                (class_id, skill_id, skill_name, injection_id, injection_name),
            )
            require_equal(deleted, len(rows), "应只删除本轮创建且仍匹配名称的测试技能")

    context.add_cleanup("删除本轮测试技能", cleanup_skill)
    context.state["skill"] = {"id": skill_id, "name": skill_name, "sourceText": source_text}
    context.state["injection_skill"] = {
        "id": injection_id,
        "name": injection_name,
        "sourceText": injection_source,
    }
