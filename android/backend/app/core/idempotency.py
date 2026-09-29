"""幂等（00-接口通则 10）：服务端存储键与首次响应，TTL 24 小时。

同 key 不同 body → 409 CONFLICT。靠 idempotency_keys 主键唯一约束兜底（schema 约定 7）。
"""
import hashlib
import json
import uuid
from datetime import datetime, timedelta, timezone

from fastapi import Request
from sqlalchemy.orm import Session

from app.core.errors import AppError, ErrorCode
from app.core.request_context import get_request_id
from app.db.models import IdempotencyKey

_TTL_HOURS = 24


def _scope_key(scope: str, request: Request, key_header: str | None) -> str:
    if not key_header:
        raise AppError(ErrorCode.INVALID_PARAM, "缺少 Idempotency-Key 请求头")
    return f"{scope}:{key_header}"


def _request_hash(body: dict) -> bytes:
    return hashlib.sha256(
        json.dumps(body, sort_keys=True, ensure_ascii=False).encode("utf-8")
    ).digest()


def begin_idempotency(
    db: Session, *, scope: str, account_id: uuid.UUID, request: Request, body: dict
) -> tuple[str, dict | None]:
    """返回 (scope_key, cached_response)。cached_response 非 None 表示重放首次结果。"""
    key = _scope_key(scope, request, request.headers.get("Idempotency-Key"))
    rh = _request_hash(body)
    existing = db.get(IdempotencyKey, key)
    if existing is not None:
        if existing.expires_at < datetime.now(timezone.utc):
            db.delete(existing)
            db.flush()
            return key, None
        if existing.request_hash != rh:
            raise AppError(ErrorCode.CONFLICT, "相同幂等键但请求内容不同")
        return key, existing.response_body
    db.add(IdempotencyKey(
        key=key,
        account_id=account_id,
        request_hash=rh,
        response_body={},  # 占位，成功后回填
        expires_at=datetime.now(timezone.utc) + timedelta(hours=_TTL_HOURS),
    ))
    db.flush()
    return key, None


def commit_idempotency(db: Session, scope_key: str, response: dict) -> None:
    row = db.get(IdempotencyKey, scope_key)
    if row is not None and not row.response_body:
        row.response_body = {"payload": response, "requestId": get_request_id()}
