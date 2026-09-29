"""请求上下文：requestId 生成与传递（README 5.8.4）。"""
import contextvars
import uuid

_request_id: contextvars.ContextVar[str] = contextvars.ContextVar("request_id", default="")


def get_request_id() -> str:
    return _request_id.get() or "-"


def set_request_id(value: str) -> str:
    _request_id.set(value)
    return value


def new_request_id() -> str:
    return f"req_{uuid.uuid4().hex}"
