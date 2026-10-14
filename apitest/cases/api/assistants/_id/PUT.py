"""验证教师更新提示词和绑定，并确认学生写入返回 403。"""

from common.assertions import require, require_equal, require_mapping

ORDER = 40
NAME = "更新智能体提示词与绑定"
METHOD = "PUT"
PATH = "/api/assistants/{id}"


def run(context) -> None:
    """临时更新本班智能体，测试结束时通过同一 API 恢复原配置。"""
    teacher = context.state["teacher"]
    student = context.state["student"]
    class_id = teacher["user"]["classId"]
    assistant = context.db.query_one(
        """
        SELECT id, name, prompt_text, enabled
        FROM assistants
        WHERE class_id = %s AND enabled = TRUE
        ORDER BY id
        LIMIT 1
        """,
        (class_id,),
    )
    require(assistant is not None, "测试班级至少需要一个已启用智能体")
    assistant_id = assistant["id"]
    original_skill_ids = [row["skill_id"] for row in context.db.query_all(
        "SELECT skill_id FROM assistant_skills WHERE assistant_id = %s ORDER BY skill_id", (assistant_id,)
    )]
    original_material_ids = [row["material_id"] for row in context.db.query_all(
        "SELECT material_id FROM assistant_materials WHERE assistant_id = %s ORDER BY material_id", (assistant_id,)
    )]
    original = {
        "name": assistant["name"],
        "promptText": assistant["prompt_text"] or "",
        "enabled": bool(assistant["enabled"]),
        "skillIds": original_skill_ids,
        "materialIds": original_material_ids,
    }

    def restore_assistant() -> None:
        response = context.http.request(
            METHOD,
            f"/api/assistants/{assistant_id}",
            token=teacher["token"],
            json_body=original,
        )
        require_equal(response.status, 200, "原智能体配置恢复应成功")

    context.add_cleanup("恢复原智能体配置", restore_assistant)

    skill_id = context.state["skill"]["id"]
    material_id = context.state["ready_material_id"]
    prompt_text = f"接口回归 {context.run_id}：请以启发式问题引导学生。"
    updated = {
        "name": assistant["name"],
        "promptText": prompt_text,
        "enabled": True,
        # 本轮仅绑定唯一测试技能和材料，避免既有配置影响路由同分或引用来源。
        "skillIds": [skill_id],
        "materialIds": [material_id],
    }
    forbidden = context.http.request(
        METHOD,
        f"/api/assistants/{assistant_id}",
        token=student["token"],
        json_body=updated,
    )
    require_equal(forbidden.status, 403, "学生更新智能体必须被服务端拒绝")

    response = context.http.request(
        METHOD,
        f"/api/assistants/{assistant_id}",
        token=teacher["token"],
        json_body=updated,
    )
    require_equal(response.status, 200, "教师更新智能体应成功")
    payload = require_mapping(response.json(), "智能体更新响应应为 JSON 对象")
    require_equal(payload.get("id"), assistant_id, "响应智能体 ID 不应改变")
    require_equal(payload.get("promptText"), prompt_text, "响应应包含更新后的角色提示词")
    require_equal(set(payload.get("skillIds") or []), set(updated["skillIds"]), "技能绑定应完整替换")
    require_equal(set(payload.get("materialIds") or []), set(updated["materialIds"]), "材料绑定应完整替换")

    stored = context.db.query_one(
        "SELECT class_id, prompt_text, enabled FROM assistants WHERE id = %s", (assistant_id,)
    )
    require(stored is not None, "更新后的智能体应存在于 MySQL")
    require_equal(stored["class_id"], class_id, "智能体班级归属不得改变")
    require_equal(stored["prompt_text"], prompt_text, "MySQL 提示词应与响应一致")
    require(bool(stored["enabled"]), "测试智能体应保持启用")
    stored_skill_ids = {row["skill_id"] for row in context.db.query_all(
        "SELECT skill_id FROM assistant_skills WHERE assistant_id = %s", (assistant_id,)
    )}
    stored_material_ids = {row["material_id"] for row in context.db.query_all(
        "SELECT material_id FROM assistant_materials WHERE assistant_id = %s", (assistant_id,)
    )}
    require_equal(stored_skill_ids, set(updated["skillIds"]), "MySQL 技能绑定应与响应一致")
    require_equal(stored_material_ids, set(updated["materialIds"]), "MySQL 材料绑定应与响应一致")
    context.state["assistant"] = {"id": assistant_id, "name": assistant["name"]}
