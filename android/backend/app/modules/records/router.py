"""API-02 · 录音与上传 + API-04 · 记录与事件 路由。"""
import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.audit import audit
from app.core.deps import (
    get_principal,
    require_consent,
    require_write,
    resolve_subject_id,
)
from app.core.errors import AppError, ErrorCode
from app.core.idempotency import begin_idempotency, commit_idempotency
from app.core.pagination import cursor_limit, encode_cursor, to_millis
from app.db.models import (
    AnalysisJob,
    AudioObject,
    EventTag,
    ManualTranscript,
    Metric,
    QualityGateResult,
    Record,
    ReminderSetting,
    UploadSession,
    AsrResult,
)
from app.db.session import get_db
from app.domain.status_machine import recompute_subject
from app.integrations.outbox import enqueue
from app.modules.records import service as svc

router = APIRouter(tags=["records"])


class UploadUrlIn(BaseModel):
    subjectId: str
    contentType: str = "audio/mp4"
    sizeBytes: int
    durationSec: int
    taskType: str


class RecordRegisterIn(BaseModel):
    sessionId: str
    objectKey: str
    sha256: str
    subjectId: str | None = None
    recorderRole: str = Field(pattern="^(ELDER_SESSION|FAMILY_PROXY)$")
    taskType: str
    scheduledAt: int | None = None
    actualStartedAt: int | None = None
    durationSec: int
    sampleRate: int = 16000
    dialect: str | None = None


class TranscriptPatchIn(BaseModel):
    asrTextManual: str = Field(min_length=1, max_length=5000)


class EventIn(BaseModel):
    type: str
    note: str | None = None


class ReminderIn(BaseModel):
    enabled: bool
    reminderTime: str = Field(pattern="^\\d{2}:\\d{2}$")
    timezone: str = "Asia/Shanghai"
    target: str = Field(pattern="^(ELDER_DEVICE|FAMILY|BOTH)$")


@router.post("/records/upload-url")
def upload_url(body: UploadUrlIn, principal=Depends(get_principal),
               db: Session = Depends(get_db)):
    sid = resolve_subject_id(db, principal, body.subjectId, write=True)
    require_consent(db, principal.account.id, "SENSITIVE")
    return svc.create_upload_session(
        db, subject_id=sid, content_type=body.contentType, size_bytes=body.sizeBytes)


@router.post("/records", status_code=201)
def register_record(body: RecordRegisterIn, request: Request,
                    principal=Depends(get_principal), db: Session = Depends(get_db)):
    sid = resolve_subject_id(db, principal, body.subjectId or (
        f"sub_{db.scalar(select(UploadSession.subject_id).where(
            UploadSession.id == uuid.UUID(body.sessionId.split('_')[-1])))}"),
        write=True)
    require_consent(db, principal.account.id, "SENSITIVE")
    idem_key, cached = begin_idempotency(
        db, scope="records", account_id=principal.account.id, request=request,
        body=body.model_dump())
    if cached:
        return cached["payload"]
    result = svc.register_record(
        db, subject_id=sid, account_id=principal.account.id,
        is_elder_session=principal.is_elder_session, body=body.model_dump())
    commit_idempotency(db, idem_key, result)
    return result


