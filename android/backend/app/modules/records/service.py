"""音频存储与上传 / 记录与事件 · 服务层。

- objectKey 服务端生成，含 subjectId 前缀；签名 ≤15 分钟、单对象、仅 PUT
- 登记：校验 session↔objectKey 绑定、对象存在、sha256；事务内写 outbox（禁止直接 delay）
- recorderRole 服务端按会话类型判定，客户端声明不可信
"""
import uuid
from datetime import datetime, timedelta, timezone

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.config import get_settings
from app.core.audit import audit
from app.core.errors import AppError, ErrorCode
from app.core.ids import uuid7
from app.db.models import (
    AnalysisJob,
    AudioObject,
    EventTag,
    Record,
    ReminderSetting,
    UploadSession,
)
from app.integrations.outbox import enqueue
from app.integrations.storage import get_storage

ALLOWED_CONTENT_TYPES = {"audio/mp4", "audio/aac", "audio/wav", "audio/x-wav"}
_TASK_TYPES = {"DAILY_FREE", "WEEKLY_PICTURE", "WEEKLY_FLUENCY", "WEEKLY_FREE"}


def create_upload_session(db: Session, *, subject_id: uuid.UUID,
                          content_type: str, size_bytes: int) -> dict:
    s = get_settings()
    if content_type not in ALLOWED_CONTENT_TYPES:
        raise AppError(ErrorCode.INVALID_FILE, "音频格式暂不支持")
    if size_bytes > s.max_audio_bytes:
        raise AppError(ErrorCode.INVALID_FILE, "音频超过大小限制")
    if size_bytes <= 0:
        raise AppError(ErrorCode.INVALID_FILE)
    now = datetime.now(timezone.utc)
    object_key = f"{subject_id}/{now.strftime('%Y/%m')}/{uuid7()}.m4a"
    upload_session = UploadSession(
        id=uuid7(), subject_id=subject_id, object_key=object_key,
        expires_at=now + timedelta(seconds=s.presign_ttl_sec),
    )
    db.add(upload_session)
    db.flush()
    storage = get_storage()
    url, exp = storage.presign_put(object_key, content_type)
    return {
        "sessionId": f"ups_{upload_session.id}",
        "objectKey": object_key,
        "uploadUrl": url,
        "method": "PUT",
        "expiresAt": int(exp.timestamp() * 1000),
        "maxSizeBytes": s.max_audio_bytes,
        "requiredHeaders": {"Content-Type": content_type},
    }


def register_record(db: Session, *, subject_id: uuid.UUID, account_id: uuid.UUID,
                    is_elder_session: bool, body: dict) -> dict:
    """登记记录（事务内；入队走 outbox）。"""
    try:
        us_id = uuid.UUID(str(body.get("sessionId", "")).split("_")[-1])
    except Exception:
        raise AppError(ErrorCode.INVALID_OBJECT_KEY)
    us = db.get(UploadSession, us_id)
    if us is None or us.subject_id != subject_id:
        raise AppError(ErrorCode.INVALID_OBJECT_KEY)
    now = datetime.now(timezone.utc)
    if us.consumed_at is not None or us.expires_at < now:
        raise AppError(ErrorCode.URL_EXPIRED)
    if body.get("objectKey") != us.object_key:
        raise AppError(ErrorCode.INVALID_OBJECT_KEY)

    task_type = body.get("taskType")
    if task_type not in _TASK_TYPES:
        raise AppError(ErrorCode.INVALID_PARAM)
    duration = body.get("durationSec")
    if not isinstance(duration, int) or not (10 <= duration <= 300):
        raise AppError(ErrorCode.INVALID_PARAM, details=[
            {"field": "durationSec", "reason": "must be between 10 and 300"}])
    # 服务端按会话类型判定（客户端声明不可信）
    recorder_role = "ELDER_SESSION" if is_elder_session else "FAMILY_PROXY"

    storage = get_storage()
    if not storage.exists(us.object_key):
        raise AppError(ErrorCode.OBJECT_NOT_FOUND)
    size, sha = storage.size_and_sha256(us.object_key)
    if body.get("sha256"):
        import binascii

        try:
            client_sha = binascii.unhexlify(body["sha256"])
        except Exception:
            raise AppError(ErrorCode.CHECKSUM_MISMATCH)
        if client_sha != sha:
            raise AppError(ErrorCode.CHECKSUM_MISMATCH)

    record = Record(
        id=uuid7(), subject_id=subject_id, recorder_role=recorder_role,
        task_type=task_type,
        scheduled_at=datetime.fromtimestamp(body["scheduledAt"] / 1000, tz=timezone.utc)
        if body.get("scheduledAt") else None,
        actual_started_at=datetime.fromtimestamp(
            (body.get("actualStartedAt") or int(now.timestamp() * 1000)) / 1000,
            tz=timezone.utc),
        duration_sec=duration,
    )
    db.add(record)
    db.flush()
    db.add(AudioObject(
        id=uuid7(), record_id=record.id, object_key=us.object_key,
        size_bytes=size, sha256=sha,
        content_type=body.get("sampleRate") and "audio/mp4" or "audio/mp4",
        sample_rate=int(body.get("sampleRate") or 16000),
        uploaded_at=now,
    ))
    db.add(AnalysisJob(id=uuid7(), record_id=record.id, subject_id=subject_id))
    us.consumed_at = now  # 单次使用
    # 入队分析：事务性发件箱（README 8.2）
    enqueue(db, aggregate_type="analysis", aggregate_id=record.id,
            event_type="record.registered", payload={"recordId": str(record.id)})
    return {
        "recordId": f"rec_{record.id}",
        "analysisState": record.analysis_state,
        "syncState": record.sync_state,
        "createdAt": int(now.timestamp() * 1000),
    }


