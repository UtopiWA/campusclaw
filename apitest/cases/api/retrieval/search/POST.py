"""验证本班关键字检索，并逐条核对材料与切片归属。"""

from common.assertions import require, require_equal, require_list, require_mapping

ORDER = 20
NAME = "本班知识检索"
METHOD = "POST"
PATH = "/api/retrieval/search"


def run(context) -> None:
    """从 READY 切片提取查询词，避免依赖写死的材料或切片 ID。"""
    student = context.state["student"]
    class_id = student["user"]["classId"]
    seed = context.db.query_one(
        """
        SELECT kc.id AS chunk_id, kc.material_id, kc.chunk_text, m.title
        FROM knowledge_chunks kc
        JOIN materials m ON m.id = kc.material_id
        WHERE kc.class_id = %s
          AND kc.index_status = 'READY'
          AND m.class_id = %s
          AND m.index_status = 'READY'
          AND CHAR_LENGTH(TRIM(kc.chunk_text)) >= 4
        ORDER BY kc.id
        LIMIT 1
        """,
        (class_id, class_id),
    )
    require(seed is not None, "当前测试班级没有 READY 知识切片，请先上传并等待一份讲义索引完成")
    query = str(seed["chunk_text"]).strip()[:80]
    response = context.http.request(
        METHOD,
        PATH,
        token=student["token"],
        json_body={"query": query, "mode": "keyword", "limit": 5},
    )
    require_equal(response.status, 200, "本班关键字检索应成功")
    payload = require_mapping(response.json(), "检索响应应为 JSON 对象")
    require_equal(payload.get("mode"), "keyword", "检索响应应保留 keyword 模式")
    hits = require_list(payload.get("hits"), "检索响应应包含 hits 数组")
    require(hits, "从 READY 切片提取的查询词应至少命中一条本班结果")

    for index, raw_hit in enumerate(hits, start=1):
        hit = require_mapping(raw_hit, "每条检索结果应为对象")
        require_equal(hit.get("rank"), index, "检索结果 rank 应从 1 连续编号")
        stored = context.db.query_one(
            """
            SELECT kc.id AS chunk_id, kc.class_id AS chunk_class_id, kc.index_status AS chunk_status,
                   kc.material_id, m.class_id AS material_class_id, m.index_status AS material_status
            FROM knowledge_chunks kc
            JOIN materials m ON m.id = kc.material_id
            WHERE kc.id = %s AND kc.material_id = %s
            """,
            (hit.get("chunkId"), hit.get("materialId")),
        )
        require(stored is not None, "响应中的切片和材料组合应存在于 MySQL")
        require_equal(stored["chunk_class_id"], class_id, "切片必须属于登录学生班级")
        require_equal(stored["material_class_id"], class_id, "材料必须属于登录学生班级")
        require_equal(stored["chunk_status"], "READY", "响应切片必须为 READY")
        require_equal(stored["material_status"], "READY", "响应材料必须为 READY")

    context.state["ready_material_id"] = seed["material_id"]
    context.state["retrieval_query"] = query