@router.get("/records")
def list_records(
    subjectId: str, cursor: str | None = None, limit: int = 20,
    from_ms: int | None = None, to_ms: int | None = None,
    taskType: str | None = None, qualityFlag: str | None = None,
    hasNote: bool | None = None, sort: str = "-recordedAt",
    principal=Depends(get_principal), db: Session = Depends(get_db),
):
    sid = resolve_subject_id(db, principal, subjectId)
    if sort not in ("-recordedAt", "recordedAt", "-durationSec"):
        raise AppError(ErrorCode.INVALID_PARAM)
    cur, lim = cursor_limit(cursor, limit)
    q = (
        select(Record, QualityGateResult.flag, AnalysisJob.state)
        .outerjoin(QualityGateResult, QualityGateResult.record_id == Record.id)
        .outerjoin(AnalysisJob, AnalysisJob.record_id == Record.id)
        .where(Record.subject_id == sid, Record.deleted_at.is_(None))
    )
    if from_ms:
        q = q.where(Record.created_at >= datetime.fromtimestamp(from_ms / 1000, tz=timezone.utc))
    if to_ms:
        q = q.where(Record.created_at < datetime.fromtimestamp(to_ms / 1000, tz=timezone.utc))
    if taskType:
        q = q.where(Record.task_type == taskType)
    if qualityFlag:
        q = q.where(QualityGateResult.flag == qualityFlag)
    if hasNote is not None:
        q = q.where(Record.family_note.isnot(None) if hasNote
                    else Record.family_note.is_(None))
    if sort == "recordedAt":
        q = q.order_by(Record.created_at.asc(), Record.id)
    elif sort == "-durationSec":
        q = q.order_by(Record.duration_sec.desc(), Record.id)
    else:
        q = q.order_by(Record.created_at.desc(), Record.id.desc())
    # 游标：按 (createdAt, id) 稳定键，翻页不重不漏
    if cur and isinstance(cur, list) and len(cur) == 2:
        c_at = datetime.fromisoformat(cur[0])
        if sort == "-recordedAt":
            q = q.where((Record.created_at < c_at) |
                        ((Record.created_at == c_at) & (Record.id < uuid.UUID(cur[1]))))
        elif sort == "recordedAt":
            q = q.where((Record.created_at > c_at) |
                        ((Record.created_at == c_at) & (Record.id > uuid.UUID(cur[1]))))
    rows = db.execute(q.limit(lim + 1)).all()
    has_more = len(rows) > lim
    rows = rows[:lim]
    items = []
    for rec, flag, job_state in rows:
        items.append({
            "recordId": f"rec_{rec.id}",
            "recordedAt": to_millis(rec.created_at),
            "taskType": rec.task_type,
            "recorderRole": rec.recorder_role,
            "durationSec": rec.duration_sec,
            "qualityFlag": flag or "VALID",
            "hasNote": rec.family_note is not None,
            "hasText": job_state == "DONE" and _has_text(db, rec.id),
            "eventTags": [t for t in db.scalars(select(EventTag.type).where(
                EventTag.record_id == rec.id, EventTag.deleted_at.is_(None))).all()],
        })
    next_cursor = None
    if has_more and rows:
        last = rows[-1][0]
        next_cursor = encode_cursor([last.created_at, str(last.id)])
    return {"items": items, "nextCursor": next_cursor, "hasMore": has_more,
            "lastSyncAt": to_millis(datetime.now(timezone.utc))}


def _has_text(db: Session, record_id: uuid.UUID) -> bool:
    return db.scalar(select(AsrResult.record_id).where(
        AsrResult.record_id == record_id)) is not None or \
        db.scalar(select(ManualTranscript.record_id).where(
            ManualTranscript.record_id == record_id)) is not None


@router.get("/records/{recordId}")
def record_detail(recordId: str, principal=Depends(get_principal),
                  db: Session = Depends(get_db)):
    sid = resolve_subject_id(db, principal, None) if principal.is_elder_session else None
    if sid is None:
        # 家属端：详情不带 subjectId 参数，先查记录再校验归属
        rec = _find_record(db, recordId)
        if rec is None:
            raise AppError(ErrorCode.NOT_FOUND)
        require_write(db, principal, rec.subject_id)
        sid = rec.subject_id
    else:
        rec = svc.get_record_or_404(db, recordId, sid)
    audio = db.scalar(select(AudioObject).where(AudioObject.record_id == rec.id))
    qg = db.get(QualityGateResult, rec.id)
    asr = db.get(AsrResult, rec.id)
    manual = db.get(ManualTranscript, rec.id)
    metrics = {m.metric_key: (float(m.value) if m.value is not None else None)
               for m in db.scalars(select(Metric).where(Metric.record_id == rec.id))}
    from app.domain.status_machine import z_band
    from sqlalchemy import select as _s

    from app.db.models import Baseline

    zbands = {}
    for key in ("SPEECH_RATE", "PAUSE", "VOCAB", "COHERENCE"):
        v = metrics.get(key)
        base = db.scalar(_s(Baseline).where(
            Baseline.subject_id == rec.subject_id, Baseline.task_type == rec.task_type,
            Baseline.metric_key == key, Baseline.computed_from == "ASR",
            Baseline.is_established.is_(True)))
        if v is None or base is None:
            zbands[key] = None
        else:
            zbands[key] = z_band(v, float(base.mean), float(base.std_dev))
    text_metrics = None
    text = (manual.text if manual else asr.text) if (asr or manual) else None
    if text:
        from app.integrations.metrics_calc import compute_text_metrics

        text_metrics = compute_text_metrics(text, rec.duration_sec)
    events = db.scalars(select(EventTag).where(
        EventTag.record_id == rec.id, EventTag.deleted_at.is_(None))).all()
    return {
        "recordId": f"rec_{rec.id}",
        "subjectId": f"sub_{rec.subject_id}",
        "recordedAt": to_millis(rec.created_at),
        "scheduledAt": to_millis(rec.scheduled_at),
        "actualStartedAt": to_millis(rec.actual_started_at),
        "durationSec": rec.duration_sec,
        "taskType": rec.task_type,
        "recorderRole": rec.recorder_role,
        "qualityFlag": qg.flag if qg else "VALID",
        "qualityReasons": (qg.reasons if qg else []),
        "transcript": {
            "asrText": asr.text if asr else None,
            "asrTextManual": manual.text if manual else None,
            "asrConfidence": float(asr.confidence) if asr else None,
            "dialect": asr.dialect if asr else None,
            "computedFrom": "MANUAL" if manual else "ASR",
        },
        "metrics": {
            "speechRate": (text_metrics or {}).get("SPEECH_RATE",
                                                   metrics.get("SPEECH_RATE")),
            "pauseRatio": metrics.get("PAUSE"),
            "longPauseCount": None,
            "mattr": metrics.get("VOCAB"),
            "meanSentenceLen": (text_metrics or {}).get("meanSentenceLen"),
            "connectiveRate": metrics.get("COHERENCE"),
        },
        "zBand": zbands,
        "caregiverNote": rec.family_note,
        "eventTags": [
            {"eventId": f"evt_{e.id}", "type": e.type, "note": e.note} for e in events
        ],
        "hasAudio": audio is not None and audio.deleted_at is None,
    }


