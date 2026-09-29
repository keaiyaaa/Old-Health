"""游标分页（00-接口通则 8）：cursor 是不透明字符串，基于稳定排序键。"""
import base64
import json
from datetime import datetime, timezone

from sqlalchemy import Column


def encode_cursor(sort_values: list) -> str:
    raw = json.dumps({"k": [v.isoformat() if isinstance(v, datetime) else str(v)
                            for v in sort_values]})
    return base64.urlsafe_b64encode(raw.encode("utf-8")).decode("ascii")


def decode_cursor(cursor: str | None) -> list | None:
    if not cursor:
        return None
    try:
        raw = json.loads(base64.urlsafe_b64decode(cursor.encode("ascii")))
        return raw["k"]
    except Exception:
        from app.core.errors import AppError, ErrorCode

        raise AppError(ErrorCode.INVALID_PARAM, "cursor 无效")


def cursor_limit(cursor: str | None, limit: int, max_limit: int = 100) -> tuple[list | None, int]:
    if limit < 1:
        limit = 1
    if limit > max_limit:
        limit = max_limit
    return decode_cursor(cursor), limit


def to_millis(dt: datetime | None) -> int | None:
    return None if dt is None else int(dt.timestamp() * 1000)


def from_millis(ms: int | None) -> datetime | None:
    return None if ms is None else datetime.fromtimestamp(ms / 1000, tz=timezone.utc)


def ms_col(col: Column) -> Column:
    return col
