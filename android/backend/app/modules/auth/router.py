"""API-01 · 认证与账号路由。Controller 不写业务逻辑。"""
import uuid

import redis
from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.audit import audit
from app.core.deps import (
    get_principal,
    require_access,
    require_elder_session,
    resolve_subject_id,
    invalidate_perm_cache,
)
from app.core.errors import AppError, ErrorCode
from app.core.redis_client import get_redis
from app.core.security import issue_access_token
from app.db.models import Account, Baseline, CareLink, Record, Session, Subject
from app.db.session import get_db
from app.modules.auth import service as auth_service

router = APIRouter(tags=["auth"])


class EmailCodeIn(BaseModel):
    email: str
    scene: str = Field(pattern="^(REGISTER|PASSWORD_RESET)$")


class RegisterIn(BaseModel):
    username: str = Field(min_length=2, max_length=32)
    email: str
    code: str = Field(min_length=4, max_length=8)
    password: str = Field(min_length=8, max_length=128)
    gender: str = Field(pattern="^(MALE|FEMALE)$")


class LoginIn(BaseModel):
    account: str
    password: str
    client: str = Field(pattern="^(ELDER_APP|FAMILY_APP)$")
    deviceId: str = ""


class RefreshIn(BaseModel):
    refreshToken: str


class LogoutIn(BaseModel):
    refreshToken: str


class PasswordResetIn(BaseModel):
    email: str
    code: str
    newPassword: str = Field(min_length=8)


class SubjectCreateIn(BaseModel):
    elderName: str = Field(min_length=1, max_length=32)
    bindCode: str | None = None
    ageBand: str
    dialect: str | None = None
    hearingIssue: bool = False


class SubjectPatchIn(BaseModel):
    displayName: str | None = None
    hearingIssue: bool | None = None
    dialect: str | None = None


class SessionSubjectIn(BaseModel):
    subjectId: str


def _token_payload(account: Account, session: Session) -> dict:
    access, exp_ms = issue_access_token(str(account.id), str(session.id))
    return {
        "accountId": f"acc_{account.id}",
        "accessToken": access,
        "refreshToken": auth_service.raw_refresh_token(session),
        "accessTokenExpiresAt": exp_ms,
    }


@router.post("/auth/email-code")
def send_email_code(body: EmailCodeIn, db: Session = Depends(get_db)):
    r = get_redis()
    # 合规：不透露邮箱是否已注册
    return auth_service.send_email_code(r, body.email, body.scene)


@router.post("/auth/register", status_code=201)
def register(body: RegisterIn, request: Request, db: Session = Depends(get_db)):
    r = get_redis()
    account = auth_service.register(
        db, r, username=body.username, email=body.email, code=body.code,
        password=body.password, gender=body.gender,
        ip=request.client.host if request.client else "")
    session = auth_service.issue_refresh(db, account.id, "", "FAMILY_APP")
    payload = _token_payload(account, session)
    payload.update({
        "relationDisplay": auth_service.relation_from_gender(account.gender),
        "onboardingCompleted": account.onboarding_completed,
    })
    return payload


@router.post("/auth/login")
def login(body: LoginIn, request: Request, db: Session = Depends(get_db)):
    r = get_redis()
    result = auth_service.login(
        db, r, account_ref=body.account, password=body.password, client=body.client,
        device_id=body.deviceId, ip=request.client.host if request.client else "")
    payload = _token_payload(result["account"], result["session"])
    payload.update({
        "pendingSubjectSelection": result["pendingSubjectSelection"],
        "accountDeleting": result["accountDeleting"],
    })
    if result["accountDeleting"]:
        payload["coolingOffUntil"] = int(result["coolingOffUntil"].timestamp() * 1000)
    return payload


@router.post("/auth/password-reset", status_code=204)
def password_reset(body: PasswordResetIn, db: Session = Depends(get_db)):
    auth_service.password_reset(
        db, get_redis(), email=body.email, code=body.code, new_password=body.newPassword)


@router.post("/auth/refresh")
def refresh(body: RefreshIn, db: Session = Depends(get_db)):
    session = auth_service.rotate_refresh(db, body.refreshToken)
    account = db.get(Account, session.account_id)
    if account is None or account.status != "ACTIVE":
        raise AppError(ErrorCode.REFRESH_INVALID)
    new_session = auth_service.issue_refresh(db, account.id, session.device_id, session.client)
    return _token_payload(account, new_session)


@router.post("/auth/logout", status_code=204)
def logout(body: LogoutIn, principal=Depends(get_principal), db: Session = Depends(get_db)):
    auth_service.logout(db, principal, body.refreshToken)


@router.get("/accounts/me")
def me(principal=Depends(get_principal), db: Session = Depends(get_db)):
    a = principal.account
    return {
        "accountId": f"acc_{a.id}",
        "username": a.username,
        "emailMasked": auth_service.masked_email(a),
        "gender": a.gender,
        "relationDisplay": auth_service.relation_from_gender(a.gender),
        "onboardingCompleted": a.onboarding_completed,
        "createdAt": int(a.created_at.timestamp() * 1000),
    }


# ---------------- 老人与绑定 ----------------