def _find_record(db: Session, record_ref: str) -> Record | None:
    try:
        rid = uuid.UUID(str(record_ref).split("_")[-1])
    except Exception:
        return None
    rec = db.get(Record, rid)
    if rec is None or rec.deleted_at is not None:
        return None
    return rec


@router.get("/records/{recordId}/audio-url")
def audio_url(recordId: str, request: Request, principal=Depends(get_principal),
              db: Session = Depends(get_db)):
    rec = _find_record(db, recordId)
    if rec is None:
        raise AppError(ErrorCode.NOT_FOUND)
    require_write(db, principal, rec.subject_id)
    require_consent(db, principal.account.id, "SENSITIVE")
    return svc.audio_play_url(db, rec, principal.account.id,
                              request.client.host if request.client else "")


@router.get("/records/{recordId}/status")
def record_status(recordId: str, principal=Depends(get_principal),
                  db: Session = Depends(get_db)):
    rec = _find_record(db, recordId)
    if rec is None:
        raise AppError(ErrorCode.NOT_FOUND)
    require_write(db, principal, rec.subject_id)
    job = db.get(AnalysisJob, rec.id)
    qg = db.get(QualityGateResult, rec.id)
    has_acoustic = db.scalar(select(Metric.id).where(
        Metric.record_id == rec.id,
        Metric.metric_key.in_(("SPEECH_RATE", "PAUSE")))) is not None
    has_text = _has_text(db, rec.id) and db.scalar(select(Metric.id).where(
        Metric.record_id == rec.id,
        Metric.metric_key.in_(("VOCAB", "COHERENCE")))) is not None
    return {
        "recordId": f"rec_{rec.id}",
        "analysisState": rec.analysis_state,
        "qualityFlag": qg.flag if qg else None,
        "hasAcoustic": has_acoustic,
        "hasText": has_text,
        "failureReason": job.failure_reason if job else None,
    }


@router.patch("/records/{recordId}/transcript")
def patch_transcript(recordId: str, body: TranscriptPatchIn, request: Request,
                     principal=Depends(get_principal), db: Session = Depends(get_db)):
    rec = _find_record(db, recordId)
    if rec is None:
        raise AppError(ErrorCode.NOT_FOUND)
    require_write(db, principal, rec.subject_id)
    idem_key, cached = begin_idempotency(
        db, scope="transcript", account_id=principal.account.id, request=request,
        body=body.model_dump())
    if cached:
        return cached["payload"]
    # 合规：人工校订不覆盖原始 asrText，两者并存
    manual = db.get(ManualTranscript, rec.id)
    if manual is None:
        manual = ManualTranscript(record_id=rec.id)
        db.add(manual)
    manual.text = body.asrTextManual
    manual.transcribed_by = principal.account.username
    manual.transcribed_at = datetime.now(timezone.utc)
    # 触发文本指标重算（MANUAL 口径）→ 基线重算
    enqueue(db, aggregate_type="analysis", aggregate_id=rec.id,
            event_type="transcript.corrected",
            payload={"recordId": str(rec.id), "computedFrom": "MANUAL"})
    commit_idempotency(db, idem_key, {"ok": True})
    audit(db, action="DELETE", target_type="transcript", target_id=rec.id,
          actor_id=principal.account.id)
    return {"ok": True, "computedFrom": "MANUAL"}


