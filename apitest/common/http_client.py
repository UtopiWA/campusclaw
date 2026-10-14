"""使用 Python 标准库发送 JSON 请求并增量解析 SSE。"""

from __future__ import annotations

from dataclasses import dataclass
import io
import json
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urljoin
from urllib.request import Request, urlopen


@dataclass(frozen=True)
class ApiResponse:
    status: int
    headers: dict[str, str]
    body: bytes

    def json(self) -> Any:
        """将响应解析为 JSON，错误中不回显可能含敏感信息的正文。"""
        try:
            return json.loads(self.body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exception:
            raise AssertionError("响应不是有效的 UTF-8 JSON") from exception


@dataclass(frozen=True)
class SseEvent:
    name: str
    data: Any


@dataclass(frozen=True)
class SseResponse:
    status: int
    headers: dict[str, str]
    events: list[SseEvent]


class HttpClient:
    """统一构造请求，确保令牌只进入 Authorization 头。"""

    def __init__(self, base_url: str, timeout: float, stream_timeout: float):
        self._base_url = base_url.rstrip("/") + "/"
        self._timeout = timeout
        self._stream_timeout = stream_timeout

    def request(self, method: str, path: str, *, token: str | None = None,
                json_body: Any | None = None) -> ApiResponse:
        """发送普通 HTTP 请求，并将非 2xx 响应保留为可断言结果。"""
        body = None if json_body is None else json.dumps(json_body, ensure_ascii=False).encode("utf-8")
        request = Request(self._url(path), data=body, method=method.upper())
        request.add_header("Accept", "application/json")
        if body is not None:
            request.add_header("Content-Type", "application/json; charset=utf-8")
        if token:
            request.add_header("Authorization", f"Bearer {token}")
        try:
            with urlopen(request, timeout=self._timeout) as response:
                return ApiResponse(response.status, self._headers(response.headers), response.read())
        except HTTPError as exception:
            return ApiResponse(exception.code, self._headers(exception.headers), exception.read())
        except URLError as exception:
            raise RuntimeError("无法连接 CampusClaw HTTP 服务") from exception

    def stream_json_sse(self, method: str, path: str, *, token: str,
                        json_body: Any) -> SseResponse:
        """逐行读取事件流，保留事件顺序并在 done/error 后立即关闭连接。"""
        body = json.dumps(json_body, ensure_ascii=False).encode("utf-8")
        request = Request(self._url(path), data=body, method=method.upper())
        request.add_header("Accept", "text/event-stream")
        request.add_header("Content-Type", "application/json; charset=utf-8")
        request.add_header("Authorization", f"Bearer {token}")
        try:
            response = urlopen(request, timeout=self._stream_timeout)
        except HTTPError as exception:
            exception.read()
            return SseResponse(exception.code, self._headers(exception.headers), [])
        except URLError as exception:
            raise RuntimeError("无法连接 CampusClaw 流式接口") from exception

        events: list[SseEvent] = []
        try:
            headers = self._headers(response.headers)
            event_name = "message"
            data_lines: list[str] = []
            text_stream = io.TextIOWrapper(response, encoding="utf-8", errors="strict", newline=None)
            for line in text_stream:
                clean = line.rstrip("\r\n")
                if not clean:
                    if data_lines:
                        events.append(self._decode_event(event_name, data_lines))
                        if events[-1].name in {"done", "error"}:
                            break
                    event_name = "message"
                    data_lines = []
                    continue
                if clean.startswith(":"):
                    continue
                field, separator, value = clean.partition(":")
                value = value[1:] if separator and value.startswith(" ") else value
                if field == "event":
                    event_name = value
                elif field == "data":
                    data_lines.append(value)
                if len(events) > 10000:
                    raise AssertionError("SSE 事件数量超过安全上限")
            if data_lines and (not events or events[-1].name not in {"done", "error"}):
                events.append(self._decode_event(event_name, data_lines))
            return SseResponse(response.status, headers, events)
        except UnicodeDecodeError as exception:
            raise AssertionError("SSE 不是有效的 UTF-8 文本") from exception
        finally:
            response.close()

    def _decode_event(self, name: str, data_lines: list[str]) -> SseEvent:
        try:
            data = json.loads("\n".join(data_lines))
        except json.JSONDecodeError as exception:
            raise AssertionError(f"SSE {name or 'message'} 事件不是有效 JSON") from exception
        return SseEvent(name or "message", data)

    def _url(self, path: str) -> str:
        return urljoin(self._base_url, path.lstrip("/"))

    @staticmethod
    def _headers(headers) -> dict[str, str]:
        return {key.lower(): value for key, value in headers.items()}
