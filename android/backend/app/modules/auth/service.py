"""账号与身份 · 服务层（docs/backend/modules/账号与身份.md + API-01）。

副作用约束：
- 验证码在 Redis（TTL 5 分钟、一次性核销、失败计数），不进库
- 注册事务内：Account + 初始 PRODUCT 同意 + 核销验证码
- 重置密码：核销验证码 + 改密 + 吊销全部会话
"""
import hashlib
import secrets
import string
import uuid
from datetime import datetime, timedelta, timezone

import redis
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.config import get_settings
from app.core.audit import audit
from app.core.errors import AppError, ErrorCode
from app.core.ids import uuid7
from app.core.redis_client import get_redis
from app.core.security import (
    aes_decrypt,
    aes_encrypt,
    check_password_strength,
    hash_password,
    hmac_hash,
    verify_password,
)
from app.db.models import Account, CareLink, Consent, Record, Session, Subject

_CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz"  # 去易混淆字符
_BIND_CODE_LEN = 8


# ---------------- 验证码（Redis，不进库） ----------------

def _code_key(scene: str, email_hash: bytes) -> str:
    return f"email_code:{scene}:{email_hash.hex()}"


def _fail_key(scene: str, email_hash: bytes) -> str:
    return f"email_code_fail:{scene}:{email_hash.hex()}"


def send_email_code(r: redis.Redis, email: str, scene: str) -> dict:
    eh = hmac_hash(email)
    if r.incr(_fail_key(scene, eh)) > 10:
        raise AppError(ErrorCode.CODE_TOO_MANY)
    r.expire(_fail_key(scene, eh), 3600)
    code = "".join(secrets.choice(string.digits) for _ in range(6))
    r.setex(_code_key(scene, eh), 300, hashlib.sha256(code.encode()).hexdigest())
    # 发信通道：生产走阿里云邮件推送/腾讯云 SES（README 8.5）；开发打日志
    _deliver_email(email, code, scene)
    return {"expiresInSec": 300, "retryAfterSec": 60}


def _deliver_email(email: str, code: str, scene: str) -> None:
    import logging

    if get_settings().is_prod:
        # TODO: 接入邮件推送服务，模板含验证码、有效期、"非本人操作请忽略"
        logging.getLogger("app.mail").info("email code sent scene=%s", scene)
    else:
        logging.getLogger("app.mail").warning(
            "[DEV] 验证码（%s -> %s）：%s", scene, email, code)


def verify_email_code(r: redis.Redis, email: str, scene: str, code: str) -> None:
    eh = hmac_hash(email)
    key = _code_key(scene, eh)
    stored = r.get(key)
    if stored is None:
        raise AppError(ErrorCode.CODE_EXPIRED)
    if stored != hashlib.sha256(code.encode()).hexdigest():
        raise AppError(ErrorCode.CODE_INVALID)
    r.delete(key)  # 一次性核销


# ---------------- 账号 ----------------

def relation_from_gender(gender: str) -> str:
    return "儿子" if gender == "MALE" else "女儿"


def register(db: Session, r: redis.Redis, *, username: str, email: str, code: str,
             password: str, gender: str, ip: str) -> Account:
    if not check_password_strength(password):
        raise AppError(ErrorCode.WEAK_PASSWORD)
    verify_email_code(r, email, "REGISTER", code)
    eh = hmac_hash(email)
    if db.scalar(select(Account.id).where(Account.email_hash == eh)):
        raise AppError(ErrorCode.EMAIL_TAKEN)
    if db.scalar(select(Account.id).where(Account.username == username)):
        raise AppError(ErrorCode.USERNAME_TAKEN)
    account = Account(
        id=uuid7(),
        username=username,
        email_encrypted=aes_encrypt(email),
        email_hash=eh,
        password_hash=hash_password(password),
        gender=gender,
        role="FAMILY",
    )
    db.add(account)
    db.flush()
    # 注册副作用：初始 PRODUCT 同意（API-01 1.2）
    db.add(Consent(
        id=uuid7(), account_id=account.id, type="PRODUCT", version="1.0",
        evidence={"ipDigest": hmac_hash(ip or "").hex(), "grantedBy": "REGISTER"},
    ))
    audit(db, action="AUTH_EVENT", target_type="account", target_id=account.id,
          actor_id=account.id, ip=ip)
    return account


def _authenticate(db: Session, account_ref: str, password: str) -> Account:
    """账号名或邮箱均可（API-01 1.3）。"""
    row = db.scalar(select(Account).where(Account.username == account_ref))
    if row is None:
        eh = hmac_hash(account_ref)
        row = db.scalar(select(Account).where(Account.email_hash == eh))
    # 统一执行一次哈希校验，防时序枚举
    if row is None:
        verify_password(password, hash_password("timing-equalizer"))
        raise AppError(ErrorCode.PASSWORD_WRONG)
    if not verify_password(password, row.password_hash):
        raise AppError(ErrorCode.PASSWORD_WRONG)
    return row


def login(db: Session, r: redis.Redis, *, account_ref: str, password: str,
          client: str, device_id: str, ip: str) -> dict:
    account = _authenticate(db, account_ref, password)
    bound = db.scalar(
        select(CareLink.id).where(
            CareLink.account_id == account.id, CareLink.revoked_at.is_(None)))
    session = _create_session(db, r, account.id, device_id, client)
    audit(db, action="AUTH_EVENT", target_type="session", target_id=session.id,
          actor_id=account.id, ip=ip)
    return {
        "account": account,
        "session": session,
        "pendingSubjectSelection": bound is not None,
        "accountDeleting": account.status == "DELETING",
        "coolingOffUntil": account.cooling_off_until,
    }