@router.post("/records/{recordId}/events", status_code=201)
def add_event(recordId: str, body: EventIn, request: Request,
              principal=Depends(get_principal), db: Session = Depends(get_db)):
    rec = _find_record(db, recordId)
    if rec is None:
        raise AppError(ErrorCode.NOT_FOUND)
    require_write(db, principal, rec.subject_id)
    idem_key, cached = begin_idempotency(
        db, scope="events", account_id=principal.account.id, request=request,
        body=body.model_dump())
    if cached:
        return cached["payload"]
    result = svc.add_event(db, record=rec, account_id=principal.account.id,
                           etype=body.type, note=body.note)
    commit_idempotency(db, idem_key, result)
    return result


@router.delete("/records/{recordId}/events/{eventId}", status_code=204)
def remove_event(recordId: str, eventId: str, principal=Depends(get_principal),
                 db: Session = Depends(get_db)):
    rec = _find_record(db, recordId)
    if rec is None:
        raise AppError(ErrorCode.NOT_FOUND)
    require_write(db, principal, rec.subject_id)
    try:
        eid = uuid.UUID(eventId.split("_")[-1])
    except Exception:
        raise AppError(ErrorCode.NOT_FOUND)
    tag = db.get(EventTag, eid)
    if tag is None or tag.record_id != rec.id or tag.deleted_at is not None:
        raise AppError(ErrorCode.NOT_FOUND)
    tag.deleted_at = datetime.now(timezone.utc)
    enqueue(db, aggregate_type="analysis", aggregate_id=rec.id,
            event_type="recompute.requested", payload={"subjectId": str(rec.subject_id)})
    audit(db, action="DELETE", target_type="event_tag", target_id=tag.id,
          actor_id=principal.account.id)


@router.put("/records/family-note/{recordId}")
def set_family_note(recordId: str, body: dict, principal=Depends(get_principal),
                    db: Session = Depends(get_db)):
    """家属备注（前端 DiaryRepository.setFamilyNote 对应；无独立契约编号，走 PATCH 语义）。"""
    rec = _find_record(db, recordId)
    if rec is None:
        raise AppError(ErrorCode.NOT_FOUND)
    require_write(db, principal, rec.subject_id)
    note = body.get("note")
    if note is not None and len(note) > 500:
        raise AppError(ErrorCode.INVALID_PARAM)
    rec.family_note = note
    audit(db, action="DELETE", target_type="record", target_id=rec.id,
          actor_id=principal.account.id)
    return {"ok": True}


# ---------------- 提醒设置（API-04 4.7/4.8） ----------------

def _reminder_view(db: Session, subject_id: uuid.UUID) -> dict:
    row = svc.get_or_default_reminder(db, subject_id)
    missed = db.scalar(select(Record.created_at).where(
        Record.subject_id == subject_id, Record.deleted_at.is_(None))
        .order_by(Record.created_at.desc()).limit(1))
    if missed is None:
        missed_days = 0
    else:
        missed_days = max(0, (datetime.now(timezone.utc) - missed).days)
    freq = svc.daily_sweep_values(missed_days)
    return {
        "enabled": row.enabled,
        "reminderTime": row.reminder_time,
        "timezone": row.timezone,
        "target": row.target,
        "consecutiveMissedDays": missed_days,
        "currentFrequency": freq["currentFrequency"],
        "autoReduced": freq["autoReduced"],
    }


@router.get("/subjects/{subjectId}/reminder")
def get_reminder(subjectId: str, principal=Depends(get_principal),
                 db: Session = Depends(get_db)):
    sid = resolve_subject_id(db, principal, subjectId)
    return _reminder_view(db, sid)


@router.put("/subjects/{subjectId}/reminder")
def put_reminder(subjectId: str, body: ReminderIn, principal=Depends(get_principal),
                 db: Session = Depends(get_db)):
    sid = resolve_subject_id(db, principal, subjectId, write=True)
    row = svc.get_or_default_reminder(db, sid)
    row.enabled = body.enabled
    row.reminder_time = body.reminderTime
    row.timezone = body.timezone
    row.target = body.target
    row.updated_at = datetime.now(timezone.utc)
    # currentFrequency 服务端推导，客户端不可写（合规 9）
    return _reminder_view(db, sid)
