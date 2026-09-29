"""API-03 · 指标与趋势。合规风险最高的接口域。

硬约束（API-03 第 4 节）：不返回 zScore / mean / stdDev / probability / score；
偏离程度只用 zBand 分档；老人端无任何指标接口（B2，404）。
"""
import uuid
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.audit import audit
from app.core.deps import get_principal, require_write, resolve_subject_id
from app.core.errors import AppError, ErrorCode
from app.core.pagination import to_millis
from app.db.models import (
    Baseline,
    QualityGateResult,
    Record,
    StatusSnapshot,
    Metric,
)
from app.db.session import get_db
from app.domain.status_machine import recompute_subject
from app.core.redis_client import get_redis

router = APIRouter(tags=["metrics"])

_RANGES = {"7d": 7, "30d": 30, "90d": 90, "all": None}


@router.get("/metrics/{subjectId}/series")
def series(subjectId: str, key: str, range: str = "30d", taskType: str = "DAILY_FREE",
           principal=Depends(get_principal), db: Session = Depends(get_db)):
    # B2：老人端会话无任何趋势接口 → 404
    if principal.is_elder_session:
        raise AppError(ErrorCode.NOT_FOUND)
    sid = resolve_subject_id(db, principal, subjectId)
    if key not in ("SPEECH_RATE", "PAUSE", "VOCAB", "COHERENCE"):
        raise AppError(ErrorCode.INVALID_PARAM)
    if range not in _RANGES:
        raise AppError(ErrorCode.INVALID_PARAM)
    days = _RANGES[range]
    since = (datetime.now(timezone.utc) - timedelta(days=days)) if days else None
    rows = db.execute(
        select(Metric.value, Record.created_at, QualityGateResult.flag)
        .join(Record, Record.id == Metric.record_id)
        .outerjoin(QualityGateResult, QualityGateResult.record_id == Record.id)
        .where(
            Record.subject_id == sid,
            Record.deleted_at.is_(None),
            Record.task_type == taskType,
            Metric.metric_key == key,
            Metric.computed_from == "ASR",
            *([Record.created_at >= since] if since else []),
        ).order_by(Record.created_at)
    ).all()
    baseline = db.scalar(select(Baseline).where(
        Baseline.subject_id == sid, Baseline.task_type == taskType,
        Baseline.metric_key == key, Baseline.computed_from == "ASR"))
    from app.domain.status_machine import z_band

    points = []
    valid_count = 0
    excluded = 0
    for value, created_at, flag in rows:
        f = flag or "VALID"
        if value is None or f != "VALID":
            excluded += 1
            points.append({"recordedAt": to_millis(created_at), "value": None,
                           "qualityFlag": f, "zBand": None})
            continue
        valid_count += 1
        zband = None
        if baseline is not None and baseline.is_established:
            zband = z_band(float(value), float(baseline.mean), float(baseline.std_dev))
        points.append({"recordedAt": to_millis(created_at), "value": float(value),
                       "qualityFlag": f, "zBand": zband})
    # 契约：不返回 mean/stdDev/zScore
    return {
        "subjectId": f"sub_{sid}",
        "metricKey": key,
        "taskType": taskType,
        "range": range,
        "points": points,
        "insufficientData": valid_count < 4,
        "excludedCount": excluded,
        "baselineEstablished": bool(baseline and baseline.is_established),
        "lastSyncAt": to_millis(datetime.now(timezone.utc)),
    }


