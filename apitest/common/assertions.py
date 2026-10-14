"""提供不依赖测试框架的轻量断言。"""


def require(condition: bool, message: str) -> None:
    """条件不成立时抛出面向报告的断言错误。"""
    if not condition:
        raise AssertionError(message)


def require_equal(actual, expected, message: str) -> None:
    """比较普通非敏感值，错误正文不自动展开实际响应。"""
    if actual != expected:
        raise AssertionError(f"{message}（期望 {expected!r}，实际 {actual!r}）")


def require_mapping(value, message: str) -> dict:
    """确认 JSON 值为对象并返回该对象。"""
    require(isinstance(value, dict), message)
    return value


def require_list(value, message: str) -> list:
    """确认 JSON 值为数组并返回该数组。"""
    require(isinstance(value, list), message)
    return value
