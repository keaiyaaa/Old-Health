"""API-05 · 导出与注销（数据生命周期与合规）。

红线：不存在任何面向医生的临床输出接口；删除=硬删除；注销 7 天冷静期幂等。
"""
import csv
import io
import json
import uuid
import zipfile
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, Request, Response
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.config import get_settings
from app.core.audit import audit
from app.core.deps import (
    get_principal,
    require_consent,
    require_elder_session,
    require_write,
    resolve_subject_id,
    invalidate_perm_cache,
)
from app.core.errors import AppError, ErrorCode
from app.core.idempotency import begin_idempotency, commit_idempotency
from app.core.pagination import cursor_limit, encode_cursor, to_millis
from app.core.security import aes_decrypt, hmac_hash
from app.db.models import (
    Account,
    AsrResult,
    AuditLog,
    AudioObject,
    CareLink,
    DeletionTask,
    EventTag,
    ExportJob,
    Followup,
    ManualTranscript,
    Metric,
    Record,
    Session,
    Subject,
)
from app.db.session import get_db
from app.domain.status_machine import recompute_subject
from app.integrations.outbox import enqueue
from app.modules.records.service import get_record_or_404

router = APIRouter(tags=["lifecycle"])


class ExportIn(BaseModel):
    subjectId: str
    scope: str = Field(pattern="^(FULL|METRICS_ONLY)$")
    fromMs: int | None = None
    toMs: int | None = None
    include: list[str] = Field(default_factory=lambda: ["AUDIO", "METRICS", "EVENTS", "TRANSCRIPT"])


@router.post("/export", status_code=202)
def create_export(body: ExportIn, request: Request,
                  principal=Depends(get_principal), db: Session = Depends(get_db)):
    sid = resolve_subject_id(db, principal, body.subjectId)
    require_consent(db, principal.account.id, "SENSITIVE")
    idem_key, cached = begin_idempotency(
        db, scope="export", account_id=principal.account.id, request=request,
        body=body.model_dump())
    if cached:
        return cached["payload"]
    record_count = db.scalar(
        select(Record.id).where(Record.subject_id == sid, Record.deleted_at.is_(None)).limit(1))
    if record_count is None:
        raise AppError(ErrorCode.INSUFFICIENT_DATA)
    job = ExportJob(
        id=uuid.uuid4(), subject_id=sid, scope=body.scope,
        include=[i for i in body.include if i in ("AUDIO", "METRICS", "EVENTS", "TRANSCRIPT")],
        requested_by=principal.account.id,
        expires_at=datetime.now(timezone.utc) + timedelta(hours=24),
    )
    db.add(job)
    db.flush()
    enqueue(db, aggregate_type="export", aggregate_id=job.id,
            event_type="export.requested", payload={"jobId": str(job.id)})
    commit_idempotency(db, idem_key, {"jobId": f"exp_{job.id}", "state": "PENDING"})
    audit(db, action="EXPORT", target_type="subject", target_id=sid,
          actor_id=principal.account.id)
    return {"jobId": f"exp_{job.id}", "state": "PENDING", "estimatedSizeBytes": 0}


@router.get("/export/{jobId}")
def export_status(jobId: str, principal=Depends(get_principal),
                  db: Session = Depends(get_db)):
    job = _get_export_job(db, jobId, principal.account.id)
    resp = {
        "jobId": f"exp_{job.id}",
        "state": job.state,
        "downloadUrl": None,
        "expiresAt": to_millis(job.expires_at),
        "sizeBytes": job.size_bytes,
        "missingAudioCount": job.missing_audio_count,
    }
    if job.state == "READY" and job.object_key:
        from app.integrations.storage import get_storage

        url, exp = get_storage().presign_get(job.object_key)
        resp["downloadUrl"] = url
    return resp


def _get_export_job(db: Session, job_ref: str, account_id) -> ExportJob:
    try:
        jid = uuid.UUID(str(job_ref).split("_")[-1])
    except Exception:
        raise AppError(ErrorCode.NOT_FOUND)
    job = db.get(ExportJob, jid)
    if job is None or job.requested_by != account_id:
        raise AppError(ErrorCode.NOT_FOUND)
    return job


class RetentionIn(BaseModel):
    audioMonths: int | None = Field(default=None)