def get_record_or_404(db: Session, record_ref: str, subject_id: uuid.UUID) -> Record:
    try:
        rid = uuid.UUID(str(record_ref).split("_")[-1])
    except Exception:
        raise AppError(ErrorCode.NOT_FOUND)
    record = db.get(Record, rid)
    if record is None or record.deleted_at is not None or record.subject_id != subject_id:
        raise AppError(ErrorCode.NOT_FOUND)  # 越权与不存在同响应（防探测）
    return record


def daily_sweep_values(consecutive_missed_days: int) -> dict:
    """降频规则服务端推导（API-04 4.7），前端只呈现。"""
    if consecutive_missed_days >= 7:
        freq = "WEEKLY"
    elif consecutive_missed_days >= 3:
        freq = "EVERY_OTHER_DAY"
    else:
        freq = "DAILY"
    return {"currentFrequency": freq, "autoReduced": freq != "DAILY"}


def get_or_default_reminder(db: Session, subject_id: uuid.UUID) -> ReminderSetting:
    row = db.get(ReminderSetting, subject_id)
    if row is None:
        row = ReminderSetting(subject_id=subject_id)
        db.add(row)
        db.flush()
    return row


def next_cleanup_at(retention_months: int | None) -> datetime | None:
    if retention_months is None:
        return None
    return datetime.now(timezone.utc) + timedelta(days=retention_months * 30)


def count_records(db: Session, subject_id: uuid.UUID) -> int:
    return db.scalar(select(func.count(Record.id)).where(
        Record.subject_id == subject_id, Record.deleted_at.is_(None))) or 0


def add_event(db: Session, *, record: Record, account_id: uuid.UUID,
              etype: str, note: str | None) -> dict:
    if etype not in ("COLD", "MOVE", "HOSPITAL", "TRAVEL", "OTHER"):
        raise AppError(ErrorCode.INVALID_PARAM)
    tag = EventTag(id=uuid7(), record_id=record.id, type=etype, note=note,
                   created_by=account_id)
    db.add(tag)
    db.flush()
    # 事件影响非疾病解释呈现 → 触发状态重算（outbox）
    enqueue(db, aggregate_type="analysis", aggregate_id=record.id,
            event_type="recompute.requested", payload={"subjectId": str(record.subject_id)})
    audit(db, action="DELETE", target_type="event_tag", target_id=tag.id,
          actor_id=account_id)
    return {"eventId": f"evt_{tag.id}", "type": tag.type, "note": tag.note}


def audio_play_url(db: Session, record: Record, account_id: uuid.UUID, ip: str) -> dict:
    audio = db.scalar(select(AudioObject).where(AudioObject.record_id == record.id))
    if audio is None or audio.deleted_at is not None:
        raise AppError(ErrorCode.NOT_FOUND)
    storage = get_storage()
    url, exp = storage.presign_get(audio.object_key)
    # 合规硬约束：每次音频访问写审计日志（NFR-1.7）
    audit(db, action="AUDIO_READ", target_type="record", target_id=record.id,
          actor_id=account_id, ip=ip)
    return {"playUrl": url, "expiresAt": int(exp.timestamp() * 1000)}
