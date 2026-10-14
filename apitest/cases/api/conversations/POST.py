"""验证学生创建私有会话并核对数据库归属。"""

from common.assertions import require, require_equal, require_mapping

ORDER = 50
NAME = "创建学生私有会话"
METHOD = "POST"
PATH = "/api/conversations"


def run(context) -> None:
    """使用当前已启用智能体创建会话，并登记精确 ID 收尾。"""
    student = context.state["student"]
    assistant_id = context.state["assistant"]["id"]
    response = context.http.request(
        METHOD,
        PATH,
        token=student["token"],
        json_body={"assistantId": assistant_id},
    )
    require_equal(response.status, 201, "学生创建有效会话应返回 201")
    payload = require_mapping(response.json(), "创建会话响应应为 JSON 对象")
    conversation_id = payload.get("id")
    require(isinstance(conversation_id, int) and conversation_id > 0, "创建会话响应应包含数字 ID")
    require_equal(payload.get("title"), "新的提问", "新会话应使用初始标题")
    require_equal(payload.get("assistantId"), assistant_id, "会话应锁定所选智能体")
    require_equal(payload.get("messages"), [], "新会话消息列表应为空")

    stored = context.db.query_one(
        """
        SELECT id, class_id, user_id, assistant_id, title
        FROM conversations
        WHERE id = %s AND user_id = %s AND class_id = %s
        """,
        (conversation_id, student["user"]["id"], student["user"]["classId"]),
    )
    require(stored is not None, "会话应写入当前学生和班级")
    require_equal(stored["assistant_id"], assistant_id, "MySQL 会话智能体应与响应一致")
    require_equal(stored["title"], payload["title"], "MySQL 会话标题应与响应一致")

    def cleanup_conversation() -> None:
        row = context.db.query_one(
            """
            SELECT c.id, c.title,
                   EXISTS(SELECT 1 FROM messages m WHERE m.conversation_id = c.id AND m.content LIKE %s) AS marked
            FROM conversations c
            WHERE c.id = %s AND c.user_id = %s AND c.class_id = %s AND c.assistant_id = %s
            """,
            (f"%{context.run_id}%", conversation_id, student["user"]["id"],
             student["user"]["classId"], assistant_id),
        )
        if row is None:
            return
        require(row["title"] == "新的提问" or bool(row["marked"]),
                "会话不再具有初始标题且没有运行标识，为避免误删而停止清理")
        deleted = context.db.cleanup_delete(
            """
            DELETE FROM conversations
            WHERE id = %s AND user_id = %s AND class_id = %s AND assistant_id = %s
            """,
            (conversation_id, student["user"]["id"], student["user"]["classId"], assistant_id),
        )
        require_equal(deleted, 1, "应只删除本轮创建的一个会话")

    context.add_cleanup("删除本轮测试会话及级联消息", cleanup_conversation)
    context.state["conversation"] = {"id": conversation_id}
