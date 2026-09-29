"""FastAPI 依赖：认证、会话、权限（三道检查统一入口，README 5.2）。

硬规则：
- 资源归属校验走统一函数，不允许每个接口手写
- 越权一律 404（唯一例外 CONSENT_REQUIRED 403）
- 老人端会话锁定 subjectId（API-01 1.13）
"""
import uuid
from dataclasses import dataclass

import redis
from fastapi import Depends, Request
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.errors import AppError, ErrorCode
from app.core.ids import parse_external_id
from app.core.redis_client import get_redis
from app.core.security import decode_access_token
from app.db.models import Account, CareLink, Consent, Session, Subject
from app.db.session import get_db


@dataclass
class Principal:
    """已认证的请求主体。"""

    account: Account
    session: Session
    # 老人端会话锁定的 subject（家属端为 None）
    locked_subject_id: uuid.UUID | None
    is_elder_session: bool
    client: str


def _load_session(db: Session, session_id: str) -> Session | None:
    return db.get(Session, uuid.UUID(session_id))


def get_principal(
    request: Request,
    db: Session = Depends(get_db),
) -> Principal:
    auth = request.headers.get("Authorization", "")
    if not auth.startswith("Bearer "):
        raise AppError(ErrorCode.TOKEN_INVALID, "请先登录")
    payload = decode_access_token(auth.removeprefix("Bearer ").strip())
    sess = _load_session(db, payload["sid"])
    if sess is None or sess.revoked_at is not None:
        raise AppError(ErrorCode.TOKEN_INVALID, "登录已失效，请重新登录")
    account = db.get(Account, sess.account_id)
    if account is None or account.status == "DELETED":
        # 注销冷静期（DELETING）内允许访问：用户需重新登录撤销注销（API-05 5.8）
        raise AppError(ErrorCode.TOKEN_INVALID, "账号不可用")
    return Principal(
        account=account,
        session=sess,
        locked_subject_id=sess.locked_subject_id,
        is_elder_session=sess.client == "ELDER_APP",
        client=sess.client,
    )


# ---------------- 权限判定（带 Redis 缓存，README 5.6） ----------------

def _perm_cache_key(account_id: uuid.UUID, subject_id: uuid.UUID) -> str:
    return f"perm:{account_id}:{subject_id}"


def can_access(db: Session, account_id: uuid.UUID, subject_id: uuid.UUID) -> bool:
    """有效绑定 = canAccess。结果缓存 1 分钟；解绑时主动失效。"""
    try:
        r: redis.Redis = get_redis()
        cached = r.get(_perm_cache_key(account_id, subject_id))
        if cached is not None:
            return cached == "1"
    except Exception:
        r = None  # Redis 不可用 → 查库（fail-open，只影响性能）
    exists = db.scalar(
        select(CareLink.id).where(
            CareLink.account_id == account_id,
            CareLink.subject_id == subject_id,
            CareLink.revoked_at.is_(None),
        )
    )
    allowed = exists is not None
    if r is not None:
        try:
            r.setex(_perm_cache_key(account_id, subject_id), 60, "1" if allowed else "0")
        except Exception:
            pass
    return allowed


def invalidate_perm_cache(db: Session, account_id: uuid.UUID, subject_id: uuid.UUID) -> None:
    """撤权失效机制（硬规则：绑定解除立即生效）。"""
    try:
        get_redis().delete(_perm_cache_key(account_id, subject_id))
    except Exception:
        pass


def require_access(db: Session, principal: Principal, subject_id: uuid.UUID) -> None:
    """角色 + 资源归属检查；失败 404（不区分不存在与无权）。"""
    if principal.account.role not in ("FAMILY", "RESEARCHER", "OPS_ADMIN"):
        raise AppError(ErrorCode.NOT_FOUND)
    if not can_access(db, principal.account.id, subject_id):
        raise AppError(ErrorCode.NOT_FOUND)


def require_write(db: Session, principal: Principal, subject_id: uuid.UUID) -> None:
    """canWrite：一期与 canAccess 相同（任意有效绑定账号，2026-09-28 角色细分取消）。"""
    require_access(db, principal, subject_id)


def require_family_session(principal: Principal) -> None:
    """家属端限定接口（趋势/状态/指标，B2：老人端无任何趋势接口）。"""
    if principal.is_elder_session:
        raise AppError(ErrorCode.NOT_FOUND)


def require_elder_session(principal: Principal) -> None:
    """老人端限定接口（选人锁定、清空记录、授 SENSITIVE 同意）。"""
    if not principal.is_elder_session:
        raise AppError(ErrorCode.NOT_FOUND)


def resolve_subject_id(db: Session, principal: Principal, subject_ref: str | None,
                       *, write: bool = False) -> uuid.UUID:
    """统一解析并校验 subjectId：
    - 老人端会话：必须与锁定值一致，否则 404（API-01 1.13 行为 2）
    - 未选人请求业务接口 → 400 SUBJECT_NOT_SELECTED
    - 家属端：must 为有效绑定
    """
    if subject_ref is None:
        if principal.locked_subject_id is None:
            raise AppError(ErrorCode.SUBJECT_NOT_SELECTED)
        return principal.locked_subject_id
    try:
        sid = parse_external_id(subject_ref)
    except Exception:
        raise AppError(ErrorCode.NOT_FOUND)
    if principal.is_elder_session:
        if principal.locked_subject_id is None:
            raise AppError(ErrorCode.SUBJECT_NOT_SELECTED)
        if sid != principal.locked_subject_id:
            raise AppError(ErrorCode.NOT_FOUND)
        return sid
    (require_write if write else require_access)(db, principal, sid)
    return sid


# ---------------- 同意校验（服务端强校验，合规硬约束） ----------------

def has_consent(db: Session, account_id: uuid.UUID, consent_type: str) -> bool:
    return db.scalar(
        select(Consent.id).where(
            Consent.account_id == account_id,
            Consent.type == consent_type,
            Consent.revoked_at.is_(None),
        )
    ) is not None


def require_consent(db: Session, account_id: uuid.UUID, consent_type: str) -> None:
    """唯一返回 403 的场景（00-接口通则 4.2）。"""
    if not has_consent(db, account_id, consent_type):
        raise AppError(ErrorCode.CONSENT_REQUIRED)
