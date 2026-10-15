"""验证流式会话事件、引用和最终消息持久化。"""

import json
import re

from common.assertions import require, require_equal, require_list, require_mapping

ORDER = 60
NAME = "发送流式会话消息"
METHOD = "POST"
PATH = "/api/conversations/{id}/messages"


def run(context) -> None:
    """解析 meta/delta/done，并将拼接正文与 MySQL 助手消息对照。"""
    student = context.state["student"]
    conversation_id = context.state["conversation"]["id"]
    skill = context.state["skill"]
    material_id = context.state["ready_material_id"]
    seed_text = context.state["retrieval_query"][:100]
    question = f"接口回归 {context.run_id}：请给出检查步骤，并根据这段材料提示下一步方向：{seed_text}"
    response = context.http.stream_json_sse(
        METHOD,
        f"/api/conversations/{conversation_id}/messages",
        token=student["token"],
        json_body={"content": question},
    )
    require_equal(response.status, 200, "流式消息接口应返回 200")
    require(response.headers.get("content-type", "").startswith("text/event-stream"),
            "流式消息 Content-Type 应为 text/event-stream")
    require("no-cache" in response.headers.get("cache-control", ""), "SSE 响应应禁用缓存")
    require_equal(response.headers.get("x-accel-buffering"), "no", "SSE 响应应要求 Nginx 禁用缓冲")
    require(response.events, "流式接口应至少返回一个 SSE 事件")
    names = [event.name for event in response.events]
    require_equal(names[0], "meta", "第一条 SSE 事件必须是 meta")
    require_equal(names[-1], "done", "正常流式回答必须以 done 结束")
    require("error" not in names, "正常流式回答不得包含 error 事件")

    meta = require_mapping(response.events[0].data, "meta 数据应为 JSON 对象")
    require_equal(meta.get("status"), "ready", "meta 状态应为 ready")
    require_equal(meta.get("skillName"), skill["name"], "问题应命中本轮创建的解题引导技能")
    citations = require_list(meta.get("citations"), "meta 应包含引用数组")
    require(citations, "绑定 READY 材料且使用其正文提问时应返回至少一条引用")
    for number, raw_citation in enumerate(citations, start=1):
        citation = require_mapping(raw_citation, "每条引用应为 JSON 对象")
        require_equal(citation.get("number"), number, "引用编号应从 1 连续排列")
        require_equal(citation.get("materialId"), material_id, "引用必须来自绑定的测试材料")

    delta_texts = []
    for event in response.events[1:-1]:
        require_equal(event.name, "delta", "meta 与 done 之间只能出现 delta")
        data = require_mapping(event.data, "delta 数据应为 JSON 对象")
        text = data.get("text")
        require(isinstance(text, str) and text, "每条 delta 应只包含非空新增文本")
        delta_texts.append(text)
    answer = "".join(delta_texts)
    require(answer.strip(), "流式回答应至少包含一条非空 delta")
    done = require_mapping(response.events[-1].data, "done 数据应为 JSON 对象")
    message_id = done.get("messageId")
    require(isinstance(message_id, int) and message_id > 0, "done 应包含已提交的助手消息 ID")

    rows = context.db.query_all(
        """
        SELECT id, role, content, skill_name, citations_json, status
        FROM messages
        WHERE conversation_id = %s
        ORDER BY id
        """,
        (conversation_id,),
    )
    require_equal(len(rows), 2, "首轮成功后应持久化一条用户消息和一条助手消息")
    user_message, assistant_message = rows
    require_equal(user_message["role"], "USER", "第一条消息应为用户消息")
    require_equal(user_message["content"], question, "MySQL 用户消息应与请求一致")
    require_equal(user_message["status"], "COMPLETED", "用户消息状态应为 COMPLETED")
    require_equal(assistant_message["id"], message_id, "done ID 应指向已提交助手消息")
    require_equal(assistant_message["role"], "ASSISTANT", "第二条消息应为助手消息")
    # 完成阶段允许过滤、重编号或补齐引用标记，但不得改写引用之外的回答正文。
    streamed_plain_text = re.sub(r"\s*\[\d+]", "", answer).strip()
    stored_plain_text = re.sub(r"\s*\[\d+]", "", assistant_message["content"]).strip()
    require_equal(stored_plain_text, streamed_plain_text, "MySQL 助手正文除引用标记外应等于全部 delta 拼接结果")
    require_equal(assistant_message["skill_name"], skill["name"], "MySQL 应记录命中技能")
    require_equal(assistant_message["status"], "COMPLETED", "助手消息状态应为 COMPLETED")
    stored_citations = assistant_message["citations_json"]
    if isinstance(stored_citations, str):
        stored_citations = json.loads(stored_citations)
    require(isinstance(stored_citations, list), "MySQL 引用快照应为数组")
    candidate_keys = {(item.get("materialId"), item.get("chunkId")) for item in citations}
    for number, raw_citation in enumerate(stored_citations, start=1):
        citation = require_mapping(raw_citation, "MySQL 每条最终引用应为 JSON 对象")
        require_equal(citation.get("number"), number, "MySQL 最终引用编号应从 1 连续排列")
        require((citation.get("materialId"), citation.get("chunkId")) in candidate_keys,
                "MySQL 最终引用必须来自本轮 meta 候选")
    referenced_numbers = {int(value) for value in re.findall(r"\[(\d+)]", assistant_message["content"])}
    require_equal(referenced_numbers, set(range(1, len(stored_citations) + 1)),
                  "MySQL 最终正文引用编号应与最终引用快照一致")

    conversation = context.db.query_one(
        "SELECT user_id, class_id, title FROM conversations WHERE id = %s", (conversation_id,)
    )
    require(conversation is not None, "流式完成后会话应继续存在")
    require_equal(conversation["user_id"], student["user"]["id"], "会话用户归属不得改变")
    require_equal(conversation["class_id"], student["user"]["classId"], "会话班级归属不得改变")
    require_equal(conversation["title"], question[:24], "首轮问题应更新为前 24 个 Unicode 字符的标题")