@router.get("/metrics/{subjectId}/baseline")
def baseline(subjectId: str, taskType: str = "DAILY_FREE",
             principal=Depends(get_principal), db: Session = Depends(get_db)):
    if principal.is_elder_session:
        raise AppError(ErrorCode.NOT_FOUND)
    sid = resolve_subject_id(db, principal, subjectId)
    rows = db.scalars(select(Baseline).where(
        Baseline.subject_id == sid, Baseline.task_type == taskType,
        Baseline.computed_from == "ASR")).all()
    by_key = {b.metric_key: b for b in rows}
    # 合规：不返回 mean / stdDev / zScore，只报 isEstablished 与还差几条
    items = []
    for key in ("SPEECH_RATE", "PAUSE", "VOCAB", "COHERENCE"):
        b = by_key.get(key)
        sample = b.sample_count if b else 0
        established = bool(b and b.is_established)
        items.append({
            "metricKey": key,
            "isEstablished": established,
            "sampleCount": sample,
            "windowStart": to_millis(b.window_start) if b else None,
            "windowEnd": to_millis(b.window_end) if b else None,
            "progressNeeded": max(0, 4 - sample),
        })
    return {"subjectId": f"sub_{sid}", "taskType": taskType, "items": items}


class MarkTreatedIn(BaseModel):
    treatedAt: int
    note: str | None = None


@router.post("/status/{subjectId}/mark-treated")
def mark_treated(subjectId: str, body: MarkTreatedIn,
                 principal=Depends(get_principal), db: Session = Depends(get_db)):
    sid = resolve_subject_id(db, principal, subjectId, write=True)
    from datetime import datetime as _dt

    from app.db.models import StatusTransition

    snapshot = db.get(StatusSnapshot, sid)
    prev = snapshot.status_code if snapshot else None
    if snapshot is None:
        snapshot = StatusSnapshot(subject_id=sid)
        db.add(snapshot)
    snapshot.status_code = "S6"
    snapshot.copy_key = "status.s6"
    snapshot.non_disease_key = None  # S6 不需要
    snapshot.since = _dt.fromtimestamp(body.treatedAt / 1000, tz=timezone.utc)
    snapshot.computed_at = _dt.now(timezone.utc)
    db.add(StatusTransition(subject_id=sid, from_code=prev, to_code="S6",
                            trigger="MANUAL_S6"))
    audit(db, action="STATUS_CHANGE", target_type="subject", target_id=sid,
          actor_id=principal.account.id)
    try:
        get_redis().delete(f"status:{sid}")
    except Exception:
        pass
    return _status_view(db, sid)


@router.get("/status/{subjectId}")
def status_view(subjectId: str, principal=Depends(get_principal),
                db: Session = Depends(get_db)):
    if principal.is_elder_session:
        raise AppError(ErrorCode.NOT_FOUND)
    sid = resolve_subject_id(db, principal, subjectId)
    return _status_view(db, sid)


def _status_view(db: Session, sid: uuid.UUID) -> dict:
    snapshot = db.get(StatusSnapshot, sid)
    if snapshot is None:
        recompute_subject(db, sid)
        db.flush()
        snapshot = db.get(StatusSnapshot, sid)
    if snapshot is None:
        raise AppError(ErrorCode.INTERNAL_ERROR)
    # 合规守卫（FR-4.3）：S4/S5 缺非疾病解释 → 500 + 告警，失败要响不要静
    if snapshot.status_code in ("S4", "S5") and not snapshot.non_disease_key:
        import logging

        logging.getLogger("app.compliance").error(
            "S4/S5 缺非疾病解释 subject=%s", sid)
        raise AppError(ErrorCode.INTERNAL_ERROR)
    baselines = db.scalars(select(Baseline).where(
        Baseline.subject_id == sid, Baseline.computed_from == "ASR",
        Baseline.is_established.is_(True))).all()
    keys = {b.metric_key for b in baselines}
    return {
        "subjectId": f"sub_{sid}",
        "statusCode": snapshot.status_code,
        "copyKey": snapshot.copy_key,
        "nonDiseaseKey": snapshot.non_disease_key,
        "validRecordCount": snapshot.valid_record_count,
        "since": to_millis(snapshot.since),
        "computedAt": to_millis(snapshot.computed_at),
        "actionKey": "action.prepare_medical" if snapshot.status_code == "S5" else None,
        "baselineEstablished": {
            "acoustic": bool(keys & {"SPEECH_RATE", "PAUSE"}),
            "text": bool(keys & {"VOCAB", "COHERENCE"}),
        },
        "lastSyncAt": to_millis(datetime.now(timezone.utc)),
    }