def _subject_brief(db: Session, subject: Subject, account_id: uuid.UUID) -> dict:
    last_record_at = db.scalar(
        select(Record.created_at).where(
            Record.subject_id == subject.id, Record.deleted_at.is_(None))
        .order_by(Record.created_at.desc()).limit(1))
    baselines = db.scalars(select(Baseline).where(
        Baseline.subject_id == subject.id, Baseline.computed_from == "ASR",
        Baseline.is_established.is_(True))).all()
    keys = {b.metric_key for b in baselines}
    return {
        "subjectId": f"sub_{subject.id}",
        "displayName": subject.display_name,
        "ageBand": subject.age_band,
        "dialect": subject.dialect,
        "hearingIssue": subject.hearing_issue,
        "retentionMonths": subject.retention_months,
        "lastSyncAt": int(subject.updated_at.timestamp() * 1000),
        "lastRecordAt": int(last_record_at.timestamp() * 1000) if last_record_at else None,
        "validRecordCount": db.scalar(
            select(Record.id).where(
                Record.subject_id == subject.id, Record.deleted_at.is_(None)).limit(1)) is not None
            and _count_valid(db, subject.id) or 0,
        "baselineEstablished": {
            "acoustic": bool(keys & {"SPEECH_RATE", "PAUSE"}),
            "text": bool(keys & {"VOCAB", "COHERENCE"}),
        },
    }


def _count_valid(db: Session, subject_id: uuid.UUID) -> int:
    from app.db.models import QualityGateResult

    return len(db.scalars(
        select(Record.id).join(QualityGateResult, QualityGateResult.record_id == Record.id)
        .where(Record.subject_id == subject_id, Record.deleted_at.is_(None),
               QualityGateResult.flag == "VALID")).all())


@router.get("/subjects")
def list_subjects(principal=Depends(get_principal), db: Session = Depends(get_db)):
    links = db.scalars(select(CareLink).where(
        CareLink.account_id == principal.account.id, CareLink.revoked_at.is_(None))).all()
    items = []
    for link in links:
        subject = db.get(Subject, link.subject_id)
        if subject and subject.status == "ACTIVE":
            items.append(_subject_brief(db, subject, principal.account.id))
    return {"items": items}


@router.post("/subjects", status_code=201)
def create_subject(body: SubjectCreateIn, request: Request,
                   principal=Depends(get_principal), db: Session = Depends(get_db)):
    result = auth_service.create_or_bind_subject(
        db, principal.account, elder_name=body.elderName,
        bind_code=body.bindCode, age_band=body.ageBand, dialect=body.dialect,
        hearing_issue=body.hearingIssue,
        ip=request.client.host if request.client else "")
    return result


@router.patch("/subjects/{subjectId}")
def patch_subject(subjectId: str, body: SubjectPatchIn,
                  principal=Depends(get_principal), db: Session = Depends(get_db)):
    sid = resolve_subject_id(db, principal, subjectId, write=True)
    subject = db.get(Subject, sid)
    if body.displayName is not None:
        subject.display_name = body.displayName
    if body.hearingIssue is not None:
        subject.hearing_issue = body.hearing_issue
    if body.dialect is not None:
        subject.dialect = body.dialect
    from datetime import datetime, timezone

    subject.updated_at = datetime.now(timezone.utc)
    return _subject_brief(db, subject, principal.account.id)


@router.delete("/subjects/{subjectId}/link", status_code=204)
def unlink(subjectId: str, principal=Depends(get_principal), db: Session = Depends(get_db)):
    sid = resolve_subject_id(db, principal, subjectId)
    link = db.scalar(select(CareLink).where(
        CareLink.account_id == principal.account.id, CareLink.subject_id == sid,
        CareLink.revoked_at.is_(None)))
    if link is None:
        raise AppError(ErrorCode.NOT_FOUND)
    from datetime import datetime, timezone

    link.revoked_at = datetime.now(timezone.utc)
    invalidate_perm_cache(db, principal.account.id, sid)
    audit(db, action="BIND_CHANGE", target_type="subject", target_id=sid,
          actor_id=principal.account.id)
    # 无人绑定的老人 → 进入清理（账号与身份 6）
    others = db.scalar(select(CareLink.id).where(
        CareLink.subject_id == sid, CareLink.revoked_at.is_(None),
        CareLink.account_id != principal.account.id))
    if others is None:
        from app.integrations.outbox import enqueue

        enqueue(db, aggregate_type="cleanup", aggregate_id=sid,
                event_type="subject.orphaned", payload={"subjectId": str(sid)})


@router.get("/subjects/{subjectId}/links")
def list_links(subjectId: str, principal=Depends(get_principal),
               db: Session = Depends(get_db)):
    sid = resolve_subject_id(db, principal, subjectId)
    links = db.scalars(select(CareLink).where(
        CareLink.subject_id == sid, CareLink.revoked_at.is_(None))).all()
    # 合规：只返回关系称谓，不返回邮箱/账号名/姓名
    return {"items": [
        {"linkId": f"lnk_{l.id}", "relationDisplay": l.relation_display,
         "createdAt": int(l.created_at.timestamp() * 1000)}
        for l in links
    ]}


@router.post("/session/subject")
def lock_subject(body: SessionSubjectIn, principal=Depends(get_principal),
                 db: Session = Depends(get_db)):
    require_elder_session(principal)
    try:
        sid = uuid.UUID(body.subjectId.split("_")[-1])
    except Exception:
        raise AppError(ErrorCode.NOT_FOUND)
    require_access(db, principal, sid)
    from datetime import datetime, timezone

    principal.session.locked_subject_id = sid
    principal.session.last_active_at = datetime.now(timezone.utc)
    subject = db.get(Subject, sid)
    audit(db, action="BIND_CHANGE", target_type="session", target_id=sid,
          actor_id=principal.account.id)
    return {"subjectId": f"sub_{sid}", "displayName": subject.display_name,
            "lockedAt": int(datetime.now(timezone.utc).timestamp() * 1000)}