@router.get("/subjects/{subjectId}/retention")
def get_retention(subjectId: str, principal=Depends(get_principal),
                  db: Session = Depends(get_db)):
    sid = resolve_subject_id(db, principal, subjectId)
    subject = db.get(Subject, sid)
    return {
        "audioMonths": subject.retention_months,
        "options": [6, 12, 24, None],
        "nextCleanupAt": to_millis(
            datetime.now(timezone.utc) + timedelta(days=(subject.retention_months or 0) * 30))
        if subject.retention_months else None,
        "policyUrl": "https://example.com/privacy#retention",
    }


@router.put("/subjects/{subjectId}/retention")
def put_retention(subjectId: str, body: RetentionIn,
                  principal=Depends(get_principal), db: Session = Depends(get_db)):
    sid = resolve_subject_id(db, principal, subjectId, write=True)
    subject = db.get(Subject, sid)
    if body.audioMonths not in (6, 12, 24, None):
        raise AppError(ErrorCode.INVALID_PARAM)
    old = subject.retention_months
    subject.retention_months = body.audioMonths
    audit(db, action="DELETE", target_type="retention", target_id=sid,
          actor_id=principal.account.id, purpose=f"retention {old}->{body.audioMonths}")
    return {
        "audioMonths": subject.retention_months,
        "options": [6, 12, 24, None],
        "nextCleanupAt": None,
        "policyUrl": "https://example.com/privacy#retention",
    }


@router.delete("/records/{recordId}", status_code=204)
def delete_record(recordId: str, request: Request,
                  principal=Depends(get_principal), db: Session = Depends(get_db)):
    rec = _owned_record(db, recordId, principal)
    require_write(db, principal, rec.subject_id)
    now = datetime.now(timezone.utc)
    rec.deleted_at = now
    audio = db.scalar(select(AudioObject).where(AudioObject.record_id == rec.id))
    if audio:
        audio.deleted_at = now
    # 硬删除由 cleanup 队列执行（含对象存储），DB 行随后清理
    enqueue(db, aggregate_type="cleanup", aggregate_id=rec.id,
            event_type="record.deleted", payload={"recordId": str(rec.id)})
    # 删除后基线重算（常见 bug 点，务必触发）
    enqueue(db, aggregate_type="analysis", aggregate_id=rec.id,
            event_type="recompute.requested", payload={"subjectId": str(rec.subject_id)})
    audit(db, action="DELETE", target_type="record", target_id=rec.id,
          actor_id=principal.account.id)


def _owned_record(db: Session, record_ref: str, principal) -> Record:
    try:
        rid = uuid.UUID(str(record_ref).split("_")[-1])
    except Exception:
        raise AppError(ErrorCode.NOT_FOUND)
    rec = db.get(Record, rid)
    if rec is None or rec.deleted_at is not None:
        raise AppError(ErrorCode.NOT_FOUND)
    return rec


class PurgeIn(BaseModel):
    confirmText: str
    reason: str = "USER_REQUEST"


@router.post("/subjects/{subjectId}/purge", status_code=202)
def purge_subject(subjectId: str, body: PurgeIn,
                  principal=Depends(get_principal), db: Session = Depends(get_db)):
    # 仅老人端会话（老人本人在场操作）；家属端 404
    require_elder_session(principal)
    sid = resolve_subject_id(db, principal, subjectId)
    if body.confirmText != "删除":
        raise AppError(ErrorCode.INVALID_PARAM, "请输入确认词")
    subject = db.get(Subject, sid)
    subject.status = "DELETING"
    subject.purge_requested_at = datetime.now(timezone.utc)
    task = DeletionTask(id=uuid.uuid4(), scope="SUBJECT_PURGE", target_id=sid,
                        requested_by=principal.account.id)
    db.add(task)
    db.flush()
    enqueue(db, aggregate_type="purge", aggregate_id=task.id,
            event_type="subject.purge", payload={"taskId": str(task.id)})
    audit(db, action="DELETE", target_type="subject", target_id=sid,
          actor_id=principal.account.id)
    return {"taskId": f"del_{task.id}", "state": "PENDING"}


class DeleteAccountIn(BaseModel):
    confirmText: str


