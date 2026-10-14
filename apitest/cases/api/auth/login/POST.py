"""验证教师和学生登录，并核对令牌用户与数据库身份。"""

from common.assertions import require, require_equal, require_mapping

ORDER = 10
NAME = "登录并取得 Bearer Token"
METHOD = "POST"
PATH = "/api/auth/login"


def _login(context, username: str, password: str, expected_role: str) -> dict:
    response = context.http.request(METHOD, PATH, json_body={"username": username, "password": password})
    require_equal(response.status, 200, f"{expected_role} 登录应成功")
    payload = require_mapping(response.json(), "登录响应应为 JSON 对象")
    require_equal(payload.get("tokenType"), "Bearer", "登录令牌类型应为 Bearer")
    token = payload.get("accessToken")
    require(isinstance(token, str) and len(token) > 20, "登录响应应包含非空访问令牌")
    user = require_mapping(payload.get("user"), "登录响应应包含用户摘要")
    require_equal(user.get("username"), username, "登录响应用户名应与请求一致")
    require_equal(user.get("role"), expected_role, "登录响应角色应与测试账号一致")
    require(isinstance(user.get("id"), int) and isinstance(user.get("classId"), int),
            "用户摘要应包含数字用户 ID 和班级 ID")

    stored = context.db.query_one(
        "SELECT id, username, role, class_id, enabled FROM users WHERE username = %s",
        (username,),
    )
    require(stored is not None, "登录用户应存在于 MySQL")
    require_equal(stored["id"], user["id"], "响应用户 ID 应与 MySQL 一致")
    require_equal(stored["class_id"], user["classId"], "响应班级 ID 应与 MySQL 一致")
    require_equal(str(stored["role"]).lower(), expected_role, "响应角色应与 MySQL 一致")
    require(bool(stored["enabled"]), "测试账号应处于启用状态")
    context.add_secret(token)
    return {"token": token, "user": user}


def run(context) -> None:
    """一次脚本覆盖同一路径下的教师与学生登录语义。"""
    context.state["teacher"] = _login(
        context,
        context.config.teacher_username,
        context.config.teacher_password,
        "teacher",
    )
    context.state["student"] = _login(
        context,
        context.config.student_username,
        context.config.student_password,
        "student",
    )
    require_equal(
        context.state["teacher"]["user"]["classId"],
        context.state["student"]["user"]["classId"],
        "默认接口回归教师和学生必须属于同一测试班级",
    )