def _create_session(db: Session, r: redis.Redis, account_id: uuid.UUID,
                    device_id: str, client: str) -> Session:
    # 并发上限 5：吊销最久未活跃者（schema 注释）
    active = db.scalars(select(Session).where(
        Session.account_id == account_id, Session.revoked_at.is_(None)
    ).order_by(Session.last_active_at)).all()
    if len(active) >= 5:
        active[0].revoked_at = datetime.now(timezone.utc)
    refresh = secrets.token_urlsafe(48)
    session = Session(
        id=uuid7(),
        account_id=account_id,
        refresh_token_hash=hashlib.sha256(refresh.encode()).digest(),
        device_id=device_id or "unknown",
        client=client,
    )
    db.add(session)
    db.flush()
    session._raw_refresh = refresh  # type: ignore[attr-defined]  # 仅本次响应可见
    return session


def rotate_refresh(db: Session, refresh_token: str) -> Session:
    h = hashlib.sha256(refresh_token.encode()).digest()
    session = db.scalar(select(Session).where(Session.refresh_token_hash == h))
    if session is None or session.revoked_at is not None:
        raise AppError(ErrorCode.REFRESH_INVALID)
    session.revoked_at = datetime.now(timezone.utc)  # 轮转：旧 token 立即失效
    return session


def issue_refresh(db: Session, account_id: uuid.UUID, device_id: str, client: str) -> Session:
    return _create_session(db, get_redis(), account_id, device_id, client)


def raw_refresh_token(session: Session) -> str:
    return session._raw_refresh  # type: ignore[attr-defined]


def logout(db: Session, principal, refresh_token: str) -> None:
    h = hashlib.sha256(refresh_token.encode()).digest()
    session = db.scalar(select(Session).where(Session.refresh_token_hash == h))
    if session is not None and session.account_id == principal.account.id:
        session.revoked_at = datetime.now(timezone.utc)


def password_reset(db: Session, r: redis.Redis, *, email: str, code: str,
                   new_password: str) -> None:
    """合规：邮箱不存在时同样 204，防枚举（API-01 1.4）。"""
    if not check_password_strength(new_password):
        raise AppError(ErrorCode.WEAK_PASSWORD)
    eh = hmac_hash(email)
    account = db.scalar(select(Account).where(Account.email_hash == eh))
    if account is None:
        return  # 静默成功
    verify_email_code(r, email, "PASSWORD_RESET", code)
    account.password_hash = hash_password(new_password)
    # 吊销全部会话（踢出所有设备）
    for s in db.scalars(select(Session).where(
            Session.account_id == account.id, Session.revoked_at.is_(None))):
        s.revoked_at = datetime.now(timezone.utc)
    audit(db, action="AUTH_EVENT", target_type="account", target_id=account.id,
          actor_id=account.id)


# ---------------- 老人与绑定 ----------------

def generate_bind_code() -> str:
    return "".join(secrets.choice(_CODE_ALPHABET) for _ in range(_BIND_CODE_LEN))


def create_or_bind_subject(db: Session, account: Account, *, elder_name: str,
                           bind_code: str | None, age_band: str, dialect: str | None,
                           hearing_issue: bool, ip: str) -> dict:
    """系统生成绑定码 / 「名字+代码」匹配（API-01 1.9）。并发靠唯一约束兜底。"""
    relation = relation_from_gender(account.gender)
    if bind_code:
        bh = hmac_hash(bind_code)
        subject = db.scalar(select(Subject).where(
            Subject.elder_name == elder_name, Subject.bind_code_hash == bh,
            Subject.status == "ACTIVE"))
        if subject is None:
            raise AppError(ErrorCode.BIND_CODE_INVALID)  # 不区分码错/不存在，防枚举
        dup = db.scalar(select(CareLink.id).where(
            CareLink.account_id == account.id, CareLink.subject_id == subject.id,
            CareLink.revoked_at.is_(None)))
        if dup:
            raise AppError(ErrorCode.CONFLICT, "已绑定这位老人")
        link = CareLink(id=uuid7(), account_id=account.id, subject_id=subject.id,
                        relation_display=relation)
        db.add(link)
        db.flush()
        audit(db, action="BIND_CHANGE", target_type="subject", target_id=subject.id,
              actor_id=account.id, ip=ip)
        return {"subjectId": f"sub_{subject.id}", "matched": True,
                "relationDisplay": relation, "bindCode": None}
    # 新建
    # 同名老人必须走「称呼+绑定码」绑定，不允许重复建档（API-01 1.9）
    dup_name = db.scalar(select(Subject.id).where(
        Subject.elder_name == elder_name, Subject.status == "ACTIVE"))
    if dup_name:
        raise AppError(ErrorCode.CONFLICT, "已有这位老人的档案。请让家人提供绑定码来绑定")
    code = generate_bind_code()
    subject = Subject(
        id=uuid7(), elder_name=elder_name, display_name=elder_name,
        bind_code_hash=hmac_hash(code), age_band=age_band, dialect=dialect,
        hearing_issue=hearing_issue, created_by_account_id=account.id,
    )
    db.add(subject)
    try:
        db.flush()
    except Exception:
        raise AppError(ErrorCode.BIND_CODE_INVALID)
    db.add(CareLink(id=uuid7(), account_id=account.id, subject_id=subject.id,
                    relation_display=relation))
    audit(db, action="BIND_CHANGE", target_type="subject", target_id=subject.id,
          actor_id=account.id, ip=ip)
    return {"subjectId": f"sub_{subject.id}", "matched": False,
            "relationDisplay": relation, "bindCode": code}


def masked_email(account: Account) -> str:
    try:
        return _mask(aes_decrypt(account.email_encrypted))
    except Exception:
        return "***"


def _mask(email: str) -> str:
    from app.core.security import mask_email

    return mask_email(email)