@router.delete("/accounts/me", status_code=202)
def delete_account(body: DeleteAccountIn, principal=Depends(get_principal),
                   db: Session = Depends(get_db)):
    if body.confirmText != "注销":
        raise AppError(ErrorCode.INVALID_PARAM, "请输入确认词")
    account = principal.account
    now = datetime.now(timezone.utc)
    # 幂等：重复调用返回同一冷静期截止时间
    if account.status == "DELETING" and account.cooling_off_until:
        return _deleting_response(account)
    account.status = "DELETING"
    account.cooling_off_until = now + timedelta(days=7)
    # 冷静期开始即解除所有绑定：他人立即无法访问
    for link in db.scalars(select(CareLink).where(
            CareLink.account_id == account.id, CareLink.revoked_at.is_(None))):
        link.revoked_at = now
        invalidate_perm_cache(db, link.account_id, link.subject_id)
    for s in db.scalars(select(Session).where(
            Session.account_id == account.id, Session.revoked_at.is_(None))):
        s.revoked_at = now
    # 冷静期结束后的彻底删除入队（outbox → cleanup）
    enqueue(db, aggregate_type="cleanup", aggregate_id=account.id,
            event_type="account.cooling_off",
            payload={"accountId": str(account.id),
                     "coolingOffUntil": int(account.cooling_off_until.timestamp() * 1000)})
    audit(db, action="DELETE", target_type="account", target_id=account.id,
          actor_id=account.id)
    return _deleting_response(account)


def _deleting_response(account: Account) -> dict:
    return {
        "state": "DELETING",
        "coolingOffUntil": int(account.cooling_off_until.timestamp() * 1000),
        "note": "7 天内登录可撤销注销。本地数据已清除，云端数据将在冷静期结束后彻底删除。",
    }


@router.post("/accounts/me/cancel-deletion")
def cancel_deletion(principal=Depends(get_principal), db: Session = Depends(get_db)):
    account = principal.account
    now = datetime.now(timezone.utc)
    if account.status != "DELETING" or not account.cooling_off_until:
        raise AppError(ErrorCode.CONFLICT)
    if account.cooling_off_until < now:
        raise AppError(ErrorCode.CONFLICT, "已过冷静期，不可撤销")
    account.status = "ACTIVE"
    account.cooling_off_until = None
    audit(db, action="AUTH_EVENT", target_type="account", target_id=account.id,
          actor_id=account.id)
    return {"state": "ACTIVE"}


class FollowupIn(BaseModel):
    treatedAt: int
    visited: bool = True
    doctorNote: str | None = None
    hospital: str | None = None


@router.post("/subjects/{subjectId}/followup", status_code=201)
def followup(subjectId: str, body: FollowupIn, request: Request,
             principal=Depends(get_principal), db: Session = Depends(get_db)):
    sid = resolve_subject_id(db, principal, subjectId, write=True)
    idem_key, cached = begin_idempotency(
        db, scope="followup", account_id=principal.account.id, request=request,
        body=body.model_dump())
    if cached:
        return cached["payload"]
    row = Followup(
        id=uuid.uuid4(), subject_id=sid,
        treated_at=datetime.fromtimestamp(body.treatedAt / 1000, tz=timezone.utc),
        visited=body.visited, doctor_note=body.doctorNote, hospital=body.hospital,
        created_by=principal.account.id,
    )
    db.add(row)
    db.flush()
    # 合规：doctorNote 是家属记录的医生原话，服务端不做分析、不提取、不结构化
    commit_idempotency(db, idem_key, {"ok": True})
    audit(db, action="DELETE", target_type="followup", target_id=row.id,
          actor_id=principal.account.id)
    return {"ok": True}


@router.get("/audit-logs")
def my_audit_logs(cursor: str | None = None, limit: int = 20, action: str | None = None,
                  from_ms: int | None = None, to_ms: int | None = None,
                  principal=Depends(get_principal), db: Session = Depends(get_db)):
    cur, lim = cursor_limit(cursor, limit)
    q = select(AuditLog).order_by(AuditLog.id.desc())
    aid = principal.account.id
    q = q.where((AuditLog.actor_id == aid))
    if action:
        q = q.where(AuditLog.action == action)
    if from_ms:
        q = q.where(AuditLog.occurred_at >= datetime.fromtimestamp(from_ms / 1000, tz=timezone.utc))
    if to_ms:
        q = q.where(AuditLog.occurred_at < datetime.fromtimestamp(to_ms / 1000, tz=timezone.utc))
    if cur and len(cur) == 1:
        q = q.where(AuditLog.id < int(cur[0]))
    rows = db.scalars(q.limit(lim + 1)).all()
    has_more = len(rows) > lim
    rows = rows[:lim]
    # 合规：只含操作类型、目标、时间——不含 IP 摘要、purpose 等内部字段
    return {
        "items": [
            {"logId": f"aud_{r.id}", "action": r.action, "targetType": r.target_type,
             "targetId": str(r.target_id) if r.target_id else None,
             "occurredAt": to_millis(r.occurred_at)}
            for r in rows
        ],
        "nextCursor": encode_cursor([str(rows[-1].id)]) if has_more and rows else None,
    }
